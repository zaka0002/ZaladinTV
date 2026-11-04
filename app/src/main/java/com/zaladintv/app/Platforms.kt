package com.zaladintv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// TMDB provider ids, in display order (Netflix, Prime Video, Disney+, Apple TV+, Paramount+, Canal+, Max, Crunchyroll, OCS, france.tv, ADN)
private val wantedProviders = listOf(8, 119, 337, 350, 531, 381, 1899, 283, 56, 236, 415)

/**
 * Platform logos; clicking one shows that platform's movies and shows (TMDB catalogue, France) in two rows of about twenty titles,
 * each with a "Voir plus" button that opens the full list. Clicking a poster opens the details panel on the right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlatformsPage(modifier: Modifier, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit, onWatch: (TmdbItem) -> Unit, onTrailer: (TmdbItem) -> Unit) {
    var logos by remember { mutableStateOf<List<TmdbProvider>>(emptyList()) }
    var selected by remember { mutableStateOf<TmdbProvider?>(null) }
    var movies by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var more by remember { mutableStateOf<Boolean?>(null) }
    var opened by remember { mutableStateOf<TmdbItem?>(null) }
    var sortIdx by remember { mutableStateOf(0) }
    var genreIdx by remember { mutableStateOf(-1) }
    val watchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { logos = withContext(Dispatchers.IO) { TmdbClient.providers(wantedProviders) } } catch (e: Exception) { status = "Catalogue : ${e.localizedMessage}" }
    }
    LaunchedEffect(selected?.id, sortIdx, genreIdx, Lang.code) {
        val p = selected ?: return@LaunchedEffect
        movies = emptyList(); shows = emptyList()
        try {
            val base = "with_watch_providers=${p.id}&watch_region=FR"
            movies = withContext(Dispatchers.IO) { TmdbClient.list("discover/movie?$base&${exSortQuery(false, sortIdx)}${exGenreQuery(false, genreIdx)}") }
            shows = withContext(Dispatchers.IO) { TmdbClient.list("discover/tv?$base&${exSortQuery(true, sortIdx)}${exGenreQuery(true, genreIdx)}") }
            status = ""
        } catch (e: Exception) { status = "Catalogue : ${e.localizedMessage}" }
    }
    // Back: close the details panel, then the full list, then the platform.
    BackHandler(enabled = selected != null && more == null && opened == null) { selected = null; sortIdx = 0; genreIdx = -1 }
    BackHandler(enabled = more != null && opened == null) { more = null }
    BackHandler(enabled = opened != null) { opened = null }

    val sel = selected
    if (sel == null) {
        Column(modifier) {
            if (logos.isEmpty()) Text(uiLabel(if (status.isNotBlank()) status else "Chargement du catalogue…"), color = Color(0xFF9DA8BF))
            LazyVerticalGrid(GridCells.Fixed(5), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                items(logos, key = { it.id }) { p ->
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.scale(if (focused) 1.06f else 1f).onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(18.dp)).background(if (focused) Color(0xFF2B3D72) else Color(0xFF141B2A))
                        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(18.dp))
                        .clickable { selected = p }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = p.logo, contentDescription = p.name, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                        Spacer(Modifier.height(8.dp))
                        Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    } else {
        Row(modifier) {
            Box(Modifier.weight(if (opened != null) 0.58f else 1f).fillMaxHeight().padding(end = if (opened != null) 16.dp else 0.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = sel.logo, contentDescription = sel.name, contentScale = ContentScale.Fit,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(12.dp))
                        Text(sel.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ExDropdown(exSortLabels[sortIdx], exSortLabels) { i -> sortIdx = i; opened = null }
                            ExDropdown(if (genreIdx < 0) "Tous les genres" else platformGenres[genreIdx].first,
                                listOf("Tous les genres") + platformGenres.map { it.first }) { i -> genreIdx = i - 1; opened = null }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                if (more != null) {
                    androidx.compose.runtime.key(sortIdx, genreIdx) {
                        PlatformGrid(Modifier.fillMaxSize(), sel, more == true, favs, onFav, if (opened != null) 4 else 6, sortIdx, genreIdx) { opened = it }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (movies.isEmpty() && shows.isEmpty()) Text(uiLabel(if (status.isNotBlank()) status else "Chargement du catalogue…"), color = Color(0xFF9DA8BF))
                        DiscoverShelf(uiLabel("Films") + " · " + sel.name, movies, { opened = it }, favs, onFav) { more = false }
                        DiscoverShelf(uiLabel("Séries") + " · " + sel.name, shows, { opened = it }, favs, onFav) { more = true }
                    }
                }
                }
            }
            opened?.let { f -> DetailPanel(Modifier.weight(0.42f).fillMaxHeight(), f, favs, onFav, onWatch, onTrailer, watchFocus) }
        }
    }
}

/** Full list of a platform's movies or shows as a poster grid, loading more pages while scrolling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlatformGrid(modifier: Modifier, p: TmdbProvider, series: Boolean, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit,
                         columns: Int, sortIdx: Int, genreIdx: Int, onOpen: (TmdbItem) -> Unit) {
    var items by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(page, Lang.code) {
        loading = true
        try {
            val kind = if (series) "tv" else "movie"
            val res = withContext(Dispatchers.IO) { TmdbClient.list("discover/$kind?with_watch_providers=${p.id}&watch_region=FR&${exSortQuery(series, sortIdx)}${exGenreQuery(series, genreIdx)}&page=$page") }
            items = (items + res).distinctBy { it.id }
            status = ""
        } catch (e: Exception) { status = "Catalogue : ${e.localizedMessage}" }
        loading = false
    }
    Column(modifier) {
        Text(uiLabel(if (series) "Séries" else "Films") + " · " + p.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) Text(uiLabel(if (status.isNotBlank()) status else "Chargement du catalogue…"), color = Color(0xFF9DA8BF))
        LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                var focused by remember { mutableStateOf(false) }
                Box(Modifier.aspectRatio(2f / 3f).scale(if (focused) 1.05f else 1f).clip(RoundedCornerShape(12.dp))
                    .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused && i >= items.size - 12 && !loading && page < 40) page += 1 }
                    .combinedClickable(onClick = { onOpen(item) }, onLongClick = { onFav(item) })) {
                    AsyncImage(model = item.poster, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (favs.contains(item)) Icon(Icons.Default.Favorite, null, tint = Color(0xFFAA89FF),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
                }
            }
        }
    }
}
