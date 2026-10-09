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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Library
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.SERVICES
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Everything the home screen shows, loaded in parallel. */
private data class HomeData(
    val trending: List<Title>,
    /** Popular movies on the first streaming service (Netflix), or plain popular movies if that fails. */
    val popular: List<Title>,
    val popularFromService: Boolean,
    val nowPlaying: List<Title>,
    val topMovies: List<Title>,
)

/**
 * Home: at most 8 rows. Continue Watching, Live TV favorites (when a playlist is set), Suggested for You, Trending This Week, Popular on <service>,
 * Top Rated, New Releases, then a small "More" row (Ask Jarvis, Browse, Live TV, Websites, Background Noise).
 * Everything else lives under Browse. Each catalog row ends in a "See all" tile that opens its full grid.
 * Everything personal (history, lists, Live TV favorites) belongs to the active profile, so the page is
 * keyed on it: switching profiles rebuilds Home. The Kids profile gets family, animation and kids rows instead.
 */
@Composable
fun HomeScreen(nav: Nav) {
    key(Prefs.activeProfile) { HomeContent(nav, Prefs.isKidsProfile) }
}

@Composable
private fun HomeContent(nav: Nav, kids: Boolean) {
    val hidden = remember { Library.hidden().map { "${it.type}-${it.id}" }.toSet() }
    fun List<Title>.visible() = filterNot { "${it.type}-${it.id}" in hidden }
    val service = SERVICES.first()

    var retry by remember { mutableIntStateOf(0) }
    val data by rememberLoad(retry) {
        if (kids) coroutineScope {
            val fam = async { Tmdb.kidsMovies() }
            val anim = async { Tmdb.kidsMovies(genreId = 16) }
            val shows = async { Tmdb.kidsShows() }
            val best = async { Tmdb.kidsMovies(genreId = 16, topRated = true) }
            HomeData(fam.await(), anim.await(), false, shows.await(), best.await())
        } else coroutineScope {
            val tr = async { Tmdb.trending() }
            val ps = async { runCatching { Tmdb.byService("movie", service.id) }.getOrNull()?.takeIf { it.isNotEmpty() } }
            val np = async { Tmdb.nowPlaying() }
            val tm = async { Tmdb.topRated("movie") }
            val svc = ps.await()
            HomeData(tr.await(), svc ?: Tmdb.popular("movie"), svc != null, np.await(), tm.await())
        }
    }
    val history = remember { Library.history().sortedByDescending { it.updatedAt } }
    val continueWatching = remember { Library.continueWatching().sortedByDescending { it.updatedAt } }
    val firstFocus = remember { FocusRequester() }
    val favorites = remember { Library.favorites() }
    // Suggestions: titles like the last few things you watched or favorited.
    val suggestions by rememberLoad {
        val seeds = (history.map { it.meta.type to it.meta.tmdbId } + favorites.map { it.type to it.id }).distinct().take(4)
        val seen = history.map { "${it.meta.type}-${it.meta.tmdbId}" }.toSet()
        coroutineScope { seeds.map { (t, id) -> async { runCatching { Tmdb.recommendations(t, id) }.getOrDefault(emptyList()) } }.map { it.await() } }
            .flatMap { it.take(8) }.distinctBy { "${it.type}-${it.id}" }.filterNot { "${it.type}-${it.id}" in seen }.take(24)
    }
    LaunchedEffect(Unit) {
        if (continueWatching.isNotEmpty()) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
    }
    // Live TV row: favorite channels (or recent ones) from the playlist. Loads in the background (playlist
    // cached 6 h) and only appears once ready, so Home never waits on a big playlist.
    val liveRow by produceState<Pair<String, List<Channel>>?>(null) {
        if (Prefs.m3uUrl.isBlank()) return@produceState
        val favs = Prefs.liveFavorites
        val recents = Prefs.liveRecents
        if (favs.isEmpty() && recents.isEmpty()) return@produceState
        val all = try {
            M3u.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@produceState
        }
        val index = LiveOrganizer.indexFor(all)
        val fav = index.indicesOf(favs)
        val recent = if (fav.isEmpty()) index.indicesOf(recents) else IntArray(0)
        value = when {
            fav.isNotEmpty() -> "Live TV · Favorites" to index.channelsOf(fav)
            recent.isNotEmpty() -> "Recent channels" to index.channelsOf(recent)
            else -> null
        }
    }
    // Home stays at 8 rows or fewer: with Continue Watching and the hero both showing, the live row takes Top Rated's place.
    val dropTopRated = liveRow != null && continueWatching.isNotEmpty()
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val heroes = (data as? Load.Ok<HomeData>)?.value?.trending?.visible()?.filter { it.backdrop != null }?.take(6) ?: emptyList()
    // Nothing to resume: the hero goes full-bleed at the very top of the page, behind the transparent
    // TopNav (Max-style), and takes focus. With Continue Watching, that row comes first and has focus.
    val heroOnTop = continueWatching.isEmpty()

    // The TV scrolls a focused item about a third of the way down the screen. For the top of Home that hides
    // the menu and the top of the hero, so focus there always shows the page from its very top.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pinTop = Modifier.onFocusChanged { f ->
        if (f.hasFocus) {
            scope.launch {
                withFrameNanos { }
                listState.animateScrollToItem(0)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().hudBackground(),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        item(key = "top") {
            Box(Modifier.fillMaxWidth().then(pinTop)) {
                if (heroOnTop && heroes.isNotEmpty()) Hero(heroes, nav, takeFocus = true, underNav = true)
                TopNav(NavTab.Home, nav.tab, profileName = Prefs.activeProfileName, onProfile = { nav.push(Screen.Profiles) })
            }
        }

        if (continueWatching.isNotEmpty()) item(key = "continue") {
            Box(pinTop) { HistoryRow("Continue Watching", continueWatching, nav, firstFocus) }
        }
        liveRow?.let { (label, channels) -> item(key = "live") { LiveChannelsRow(label, channels, nav) } }
        if (!heroOnTop && heroes.isNotEmpty()) item(key = "hero") { Hero(heroes, nav, takeFocus = false) }
        item(key = "suggested") {
            val sg = suggestions
            if (sg is Load.Ok && sg.value.isNotEmpty()) TitleRow("Suggested for You", sg.value.visible(), openTitle)
        }

        when (val d = data) {
            is Load.Loading -> item { StatusText("Loading…", Modifier.padding(start = 48.dp)) }
            is Load.Err -> item {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    StatusText(d.message)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Retry", { retry++ }, primary = Prefs.tmdbKey.isNotBlank())
                        if (Prefs.tmdbKey.isBlank()) ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
                    }
                }
            }
            is Load.Ok -> {
                val v = d.value
                if (kids) {
                    item(key = "kids_family") { TitleRow("Family Movies", v.trending.visible(), openTitle) }
                    item(key = "kids_animation") { TitleRow("Animated Movies", v.popular.visible(), openTitle) }
                    item(key = "kids_shows") { TitleRow("Kids Shows", v.nowPlaying.visible(), openTitle) }
                    if (!dropTopRated) item(key = "kids_best") { TitleRow("Best Rated Animation", v.topMovies.visible(), openTitle) }
                } else {
                item(key = "trending") {
                    TitleRail("Trending This Week", v.trending.visible(), { nav.push(Screen.BrowseGrid("trending")) }, openTitle)
                }
                item(key = "popular") {
                    if (v.popularFromService) {
                        TitleRail("Popular on ${service.name} · Movies", v.popular.visible(), { nav.push(Screen.ServiceGrid(service)) }, openTitle)
                    } else {
                        TitleRail("Popular · Movies", v.popular.visible(), { nav.push(Screen.BrowseGrid("popular", "movie")) }, openTitle)
                    }
                }
                if (!dropTopRated) item(key = "toprated") {
                    TitleRail("Top Rated · Movies", v.topMovies.visible(), { nav.push(Screen.BrowseGrid("top", "movie")) }, openTitle)
                }
                item(key = "new") {
                    TitleRail("New Releases · In Theaters", v.nowPlaying.visible(), { nav.push(Screen.BrowseGrid("new")) }, openTitle)
                }
                }
            }
        }
        item(key = "more") { MoreRow(nav) }
    }
}

