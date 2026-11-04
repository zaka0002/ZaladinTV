package com.zaladintv.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmdbItem(val title: String, val overview: String, val rating: Double, val year: String, val poster: String, val backdrop: String, val series: Boolean, val id: Int = 0)

data class TmdbInfo(val title: String, val overview: String, val rating: Double, val year: String, val poster: String, val backdrop: String, val id: Int = 0, val series: Boolean = false)

data class TmdbProvider(val id: Int, val name: String, val logo: String)

data class TmdbDetail(val runtime: Int, val genres: List<String>, val people: List<String>, val cast: List<String>)

/** Metadata (posters, synopsis, ratings) from TMDB, in French. The key is read from local.properties (tmdb.key) at build time. */
object TmdbClient {
    private val key: String = BuildConfig.TMDB_KEY
    private const val BASE = "https://api.themoviedb.org/3"
    private val posters = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val junk = Regex("""[\[(].*?[\])]|\b(4k|uhd|fhd|hd|1080p|720p|multi|vostfr|vf|truefrench|x264|x265|hevc)\b""", RegexOption.IGNORE_CASE)
    private val prefix = Regex("""^[A-Za-z]{2,3}\s*[|:\-]\s*""")

    private fun img(size: String, p: String) = if (p.isBlank() || p == "null") "" else "https://image.tmdb.org/t/p/$size$p"

    private fun get(path: String, lang: String = Lang.tmdb): JSONObject {
        require(key.isNotBlank()) { "Clé TMDB absente (tmdb.key dans local.properties)" }
        val sep = if (path.contains('?')) "&" else "?"
        val conn = URL("$BASE/$path${sep}api_key=${URLEncoder.encode(key, "UTF-8")}&language=$lang").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        try {
            require(conn.responseCode in 200..299) { if (conn.responseCode == 401) "Clé TMDB invalide" else "TMDB HTTP ${conn.responseCode}" }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    fun cleanTitle(name: String): Pair<String, String?> {
        val year = Regex("""\((19|20)\d{2}\)""").find(name)?.value?.trim('(', ')')
        val t = name.replace(prefix, "").replace(junk, " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '|')
        return t to year
    }

    private fun item(o: JSONObject, series: Boolean): TmdbItem? {
        val poster = img("w500", o.optString("poster_path"))
        if (poster.isBlank()) return null
        return TmdbItem(o.optString(if (series) "name" else "title"), o.optString("overview"), o.optDouble("vote_average", 0.0),
            o.optString(if (series) "first_air_date" else "release_date").take(4), poster, img("w780", o.optString("backdrop_path")), series, o.optInt("id"))
    }

    /** A TMDB list endpoint such as "trending/movie/week" or "discover/tv?with_genres=18". */
    fun list(path: String): List<TmdbItem> {
        val series = path.contains("tv")
        val arr = get(path).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(arr.length(), 20)).mapNotNull { i -> arr.optJSONObject(i)?.let { item(it, series) } }
    }

    fun lookup(rawName: String, series: Boolean): TmdbInfo? {
        val (title, year) = cleanTitle(rawName)
        if (title.isBlank()) return null
        val yearParam = if (year != null) "&" + (if (series) "first_air_date_year" else "year") + "=$year" else ""
        val o = get("search/${if (series) "tv" else "movie"}?query=${URLEncoder.encode(title, "UTF-8")}$yearParam").optJSONArray("results")?.optJSONObject(0) ?: return null
        val it = item(o, series) ?: return TmdbInfo(o.optString(if (series) "name" else "title", title), o.optString("overview"), o.optDouble("vote_average", 0.0), "", "", "", o.optInt("id"), series)
        return TmdbInfo(it.title, it.overview, it.rating, it.year, it.poster, it.backdrop, it.id, series)
    }

    /** Runtime, genres, director(s) or creator(s) and main cast of a title. */
    fun details(id: Int, series: Boolean): TmdbDetail {
        val o = get("${if (series) "tv" else "movie"}/$id?append_to_response=credits")
        fun names(a: org.json.JSONArray?, filter: (JSONObject) -> Boolean = { true }, max: Int = 6): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter(filter).map { it.optString("name") }.filter { it.isNotBlank() }.take(max)
        val credits = o.optJSONObject("credits")
        val runtime = if (series) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime")
        val people = if (series) names(o.optJSONArray("created_by"), max = 4) else names(credits?.optJSONArray("crew"), { it.optString("job") == "Director" }, 3)
        return TmdbDetail(runtime, names(o.optJSONArray("genres"), max = 5), people, names(credits?.optJSONArray("cast"), max = 5))
    }

    /** Streaming platforms available in France, in the order of [ids]. */
    fun providers(ids: List<Int>): List<TmdbProvider> {
        val arr = get("watch/providers/movie?watch_region=FR").optJSONArray("results") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optInt("provider_id") }
        return ids.mapNotNull { id -> all[id]?.let { TmdbProvider(id, it.optString("provider_name"), img("w154", it.optString("logo_path"))) } }
    }

    /** YouTube key of the best trailer (French first, then any language), or null. */
    fun trailer(id: Int, series: Boolean): String? {
        val kind = if (series) "tv" else "movie"
        for (lang in listOf(Lang.tmdb, "en-US").distinct()) {
            val arr = get("$kind/$id/videos", lang).optJSONArray("results") ?: continue
            val vids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("site") == "YouTube" }
            val pick = vids.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                ?: vids.firstOrNull { it.optString("type") == "Trailer" } ?: vids.firstOrNull { it.optString("type") == "Teaser" }
            if (pick != null) return pick.optString("key")
        }
        return null
    }

    /** Intro range (start ms, end ms) from theintrodb.org for a movie or a TV episode, or null when unknown. */
    fun intro(id: Int, series: Boolean, season: Int, episode: Int): Pair<Long, Long>? {
        val q = if (series && season > 0 && episode > 0) "&season=$season&episode=$episode" else ""
        val conn = URL("https://api.theintrodb.org/v2/media?tmdb_id=$id$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        try {
            if (conn.responseCode !in 200..299) return null
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("intro") ?: return null
            val o = arr.optJSONObject(0) ?: return null
            if (o.isNull("end_ms")) return null
            return (if (o.isNull("start_ms")) 0L else o.optLong("start_ms")) to o.optLong("end_ms")
        } finally { conn.disconnect() }
    }

    /** Poster URL for a library title, cached in memory. Blank when none found. */
    fun poster(name: String, series: Boolean): String =
        posters.getOrPut("$series|$name") { try { lookup(name, series)?.poster.orEmpty() } catch (e: Exception) { return "" } }
}
