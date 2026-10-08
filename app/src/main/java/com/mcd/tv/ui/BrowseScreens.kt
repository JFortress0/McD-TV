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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Shared page frame: background + top nav + content. */
@Composable
fun TabPage(nav: Nav, tab: NavTab, autoFocus: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(ScreenBackground)) {
        // autoFocus: start on the current tab so the remote has somewhere to go. Screens that focus
        // something themselves (Search's text field) pass false.
        TopNav(tab, nav.tab, autoFocus = autoFocus)
        content()
    }
}

/** Poster grid used by Search, Services and Library. */
@Composable
fun PosterGrid(items: List<Title>, state: LazyGridState = rememberLazyGridState(), onOpen: (Title) -> Unit) {
    LazyVerticalGrid(
        state = state,
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
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    // Focus the field (no keyboard yet: OK opens it), so typing is one press away.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { field.requestFocus() } }
    TabPage(nav, NavTab.Search, autoFocus = false) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = McdColors.White, fontSize = 22.sp),
            cursorBrush = SolidColor(McdColors.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
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
                is Load.Ok -> PosterGrid(r.value, gridState) { nav.push(Screen.Detail(it.type, it.id)) }
            }
        }
    }
}

// ============================== Library ==============================

@Composable
fun LibraryScreen(nav: Nav) {
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val history = remember { Library.history() }
    val watchlist = remember { Library.watchlist() }
    val favorites = remember { Library.favorites() }
    val noiseShows = remember { Library.noiseShows() }
    TabPage(nav, NavTab.Library) {
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item { Row(Modifier.padding(horizontal = 48.dp)) { ActionButton("☁  Real-Debrid Cloud", { nav.push(Screen.RdCloud) }, primary = true) } }
            item { TitleRow("Watchlist", watchlist, openTitle) }
            item { TitleRow("Favorites", favorites, openTitle) }
            item { TitleRow("Background Noise Shows", noiseShows, openTitle) }
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
            if (history.isEmpty() && watchlist.isEmpty() && favorites.isEmpty()) item {
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
    var type by rememberSaveable { mutableStateOf("movie") }
    var pages by rememberSaveable(type) { mutableIntStateOf(2) }
    var retry by remember { mutableIntStateOf(0) }
    val newest by rememberLoad(service.id, type) { Tmdb.byService(type, service.id, 1, newest = true) }
    // "Load more" only fetches the new pages and appends them; pages already shown stay put.
    val all = remember(type) { mutableStateListOf<Title>() }
    var loaded by remember(type) { mutableIntStateOf(0) }
    var allErr by remember(type) { mutableStateOf<String?>(null) }
    LaunchedEffect(service.id, type, pages, retry) {
        allErr = null
        while (loaded < pages) {
            val next = loaded + 1
            val got = try {
                Tmdb.byService(type, service.id, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allErr = e.message ?: e.toString()
                return@LaunchedEffect
            }
            val seen = all.map { it.id }.toHashSet()
            all.addAll(got.distinctBy { it.id }.filter { it.id !in seen })
            loaded = next
        }
    }
    val listState = rememberLazyListState()
    // Movies go straight to the source list; shows open their page to pick an episode.
    val pick: (Title) -> Unit = { t -> nav.push(Screen.Detail(t.type, t.id, openSources = t.type == "movie")) }
    TabPage(nav, NavTab.Services) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(service.name.uppercase(), style = broadcastStyle(30.sp))
            ActionButton("Movies", { type = "movie" }, primary = type == "movie")
            ActionButton("Shows", { type = "tv" }, primary = type == "tv")
        }
        Text(
            "Current US catalog, refreshed daily from TMDB / JustWatch. Selecting a movie opens its sources.",
            color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
        )
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                val n = newest
                if (n is Load.Ok) TitleRow("New on ${service.name}", n.value, pick)
            }
            item { RailHeader("Everything on ${service.name}", Modifier.padding(start = 48.dp, top = 18.dp)) }
            val err = allErr
            if (all.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 48.dp)) {
                        StatusText(err ?: "Loading…")
                        if (err != null) ActionButton("Retry", { retry++ }, primary = true)
                    }
                }
            } else {
                // Rows of 5 posters (5 x 140dp + gaps fits the 864dp content width), so the whole catalog scrolls in one list.
                items(all.chunked(5)) { rowItems ->
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        rowItems.forEach { t -> PosterCard(t, onClick = { pick(t) }) }
                    }
                }
                item(key = "loadMore") {
                    Row(Modifier.padding(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton(
                            if (err != null) "Retry (${all.size} shown)" else "Load more (${all.size} shown)",
                            { if (err != null) retry++ else pages += 2 },
                            primary = true,
                        )
                        if (err != null) StatusText(err) else if (loaded < pages) StatusText("Loading…")
                    }
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
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shows.isNotEmpty()) ActionButton("▶  Play Background Noise", { nav.push(Screen.NoiseRun) }, primary = true)
                Text("Your shows: ${shows.size} of 50", color = McdColors.Muted)
            }
            if (shows.isEmpty()) StatusText("No shows yet. Open any show and press \"+ Background Noise\" to add it here.")
        }
        // After Remove, focus moves to the neighbouring show's Remove button instead of getting lost.
        var focusAfterRemove by remember { mutableStateOf<Int?>(null) }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(shows, key = { _, t -> t.id }) { i, t ->
                val removeFocus = remember { FocusRequester() }
                LaunchedEffect(focusAfterRemove) {
                    if (focusAfterRemove == t.id) {
                        withFrameNanos { }
                        runCatching { removeFocus.requestFocus() }
                        focusAfterRemove = null
                    }
                }
                Column {
                    PosterCard(t, onClick = { nav.push(Screen.Detail("tv", t.id)) })
                    ActionButton("Remove", {
                        Library.toggleNoise(t)
                        val left = Library.noiseShows()
                        shows = left
                        focusAfterRemove = (left.getOrNull(i) ?: left.lastOrNull())?.id
                    }, Modifier.focusRequester(removeFocus))
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
    var headers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
                val (src, u) = Resolver.best(list)
                Triple(m, u, src.headers)
            }.getOrNull()
            if (pick != null) { meta = pick.first; headers = pick.third; url = pick.second; return@LaunchedEffect }
            status = "That one didn't work, trying another… (${attempt + 1})"
            delay(500)
        }
        status = "Could not find playable episodes. Check your addons and Real-Debrid."
    }

    val u = url
    val m = meta
    if (u != null && m != null) {
        key(u) { PlayerScreen(url = u, title = "Background Noise  •  ${m.label}", meta = m, onEnded = { round++ }, headers = headers) }
    } else {
        Box(Modifier.fillMaxSize().background(McdColors.Navy).padding(48.dp)) {
            Column {
                McdLogo()
                Text(status, color = McdColors.White, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

// ============================== Genres ==============================

@Composable
fun GenresScreen(nav: Nav) {
    var type by rememberSaveable { mutableStateOf("movie") }
    val genres = if (type == "movie") com.mcd.tv.data.MOVIE_GENRES else com.mcd.tv.data.TV_GENRES
    // Saved by id (Genre itself is not saveable) so Back from a title restores the selection.
    var genreId by rememberSaveable(type) { mutableIntStateOf(genres.first().id) }
    val genre = genres.firstOrNull { it.id == genreId } ?: genres.first()
    var topRated by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    val gridState = rememberLazyGridState()
    val years = remember { listOf<Int?>(null) + (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) downTo 1960).toList() }
    val res by rememberLoad(type, genre.id, topRated, year) {
        (1..3).flatMap { Tmdb.byGenre(type, genre.id, topRated, it, year) }.distinctBy { it.id }
    }
    TabPage(nav, NavTab.Genres) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Movies", { type = "movie" }, primary = type == "movie")
            ActionButton("Shows", { type = "tv" }, primary = type == "tv")
            ActionButton(if (topRated) "Sort: Top Rated" else "Sort: Popular", { topRated = !topRated })
            Text(
                com.mcd.tv.data.Prefs.origin.label, color = McdColors.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres, key = { it.id }) { g -> ActionButton(g.name, { genreId = g.id }, primary = g.id == genre.id) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y -> ActionButton(y?.toString() ?: "All years", { year = y }, primary = y == year) }
        }
        when (val r = res) {
            is Load.Loading -> StatusText("Loading ${genre.name}${year?.let { " from $it" } ?: ""}…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> PosterGrid(r.value, gridState) { nav.push(Screen.Detail(it.type, it.id)) }
        }
    }
}
