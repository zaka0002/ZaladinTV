package com.zaladintv.app

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** What the app keeps of its WARP registration (no account, no password: a device key Cloudflare knows). */
data class WarpIdentity(
    val privateKey: String,
    val address: String,
    val peerKey: String,
    val host: String,
    val ip: String?,
)

/**
 * Cloudflare WARP, free and without an account: the app registers its own WireGuard key with
 * Cloudflare's client API, the way the 1.1.1.1 app and open-source tools such as wgcf (MIT) do.
 * The API is not documented by Cloudflare and may change; the request mirrors wgcf's.
 */
object Warp {
    private const val API = "https://api.cloudflareclient.com/v0a5641"
    private const val USER_AGENT = "1.1.1.1/6.38.9-5641 (Android 16.0.0)"
    private const val CLIENT_VERSION = "a-6.38.9-5641"
    const val DEFAULT_HOST = "engage.cloudflareclient.com"
    const val DEFAULT_PORT = 2408

    /** Other ports WARP listens on, tried when an ISP blocks 2408. */
    val FALLBACK_PORTS = listOf(500, 4500, 1701)

    /** Cloudflare's API only takes TLS 1.2 over HTTP/1.1. */
    private fun client(base: OkHttpClient) = base.newBuilder()
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()))
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Registers a new key with Cloudflare (through the normal connection, before any tunnel). */
    suspend fun register(http: OkHttpClient): WarpIdentity = withContext(Dispatchers.IO) {
        val keys = KeyPair()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject()
            .put("key", keys.publicKey.toBase64())
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("type", "Android")
            .put("model", "Android TV")
            .put("locale", "en_US")
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .toString()
        val request = Request.Builder().url("$API/reg")
            .header("User-Agent", USER_AGENT)
            .header("CF-Client-Version", CLIENT_VERSION)
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        val text = client(http).newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("WARP HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        parse(text, keys.privateKey.toBase64())
    }

    /** Reads Cloudflare's answer to a registration. */
    fun parse(text: String, privateKey: String): WarpIdentity {
        val config = JSONObject(text).optJSONObject("config") ?: throw IOException("WARP: no config")
        val peer = config.optJSONArray("peers")?.optJSONObject(0) ?: throw IOException("WARP: no server")
        val endpoint = peer.optJSONObject("endpoint")
        val address = config.optJSONObject("interface")?.optJSONObject("addresses")?.optString("v4")?.takeIf { it.isNotBlank() }
            ?: throw IOException("WARP: no address")
        val host = endpoint?.optString("host")?.takeIf { it.isNotBlank() } ?: "$DEFAULT_HOST:$DEFAULT_PORT"
        return WarpIdentity(
            privateKey = privateKey,
            address = address.substringBefore('/'),
            peerKey = peer.optString("public_key").takeIf { it.isNotBlank() } ?: throw IOException("WARP: no server key"),
            host = host,
            ip = endpoint?.optString("v4")?.substringBeforeLast(':')?.takeIf { it.isNotBlank() },
        )
    }

    /** WireGuard files to try, best first: Cloudflare's host, then its address on the other ports. */
    fun configs(id: WarpIdentity): List<String> {
        val hostPort = id.host.substringAfterLast(':', "").toIntOrNull() ?: DEFAULT_PORT
        val hostName = id.host.substringBeforeLast(':').ifBlank { DEFAULT_HOST }
        val endpoints = buildList {
            add("$hostName:$hostPort")
            id.ip?.let { ip -> (listOf(hostPort) + FALLBACK_PORTS).distinct().forEach { add("$ip:$it") } }
        }.distinct()
        return endpoints.map { endpoint ->
            """
            [Interface]
            PrivateKey = ${id.privateKey}
            Address = ${id.address}/32
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            # Cloudflare WARP
            PublicKey = ${id.peerKey}
            AllowedIPs = 0.0.0.0/0
            Endpoint = $endpoint
            PersistentKeepalive = 25
            """.trimIndent()
        }
    }

    fun encode(id: WarpIdentity): String = JSONObject()
        .put("k", id.privateKey).put("a", id.address).put("p", id.peerKey).put("h", id.host)
        .also { o -> id.ip?.let { o.put("i", it) } }.toString()

    fun decode(text: String?): WarpIdentity? = runCatching {
        val o = JSONObject(text ?: return null)
        WarpIdentity(o.getString("k"), o.getString("a"), o.getString("p"), o.getString("h"), o.optString("i").takeIf { it.isNotBlank() })
    }.getOrNull()
}
