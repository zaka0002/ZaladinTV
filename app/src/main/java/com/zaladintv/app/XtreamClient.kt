package com.zaladintv.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Reads only the catalog supplied by a user-authorized Xtream-compatible service. */
class XtreamClient(private val server: String, private val user: String, private val password: String, val id: String = "") {
    init { require(server.startsWith("http://", true) || server.startsWith("https://", true)) { "Adresse du serveur invalide (http:// ou https://)" } }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val root = server.trimEnd('/')
    private fun api(action: String, extra: String = ""): String =
        "$root/player_api.php?username=${enc(user)}&password=${enc(password)}&action=$action$extra"
    private fun fetch(url: String): String {
        Net.get(url, 12_000, 25_000, follow = false).use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            return (r.body?.string().orEmpty()).take(25_000_000)
        }
    }
    private fun arr(action: String, extra: String = ""): JSONArray = JSONArray(fetch(api(action, extra)))
    private val wanted = setOf("name", "stream_id", "series_id", "container_extension", "category_id", "stream_icon", "cover")
    /** Reads a JSON array of objects one at a time, keeping only the fields we use. */
    private fun stream(url: String, limit: Int, each: (Map<String, String>) -> Unit) {
        Net.get(url, 12_000, 25_000, follow = false).use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            android.util.JsonReader(resp.body!!.charStream()).use { r ->
                r.beginArray()
                var n = 0
                while (r.hasNext() && n < limit) {
                    if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    val m = HashMap<String, String>(8)
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        val t = r.peek()
                        if (k in wanted && (t == android.util.JsonToken.STRING || t == android.util.JsonToken.NUMBER)) m[k] = r.nextString() else r.skipValue()
                    }
                    r.endObject()
                    each(m); n++
                }
            }
        }
    }
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
            // Streamed parse: a big catalogue is tens of MB of JSON, reading it as one String ran out of memory.
            stream(api(action), 15000) { o ->
                val id = o[if (kind == "Séries") "series_id" else "stream_id"].orEmpty()
                if (id.isNotBlank()) {
                    val ext = o["container_extension"].orEmpty().ifBlank { "mp4" }
                    val url = when (kind) {
                        "TV en direct" -> "$root/live/${enc(user)}/${enc(password)}/$id.m3u8"
                        "Films" -> "$root/movie/${enc(user)}/${enc(password)}/$id.$ext"
                        else -> "series:$id"
                    }
                    val logo = if (kind == "Séries") o["cover"].orEmpty() else o["stream_icon"].orEmpty()
                    result.add(Channel(o["name"] ?: "Sans titre", url,
                        cats[o["category_id"].orEmpty()] ?: kind, logo, kind,
                        if (kind == "Séries") id else "", src = this.id))
                }
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
