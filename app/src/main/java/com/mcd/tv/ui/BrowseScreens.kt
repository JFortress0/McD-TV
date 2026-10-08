package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.Library
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Resolver
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Service
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.delay

/** Shared page frame: background + top nav + content. */
@Composable
fun TabPage(nav: Nav, tab: NavTab, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(ScreenBackground)) {
        TopNav(tab, nav.tab)
        content()
    }
}

/** Poster grid used by Search, Services and Library. */
@Composable
fun PosterGrid(items: List<Title>, onOpen: (Title) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = { "${it.type}-${it.id}" }) { t -> PosterCard(t, onClick = { onOpen(t) }) }
    }
}

// ============================== Search ==============================

@Composable
fun SearchScreen(nav: Nav) {
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
    TabPage(nav, NavTab.Search) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = McdColors.White, fontSize = 22.sp),
            cursorBrush = SolidColor(McdColors.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().focusRequester(field)
                .background(McdColors.Card, RoundedCornerShape(8.dp)).border(2.dp, McdColors.Red, RoundedCornerShape(8.dp))
                .padding(16.dp),
        )
        Text("Search movies and TV shows. Press OK to type, then Search on the keyboard.", color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 6.dp))
        if (submitted.isNotBlank()) {
            val res by rememberLoad(submitted) { Tmdb.search(submitted) }
            when (val r = res) {
                is Load.Loading -> StatusText("Searching…", Modifier.padding(start = 48.dp))
                is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
                is Load.Ok -> PosterGrid(r.value) { nav.push(Screen.Detail(it.type, it.id)) }
            }
        }
    }
}

// ============================== Library ==============================

@Composable
fun LibraryScreen(nav: Nav) {
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val history = remember { Library.history() }
    TabPage(nav, NavTab.Library) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Row(Modifier.padding(horizontal = 48.dp)) { ActionButton("☁  Real-Debrid Cloud", { nav.push(Screen.RdCloud) }, primary = true) } }
            item { TitleRow("Watchlist", Library.watchlist(), openTitle) }
            item { TitleRow("Favorites", Library.favorites(), openTitle) }
            item { TitleRow("Background Noise Shows", Library.noiseShows(), openTitle) }
            if (history.isNotEmpty()) item {
                Column(Modifier.padding(top = 18.dp)) {
                    RailHeader("Watch History", Modifier.padding(start = 48.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(history, key = { it.meta.historyKey }) { h ->
                            WideCard(
                                title = h.meta.name,
                                subtitle = if (h.finished) "Watched" else "${(h.progress * 100).toInt()}%",
                                image = Tmdb.img(h.meta.backdrop, "w780"),
                                progress = h.progress,
                                onClick = { nav.push(Screen.Detail(h.meta.type, h.meta.tmdbId)) },
                            )
                        }
                    }
                }
            }
            if (history.isEmpty() && Library.watchlist().isEmpty() && Library.favorites().isEmpty()) item {
                StatusText("Nothing here yet. Use Favorite and Watchlist on any title page.", Modifier.padding(start = 48.dp))
            }
        }
    }
}

// ============================== Services ==============================

@Composable
fun ServicesScreen(nav: Nav) {
    TabPage(nav, NavTab.Services) {
        Text("Browse the catalog of any major streaming service.", color = McdColors.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(SERVICES) { s -> TileCard(title = s.name, subtitle = "Movies and shows", onClick = { nav.push(Screen.ServiceGrid(s)) }) }
        }
    }
}

@Composable
fun ServiceGridScreen(nav: Nav, service: Service) {
    var type by remember { mutableStateOf("movie") }
    val res by rememberLoad(service.id, type) {
        (1..3).flatMap { Tmdb.byService(type, service.id, it) }.distinctBy { it.id }
    }
    TabPage(nav, NavTab.Services) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(service.name.uppercase(), style = broadcastStyle(30.sp))
            ActionButton("Movies", { type = "movie" }, primary = type == "movie")
            ActionButton("Shows", { type = "tv" }, primary = type == "tv")
        }
        when (val r = res) {
            is Load.Loading -> StatusText("Loading…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> PosterGrid(r.value) { nav.push(Screen.Detail(it.type, it.id)) }
        }
    }
}

