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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
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

data class Channel(val name: String, val url: String, val group: String, val logo: String = "", val type: String = "", val seriesId: String = "", val season: Int = 0, val episode: Int = 0, val show: String = "", val src: String = "")

data class XtAccount(val id: String, val name: String, val server: String, val user: String, val pass: String, val frOnly: Boolean = true)

/** Library entry for a TMDB title: exact title first, never an Arabic upload when a French/other one exists. */
private val nonAlnum = Regex("[^\\p{L}\\p{N}]")
/** Exact title only ("Flash" must not open "Flash Gordon"); accents, case, punctuation and a leading "The" are ignored. */
private fun normTitle(s: String) = s.lowercase().replace(nonAlnum, "").removePrefix("the")
/** Built once, off the main thread: a 100k-item library scanned with regexes on every click froze the UI (ANR). */
private fun buildLibraryIndex(channels: List<Channel>, kind: (Channel) -> String): Map<String, List<Channel>> =
    channels.groupBy { kind(it) + "|" + normTitle(TmdbClient.cleanTitle(it.name).first) }
private fun bestMatch(index: Map<String, List<Channel>>?, type: String, title: String): Channel? =
    index?.get(type + "|" + normTitle(title))
        ?.minWithOrNull(compareBy<Channel>({ if (isArabicChannel(it)) 1 else 0 }, { if (isFrench(it.group) || isFrench(it.name)) 0 else 1 }))