/** Small last row: shortcuts to everything that moved off Home. */
@Composable
private fun MoreRow(nav: Nav) {
    Column(Modifier.padding(top = 24.dp)) {
        RailHeader("More", Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CompactTile("Browse", "Genres, years, languages, services", { nav.tab(NavTab.Browse) }) }
            item { CompactTile("Live TV", "Your channels and favorites", { nav.tab(NavTab.Live) }) }
            item { CompactTile("Games", "Every NFL game this week", { nav.push(Screen.Games(com.mcd.tv.data.League.NFL)) }) }
            item { CompactTile("Websites", "Sites you added on the Control page", { nav.push(Screen.Sports) }) }
            item { CompactTile("Background Noise", "Random episodes of your shows", { nav.push(Screen.Noise) }) }
        }
    }
}

/** Hero height: about 65% of the 540dp TV canvas. */
private val HeroHeight = 350.dp

/**
 * Full-bleed backdrop banner that rotates through trending titles (Max-style): the image fades into
 * the page on the left and at the bottom, with title, meta line, overview and Play / More Info on top.
 * [underNav]: the hero is the first thing on the page and the transparent TopNav floats over its top.
 */
@Composable
private fun Hero(items: List<Title>, nav: Nav, takeFocus: Boolean = true, underNav: Boolean = false) {
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val t = items[index % items.size]
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(paused) {
        while (!paused) {
            kotlinx.coroutines.delay(8000)
            index = (index + 1) % items.size
        }
    }

    Box(Modifier.fillMaxWidth().height(HeroHeight)) {
        AsyncImage(
            model = Tmdb.img(t.backdrop, "w1280"),
            contentDescription = t.name,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            colorFilter = HudDuotone,
            modifier = Modifier.fillMaxSize(),
        )
        // Duotone: darken, then a faint cyan wash and scanlines over the backdrop.
        Box(Modifier.fillMaxSize().background(McdColors.Ink.copy(alpha = 0.25f)).background(McdColors.Accent.copy(alpha = 0.15f)).hudScanlines())
        // Left: solid page color fading to clear, so the text always reads.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to McdColors.Navy,
                    0.30f to McdColors.Navy.copy(alpha = 0.85f),
                    0.60f to McdColors.Navy.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
            ),
        )
        // Top (only when the nav floats over it, or the hero sits mid-page) and bottom: melt into the page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to McdColors.Navy.copy(alpha = if (underNav) 0.6f else 0.9f),
                    0.25f to Color.Transparent,
                    0.60f to Color.Transparent,
                    0.85f to McdColors.Navy.copy(alpha = 0.8f),
                    1f to McdColors.Navy,
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 22.dp).width(580.dp)) {
            Text(
                if (t.type == "tv") "▸ FEATURED SERIES" else "▸ FEATURED MOVIE",
                style = hudLabelStyle(10.sp, McdColors.Accent),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name.uppercase(),
                    style = broadcastStyle(34.sp).copy(lineHeight = 34.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (t.rating > 0) {
                    Spacer(Modifier.width(14.dp))
                    HudGauge((t.rating / 10.0).toFloat(), "%.1f".format(t.rating), "TMDB")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(t.year.ifBlank { null }, if (t.type == "tv") "Series" else "Movie").joinToString("  ▪  ").uppercase(),
                color = McdColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(t.overview, color = McdColors.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("▶  Play", { paused = true; nav.push(Screen.Detail(t.type, t.id)) }, Modifier.focusRequester(playFocus), primary = true)
                ActionButton("More Info", { paused = true; nav.push(Screen.Detail(t.type, t.id)) })
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.indices.forEach { i ->
                Box(
                    Modifier.width(if (i == index) 22.dp else 8.dp).height(3.dp)
                        .background(if (i == index) McdColors.Accent else McdColors.Line),
                )
            }
        }
    }
}

/** A row of landscape cards from watch history (resume points or recently watched). */
@Composable
private fun HistoryRow(label: String, list: List<com.mcd.tv.data.HistoryEntry>, nav: Nav, focus: FocusRequester?) {
    Column(Modifier.padding(top = 12.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(list, key = { _, h -> h.meta.historyKey }) { i, h ->
                WideCard(
                    title = h.meta.name,
                    subtitle = when {
                        h.finished -> "Watched"
                        h.meta.type == "tv" -> "S${h.meta.season} E${h.meta.episode}  •  ${(h.progress * 100).toInt()}%"
                        else -> "${(h.progress * 100).toInt()}% watched"
                    },
                    image = Tmdb.img(h.meta.backdrop, "w780"),
                    progress = if (h.finished) null else h.progress,
                    onClick = { nav.push(Screen.Detail(h.meta.type, h.meta.tmdbId)) },
                    modifier = if (i == 0 && focus != null) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}
