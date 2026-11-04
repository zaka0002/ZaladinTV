package com.zaladintv.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One Proton VPN free WireGuard server. */
data class ProtonServer(val country: String, val name: String, val ip: String, val publicKey: String)

/**
 * Public list of Proton VPN's free WireGuard servers (address and server key, no secret), so a single Proton file is
 * enough to reach every free country. Shipped with the app and refreshed once a week from gluetun-servers (MIT).
 */
class ProtonServers(private val context: Context, private val http: OkHttpClient) {
    private val cache = File(context.filesDir, "proton_free.json")
    private val sp = context.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    @Volatile var servers: List<ProtonServer> = load()
        private set

    /** Free countries, in a stable order. */
    val countries: List<String> get() = servers.map { it.country }.distinct().sortedWith(compareBy({ ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }, { it }))

    fun inCountry(code: String) = servers.filter { it.country == code }

    /** Weekly refresh; keeps the current list on any error. */
    suspend fun refreshIfStale() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_REFRESHED, 0) < 7 * 24 * 3_600_000L) return@withContext
        runCatching {
            val body = http.newCall(Request.Builder().url(SOURCE).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            val fresh = fromGluetun(body)
            if (fresh.size >= 5) {
                cache.writeText(toCompact(fresh))
                servers = fresh
            }
            sp.edit().putLong(KEY_REFRESHED, now).apply()
        }
    }

    private fun load(): List<ProtonServer> {
        val text = runCatching { cache.takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { context.assets.open("proton_free.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return emptyList()
        return runCatching { fromCompact(text) }.getOrDefault(emptyList())
    }

    companion object {
        private const val SOURCE = "https://raw.githubusercontent.com/qdm12/gluetun-servers/main/pkg/servers/protonvpn.json"
        private const val KEY_REFRESHED = "servers_refreshed_at"
        private val ORDER = listOf("NL", "CH", "US", "CA", "JP", "NO", "RO", "PL", "MX", "SG")

        fun fromCompact(text: String): List<ProtonServer> {
            val arr = JSONObject(text).getJSONArray("servers")
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val c = o.optString("c"); val ip = o.optString("ip"); val k = o.optString("k")
                if (c.isBlank() || ip.isBlank() || k.isBlank()) null else ProtonServer(c, o.optString("n"), ip, k)
            }
        }

        private fun toCompact(list: List<ProtonServer>): String =
            JSONObject().put("servers", JSONArray().also { arr ->
                list.forEach { arr.put(JSONObject().put("c", it.country).put("n", it.name).put("ip", it.ip).put("k", it.publicKey)) }
            }).toString()

        /** gluetun format: free WireGuard servers only, country names turned into ISO codes. */
        fun fromGluetun(text: String): List<ProtonServer> {
            val byName = Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH) }
            val arr = JSONObject(text).getJSONArray("servers")
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                if (!o.optBoolean("free", false) || o.optString("vpn") != "wireguard") return@mapNotNull null
                val code = byName[o.optString("country")] ?: return@mapNotNull null
                val ip = o.optJSONArray("ips")?.optString(0)?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                val key = o.optString("wgpubkey").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                ProtonServer(code, o.optString("server_name"), ip, key)
            }
        }
    }
}
