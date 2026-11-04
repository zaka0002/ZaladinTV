package com.zaladintv.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val exPanel = Color(0xFF141B2A)
private val exAccent = Color(0xFFAA89FF)
private val exMuted = Color(0xFF9DA8BF)

private val movieGenres = listOf("Action" to 28, "Aventure" to 12, "Animation" to 16, "Comédie" to 35, "Crime" to 80, "Documentaire" to 99,
    "Drame" to 18, "Famille" to 10751, "Fantastique" to 14, "Horreur" to 27, "Romance" to 10749, "Science-fiction" to 878, "Thriller" to 53)
private val tvGenres = listOf("Action et aventure" to 10759, "Animation" to 16, "Comédie" to 35, "Crime" to 80, "Documentaire" to 99,
    "Drame" to 18, "Famille" to 10751, "Mystère" to 9648, "Science-fiction et fantastique" to 10765)
private val sorts = listOf("Populaires", "Mieux notés", "Récents")
// TMDB watch-provider ids (catalogue filter only, availability in France)
private val providers = listOf("Netflix" to 8, "Prime Video" to 119, "Disney+" to 337, "Apple TV+" to 350, "Paramount+" to 531, "Canal+" to 381, "Max" to 1899, "Crunchyroll" to 283)

/** Discover screen: filters on top, poster grid on the left, details of the focused title on the right. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExplorePage(modifier: Modifier, series: Boolean, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit,
                onWatch: (TmdbItem) -> Unit, onTrailer: (TmdbItem) -> Unit) {
    var sortIdx by remember { mutableIntStateOf(0) }
    var genreIdx by remember { mutableIntStateOf(-1) }
    var providerIdx by remember { mutableIntStateOf(-1) }
    var page by remember { mutableIntStateOf(1) }
    var items by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<TmdbItem?>(null) }
    var lastOpened by remember { mutableStateOf<TmdbItem?>(null) }
    val watchFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    // Panel opens -> focus its Watch button so the remote can reach the actions; panel closes -> focus returns to the poster.
    LaunchedEffect(opened) {
        delay(150)
        try { if (opened != null) watchFocus.requestFocus() else if (lastOpened != null) restoreFocus.requestFocus() } catch (e: Exception) { }
    }
    val genres = if (series) tvGenres else movieGenres

    LaunchedEffect(series, sortIdx, genreIdx, providerIdx, page, Lang.code) {
        loading = true
        try {
            val base = if (series) "tv" else "movie"
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val sort = when (sortIdx) {
                0 -> "sort_by=popularity.desc"
                1 -> "sort_by=vote_average.desc&vote_count.gte=${if (series) 300 else 500}"
                else -> if (series) "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=20"
                else "sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=50"
            }
            val g = (if (genreIdx >= 0) "&with_genres=${genres[genreIdx].second}" else "") +
                (if (providerIdx >= 0) "&with_watch_providers=${providers[providerIdx].second}&watch_region=FR" else "")
            val res = withContext(Dispatchers.IO) { TmdbClient.list("discover/$base?$sort$g&page=$page") }
            items = if (page == 1) res else (items + res).distinctBy { "${it.series}|${it.id}" }
            status = ""
        } catch (e: Exception) { status = "Catalogue : ${e.localizedMessage}" }
        loading = false
    }
    androidx.activity.compose.BackHandler(enabled = opened != null) { opened = null }

    Row(modifier) {
        Column(Modifier.weight(if (opened != null) 0.58f else 1f).fillMaxHeight().padding(end = if (opened != null) 16.dp else 0.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExDropdown(sorts[sortIdx], sorts) { i -> sortIdx = i; page = 1; opened = null }
                ExDropdown(if (genreIdx < 0) "Tous les genres" else genres[genreIdx].first, listOf("Tous les genres") + genres.map { it.first }) { i ->
                    genreIdx = i - 1; page = 1; opened = null
                }
                ExDropdown(if (providerIdx < 0) "Plateformes" else providers[providerIdx].first, listOf("Plateformes") + providers.map { it.first }) { i ->
                    providerIdx = i - 1; page = 1; opened = null
                }
            }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) Text(uiLabel(if (status.isNotBlank()) status else if (loading) "Chargement du catalogue…" else "Aucune chaîne à afficher."), color = exMuted)
            LazyVerticalGrid(GridCells.Fixed(if (opened != null) 4 else 6), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items, key = { _, it -> "${it.series}|${it.id}|${it.title}" }) { i, item ->
                    var isFocused by remember { mutableStateOf(false) }
                    Box(Modifier.aspectRatio(2f / 3f).then(if (item == lastOpened) Modifier.focusRequester(restoreFocus) else Modifier).scale(if (isFocused) 1.05f else 1f).clip(RoundedCornerShape(12.dp))
                        .border(if (isFocused) 3.dp else 0.dp, if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused && i >= items.size - 8 && !loading && page < 25) page += 1
                        }
                        .combinedClickable(onClick = { opened = item; lastOpened = item }, onLongClick = { onFav(item) })) {
                        AsyncImage(model = item.poster, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (favs.contains(item)) Icon(Icons.Default.Favorite, null, tint = exAccent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                        if (isFocused) Column(Modifier.fillMaxSize().background(Color(0xE6050810)).padding(8.dp)) {
                            if (item.rating > 0) Text("★ %.1f".format(item.rating), color = Color(0xFFFFD66B), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (item.year.isNotBlank()) Text(item.year, color = exMuted, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(item.overview.ifBlank { uiLabel("Pas de résumé disponible.") }, color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        opened?.let { f -> DetailPanel(Modifier.weight(0.42f).fillMaxHeight(), f, favs, onFav, onWatch, onTrailer, watchFocus) }
    }
}

/** Details of a title (synopsis, genres, cast) with trailer / favourite / watch buttons. Used by Films, Séries and Plateformes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailPanel(modifier: Modifier, f: TmdbItem, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit,
                onWatch: (TmdbItem) -> Unit, onTrailer: (TmdbItem) -> Unit, watchFocus: FocusRequester) {
    var detail by remember(f.id) { mutableStateOf<TmdbDetail?>(null) }
    LaunchedEffect(f.id, Lang.code) {
        detail = null
        if (f.id > 0) {
            delay(250)
            detail = try { withContext(Dispatchers.IO) { TmdbClient.details(f.id, f.series) } } catch (e: Exception) { null }
        }
    }
    // Opening the panel moves the remote's focus to the Watch button so the actions are reachable.
    LaunchedEffect(f.id) { delay(150); try { watchFocus.requestFocus() } catch (e: Exception) { } }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(exPanel)) {
        if (f.backdrop.isNotBlank()) AsyncImage(model = f.backdrop, contentDescription = null, contentScale = ContentScale.Crop,
            alpha = 0.22f, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            // Focusable so the remote can scroll the text (Up from the buttons, then Up/Down).
            var textFocused by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f).onFocusChanged { textFocused = it.isFocused }.verticalScroll(rememberScrollState()).focusable()
                .border(if (textFocused) 2.dp else 0.dp, if (textFocused) exAccent else Color.Transparent, RoundedCornerShape(20.dp)).padding(14.dp)) {
                val d = detail
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (f.poster.isNotBlank()) AsyncImage(model = f.poster, contentDescription = f.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(84.dp).height(126.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(f.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        if (f.rating > 0) Text("★ %.1f / 10".format(f.rating), color = Color(0xFFFFD66B), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val runtime = d?.runtime?.takeIf { it > 0 }?.let { "$it min" }.orEmpty()
                        val meta = listOf(f.year, runtime, uiLabel(if (f.series) "Séries" else "Films")).filter { it.isNotBlank() }.joinToString("  •  ")
                        Spacer(Modifier.height(3.dp))
                        Text(meta, color = exAccent, fontSize = 11.sp)
                        if (d != null && d.genres.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(d.genres.take(3).joinToString(" · "), color = exMuted, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ExSection("Synopsis", emptyList())
                Text(f.overview.ifBlank { uiLabel("Pas de synopsis disponible.") }, color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
                if (d != null) {
                    if (d.people.isNotEmpty()) ExSection(if (f.series) "Création" else "Réalisation", d.people)
                    if (d.cast.isNotEmpty()) ExSection("Distribution", d.cast)
                }
                Spacer(Modifier.height(8.dp))
                Text(uiLabel("Retour : fermer   •   OK maintenu : favori"), color = exMuted, fontSize = 8.sp)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ExRound(Icons.Default.Theaters, "Bande-annonce") { onTrailer(f) }
                ExRound(if (favs.contains(f)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favoris") { onFav(f) }
                ExWatch(watchFocus) { onWatch(f) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExSection(title: String, values: List<String>) {
    Spacer(Modifier.height(9.dp))
    Text(uiLabel(title).uppercase(), color = exMuted, fontSize = 9.sp, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(4.dp))
    if (values.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { Text(it, color = Color.White, fontSize = 10.sp, modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(50.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) }
    }
}

@Composable
private fun ExRound(icon: ImageVector, desc: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.size(40.dp).scale(if (focused) 1.12f else 1f).clip(CircleShape)
        .background(if (focused) Color(0xFF3B4F8F) else Color(0x55FFFFFF))
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, CircleShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, uiLabel(desc), tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ExWatch(requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.scale(if (focused) 1.06f else 1f).clip(RoundedCornerShape(50.dp))
        .background(if (focused) Color(0xFF3FD68A) else Color(0xFF2EB872))
        .focusRequester(requester)
        .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(50.dp))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(uiLabel("Regarder"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Genres shared by movies and shows: name, TMDB movie genre id, TMDB tv genre id. */
