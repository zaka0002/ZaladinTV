package com.zaladintv.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny web server on the local network: the TV shows a QR code, the phone scans it and fills a form
 * (playlist link or Xtream login) so nobody has to type on the TV remote. Only reachable on the home Wi-Fi,
 * and only through the random token that is in the QR code.
 */
class PhoneSetupServer(private val onXtream: ((String, String, String, String) -> Unit)? = null, private val onVpn: ((String) -> Unit)? = null) {
    private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    private val rnd = SecureRandom()
    val token = (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    var port = 0
        private set

    fun start(): Boolean {
        server = try { ServerSocket(8765) } catch (e: Exception) { try { ServerSocket(0) } catch (e2: Exception) { return false } }
        port = server!!.localPort
        thread(isDaemon = true, name = "phone-setup") {
            val s = server ?: return@thread
            while (!s.isClosed) {
                try { handle(s.accept()) } catch (e: Exception) { if (s.isClosed) break }
            }
        }
        return true
    }

    fun stop() { try { server?.close() } catch (e: Exception) { }; server = null }

    private fun handle(c: Socket) = c.use { sock ->
        sock.soTimeout = 5000
        val input = sock.getInputStream().buffered()
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (b.size() < 4096) { val x = input.read(); if (x < 0 || x == '\n'.code) break; if (x != '\r'.code) b.write(x) }
            return b.toString("ISO-8859-1")
        }
        val request = line().split(" ")
        if (request.size < 2) return@use
        var length = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("content-length:", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val out = sock.getOutputStream()
        if (request[1].substringBefore('?') != "/$token") { reply(out, 404, "<p>404</p>"); return@use }
        if (request[0] == "POST" && length in 1..65536) {
            val body = ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }
            val f = String(body, Charsets.UTF_8).split('&').mapNotNull {
                val p = it.split('=', limit = 2); if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8").trim() else null
            }.toMap()
            val wg = f["wg"].orEmpty()
            val srv = f["server"].orEmpty()
            if (wg.isNotBlank() && onVpn != null) {
                main.post { onVpn(wg) }
                reply(out, 200, page(uiLabel("C'est envoyé à la TV. Tu peux fermer cette page.")))
            } else if (onXtream != null && (srv.startsWith("https://") || srv.startsWith("http://")) && !f["user"].isNullOrBlank() && !f["pass"].isNullOrBlank()) {
                main.post { onXtream(f["name"].orEmpty(), srv, f["user"].orEmpty(), f["pass"].orEmpty()) }
                reply(out, 200, page(uiLabel("C'est envoyé à la TV. Tu peux fermer cette page ou en ajouter une autre.")))
            } else reply(out, 400, page(uiLabel(if (onVpn != null) "Rien reçu : colle le contenu du fichier WireGuard." else "Lien invalide : il doit commencer par http:// ou https://")))
        } else reply(out, 200, page(""))
    }

    private fun reply(out: java.io.OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun page(message: String): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ZaladinTV</title><style>body{font-family:sans-serif;background:#0b1020;color:#fff;margin:0;padding:20px;max-width:520px}
h1{font-size:22px}h2{font-size:16px;margin-top:28px;color:#c8b6ff}textarea{width:100%;box-sizing:border-box;padding:12px;margin:6px 0;border-radius:10px;border:1px solid #33405f;background:#141b2a;color:#fff;font-size:14px;font-family:monospace}input{width:100%;box-sizing:border-box;padding:12px;margin:6px 0;border-radius:10px;border:1px solid #33405f;background:#141b2a;color:#fff;font-size:16px}
button{width:100%;padding:14px;margin-top:8px;border:0;border-radius:10px;background:#8f6bff;color:#fff;font-size:16px;font-weight:bold}.m{background:#1b3a2a;padding:12px;border-radius:10px}</style></head><body>
<h1>ZaladinTV</h1>${if (message.isNotBlank()) "<p class=m>$message</p>" else ""}
${if (onXtream != null) """<h2>${uiLabel("Ajouter une source Xtream")}</h2>
<form method="post"><input name="name" placeholder="${uiLabel("Nom (facultatif)")}"><input name="server" type="url" placeholder="http://monserveur.example:8080"><input name="user" placeholder="${uiLabel("Identifiant")}" autocapitalize="none">
<input name="pass" type="password" placeholder="${uiLabel("Mot de passe")}"><button>${uiLabel("Envoyer à la TV")}</button></form>""" else ""}
${if (onVpn != null) """<h2>${uiLabel("Clé WireGuard (VPN)")}</h2>
<form method="post"><textarea name="wg" rows="12" placeholder="[Interface]&#10;PrivateKey = ...&#10;[Peer]&#10;..."></textarea><button>${uiLabel("Envoyer à la TV")}</button></form>""" else ""}
</body></html>"""

    companion object {
        /** First private IPv4 address of the TV on the home network. */
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }
    }
}

@Composable
fun QrCode(text: String, size: Dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/** Settings card: the QR code is shown right away (the local server runs while the card is on screen). */
@Composable
fun PhoneSetupCard(hint: String, build: ((String) -> Unit) -> PhoneSetupServer) {
    var status by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    val buildNow by rememberUpdatedState(build)
    val server = remember { buildNow { status = it } }
    DisposableEffect(server) {
        val ip = PhoneSetupServer.localIp()
        if (ip == null || !server.start()) status = uiLabel("Pas de réseau local détecté. Connecte la TV au Wi-Fi.")
        else link = "http://$ip:${server.port}/${server.token}"
        onDispose { server.stop() }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        if (link.isNotBlank()) {
            QrCode(link, 128.dp)
            Spacer(Modifier.height(8.dp))
            Text(uiLabel(hint), color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(link, color = Color(0xFFC8B6FF), fontSize = 11.sp)
        }
        if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(uiLabel(status), color = Color(0xFFC8B6FF), fontSize = 13.sp) }
    }
}
