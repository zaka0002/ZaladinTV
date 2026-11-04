package com.zaladintv.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val arabicWords = Regex("(?i)(^|[^a-z])(ar|arab|arabe|arabic|arabia|maghreb|ramadan)([^a-z]|$)")
private val arabicScript = Regex("[\\u0600-\\u06FF]")

/** A category looks Arabic: Latin markers (AR, Arabic, Arabe, Maghreb...) or Arabic script. */
fun isArabic(category: String) = arabicWords.containsMatchIn(category) || arabicScript.containsMatchIn(category)

/** Titles are only matched on Arabic script: a French title such as "L'Arabe dans le poste" is not Arabic content. */
fun isArabicChannel(c: Channel) = isArabic(c.group) || arabicScript.containsMatchIn(c.name)

/**
 * "Arabe" tab: Arabic-language movies and shows from the TMDB catalogue (two rows with "Voir plus", sort and genre filters,
 * details panel on the right), then whatever Arabic content the user's own sources contain ([library]).
 */
@Composable
fun ArabicPage(modifier: Modifier, favs: List<TmdbItem>, onFav: (TmdbItem) -> Unit, onWatch: (TmdbItem) -> Unit,
               onTrailer: (TmdbItem) -> Unit, library: @Composable () -> Unit) {
    var movies by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var more by remember { mutableStateOf<Boolean?>(null) }
    var opened by remember { mutableStateOf<TmdbItem?>(null) }
    var sortIdx by remember { mutableStateOf(0) }
    var genreIdx by remember { mutableStateOf(-1) }
    val watchFocus = remember { FocusRequester() }
    val base = "with_original_language=ar"

    LaunchedEffect(sortIdx, genreIdx, Lang.code) {
        movies = emptyList(); shows = emptyList()
        try {
            movies = withContext(Dispatchers.IO) { TmdbClient.list("discover/movie?$base&${exSortQuery(false, sortIdx)}${exGenreQuery(false, genreIdx)}") }
            shows = withContext(Dispatchers.IO) { TmdbClient.list("discover/tv?$base&${exSortQuery(true, sortIdx)}${exGenreQuery(true, genreIdx)}") }
            status = ""
        } catch (e: Exception) { status = "Catalogue : ${e.localizedMessage}" }
    }
    BackHandler(enabled = more != null && opened == null) { more = null }
    BackHandler(enabled = opened != null) { opened = null }

    Row(modifier) {
        Box(Modifier.weight(if (opened != null) 0.58f else 1f).fillMaxHeight().padding(end = if (opened != null) 16.dp else 0.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(uiLabel("Arabe"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                        PlatformGrid(Modifier.fillMaxSize(), uiLabel("Arabe"), base, more == true, favs, onFav, if (opened != null) 4 else 6, sortIdx, genreIdx) { opened = it }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (movies.isEmpty() && shows.isEmpty()) Text(uiLabel(if (status.isNotBlank()) status else "Chargement du catalogue…"), color = Color(0xFF9DA8BF))
                        DiscoverShelf(uiLabel("Films") + " · " + uiLabel("Arabe"), movies, { opened = it }, favs, onFav) { more = false }
                        DiscoverShelf(uiLabel("Séries") + " · " + uiLabel("Arabe"), shows, { opened = it }, favs, onFav) { more = true }
                        library()
                    }
                }
            }
        }
        opened?.let { f -> DetailPanel(Modifier.weight(0.42f).fillMaxHeight(), f, favs, onFav, onWatch, onTrailer, watchFocus) }
    }
}