// ============================== Calendar ==============================

@Composable
fun CalendarScreen(nav: Nav) {
    val res by rememberLoad {
        Library.watchlist().filter { it.type == "tv" }
            .mapNotNull { t -> runCatching { Tmdb.details("tv", t.id) }.getOrNull() }
            .mapNotNull { d -> d.nextEpisode?.let { d to it } }
            .sortedBy { it.second.airDate }
    }
    TabPage(nav, NavTab.Calendar) {
        Text("Upcoming episodes for shows in your watchlist.", color = McdColors.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        when (val r = res) {
            is Load.Loading -> StatusText("Checking air dates…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> if (r.value.isEmpty()) StatusText("No upcoming episodes. Add shows to your Watchlist.", Modifier.padding(start = 48.dp))
            else LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.value) { (d, e) ->
                    WideCard(
                        title = d.title.name,
                        subtitle = "${e.airDate}  •  S${e.season}E${e.number} ${e.name}",
                        image = Tmdb.img(e.still ?: d.title.backdrop, "w780"),
                        onClick = { nav.push(Screen.Detail("tv", d.title.id)) },
                    )
                }
            }
        }
    }
}

// ============================== Background Noise ==============================

@Composable
fun NoiseScreen(nav: Nav) {
    var shows by remember { mutableStateOf(Library.noiseShows()) }
    TabPage(nav, NavTab.Noise) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Text("BACKGROUND NOISE", style = broadcastStyle(32.sp))
            Text(
                "Pick your shows once. Press play and random episodes keep coming, like leaving a TV on. " +
                    "Add shows with \"+ Background Noise\" on any show page.",
                color = McdColors.Muted, fontSize = 15.sp,
            )
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("▶  Play Background Noise", { if (shows.isNotEmpty()) nav.push(Screen.NoiseRun) }, primary = true)
                Text("Your shows: ${shows.size} of 50", color = McdColors.Muted)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(shows, key = { it.id }) { t ->
                Column {
                    PosterCard(t, onClick = { nav.push(Screen.Detail("tv", t.id)) })
                    ActionButton("Remove", { Library.toggleNoise(t); shows = Library.noiseShows() })
                }
            }
        }
    }
}

/** Picks a random show + episode, finds the best source, plays, repeats when it ends. */
@Composable
fun NoiseRunScreen(nav: Nav) {
    var round by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf<PlayMeta?>(null) }
    var status by remember { mutableStateOf("Shuffling…") }

    LaunchedEffect(round) {
        url = null
        val shows = Library.noiseShows()
        if (shows.isEmpty()) { status = "Add shows first."; return@LaunchedEffect }
        repeat(6) { attempt ->
            val pick = runCatching {
                val show = shows.random()
                status = "Shuffling… ${show.name}"
                val d = Tmdb.details("tv", show.id)
                val imdb = d.imdbId ?: error("No IMDb id")
                val s = d.seasons.filter { it.episodeCount > 0 }.random()
                val e = (1..s.episodeCount).random()
                val m = PlayMeta("tv", show.id, show.name, show.poster, show.backdrop, s.number, e)
                val list = Addons.streams("tv", "$imdb:${s.number}:$e")
                m to Resolver.best(list).second
            }.getOrNull()
            if (pick != null) { meta = pick.first; url = pick.second; return@LaunchedEffect }
            status = "That one didn't work, trying another… (${attempt + 1})"
            delay(500)
        }
        status = "Could not find playable episodes. Check your addons and Real-Debrid."
    }

    val u = url
    val m = meta
    if (u != null && m != null) {
        key(u) { PlayerScreen(url = u, title = "Background Noise  •  ${m.label}", meta = m, onEnded = { round++ }) }
    } else {
        Box(Modifier.fillMaxSize().background(McdColors.Navy).padding(48.dp)) {
            Column {
                McdLogo()
                Text(status, color = McdColors.White, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}
