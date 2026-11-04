package com.zaladintv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Reads only the catalog supplied by a user-authorized Xtream-compatible service. */
class XtreamClient(private val server: String, private val user: String, private val password: String) {
    init { require(server.startsWith("http://", true) || server.startsWith("https://", true)) { "Adresse du serveur invalide (http:// ou https://)" } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val root = server.trimEnd('/')
    private fun api(action: String, extra: String = ""): String =
        "$root/player_api.php?username=${enc(user)}&password=${enc(password)}&action=$action$extra"
    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.instanceFollowRedirects = false
        try {
            require(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText().take(25_000_000) }
        } finally { conn.disconnect() }
    }
    private fun arr(action: String, extra: String = ""): JSONArray = JSONArray(fetch(api(action, extra)))
    private fun categories(action: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val data = arr(action)
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            result[o.optString("category_id")] = o.optString("category_name", "Autres")
        }
        return result
    }
    fun loadCatalog(): List<Channel> {
        val account = JSONObject(fetch("$root/player_api.php?username=${enc(user)}&password=${enc(password)}"))
        require(account.optJSONObject("user_info")?.optInt("auth") == 1) { "Identifiants refusés par le serveur" }
        val result = ArrayList<Channel>()
        fun add(action: String, catAction: String, kind: String) {
            val cats = categories(catAction)
            val list = arr(action)
            for (i in 0 until minOf(list.length(), 15000)) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optString(if (kind == "Séries") "series_id" else "stream_id")
                if (id.isBlank()) continue
                val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                val url = when (kind) {
                    "TV en direct" -> "$root/live/${enc(user)}/${enc(password)}/$id.m3u8"
                    "Films" -> "$root/movie/${enc(user)}/${enc(password)}/$id.$ext"
                    else -> "series:$id"
                }
                val logo = if (kind == "Séries") o.optString("cover") else o.optString("stream_icon")
                result.add(Channel(o.optString("name", "Sans titre"), url,
                    cats[o.optString("category_id")] ?: kind, logo, kind,
                    if (kind == "Séries") id else ""))
            }
        }
        add("get_vod_streams", "get_vod_categories", "Films")
        add("get_series", "get_series_categories", "Séries")
        return result
    }
    fun loadEpisodes(seriesId: String): List<Channel> {
        require(seriesId.all { it.isDigit() }) { "Identifiant de série invalide" }
        val info = JSONObject(fetch(api("get_series_info", "&series_id=$seriesId")))
        val episodes = info.optJSONObject("episodes") ?: return emptyList()
        val output = mutableListOf<Channel>()
        for (season in episodes.keys()) {
            val list = episodes.optJSONArray(season) ?: continue
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val ext = item.optJSONObject("info")?.optString("container_extension", "mp4") ?: "mp4"
                val title = item.optString("title", "Épisode ${i + 1}")
                output.add(Channel("Saison $season • $title", "$root/series/${enc(user)}/${enc(password)}/$id.$ext", "Saison $season", type = "Épisode", season = season.toIntOrNull() ?: 0, episode = item.optInt("episode_num", i + 1)))
            }
        }
        return output.sortedWith(compareBy<Channel>(
            { it.group.substringAfter("Saison ").toIntOrNull() ?: Int.MAX_VALUE },
            { Regex("(?:[Ee]pisode|[Ee]p\\.?)[^0-9]*([0-9]+)").find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
            { it.name }
        ))
    }
}
