package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Library
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Everything the home screen shows, loaded in parallel. */
private data class HomeData(
    val trending: List<Title>,
    val popularMovies: List<Title>,
    val popularTv: List<Title>,
    val nowPlaying: List<Title>,
    val topMovies: List<Title>,
    val topTv: List<Title>,
)

@Composable
fun HomeScreen(nav: Nav) {
    val hidden = remember { Library.hidden().map { "${it.type}-${it.id}" }.toSet() }
    fun List<Title>.visible() = filterNot { "${it.type}-${it.id}" in hidden }

    val data by rememberLoad {
        coroutineScope {
            val tr = async { Tmdb.trending() }
            val pm = async { Tmdb.popular("movie") }
            val pt = async { Tmdb.popular("tv") }
            val np = async { Tmdb.nowPlaying() }
            val tm = async { Tmdb.topRated("movie") }
            val tt = async { Tmdb.topRated("tv") }
            HomeData(tr.await(), pm.await(), pt.await(), np.await(), tm.await(), tt.await())
        }
    }
    val continueWatching = remember { Library.continueWatching() }
    val watchlist = remember { Library.watchlist() }
    val favorites = remember { Library.favorites() }
    val scope = rememberCoroutineScope()
    var familyMsg by remember { mutableStateOf("") }
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ScreenBackground),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item { TopNav(NavTab.Home, nav.tab) }

        when (val d = data) {
            is Load.Loading -> item { StatusText("Loading…", Modifier.padding(start = 48.dp)) }
            is Load.Err -> item {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    StatusText(d.message)
                    if (Prefs.tmdbKey.isBlank()) ActionButton("Open Phone Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
                }
            }
            is Load.Ok -> {
                val heroes = d.value.trending.visible().filter { it.backdrop != null }.take(6)
                if (heroes.isNotEmpty()) item { Hero(heroes, nav) }

                if (continueWatching.isNotEmpty()) item {
                    Column(Modifier.padding(top = 18.dp)) {
                        RailHeader("Continue Watching", Modifier.padding(start = 48.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(continueWatching, key = { it.meta.historyKey }) { h ->
                                WideCard(
                                    title = h.meta.name,
                                    subtitle = if (h.meta.type == "tv") "S${h.meta.season} E${h.meta.episode}" else "${(h.progress * 100).toInt()}% watched",
                                    image = Tmdb.img(h.meta.backdrop, "w780"),
                                    progress = h.progress,
                                    onClick = { nav.push(Screen.Detail(h.meta.type, h.meta.tmdbId)) },
                                )
                            }
                        }
                    }
                }
                item { TitleRow("My Watchlist", watchlist, openTitle) }
                item { TitleRow("Favorites", favorites, openTitle) }
                item { TitleRow("Trending This Week", d.value.trending.visible(), openTitle) }
                item { TitleRow("Popular Movies", d.value.popularMovies.visible(), openTitle) }
                item { TitleRow("Popular TV Shows", d.value.popularTv.visible(), openTitle) }
                item { TitleRow("Now Playing in Theaters", d.value.nowPlaying.visible(), openTitle) }
                item { TitleRow("Top Rated Movies", d.value.topMovies.visible(), openTitle) }
                item { TitleRow("Top Rated TV Shows", d.value.topTv.visible(), openTitle) }
                item {
                    Column(Modifier.padding(start = 48.dp, top = 24.dp)) {
                        RailHeader("Family Movie Night")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ActionButton("Pick a family movie for us", {
                                scope.launch {
                                    familyMsg = "Picking…"
                                    val pick = runCatching { Tmdb.familyMovies((1..5).random()).random() }
                                    val chosen = pick.getOrNull()
                                    if (chosen != null) {
                                        familyMsg = ""
                                        openTitle(chosen)
                                    } else {
                                        familyMsg = pick.exceptionOrNull()?.message ?: "Could not pick"
                                    }
                                }
                            }, primary = true)
                            if (familyMsg.isNotBlank()) Text(familyMsg, color = McdColors.Muted)
                        }
                    }
                }
                item { BrowseByYear(nav) }
            }
        }
    }
}

/** Big backdrop banner that rotates through trending titles, like HuberTV's hero. */
@Composable
private fun Hero(items: List<Title>, nav: Nav) {
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val t = items[index % items.size]
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(paused) {
        while (!paused) {
            kotlinx.coroutines.delay(8000)
            index = (index + 1) % items.size
        }
    }

    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(
            model = Tmdb.img(t.backdrop, "w1280"),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(McdColors.Navy, McdColors.Navy.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, McdColors.Navy))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 24.dp).width(620.dp)) {
            Text(t.name.uppercase(), style = broadcastStyle(44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(t.year.ifBlank { null }, if (t.rating > 0) "★ %.1f".format(t.rating) else null, if (t.type == "tv") "SERIES" else "MOVIE").joinToString("  •  "),
                color = McdColors.Muted, fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(t.overview, color = Color.White, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("▶  Play", { paused = true; nav.push(Screen.Detail(t.type, t.id)) }, Modifier.focusRequester(playFocus), primary = true)
                ActionButton("More Info", { paused = true; nav.push(Screen.Detail(t.type, t.id)) })
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.indices.forEach { i ->
                Box(Modifier.width(if (i == index) 22.dp else 8.dp).height(4.dp).background(if (i == index) McdColors.Red else Color.White.copy(alpha = 0.4f)))
            }
        }
    }
}

@Composable
private fun BrowseByYear(nav: Nav) {
    var year by remember { mutableIntStateOf(0) }
    val years = remember { (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) downTo 1970).toList() }
    Column(Modifier.padding(top = 24.dp)) {
        RailHeader("Browse by Year", Modifier.padding(start = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y -> ActionButton("$y", { year = y }, primary = y == year) }
        }
        if (year != 0) {
            val movies by rememberLoad(year) { Tmdb.byYear("movie", year) }
            val shows by rememberLoad(year) { Tmdb.byYear("tv", year) }
            val m = movies
            val sh = shows
            if (m is Load.Ok) TitleRow("Movies from $year", m.value) { t -> nav.push(Screen.Detail(t.type, t.id)) }
            if (sh is Load.Ok) TitleRow("Shows from $year", sh.value) { t -> nav.push(Screen.Detail(t.type, t.id)) }
        }
    }
}