private class HomeLists(val featured: List<Channel>, val shelf: List<Channel>, val films: List<Channel>, val series: List<Channel>, val groups: List<Pair<String, List<Channel>>>)
private val frenchMarker =Regex("(?i)(^|[^a-z])(fr|vf|vff|vfq|vostfr|truefrench|french|france|francais|français)([^a-z]|$)")
private fun isFrench(text: String) = frenchMarker.containsMatchIn(text)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("zaladintv", MODE_PRIVATE) }
    private val appScope = kotlinx.coroutines.MainScope()
    private val vpn by lazy { Vpn(applicationContext, ProtonServers(applicationContext, Net.client)) }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) appScope.launch { vpn.connect() }
    }
    /** Asks Android for the VPN permission when needed (a system dialog), then brings the tunnel up. */
    private fun startVpn() {
        val intent = vpn.permissionIntent()
        if (intent != null) vpnConsent.launch(intent) else appScope.launch { vpn.connect() }
    }
    private val accounts = mutableStateListOf<XtAccount>()
    private val catalogs = mutableMapOf<String, List<Channel>>()
    private val sessions = mutableStateMapOf<String, XtreamClient>()
    private val statuses = mutableStateMapOf<String, String>()
    // Immutable lists swapped in one go: a 130k-item SnapshotStateList cleared and refilled on the UI thread froze the app.
    private var channels by mutableStateOf<List<Channel>>(emptyList())
    private var arabic by mutableStateOf<List<Channel>>(emptyList())
    private var rebuildGen = 0
    private var message by mutableStateOf("Ajoute une source Xtream pour commencer")

    private fun saveAccounts() {
        val arr = org.json.JSONArray()
        accounts.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("server", it.server).put("user", it.user).put("pass", it.pass).put("fr", it.frOnly)) }
        prefs.edit().putString("xt_accounts", arr.toString()).apply()
    }
    private fun loadAccounts() {
        try {
            val arr = org.json.JSONArray(prefs.getString("xt_accounts", "[]"))
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { accounts.add(XtAccount(it.getString("id"), it.getString("name"), it.getString("server"), it.getString("user"), it.getString("pass"), it.optBoolean("fr", true))) }
        } catch (e: Exception) { }
        // Older versions kept a single Xtream account in three keys.
        val xs = prefs.getString("xt_server", null); val xu = prefs.getString("xt_user", null); val xp = prefs.getString("xt_pass", null)
        if (xs != null && xu != null && xp != null && accounts.none { it.server == xs && it.user == xu }) {
            accounts.add(XtAccount(java.util.UUID.randomUUID().toString().take(8), hostOf(xs), xs, xu, xp))
        }
        prefs.edit().remove("xt_server").remove("xt_user").remove("xt_pass").remove("playlist_uri").remove("playlist_remote").apply()
        saveAccounts()
    }
    private fun hostOf(server: String) = try { Uri.parse(server).host ?: server } catch (e: Exception) { server }
    private fun rebuild() {
        // Heavy merge/filter/dedupe runs off the UI thread; only the finished lists are published (latest request wins).
        val gen = ++rebuildGen
        val accs = accounts.toList(); val cats = HashMap(catalogs)
        Thread {
            val merged = accs.flatMap { a -> cats[a.id].orEmpty().filter { !a.frOnly || isFrench(it.group) || isFrench(it.name) } }.distinctBy { "${it.type}|${it.name.lowercase()}" }
            // Arabic content ignores the French-only switch so the "Arabe" tab always shows what the sources hold.
            val ar = accs.flatMap { cats[it.id].orEmpty() }.filter { isArabicChannel(it) }.distinctBy { "${it.type}|${it.name.lowercase()}" }
            runOnUiThread {
                if (gen != rebuildGen) return@runOnUiThread
                channels = merged; arabic = ar
                message = if (accs.isEmpty()) "Ajoute une source Xtream pour commencer" else "${merged.size} films et séries"
            }
        }.start()
    }
    private fun loadAccount(a: XtAccount, onDone: (Boolean, String) -> Unit) {
        statuses[a.id] = "Chargement…"
        Thread {
            try {
                val client = XtreamClient(a.server, a.user, a.pass, a.id)
                val items = client.loadCatalog()
                runOnUiThread { catalogs[a.id] = items; sessions[a.id] = client; statuses[a.id] = "${items.size} contenus"; rebuild(); onDone(true, "${a.name} : ${items.size} contenus chargés") }
            } catch (e: Throwable) {
                val why = if (e is OutOfMemoryError) "catalogue trop gros pour la mémoire de la TV" else e.localizedMessage
                runOnUiThread { statuses[a.id] = "Erreur : $why"; onDone(false, "Connexion impossible : $why") }
            }
        }.start()
    }
    private fun addAccount(name: String, server: String, user: String, pass: String, onDone: (String) -> Unit) {
        val s = server.trim().trimEnd('/')
        if (accounts.any { it.server == s && it.user == user.trim() }) { onDone("Cette source est déjà ajoutée"); return }
        val a = XtAccount(java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { hostOf(s) }, s, user.trim(), pass)
        accounts.add(a)
        loadAccount(a) { ok, msg ->
            if (!ok) { removeAccount(a.id) } else saveAccounts()
            onDone(msg)
        }
    }
    private fun toggleFrench(id: String) {
        val i = accounts.indexOfFirst { it.id == id }
        if (i >= 0) { accounts[i] = accounts[i].copy(frOnly = !accounts[i].frOnly); saveAccounts(); rebuild() }
    }
    /** Re-downloads one source's catalogue; the old one stays in place if the server is unreachable. */
    private fun refreshAccount(id: String) {
        val a = accounts.firstOrNull { it.id == id } ?: return
        if (statuses[id] == "Chargement…") return
        loadAccount(a) { ok, msg -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun removeAccount(id: String) {
        accounts.removeAll { it.id == id }; catalogs.remove(id); sessions.remove(id); statuses.remove(id)
        saveAccounts(); rebuild()
    }
    private fun forgetAll() {
        accounts.clear(); catalogs.clear(); sessions.clear(); statuses.clear()
        saveAccounts(); rebuild()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.code = prefs.getString("lang", "fr") ?: "fr"
        Net.dns = prefs.getString("dns", DnsChoice.SYSTEM) ?: DnsChoice.SYSTEM
        // Images (Coil) and video (Media3) share the app's HTTP client, so DNS and VPN apply to them too.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(applicationContext).okHttpClient(Net.client).build())
        appScope.launch { vpn.startIfEnabled() }
        loadAccounts()
        // One account at a time: two big catalogues downloaded together doubled the memory use.
        fun loadNext(rest: List<XtAccount>) { if (rest.isNotEmpty()) loadAccount(rest.first()) { _, _ -> loadNext(rest.drop(1)) } }
        loadNext(accounts.toList())
        setContent { NovaScreen(vpn, { startVpn() }, appScope, channels, arabic, message, accounts, statuses, sessions, { n, s, u, p, done -> addAccount(n, s, u, p, done) }, { removeAccount(it) }, { toggleFrench(it) }, { refreshAccount(it) }, { forgetAll() }) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NovaScreen(vpn: Vpn, startVpn: () -> Unit, appScope: kotlinx.coroutines.CoroutineScope, channels: List<Channel>, arabic: List<Channel>, message: String, accounts: List<XtAccount>, statuses: Map<String, String>, xtreams: Map<String, XtreamClient>,
                       addAccount: (String, String, String, String, (String) -> Unit) -> Unit, removeAccount: (String) -> Unit, toggleFr: (String) -> Unit, refreshSource: (String) -> Unit, forgetSource: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zaladintv", Activity.MODE_PRIVATE) }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) } }
    var page by remember { mutableStateOf("Accueil") }
    var featuredIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var accName by remember { mutableStateOf("") }
    var dnsChoice by remember { mutableStateOf(prefs.getString("dns", DnsChoice.SYSTEM) ?: DnsChoice.SYSTEM) }
    var arabicTab by remember { mutableStateOf(prefs.getBoolean("arabic_tab", false)) }
    LaunchedEffect(arabicTab) { if (!arabicTab && page == "Arabe") page = "Accueil" }
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
    val homeScroll = rememberScrollState()
    LaunchedEffect(page) { homeScroll.scrollTo(0) }
    fun kind(ch: Channel): String = when {
        ch.type.isNotBlank() -> ch.type
        listOf("series", "séries", "tv shows", "episodes").any { ch.group.contains(it, true) } -> "Séries"
        listOf("vod", "movie", "film", "cinema", "cinéma").any { ch.group.contains(it, true) } -> "Films"
        else -> "TV en direct"
    }
    var libIndex by remember { mutableStateOf<Map<String, List<Channel>>?>(null) }
    LaunchedEffect(channels.size) {
        val snapshot = channels.toList()
        libIndex = withContext(Dispatchers.Default) { buildLibraryIndex(snapshot) { kind(it) } }
    }
    var playing by remember { mutableStateOf<Channel?>(null) }
    val recent = remember { mutableStateListOf<String>().apply { addAll(prefs.getString("recent", "")!!.split("\n").filter { it.isNotBlank() }) } }
    val startChannel: (Channel) -> Unit = { ch ->
        if (ch.type == "Séries" && ch.seriesId.isNotBlank() && xtreams[ch.src] != null) {
            selectedSeries = ch
            episodes = emptyList()
            episodeStatus = "Chargement des épisodes…"
            scope.launch {
                try {
                    episodes = withContext(Dispatchers.IO) { xtreams[ch.src]!!.loadEpisodes(ch.seriesId).map { it.copy(show = ch.name) } }
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
        val m = bestMatch(libIndex, type, item.title)
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
                            val m = bestMatch(libIndex, d.type, d.name)
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
                        val seasons = episodes.map { it.season }.distinct().sorted()
                        var epSeason by remember(episodes) { mutableIntStateOf(seasons.firstOrNull() ?: 0) }
                        val firstEp = remember { FocusRequester() }
                        // Without this the D-pad starts on "Fermer" and no episode can be reached.
                        LaunchedEffect(episodes) { if (episodes.isNotEmpty()) { kotlinx.coroutines.delay(150); try { firstEp.requestFocus() } catch (e: Exception) { } } }
                        Column {
                            Text(uiLabel(episodeStatus))
                            if (seasons.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    seasons.forEach { s -> FavChip(uiLabel("Saison") + " $s", s == epSeason) { epSeason = s } }
                                }
                            }
                            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                                episodes.filter { it.season == epSeason }.forEachIndexed { i, episode ->
                                    TextButton(onClick = { selectedSeries = null; playing = episode },
                                        modifier = if (i == 0) Modifier.focusRequester(firstEp) else Modifier) {
                                        Text(episode.name)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { selectedSeries = null }) { Text(uiLabel("Fermer")) } }
                )
            }
            val navSelected = remember { FocusRequester() }
            Column(Modifier.fillMaxSize().background(bg)) {
                // Coming up from the content lands on the current tab, not on whichever tab sits above the focused widget.
                Row(Modifier.fillMaxWidth().focusProperties { enter = { navSelected } }.focusGroup().onFocusChanged { if (it.hasFocus) scope.launch { homeScroll.animateScrollTo(0) } }.background(Brush.verticalGradient(listOf(Color(0xFF101626), bg))).padding(horizontal = 24.dp, vertical = 8.dp),
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
                        Pair("Arabe", Icons.Default.Language),
                        Pair("Favoris", Icons.Default.Favorite),
                        Pair("Recherche", Icons.Default.Search),
                        Pair("Réglages", Icons.Default.Settings),
                        Pair("Quitter", Icons.Default.Close)
                    ).filter { it.first != "Arabe" || arabicTab }.forEach { (name, icon) ->
                        NavigationItem(name, icon, page == name, navSelected) {
                            if (name == "Quitter") (context as? android.app.Activity)?.finishAffinity() else page = name
                        }
                    }
                }
                if (page == "Films" || page == "Séries") androidx.compose.runtime.key(page) { ExplorePage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), page == "Séries", favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover) }
                else if (page == "Arabe" && arabicTab) ArabicPage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover) {
                    Text(uiLabel("Dans tes sources"), color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    if (arabic.isEmpty()) Text(uiLabel("Aucun contenu arabe détecté dans tes sources (catégories AR, Arabic, Maghreb…)."), color = muted)
                    arabic.groupBy { it.group }.entries.sortedByDescending { it.value.size }.take(12).forEach { (g, items) -> ChannelShelf(g, items.take(40), favorites, playChannel, toggleFavorite) }
                }
                else if (page == "Plateformes") PlatformsPage(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), favTmdb, toggleFavTmdb, watchDiscover, trailerDiscover)
                else Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(if (panelItem != null) 0.58f else 1f).fillMaxHeight().verticalScroll(homeScroll).padding(horizontal = 38.dp, vertical = 12.dp)) {
                    Spacer(Modifier.height(8.dp))
                    when (page) {
                        "Accueil" -> {
                            // "À la une" only shows the French catalogue, whatever the per-source filter says.
                            // Computed once per catalogue change: scanning a 100k-item library on every recomposition froze the UI (ANR).
                            val home by remember { derivedStateOf {
                                val fr = channels.asSequence().filter { !isArabicChannel(it) && (isFrench(it.group) || isFrench(it.name)) }.take(400).toList()
                                HomeLists(
                                    featured = fr.filter { kind(it) == "Films" || kind(it) == "Séries" }.take(8),
                                    shelf = fr.take(18),
                                    films = channels.asSequence().filter { kind(it) == "Films" }.take(18).toList(),
                                    series = channels.asSequence().filter { kind(it) == "Séries" }.take(18).toList(),
                                    // First 8 groups only, 18 items each: one cheap pass instead of grouping the whole library.
                                    groups = run {
                                        val m = LinkedHashMap<String, ArrayList<Channel>>()
                                        for (c in channels) {
                                            val l = m[c.group] ?: if (m.size < 8) ArrayList<Channel>().also { m[c.group] = it } else continue
                                            if (l.size < 18) l.add(c)
                                        }
                                        m.entries.map { it.key to it.value.toList() }
                                    }
                                )
                            } }
                            val featured = home.featured
                            val hero = featured.getOrNull(featuredIndex % featured.size.coerceAtLeast(1))
                            val heroImg = if (hero != null) rememberPoster(hero) else ""
                            if (hero != null) {
                            Box(Modifier.fillMaxWidth().height(260.dp).onFocusChanged { if (it.hasFocus) scope.launch { homeScroll.animateScrollTo(0) } }.clip(RoundedCornerShape(22.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF392358), Color(0xFF172744), Color(0xFF0C1220))))) {
                                if (heroImg.startsWith("https://", true)) {
                                    // Faint backdrop with a dark fade from the left so the text stays readable.
                                    AsyncImage(model = heroImg, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, alpha = 0.3f, modifier = Modifier.fillMaxSize())
                                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF2101626), Color(0xB3101626), Color(0x33101626)))))
                                }
                                // The poster slot is always drawn, so changing title with the arrows never makes the layout jump.
                                Box(Modifier.align(Alignment.CenterEnd).padding(end = 190.dp).width(150.dp).height(225.dp)
                                    .clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF433A72), Color(0xFF204263))))
                                    .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    if (heroImg.startsWith("https://", true)) AsyncImage(model = heroImg, contentDescription = hero.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Icon(Icons.Default.Movie, null, tint = Color(0xFFE2D6FF), modifier = Modifier.size(40.dp))
                                }
                                Box(Modifier.align(Alignment.CenterEnd).size(270.dp)
                                    .background(Brush.radialGradient(listOf(Color(0x665D53B8), Color.Transparent))))
                                Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.62f).padding(32.dp)) {
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
                                        HeroArrow(Icons.Default.ArrowBack) { featuredIndex = (featuredIndex + featured.size - 1) % featured.size }
                                        HeroArrow(Icons.Default.ArrowForward) { featuredIndex = (featuredIndex + 1) % featured.size }
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
                                if (home.shelf.isNotEmpty()) ChannelShelf("À la une", home.shelf, favorites, playChannel, toggleFavorite) { search = ""; page = "Recherche" }
                                ChannelShelf("Films", home.films, favorites, playChannel, toggleFavorite) { page = "Films" }
                                ChannelShelf("Séries", home.series, favorites, playChannel, toggleFavorite) { page = "Séries" }
                                home.groups.forEach { (group, items) ->
                                    ChannelShelf(group, items, favorites, playChannel, toggleFavorite) { search = group; page = "Recherche" }
                                }
                            }
                        }
                        "Recherche" -> {
                            TvField(value = search, onValueChange = { search = it }, label = { Text(uiLabel("Rechercher une chaîne, un film ou une série")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(18.dp))
                            ChannelGrid(remember(search, channels.size) { channels.asSequence().filter { it.name.contains(search, true) || it.group.contains(search, true) }.take(300).toList() }, favorites, playChannel, toggleFavorite)
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                // Sidebar, like Apple TV settings: moving over an entry shows its page.
                                Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(uiLabel("Réglages"), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(10.dp))
                                    listOf("Bibliothèque" to Icons.Default.VideoLibrary, "Langue" to Icons.Default.Language, "Menu" to Icons.Default.Menu,
                                        "Lecture" to Icons.Default.PlayArrow, "DNS" to Icons.Default.Dns, "VPN" to Icons.Default.VpnKey, "Données" to Icons.Default.Delete, "À propos" to Icons.Default.Info
                                    ).forEach { (t, ic) -> SideItem(ic, t, settingsTab == t) { settingsTab = t } }
                                }
                                Column(Modifier.weight(1f)) {
                                    when (settingsTab) {
                                        "Bibliothèque" -> {
                                            SectionTitle("Sources")
                                            SettingsCard {
                                                if (accounts.isEmpty()) Text(uiLabel("Aucune source pour l'instant."), color = muted)
                                                accounts.forEachIndexed { i, acc ->
                                                    if (i > 0) Spacer(Modifier.height(8.dp))
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        ToggleRow(acc.name, acc.server + "  •  " + uiLabel(statuses[acc.id] ?: "") + "  •  " + uiLabel("Français seulement"), acc.frOnly, Modifier.weight(1f)) { toggleFr(acc.id) }
                                                        PillButton(if (statuses[acc.id] == "Chargement…") "Mise à jour…" else "Mettre à jour") { refreshSource(acc.id) }
                                                        RemoveButton { removeAccount(acc.id) }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                Column(Modifier.weight(1f)) {
                                                    SectionTitle("Ajouter avec le téléphone")
                                                    SettingsCard {
                                                        PhoneSetupCard("Scanne avec ton téléphone (même Wi-Fi), puis saisis ta source Xtream.") { setStatus ->
                                                            PhoneSetupServer(onXtream = { n, s, u, pw -> setStatus(uiLabel("Reçu, chargement…")); addAccount(n, s, u, pw) { r -> setStatus(r) } })
                                                        }
                                                    }
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    SectionTitle("Ajout manuel")
                                                    SettingsCard {
                                                        TvField(value = accName, onValueChange = { accName = it }, label = { Text(uiLabel("Nom (facultatif)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                                        Spacer(Modifier.height(6.dp))
                                                        TvField(value = server, onValueChange = { server = it }, label = { Text(uiLabel("Serveur (http://…)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                                        Spacer(Modifier.height(6.dp))
                                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                            TvField(value = username, onValueChange = { username = it }, label = { Text(uiLabel("Identifiant")) }, modifier = Modifier.weight(1f), singleLine = true)
                                                            TvField(value = password, onValueChange = { password = it }, label = { Text(uiLabel("Mot de passe")) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f), singleLine = true)
                                                        }
                                                        Spacer(Modifier.height(8.dp))
                                                        Button(onClick = {
                                                            remoteStatus = uiLabel("Chargement…")
                                                            addAccount(accName, server, username, password) { r ->
                                                                remoteStatus = r
                                                                if (!r.startsWith("Connexion impossible") && !r.startsWith("Cette source")) { accName = ""; server = ""; username = ""; password = "" }
                                                            }
                                                        }, enabled = (server.startsWith("https://") || server.startsWith("http://")) && username.isNotBlank() && password.isNotBlank()) { Text(uiLabel("Ajouter la source")) }
                                                        if (remoteStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(uiLabel(remoteStatus), color = muted, fontSize = 13.sp) }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text(uiLabel(message) + "  •  " + uiLabel("Ajoute uniquement des sources auxquelles tu as droit."), color = muted, fontSize = 12.sp)
                                        }
                                        "Langue" -> {
                                            SectionTitle("Langue de l'application et des fiches")
                                            SettingsCard {
                                                listOf("fr" to "Français", "en" to "English").forEachIndexed { i, (code, name) ->
                                                    if (i > 0) Spacer(Modifier.height(6.dp))
                                                    CheckRow(name, Lang.code == code) { setLang(code) }
                                                }
                                            }
                                        }
                                        "Menu" -> {
                                            SectionTitle("Onglets du menu")
                                            SettingsCard {
                                                ToggleRow(uiLabel("Onglet Arabe"), uiLabel("Active les onglets supplémentaires du menu du haut."), arabicTab) {
                                                    arabicTab = !arabicTab; prefs.edit().putBoolean("arabic_tab", arabicTab).apply()
                                                }
                                            }
                                        }
                                        "Lecture" -> {
                                            SectionTitle("Lecture")
                                            SettingsCard {
                                                ToggleRow(uiLabel("Reprendre là où je me suis arrêté"), "", resumeOn) { resumeOn = !resumeOn; prefs.edit().putBoolean("resume", resumeOn).apply() }
                                            }
                                        }
                                        "DNS" -> {
                                            SectionTitle("DNS")
                                            Text(uiLabel("Choisis le serveur DNS utilisé par ZaladinTV (chiffré). Il ne change que le trafic de l'appli, pas le reste de la TV."), color = muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
                                            SettingsCard {
                                                listOf(DnsChoice.SYSTEM to "Système (par défaut)", DnsChoice.ADGUARD to "AdGuard (bloque pubs et traqueurs)",
                                                    DnsChoice.ADGUARD_FAMILY to "AdGuard Famille (filtre adulte)", DnsChoice.ADGUARD_UNFILTERED to "AdGuard sans filtre",
                                                    DnsChoice.CLOUDFLARE to "Cloudflare 1.1.1.1").forEachIndexed { i, (key, label) ->
                                                    if (i > 0) Spacer(Modifier.height(6.dp))
                                                    CheckRow(uiLabel(label), dnsChoice == key) { dnsChoice = key; Net.dns = key; prefs.edit().putString("dns", key).apply() }
                                                }
                                            }
                                        }
                                        "VPN" -> VpnPage(vpn, startVpn, appScope)
                                        "Données" -> {
                                            SectionTitle("Données")
                                            SettingsCard {
                                                ConfirmButton("Vider les favoris") { favTmdb.clear(); favorites.clear(); prefs.edit().remove("fav_tmdb").remove("favorites").apply(); dataStatus = "Fait" }
                                                Spacer(Modifier.height(10.dp))
                                                ConfirmButton("Effacer « Reprendre le visionnage »") { recent.clear(); prefs.edit().remove("recent").apply(); dataStatus = "Fait" }
                                                Spacer(Modifier.height(10.dp))
                                                ConfirmButton("Oublier toutes les sources") { forgetSource(); dataStatus = "Fait" }
                                                Spacer(Modifier.height(10.dp))
                                                ConfirmButton("Vider le cache des images") {
                                                    context.imageLoader.memoryCache?.clear(); context.imageLoader.diskCache?.clear(); dataStatus = "Fait"
                                                }
                                                if (dataStatus.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(uiLabel(dataStatus), color = muted) }
                                            }
                                        }
                                        "À propos" -> {
                                            SectionTitle("Mise à jour")
                                            SettingsCard { UpdateCard() }
                                            Spacer(Modifier.height(18.dp))
                                            SectionTitle("À propos")
                                            SettingsCard {
                                                Text("ZaladinTV  " + BuildConfig.VERSION_NAME, color = Color.White, fontSize = 19.sp)
                                                Spacer(Modifier.height(8.dp))
                                                Text(uiLabel("Licence MIT"), color = muted)
                                                Spacer(Modifier.height(12.dp))
                                                Text(uiLabel("Les fiches, affiches et bandes-annonces viennent de TMDB. Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB."), color = muted, fontSize = 13.sp)
                                            }
                                        }
                                    }
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
private fun NavigationItem(name: String, icon: ImageVector, selected: Boolean, selectedRequester: FocusRequester, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50.dp)
    var focused by remember { mutableStateOf(false) }
    Row((if (selected) Modifier.focusRequester(selectedRequester) else Modifier).scale(if (focused) 1.06f else 1f).background(if (focused) Color(0xFF3B4F8F) else if (selected) Color(0xFF2B3D72) else Color.Transparent, shape).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, uiLabel(name), tint = if (selected) Color.White else muted, modifier = Modifier.size(16.dp))
        // "Quitter" is icon-only so the nine tabs fit on one line.
        if (name != "Quitter") {
            Spacer(Modifier.width(5.dp))
            Text(uiLabel(name), color = if (selected) Color.White else muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
        }
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
        ExoPlayer.Builder(context).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Net.client))
        ).build().apply {
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

private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

@Composable
private fun rememberPoster(channel: Channel): String {
    // Remembered across tab switches: coming back to Accueil no longer repeats the TMDB lookup.
    val key = channel.type + "|" + channel.name
    var poster by remember(channel.url) { mutableStateOf(channel.logo.ifBlank { posterCache[key].orEmpty() }) }
    LaunchedEffect(channel.url) {
        if (poster.isBlank() && (channel.type == "Films" || channel.type == "Séries" || channel.type.isBlank())) {
            poster = withContext(Dispatchers.IO) { TmdbClient.poster(channel.name, channel.type == "Séries") }
            if (poster.isNotBlank()) posterCache[key] = poster
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
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(editing) { if (editing) keyboard?.show() else keyboard?.hide() }
    OutlinedTextField(value = value, onValueChange = onValueChange, label = label, singleLine = singleLine,
        visualTransformation = visualTransformation, readOnly = !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) editing = false }.onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            val dir = when (e.key) {
                Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
                Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
                Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                else -> null
            }
            val vertical = dir == androidx.compose.ui.focus.FocusDirection.Up || dir == androidx.compose.ui.focus.FocusDirection.Down
            // Up/Down reach the app only when the on-screen keyboard is hidden (Back closes it without ending "editing"), so they always leave the field.
            if (dir != null && (!editing || vertical)) {
                if (editing) editing = false
                // Not typing: D-pad must move the focus (to the menu bar, the sidebar, the next field), never get stuck in the text field.
                if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(dir)
                true
            } else if (!editing && ok) { if (e.type == KeyEventType.KeyUp) editing = true; true } else false
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

/** Round previous/next button of the home banner: solid dark disc, white arrow, white ring when focused. */
@Composable
private fun HeroArrow(icon: ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(50.dp).scale(if (focused) 1.12f else 1f).clip(CircleShape)
        .background(if (focused) Color(0xFF5B4AA8) else Color(0xCC0B1020))
        .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x66FFFFFF), CircleShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

// ---------------------------------------------------------------- Apple-style settings building blocks
private val cardBg = Color(0xFF161B28)

@Composable
private fun SectionTitle(text: String) {
    Text(uiLabel(text).uppercase(), color = Color(0xFF8A93A8), fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cardBg)
        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp)).padding(12.dp), content = content)
}

@Composable
private fun SideItem(icon: ImageVector, label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (focused) Color(0xFF3B4F8F) else if (selected) Color(0xFF232C45) else Color.Transparent)
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }.clickable(onClick = onSelect)
        .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected || focused) Color.White else muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(uiLabel(label), color = if (selected || focused) Color.White else muted, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** One focus target per row (like Apple TV): press OK anywhere on the row to flip the switch. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, showSwitch: Boolean = true, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (focused) Color(0xFF2B3552) else Color(0xFF1C2232))
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showSwitch) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(54.dp).height(31.dp).clip(RoundedCornerShape(50.dp)).background(if (checked) Color(0xFF34C759) else Color(0xFF3A3F4F))) {
                Box(Modifier.padding(3.dp).align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(25.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (focused) Color(0xFF2B3552) else Color(0xFF1C2232))
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = Color(0xFF34C759), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(uiLabel(label), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50.dp))
            .background(if (focused) Color(0xFF3B4F8F) else Color(0xFF2B3D72))
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp))
}

/** Two presses: the first one asks for confirmation, so a source can't be removed by a stray OK. */
@Composable
private fun RemoveButton(onRemove: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    Text(uiLabel(if (armed) "Confirmer ?" else "Supprimer"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50.dp))
            .background(if (armed) Color(0xFFD64545) else if (focused) Color(0xFF3B4F8F) else Color(0xFF2A3047))
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50.dp))
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) armed = false }
            .clickable { if (armed) { armed = false; onRemove() } else armed = true }
            .padding(horizontal = 18.dp, vertical = 12.dp))
}

/** Settings > VPN: switch, country, public address check, and the QR code to send the WireGuard key from a phone. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VpnPage(vpn: Vpn, startVpn: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val state by vpn.state.collectAsState()
    val enabled by vpn.enabled.collectAsState()
    val configured by vpn.configured.collectAsState()
    val choices by vpn.choices.collectAsState()
    val selected by vpn.selected.collectAsState()
    var ip by remember { mutableStateOf("") }
    LaunchedEffect(state) { if (state is VpnState.NeedsPermission && enabled) startVpn() }
    val status = when (val st = state) {
        VpnState.Off -> uiLabel("Désactivé")
        VpnState.Connecting -> uiLabel("Connexion…")
        is VpnState.On -> uiLabel("Connecté") + " · " + st.server
        VpnState.NeedsPermission -> uiLabel("Autorisation Android requise")
        is VpnState.Failed -> uiLabel("Échec") + " : " + st.message
    }
    SectionTitle("VPN")
    Text(uiLabel("VPN WireGuard réservé à ZaladinTV : le reste de la TV garde sa connexion normale. Tu fournis ta propre clé (par exemple un fichier WireGuard gratuit de Proton VPN)."),
        color = muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            SettingsCard {
                ToggleRow(uiLabel("Activer le VPN"), if (configured) status else uiLabel("Ajoute d'abord une clé."), enabled && configured) {
                    if (!configured) return@ToggleRow
                    if (enabled) { vpn.setEnabled(false); scope.launch { vpn.disconnect() } } else { vpn.setEnabled(true); startVpn() }
                }
                if (choices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { code -> FavChip(Vpn.countryName(code), selected == code) { scope.launch { vpn.select(code) } } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(uiLabel("Mon adresse IP"), if (ip.isBlank()) uiLabel("Appuie pour vérifier") else ip, false, showSwitch = false) {
                    ip = uiLabel("Vérification…")
                    scope.launch { ip = try { vpn.publicIp(Net.client) } catch (e: Exception) { uiLabel("Échec") + " : " + e.localizedMessage } }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            SettingsCard {
                PhoneSetupCard("Scanne avec ton téléphone (même Wi-Fi), puis colle le fichier WireGuard.") { setStatus ->
                    PhoneSetupServer(onVpn = { text ->
                        val n = vpn.importConfigs(text)
                        if (n > 0) { vpn.setEnabled(true); startVpn(); setStatus("$n " + uiLabel("clé(s) reçue(s)")) } else setStatus(uiLabel("Aucune clé WireGuard valide dans ce texte."))
                    })
                }
            }
        }
    }
}
