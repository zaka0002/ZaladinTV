package com.zaladintv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import coil.imageLoader
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

private val bg = Color(0xFF070A12)
private val panel = Color(0xFF141B2A)
private val accent = Color(0xFFAA89FF)
private val muted = Color(0xFF9DA8BF)

data class Channel(val name: String, val url: String, val group: String, val logo: String = "", val type: String = "", val seriesId: String = "", val season: Int = 0, val episode: Int = 0, val show: String = "")

class MainActivity : ComponentActivity() {
    private val channels = mutableStateListOf<Channel>()
    private var xtreamSession by mutableStateOf<XtreamClient?>(null)
    private var message by mutableStateOf("Importe une playlist M3U pour commencer")
    private fun importUri(uri: Uri, persist: Boolean = false) {
        try {
            if (persist) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getSharedPreferences("zaladintv", MODE_PRIVATE).edit().putString("playlist_uri", uri.toString()).apply()
            }
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val parsed = parseM3u(content)
            channels.clear(); channels.addAll(parsed)
            xtreamSession = null
            getSharedPreferences("zaladintv", MODE_PRIVATE).edit().remove("xt_server").remove("xt_user").remove("xt_pass").apply()
            message = "${parsed.size} films et séries importés"
        } catch (e: Exception) { message = "Import impossible : ${e.localizedMessage}" }
    }
    private fun importRemote(url: String, onDone: (String) -> Unit) {
        Thread {
            try {
                require(url.startsWith("https://", true)) { "Utilise une URL HTTPS" }
                val connection = URL(url).openConnection().apply { connectTimeout = 12000; readTimeout = 20000 }
                val text = connection.getInputStream().bufferedReader().use { it.readText().take(12_000_000) }
                val parsed = parseM3u(text)
                runOnUiThread {
                    channels.clear(); channels.addAll(parsed)
                    xtreamSession = null
                    getSharedPreferences("zaladintv", MODE_PRIVATE).edit().putString("playlist_remote", url).remove("xt_server").remove("xt_user").remove("xt_pass").apply()
                    message = "${parsed.size} films et séries importés"
                    onDone(message)
                }
            } catch (e: Exception) { runOnUiThread { message = "Erreur : ${e.localizedMessage}"; onDone(message) } }
        }.start()
    }
    private fun connectXtream(server: String, user: String, password: String, onDone: (String) -> Unit) {
        Thread {
            try {
                val client = XtreamClient(server.trim(), user.trim(), password)
                val items = client.loadCatalog()
                runOnUiThread {
                    channels.clear(); channels.addAll(items)
                    xtreamSession = client
                    getSharedPreferences("zaladintv", MODE_PRIVATE).edit().putString("xt_server", server.trim()).putString("xt_user", user.trim()).putString("xt_pass", password).apply()
                    message = "${items.size} contenus chargés depuis la source"
                    onDone(message)
                }
            } catch (e: Exception) {
                runOnUiThread { message = "Connexion impossible : ${e.localizedMessage}"; onDone(message) }
            }
        }.start()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri, true)
    }
    private fun forgetSource() {
        channels.clear(); xtreamSession = null
        getSharedPreferences("zaladintv", MODE_PRIVATE).edit().remove("xt_server").remove("xt_user").remove("xt_pass")
            .remove("playlist_uri").remove("playlist_remote").apply()
        message = "Importe une playlist M3U pour commencer"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.code = getSharedPreferences("zaladintv", MODE_PRIVATE).getString("lang", "fr") ?: "fr"
        getSharedPreferences("zaladintv", MODE_PRIVATE).getString("playlist_uri", null)?.let {
            importUri(Uri.parse(it))
        }
        getSharedPreferences("zaladintv", MODE_PRIVATE).getString("playlist_remote", null)?.let { importRemote(it) {} }
        getSharedPreferences("zaladintv", MODE_PRIVATE).let { p ->
            val xs = p.getString("xt_server", null); val xu = p.getString("xt_user", null); val xp = p.getString("xt_pass", null)
            if (xs != null && xu != null && xp != null) connectXtream(xs, xu, xp) {}
        }
        setContent { NovaScreen(channels, message, { picker.launch(arrayOf("*/*")) }, { url, done -> importRemote(url, done) }, { server, user, password, done -> connectXtream(server, user, password, done) }, xtreamSession, { forgetSource() }) }
    }
}

