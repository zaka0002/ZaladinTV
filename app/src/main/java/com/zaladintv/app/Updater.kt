package com.zaladintv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-app update: checks the latest GitHub release, downloads its APK and hands it to the Android installer. */
object Updater {
    private const val REPO = "zaka0002/ZaladinTV"

    data class Release(val version: String, val apkUrl: String)

    /** Latest release with an APK asset, or null when none. */
    fun latest(): Release? {
        Net.get("https://api.github.com/repos/$REPO/releases/latest", 10_000, 15_000, headers = mapOf("Accept" to "application/vnd.github+json")).use { r ->
            if (r.code != 200) return null
            val j = JSONObject(r.body?.string().orEmpty())
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) return Release(j.optString("tag_name").removePrefix("v"), a.optString("browser_download_url"))
            }
            return null
        }
    }

    /** True when [remote] is a higher dotted version than [local] (e.g. 2.3.0 > 2.2.0). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "ZaladinTV.apk")
        Net.get(url, 15_000, 30_000).use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body ?: error("empty")
            val total = body.contentLength()
            body.byteStream().use { input -> file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            } }
        }
        return file
    }

    /** Opens the system installer; returns false when the user must first allow installs from this app. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    val muted = Color(0xFF9DA8BF)

    Text(uiLabel("Mise à jour"), color = Color.White, fontSize = 19.sp)
    Spacer(Modifier.height(8.dp))
    Button(enabled = !busy, onClick = {
        busy = true; release = null; status = uiLabel("Recherche de mise à jour…")
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Updater.latest() }
                if (r != null && Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) { release = r; status = uiLabel("Nouvelle version disponible") + " : " + r.version }
                else status = uiLabel("Tu as la dernière version") + " (" + BuildConfig.VERSION_NAME + ")"
            } catch (e: Exception) { status = uiLabel("Impossible de vérifier : vérifie ta connexion.") }
            busy = false
        }
    }) { Text(uiLabel("Vérifier les mises à jour")) }
    release?.let { r ->
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val f = withContext(Dispatchers.IO) { Updater.download(context, r.apkUrl) { p -> status = uiLabel("Téléchargement…") + " $p %" } }
                    status = if (Updater.install(context, f)) uiLabel("Installation lancée : valide sur l'écran.")
                    else uiLabel("Autorise ZaladinTV à installer des applis, puis reviens et relance.")
                } catch (e: Exception) { status = uiLabel("Échec du téléchargement.") }
                busy = false
            }
        }) { Text(uiLabel("Télécharger et installer") + " " + r.version) }
    }
    if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, color = muted) }
    Spacer(Modifier.height(14.dp))
}