val platformGenres = listOf(Triple("Action", 28, 10759), Triple("Aventure", 12, 10759), Triple("Animation", 16, 16), Triple("Comédie", 35, 35),
    Triple("Crime", 80, 80), Triple("Documentaire", 99, 99), Triple("Drame", 18, 18), Triple("Famille", 10751, 10751),
    Triple("Fantastique", 14, 10765), Triple("Science-fiction", 878, 10765), Triple("Thriller / Mystère", 53, 9648))
val exSortLabels get() = sorts

/** TMDB discover query part for a sort choice (0 popular, 1 top rated, 2 recent). */
fun exSortQuery(series: Boolean, sortIdx: Int): String {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return when (sortIdx) {
        0 -> "sort_by=popularity.desc"
        1 -> "sort_by=vote_average.desc&vote_count.gte=${if (series) 300 else 500}"
        else -> if (series) "sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=20"
        else "sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=50"
    }
}

fun exGenreQuery(series: Boolean, genreIdx: Int): String =
    if (genreIdx < 0) "" else "&with_genres=${platformGenres[genreIdx].let { if (series) it.third else it.second }}"

@Composable
fun ExDropdown(label: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ExChip(label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(exPanel).heightIn(max = 420.dp)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(uiLabel(o), color = if (uiLabel(o) == uiLabel(label)) exAccent else Color.White, fontSize = 15.sp) },
                    onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun ExChip(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(uiLabel(label) + "  ▾", color = if (focused) Color.White else exMuted, fontSize = 13.sp,
        modifier = Modifier.scale(if (focused) 1.05f else 1f).onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF3B4F8F) else exPanel, RoundedCornerShape(50.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp))
}