private fun parseM3u(content: String): List<Channel> {
    val out = mutableListOf<Channel>()
    var title = ""; var group = "Autres"; var logo = ""
    val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    content.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith("#EXTINF", true) -> {
                val attrs = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                title = line.substringAfter(',', "").trim().ifBlank { attrs["tvg-name"] ?: "Chaîne" }
                group = attrs["group-title"]?.ifBlank { uiLabel("Autres") } ?: "Autres"
                logo = attrs["tvg-logo"] ?: ""
            }
            line.isNotBlank() && !line.startsWith('#') -> {
                if (line.startsWith("https://", true)) out += Channel(title.ifBlank { uiLabel("Chaîne") }, line, group, logo)
                title = ""; group = "Autres"; logo = ""
            }
        }
    }
    val vodGroups = listOf("vod", "movie", "film", "cinema", "cinéma", "series", "séries", "serie", "tv shows", "episodes", "saison", "season")
    return out.filter { ch -> vodGroups.any { ch.group.contains(it, true) } || ch.url.contains("/movie/", true) || ch.url.contains("/series/", true) }.distinctBy { it.url }.take(10000)
}

@Composable
private fun NovaScreen(channels: List<Channel>, message: String, importPlaylist: () -> Unit, importRemote: (String, (String) -> Unit) -> Unit, connectXtream: (String, String, String, (String) -> Unit) -> Unit, xtream: XtreamClient?, forgetSource: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zaladintv", Activity.MODE_PRIVATE) }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) } }
    var page by remember { mutableStateOf("Accueil") }
    var featuredIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var remoteStatus by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedSeries by remember { mutableStateOf<Channel?>(null) }
    var discover by remember { mutableStateOf<List<Pair<String, List<TmdbItem>>>>(emptyList()) }
    var discoverStatus by remember { mutableStateOf("") }
    LaunchedEffect(Lang.code) {
        try {
            discover = withContext(Dispatchers.IO) {
                listOf("Films tendance" to "trending/movie/week", "Séries tendance" to "trending/tv/week",
                    "Films populaires" to "movie/popular", "Séries les mieux notées" to "tv/top_rated")
                    .map { (t, path) -> t to TmdbClient.list(path) }.filter { it.second.isNotEmpty() }
            }
            discoverStatus = ""
        } catch (e: Exception) { discoverStatus = "Catalogue : ${e.localizedMessage}" }
    }
    val favTmdb = remember { mutableStateListOf<TmdbItem>().apply { addAll(loadFavTmdb(prefs.getString("fav_tmdb", "") ?: "")) } }
    val toggleFavTmdb: (TmdbItem) -> Unit = { item ->
        if (favTmdb.contains(item)) favTmdb.remove(item) else favTmdb.add(item)
        prefs.edit().putString("fav_tmdb", saveFavTmdb(favTmdb)).apply()
    }
    var favFilter by remember { mutableStateOf("Tout") }
    var settingsTab by remember { mutableStateOf("Bibliothèque") }
    var resumeOn by remember { mutableStateOf(prefs.getBoolean("resume", true)) }
    var dataStatus by remember { mutableStateOf("") }
    val setLang: (String) -> Unit = { c -> Lang.code = c; prefs.edit().putString("lang", c).apply() }
    var detailItem by remember { mutableStateOf<TmdbItem?>(null) }
    var detail by remember { mutableStateOf<Channel?>(null) }
    var detailInfo by remember { mutableStateOf<TmdbInfo?>(null) }
    var detailStatus by remember { mutableStateOf("") }
    var episodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var episodeStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun kind(ch: Channel): String = when {
        ch.type.isNotBlank() -> ch.type
        listOf("series", "séries", "tv shows", "episodes").any { ch.group.contains(it, true) } -> "Séries"
        listOf("vod", "movie", "film", "cinema", "cinéma").any { ch.group.contains(it, true) } -> "Films"
        else -> "TV en direct"
    }
    var playing by remember { mutableStateOf<Channel?>(null) }
    val recent = remember { mutableStateListOf<String>().apply { addAll(prefs.getString("recent", "")!!.split("\n").filter { it.isNotBlank() }) } }
    val startChannel: (Channel) -> Unit = { ch ->
        if (ch.type == "Séries" && ch.seriesId.isNotBlank() && xtream != null) {
            selectedSeries = ch
            episodes = emptyList()
            episodeStatus = "Chargement des épisodes…"
            scope.launch {
                try {
                    episodes = withContext(Dispatchers.IO) { xtream.loadEpisodes(ch.seriesId).map { it.copy(show = ch.name) } }
                    episodeStatus = if (episodes.isEmpty()) "Aucun épisode disponible" else "Choisis un épisode"
                } catch (e: Exception) { episodeStatus = "Erreur : ${e.localizedMessage}" }
            }
        } else playing = ch
        if (ch.type != "Séries") {
            recent.remove(ch.url); recent.add(0, ch.url)
            while (recent.size > 25) recent.removeAt(recent.lastIndex)
            prefs.edit().putString("recent", recent.joinToString("\n")).apply()
        }
    }
    val playChannel: (Channel) -> Unit = { ch ->
        if (ch.type == "Films" || ch.type == "Séries") {
            detailItem = null; detail = ch; detailInfo = null; detailStatus = "Recherche de la fiche…"
            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) { TmdbClient.lookup(ch.name, ch.type == "Séries") }
                    detailInfo = info
                    detailStatus = if (info == null) "Aucune fiche trouvée" else ""
                } catch (e: Exception) { detailStatus = "Erreur : ${e.localizedMessage}" }
            }
        } else startChannel(ch)
    }
    var panelItem by remember { mutableStateOf<TmdbItem?>(null) }
    val panelWatchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(page) { panelItem = null }
    LaunchedEffect(panelItem) {
        if (panelItem != null) { kotlinx.coroutines.delay(150); try { panelWatchFocus.requestFocus() } catch (e: Exception) { } }
    }
    BackHandler(enabled = panelItem != null && detail == null && playing == null) { panelItem = null }
    val openDiscover: (TmdbItem) -> Unit = { it -> panelItem = it }
    val watchDiscover: (TmdbItem) -> Unit = { item ->
        val type = if (item.series) "Séries" else "Films"
        val q = item.title.lowercase()
        val m = channels.firstOrNull { kind(it) == type && TmdbClient.cleanTitle(it.name).first.lowercase() == q }
            ?: channels.firstOrNull { kind(it) == type && it.name.contains(item.title, true) }
        if (m != null) startChannel(m)
        else android.widget.Toast.makeText(context, uiLabel("Absent de ta bibliothèque. Ajoute une source dans Réglages."), android.widget.Toast.LENGTH_LONG).show()
    }
    val trailerDiscover: (TmdbItem) -> Unit = { item ->
        scope.launch {
            val k = try { withContext(Dispatchers.IO) { TmdbClient.trailer(item.id, item.series) } } catch (e: Exception) { null }
            val ok = k != null && openYoutube(context, k)
            if (!ok) android.widget.Toast.makeText(context, uiLabel(if (k == null) "Pas de bande-annonce disponible" else "Aucune appli pour lire la vidéo"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val toggleFavorite: (Channel) -> Unit = { channel ->
        if (favorites.contains(channel.url)) favorites.remove(channel.url) else favorites.add(channel.url)
        prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
    }
    BackHandler(enabled = playing != null || selectedSeries != null) {
        if (playing != null) playing = null else selectedSeries = null
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg, surface = panel)) {
        if (playing != null) {
            PlayerScreen(playing!!, onBack = { playing = null })
        } else {
            if (detail != null) {
                val d = detail!!
                AlertDialog(
                    onDismissRequest = { detail = null },
                    title = { Text(detailInfo?.title ?: d.name) },
                    text = {
                        Row(Modifier.heightIn(max = 380.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val poster = detailInfo?.poster.orEmpty().ifBlank { d.logo }
                            if (poster.startsWith("https://", true)) AsyncImage(model = poster, contentDescription = d.name, contentScale = ContentScale.Crop, modifier = Modifier.width(130.dp).height(195.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                detailInfo?.let {
                                    val meta = listOf(it.year, if (it.rating > 0) "★ %.1f".format(it.rating) else "").filter { x -> x.isNotBlank() }.joinToString("  •  ")
                                    if (meta.isNotBlank()) Text(meta, color = accent)
                                    Spacer(Modifier.height(8.dp))
                                    Text(it.overview.ifBlank { uiLabel("Pas de synopsis disponible.") })
                                }
                                if (detailStatus.isNotBlank()) Text(uiLabel(detailStatus), color = muted)
                                if ((detailInfo?.id ?: 0) > 0) TextButton(onClick = {
                                    val inf = detailInfo!!
                                    detailStatus = "Recherche de la bande-annonce…"
                                    scope.launch {
                                        try {
                                            val k = withContext(Dispatchers.IO) { TmdbClient.trailer(inf.id, inf.series) }
                                            detailStatus = if (k == null) "Pas de bande-annonce disponible" else { if (openYoutube(context, k)) "" else "Aucune appli pour lire la vidéo" }
                                        } catch (e: Exception) { detailStatus = "Erreur : ${e.localizedMessage}" }
                                    }
                                }) {
                                    Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(uiLabel("Bande-annonce"))
                                }
                                detailItem?.let { di ->
                                    val isFav = favTmdb.contains(di)
                                    TextButton(onClick = { toggleFavTmdb(di) }) {
                                        Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = accent, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(uiLabel(if (isFav) "Retirer des favoris" else "Ajouter aux favoris"))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                        if (d.url.startsWith("tmdb:")) {
                            val q = d.name.lowercase()
                            val m = channels.firstOrNull { kind(it) == d.type && TmdbClient.cleanTitle(it.name).first.lowercase() == q }
                                ?: channels.firstOrNull { kind(it) == d.type && it.name.contains(d.name, true) }
                            if (m != null) { detail = null; startChannel(m) } else detailStatus = "Absent de ta bibliothèque. Ajoute une source dans Réglages."
                        } else { detail = null; startChannel(d) }
                    }) { Text(uiLabel(if (d.type == "Séries") "Épisodes" else "Regarder")) } },
                    dismissButton = { TextButton(onClick = { detail = null }) { Text(uiLabel("Fermer")) } }
                )
            }
            if (selectedSeries != null) {
                AlertDialog(
                    onDismissRequest = { selectedSeries = null },
                    title = { Text(selectedSeries!!.name) },
                    text = {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            Text(uiLabel(episodeStatus))
                            episodes.forEach { episode ->
                                TextButton(onClick = { selectedSeries = null; playing = episode }) {
                                    Text(episode.name)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { selectedSeries = null }) { Text(uiLabel("Fermer")) } }
                )
            }
            Column(Modifier.fillMaxSize().background(bg)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF101626), bg))).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Image(painterResource(R.drawable.zaladin_logo), null, Modifier.height(34.dp).padding(end = 8.dp))
                    Text(uiLabel("ZALADIN"), fontSize = 19.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text(uiLabel("TV"), fontSize = 19.sp, fontWeight = FontWeight.Black, color = accent)
                    Spacer(Modifier.width(12.dp))
                    listOf(
                        Pair("Accueil", Icons.Default.Home),
                        Pair("Films", Icons.Default.Movie),
                        Pair("Séries", Icons.Default.VideoLibrary),
                        Pair("Plateformes", Icons.Default.Apps),
                        Pair("Favoris", Icons.Default.Favorite),
                        Pair("Recherche", Icons.Default.Search),
                        Pair("Réglages", Icons.Default.Settings),
                        Pair("Quitter", Icons.Default.Close)
                    ).forEach { (name, icon) ->
                        NavigationItem(name, icon, page == name) {
                            if (name == "Quitter") (context as? android.app.Activity)?.finishAffinity() else page = name
                        }
                    }
                }
                if (page == "Films" || page == "Séries") androidx.compose.runtime.key(page) { ExplorePage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), page == "Séries", favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover) }
                else if (page == "Plateformes") PlatformsPage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover)
                else Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(if (panelItem != null) 0.58f else 1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 38.dp, vertical = 12.dp)) {
                    Spacer(Modifier.height(8.dp))
                    when (page) {
                        "Accueil" -> {
                            val featured = channels.filter { kind(it) == "Films" || kind(it) == "Séries" }.ifEmpty { channels }.take(8)
                            val hero = featured.getOrNull(featuredIndex % featured.size.coerceAtLeast(1))
                            val heroImg = if (hero != null) rememberPoster(hero) else ""
                            if (hero != null) {
                            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(22.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF392358), Color(0xFF172744), Color(0xFF0C1220))))) {
                                if (heroImg.startsWith("https://", true)) AsyncImage(model = heroImg, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.3f, modifier = Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.CenterEnd).size(270.dp)
                                    .background(Brush.radialGradient(listOf(Color(0x665D53B8), Color.Transparent))))
                                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.79f).padding(32.dp)) {
                                    Text(uiLabel("ZALADINTV  /  À LA UNE"), color = Color(0xFFC8B6FF), fontSize = 12.sp, letterSpacing = 2.sp)
                                    Spacer(Modifier.height(13.dp))
                                    Text(hero?.name ?: uiLabel("Bienvenue dans ZaladinTV"), color = Color.White,
                                        fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(12.dp))
                                    Text(hero?.group ?: uiLabel("Films, séries et chaînes depuis tes sources autorisées"), color = Color(0xFFCED3E7), fontSize = 15.sp)
                                    Spacer(Modifier.height(22.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = { if (hero != null) playChannel(hero) else page = "Réglages" }) {
                                            Icon(if (hero != null) Icons.Default.PlayArrow else Icons.Default.Add, null)
                                            Spacer(Modifier.width(7.dp))
                                            Text(uiLabel(if (hero != null) "Regarder" else "Ajouter une source"))
                                        }
                                        if (hero != null) {
                                            OutlinedButton(onClick = { toggleFavorite(hero) }) {
                                                Icon(if (favorites.contains(hero.url)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                                                Spacer(Modifier.width(6.dp)); Text(uiLabel("Ma liste"))
                                            }
                                        }
                                    }
                                }
                                if (featured.size > 1) {
                                    Row(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { featuredIndex = (featuredIndex + featured.size - 1) % featured.size }) { Text(uiLabel("‹")) }
                                        OutlinedButton(onClick = { featuredIndex = (featuredIndex + 1) % featured.size }) { Text(uiLabel("›")) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(26.dp))
                            }
                            discover.forEach { (t, items) -> DiscoverShelf(t, items, openDiscover, favTmdb, toggleFavTmdb) { page = if (t.startsWith("Films")) "Films" else "Séries" } }
                            if (discoverStatus.isNotBlank()) Text(uiLabel(discoverStatus), color = muted)
                            if (channels.isEmpty()) {
                            } else {
                                val recentChannels = recent.mapNotNull { url -> channels.find { it.url == url } }
                                if (recentChannels.isNotEmpty()) {
                                    ChannelShelf("Reprendre le visionnage", recentChannels, favorites, playChannel, toggleFavorite) { search = ""; page = "Recherche" }
                                }
                                ChannelShelf("À la une", channels.take(18), favorites, playChannel, toggleFavorite) { search = ""; page = "Recherche" }
                                listOf("Films", "Séries").forEach { type ->
                                    ChannelShelf(type, channels.filter { kind(it) == type }.take(18), favorites, playChannel, toggleFavorite) { page = type }
                                }
                                channels.groupBy { it.group }.entries.take(8).forEach { (group, items) ->
                                    ChannelShelf(group, items.take(18), favorites, playChannel, toggleFavorite) { search = group; page = "Recherche" }
                                }
                            }
                        }
                        "Recherche" -> {
                            TvField(value = search, onValueChange = { search = it }, label = { Text(uiLabel("Rechercher une chaîne, un film ou une série")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(18.dp))
                            ChannelGrid(channels.filter { it.name.contains(search, true) || it.group.contains(search, true) }, favorites, playChannel, toggleFavorite)
                        }
                        "Favoris" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("Tout", "Films", "Séries").forEach { f -> FavChip(f, favFilter == f) { favFilter = f } }
                            }
                            Spacer(Modifier.height(18.dp))
                            val tmFilms = favTmdb.filter { !it.series }
                            val tmSeries = favTmdb.filter { it.series }
                            val lib = channels.filter { it.url in favorites && (favFilter == "Tout" || kind(it) == favFilter) }
                            val showFilms = favFilter != "Séries"
                            val showSeries = favFilter != "Films"
                            if (lib.isEmpty() && (!showFilms || tmFilms.isEmpty()) && (!showSeries || tmSeries.isEmpty()))
                                Text(uiLabel("Aucun favori ici. Maintiens OK sur une affiche (ou ouvre sa fiche) pour l'ajouter."), color = muted)
                            if (showFilms) DiscoverShelf("Films favoris", tmFilms, openDiscover, favTmdb, toggleFavTmdb) { favFilter = "Films" }
                            if (showSeries) DiscoverShelf("Séries favorites", tmSeries, openDiscover, favTmdb, toggleFavTmdb) { favFilter = "Séries" }
                            if (lib.isNotEmpty()) {
                                Text(uiLabel("Ma bibliothèque"), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                ChannelGrid(lib, favorites, playChannel, toggleFavorite)
                            }
                        }
                        "Réglages" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("Bibliothèque", "Langue", "Lecture", "Données", "À propos").forEach { t -> FavChip(t, settingsTab == t) { settingsTab = t } }
                            }
                            Spacer(Modifier.height(22.dp))
                            when (settingsTab) {
                                "Bibliothèque" -> {
                            Button(onClick = importPlaylist) { Text(uiLabel("Importer un fichier M3U / M3U8")) }
                            Spacer(Modifier.height(12.dp))
                            Spacer(Modifier.height(18.dp))
                            Text(uiLabel("Importer une playlist HTTPS"), color = Color.White, fontSize = 19.sp)
                            TvField(value = playlistUrl, onValueChange = { playlistUrl = it }, label = { Text(uiLabel("https://exemple.org/playlist.m3u")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { importRemote(playlistUrl) { remoteStatus = it } }, enabled = playlistUrl.startsWith("https://")) { Text(uiLabel("Charger la playlist")) }
                            Text(uiLabel(remoteStatus), color = muted)
                            Spacer(Modifier.height(14.dp))
                            Text(uiLabel(message), color = muted)
                            Spacer(Modifier.height(12.dp))
                            Spacer(Modifier.height(22.dp))
                            Text(uiLabel("Connexion à une source Xtream autorisée"), color = Color.White, fontSize = 19.sp)
                            TvField(value = server, onValueChange = { server = it }, label = { Text(uiLabel("Serveur HTTPS (ex. https://monserveur.example)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            TvField(value = username, onValueChange = { username = it }, label = { Text(uiLabel("Identifiant")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            TvField(value = password, onValueChange = { password = it }, label = { Text(uiLabel("Mot de passe")) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = { connectXtream(server, username, password) { remoteStatus = it } }, enabled = server.startsWith("https://") && username.isNotBlank() && password.isNotBlank()) { Text(uiLabel("Charger films et séries")) }
                            Spacer(Modifier.height(12.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(uiLabel("Aucun contenu n’est fourni. Les catégories Films et Séries sont détectées à partir des groupes M3U (VOD, Films, Series…). Utilise uniquement des sources autorisées."), color = muted)
                                }
                                "Langue" -> {
                                    Text(uiLabel("Langue de l'application et des fiches"), color = Color.White, fontSize = 19.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        listOf("fr" to "Français", "en" to "English").forEach { (code, name) -> FavChip(name, Lang.code == code) { setLang(code) } }
                                    }
                                }
                                "Lecture" -> {
                                    Text(uiLabel("Reprendre là où je me suis arrêté"), color = Color.White, fontSize = 19.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { resumeOn = !resumeOn; prefs.edit().putBoolean("resume", resumeOn).apply() }) { Text(uiLabel(if (resumeOn) "Activé" else "Désactivé")) }
                                }
                                "Données" -> {
                                    ConfirmButton("Vider les favoris") { favTmdb.clear(); favorites.clear(); prefs.edit().remove("fav_tmdb").remove("favorites").apply(); dataStatus = "Fait" }
                                    Spacer(Modifier.height(10.dp))
                                    ConfirmButton("Effacer « Reprendre le visionnage »") { recent.clear(); prefs.edit().remove("recent").apply(); dataStatus = "Fait" }
                                    Spacer(Modifier.height(10.dp))
                                    ConfirmButton("Oublier la source (M3U / Xtream)") { forgetSource(); dataStatus = "Fait" }
                                    Spacer(Modifier.height(10.dp))
                                    ConfirmButton("Vider le cache des images") {
                                        context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); dataStatus = "Fait"
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(uiLabel(dataStatus), color = muted)
                                }
                                "À propos" -> {
                                    Text("ZaladinTV  " + BuildConfig.VERSION_NAME, color = Color.White, fontSize = 19.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(uiLabel("Licence MIT"), color = muted)
                                    Spacer(Modifier.height(14.dp))
                                    Text(uiLabel("Les fiches, affiches et bandes-annonces viennent de TMDB. Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB."), color = muted)
                                }
                            }
                        }
                    }
                }
                panelItem?.let { f -> DetailPanel(Modifier.weight(0.42f).fillMaxHeight().padding(top = 8.dp, end = 24.dp, bottom = 8.dp), f, favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover, panelWatchFocus) }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(name: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50.dp)
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.scale(if (focused) 1.06f else 1f).background(if (focused) Color(0xFF3B4F8F) else if (selected) Color(0xFF2B3D72) else Color.Transparent, shape).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) Color.White else muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(uiLabel(name), color = if (selected) Color.White else muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** "Voir plus" end card: [slotW] x [slotH] footprint of a poster card, with the button itself poster-sized and aligned on the posters. */
@Composable
fun MoreCard(slotW: androidx.compose.ui.unit.Dp, slotH: androidx.compose.ui.unit.Dp, topPad: androidx.compose.ui.unit.Dp,
             btnW: androidx.compose.ui.unit.Dp, btnH: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.width(slotW).height(slotH).padding(top = topPad), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(btnW).height(btnH).scale(if (focused) 1.08f else 1f).onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(if (focused) listOf(Color(0xFF5B4AA8), Color(0xFF2B3D72)) else listOf(Color(0xFF1E2640), panel)))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x33AA89FF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (focused) Color.White else Color(0x33AA89FF)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowForward, null, tint = if (focused) Color(0xFF2B3D72) else accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(uiLabel("Voir plus"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ChannelShelf(title: String, channels: List<Channel>, favorites: List<String>, onPlay: (Channel) -> Unit, onFavorite: (Channel) -> Unit, onMore: (() -> Unit)? = null) {
    if (channels.isEmpty()) return
    val portrait = channels.first().type.let { it == "Films" || it == "Séries" }
    Text(uiLabel(title), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 6.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)) {
        items(channels, key = { it.url }) { channel ->
            var focused by remember { mutableStateOf(false) }
            val poster = rememberPoster(channel)
            Column(Modifier.width(if (portrait) 130.dp else 215.dp).scale(if (focused) 1.06f else 1f)
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color(0xFF33466E) else panel, RoundedCornerShape(14.dp))
                .clickable { onPlay(channel) }.padding(12.dp)) {
                Box(Modifier.fillMaxWidth().height(if (portrait) 190.dp else 116.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF433A72), Color(0xFF204263))), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) {
                    if (poster.startsWith("https://", true)) AsyncImage(model = poster, contentDescription = channel.name, contentScale = if (channel.type == "TV en direct" || channel.type.isBlank()) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (channel.type == "Films") Icons.Default.Movie else if (channel.type == "Séries") Icons.Default.VideoLibrary else Icons.Default.LiveTv,
                            null, tint = Color(0xFFE2D6FF), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(channel.name.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.group, maxLines = 1, color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onFavorite(channel) }, modifier = Modifier.size(32.dp)) {
                        Icon(if (favorites.contains(channel.url)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Favori", tint = if (favorites.contains(channel.url)) accent else muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (onMore != null) item(key = "voir-plus") {
            if (portrait) MoreCard(130.dp, 277.dp, 12.dp, 106.dp, 190.dp, onMore) else MoreCard(215.dp, 203.dp, 12.dp, 191.dp, 116.dp, onMore)
        }
    }
    Spacer(Modifier.height(25.dp))
}

@Composable
private fun ChannelGrid(channels: List<Channel>, favorites: List<String>, onPlay: (Channel) -> Unit, onFavorite: (Channel) -> Unit) {
    if (channels.isEmpty()) { Text(uiLabel("Aucune chaîne à afficher."), color = muted); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.heightIn(max = 650.dp)) {
      items(channels.chunked(3)) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { channel ->
                var focused by remember { mutableStateOf(false) }
                val poster = rememberPoster(channel)
                Column(Modifier.weight(1f).scale(if (focused) 1.035f else 1f)
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (focused) Color(0xFF30345B) else panel, RoundedCornerShape(14.dp))
                    .clickable { onPlay(channel) }.padding(15.dp)) {
                    Box(Modifier.fillMaxWidth().height(86.dp).background(Brush.linearGradient(listOf(Color(0xFF314C83), Color(0xFF202A42))), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        if (poster.startsWith("https://", true)) AsyncImage(model = poster, contentDescription = channel.name, contentScale = if (channel.type == "TV en direct" || channel.type.isBlank()) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Movie, null, tint = Color(0xFFDBCAFF), modifier = Modifier.size(30.dp))
                            Text(channel.name, color = Color.White, fontSize = 12.sp, maxLines = 2,
                                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(channel.group, maxLines = 1, color = muted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onPlay(channel) }) { Text(uiLabel("Regarder")) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onFavorite(channel) }) { Icon(if (favorites.contains(channel.url)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favori", tint = if (favorites.contains(channel.url)) accent else muted) }
                    }
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
}

@Composable
private fun PlayerScreen(channel: Channel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zaladintv", Activity.MODE_PRIVATE) }
    val posKey = "pos_${channel.url.hashCode()}"
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            val saved = if (prefs.getBoolean("resume", true)) prefs.getLong(posKey, 0L) else 0L
            setMediaItem(MediaItem.fromUri(Uri.parse(channel.url)), if (saved > 10_000L) saved else 0L)
            prepare()
            playWhenReady = true
        }
    }
    fun savePosition() {
        val dur = player.duration
        if (dur > 0 && !player.isCurrentMediaItemDynamic && !player.isCurrentMediaItemLive) {
            val pos = player.currentPosition
            if (pos > dur - 30_000L) prefs.edit().remove(posKey).apply() else prefs.edit().putLong(posKey, pos).apply()
        }
    }
    LaunchedEffect(player) { while (true) { delay(5000); savePosition() } }
    var intro by remember(channel.url) { mutableStateOf<Pair<Long, Long>?>(null) }
    var inIntro by remember(channel.url) { mutableStateOf(false) }
    LaunchedEffect(channel.url) {
        if (channel.type == "Épisode" || channel.type == "Films") {
            try {
                intro = withContext(Dispatchers.IO) {
                    val series = channel.type == "Épisode"
                    val id = TmdbClient.lookup(if (series) channel.show else channel.name, series)?.id ?: 0
                    if (id > 0) TmdbClient.intro(id, series, channel.season, channel.episode) else null
                }
            } catch (e: Exception) { }
        }
    }
    LaunchedEffect(intro) {
        val r = intro ?: return@LaunchedEffect
        while (true) { val pos = player.currentPosition; inIntro = pos >= r.first && pos < r.second - 1000; delay(500) }
    }
    val skipFocus = remember { FocusRequester() }
    LaunchedEffect(inIntro) { if (inIntro) try { skipFocus.requestFocus() } catch (e: Exception) { } }
    DisposableEffect(player) { onDispose { savePosition(); player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxSize())
        if (inIntro) Button(onClick = { intro?.let { player.seekTo(it.second) }; inIntro = false },
            modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp).focusRequester(skipFocus)) { Text(uiLabel("Passer l'intro")) }
        Row(Modifier.align(Alignment.TopStart).background(Color(0xB9000000)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(uiLabel("← Retour")) }
            Text(channel.name, color = Color.White)
        }
    }
}

@Composable
private fun rememberPoster(channel: Channel): String {
    var poster by remember(channel.url) { mutableStateOf(channel.logo) }
    LaunchedEffect(channel.url) {
        if (poster.isBlank() && (channel.type == "Films" || channel.type == "Séries" || channel.type.isBlank())) {
            poster = withContext(Dispatchers.IO) { TmdbClient.poster(channel.name, channel.type == "Séries") }
        }
    }
    return poster
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DiscoverShelf(title: String, items: List<TmdbItem>, onOpen: (TmdbItem) -> Unit, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit, onMore: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(uiLabel(title), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 10.dp, end = 24.dp, top = 14.dp, bottom = 14.dp)) {
        items(items, key = { "${it.series}|${it.title}|${it.year}" }) { item ->
            var focused by remember { mutableStateOf(false) }
            Column(Modifier.width(130.dp).scale(if (focused) 1.08f else 1f).onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color(0xFF33466E) else panel, RoundedCornerShape(14.dp))
                .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onFav(item) }).padding(8.dp)) {
                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(10.dp))) {
                    AsyncImage(model = item.poster, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (favs.contains(item)) Icon(Icons.Default.Favorite, null, tint = accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                    if (focused) Column(Modifier.fillMaxSize().background(Color(0xE6050810)).padding(8.dp)) {
                        if (item.rating > 0) Text("★ %.1f".format(item.rating), color = Color(0xFFFFD66B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (item.year.isNotBlank()) Text(item.year, color = muted, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(item.overview.ifBlank { uiLabel("Pas de résumé disponible.") }, color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 11, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(listOf(item.year, if (item.rating > 0) "★ %.1f".format(item.rating) else "").filter { it.isNotBlank() }.joinToString("  •  "), color = muted, fontSize = 11.sp)
            }
        }
        if (onMore != null) item(key = "voir-plus") { MoreCard(130.dp, 244.dp, 8.dp, 114.dp, 190.dp, onMore) }
    }
    Spacer(Modifier.height(25.dp))
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvField(value: String, onValueChange: (String) -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier,
                    singleLine: Boolean = true, visualTransformation: VisualTransformation = VisualTransformation.None) {
    // On TV the keyboard must open only when the user presses OK on the field, not when D-pad focus passes over it.
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) { if (editing) keyboard?.show() else keyboard?.hide() }
    OutlinedTextField(value = value, onValueChange = onValueChange, label = label, singleLine = singleLine,
        visualTransformation = visualTransformation, readOnly = !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) editing = false }.onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!editing && ok) { if (e.type == KeyEventType.KeyUp) editing = true; true } else false
        })
}

private fun loadFavTmdb(json: String): List<TmdbItem> = try {
    val a = org.json.JSONArray(json.ifBlank { uiLabel("[]") })
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        TmdbItem(o.getString("t"), o.optString("o"), o.optDouble("r", 0.0), o.optString("y"), o.getString("p"), o.optString("b"), o.optBoolean("s"), o.optInt("i"))
    }
} catch (e: Exception) { emptyList() }

private fun saveFavTmdb(items: List<TmdbItem>): String = org.json.JSONArray().apply {
    items.forEach { put(org.json.JSONObject().put("t", it.title).put("o", it.overview).put("r", it.rating).put("y", it.year).put("p", it.poster).put("b", it.backdrop).put("s", it.series).put("i", it.id)) }
}.toString()

@Composable
private fun FavChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(uiLabel(label), color = if (selected || focused) Color.White else muted, fontSize = 14.sp,
        modifier = Modifier.scale(if (focused) 1.06f else 1f).onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF3B4F8F) else if (selected) Color(0xFF2B3D72) else panel, RoundedCornerShape(50.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp))
}

private fun openYoutube(context: android.content.Context, key: String): Boolean {
    for (uri in listOf("vnd.youtube://$key", "https://www.youtube.com/watch?v=$key")) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))); return true } catch (e: Exception) { }
    }
    return false
}

@Composable
private fun ConfirmButton(label: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Button(onClick = { if (armed) { armed = false; onConfirm() } else armed = true },
        modifier = Modifier.onFocusChanged { if (!it.isFocused) armed = false }) { Text(uiLabel(if (armed) "Confirmer ?" else label)) }
}

@Composable
fun MoreChip(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(uiLabel("Voir plus") + "  ›", color = if (focused) Color.White else accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.scale(if (focused) 1.06f else 1f).onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF3B4F8F) else Color(0xFF1B2440), RoundedCornerShape(50.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp))
}
