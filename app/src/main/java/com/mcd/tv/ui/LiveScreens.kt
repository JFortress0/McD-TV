package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Epg
import com.mcd.tv.data.Programme
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.LiveCatalog
import com.mcd.tv.data.LiveItem
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs

/**
 * Live TV. Sources across the top: your M3U playlist (if set), then every live channel or
 * event catalog from your addons. Pick one, then a channel.
 */
@Composable
fun LiveTvScreen(nav: Nav) {
    val catalogs by rememberLoad(Prefs.addonUrls) { Addons.liveCatalogs() }
    val hasPlaylist = Prefs.m3uUrl.isNotBlank()
    // null = the M3U playlist; otherwise the index into the catalog list.
    var source by remember { mutableStateOf<Int?>(null) }
    val cats: List<LiveCatalog> = catalogs.let { if (it is Load.Ok) it.value else emptyList() }
    if (!hasPlaylist && source == null && cats.isNotEmpty()) source = 0

    TabPage(nav, NavTab.Live) {
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasPlaylist) item { ActionButton("My Playlist", { source = null }, primary = source == null) }
            items(cats.size) { i -> ActionButton(cats[i].label, { source = i }, primary = source == i) }
            if (catalogs is Load.Loading) item { StatusText("Checking addons…") }
        }
        val idx = source
        when {
            idx != null && idx < cats.size -> CatalogList(nav, cats[idx])
            hasPlaylist -> PlaylistList(nav)
            catalogs is Load.Loading -> StatusText("Loading…", Modifier.padding(start = 48.dp))
            else -> Column(Modifier.padding(horizontal = 48.dp)) {
                StatusText("No live sources yet. Add an M3U playlist, or an addon that has live channels, on the Control page.")
                ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
            }
        }
    }
}

/** Pseudo-groups in the playlist's group row. */
private const val GROUP_FAV = "\u0001favorites"
private const val GROUP_RECENT = "\u0001recent"

@Composable
private fun PlaylistList(nav: Nav) {
    val res by rememberLoad(Prefs.m3uUrl) { M3u.load() }
    // null = All. Saveable, so Back from a channel returns to the same group.
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(Prefs.liveFavorites) }
    val recents = remember { Prefs.liveRecents }
    val lastUrl = remember { Prefs.liveLastUrl }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchField = remember { FocusRequester() }
    // Ticks every minute so now/next and progress bars stay current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    when (val r = res) {
        is Load.Loading -> StatusText("Loading your channels… big playlists can take up to a minute the first time.", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> {
            val all = r.value
            // Program guide (XMLTV named in the playlist header): loads in the background, cached 6 h.
            LaunchedEffect(all) {
                val url = M3u.guideUrl
                if (url.isNotBlank()) {
                    val g = Epg.load(url, all)
                    if (g.isNotEmpty()) LiveSession.guide = g
                }
            }
            val guide = LiveSession.guide
            val byUrl = remember(all) { all.associateBy { it.url } }
            val groups = remember(all) { all.map { it.group }.distinct() }
            val favSet = remember(favorites) { favorites.toSet() }
            val favChannels = remember(byUrl, favorites) { favorites.mapNotNull { byUrl[it] } }
            val recentChannels = remember(byUrl, recents) { recents.mapNotNull { byUrl[it] } }
            val lastChannel = remember(byUrl, lastUrl) { if (lastUrl.isBlank()) null else byUrl[lastUrl] }
            // A pseudo-group that emptied (last favorite removed) falls back to All.
            val sel = when {
                group == GROUP_FAV && favChannels.isEmpty() -> null
                group == GROUP_RECENT && recentChannels.isEmpty() -> null
                else -> group
            }
            val base = remember(all, sel, favChannels, recentChannels) {
                when (sel) {
                    null -> all
                    GROUP_FAV -> favChannels
                    GROUP_RECENT -> recentChannels
                    else -> all.filter { it.group == sel }
                }
            }
            val q = if (searching) query.trim() else ""
            // Filter first, then cap, so a search finds channels beyond the first 500.
            val shown = remember(base, q) {
                (if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) }).take(500)
            }
            fun play(list: List<Channel>, i: Int) {
                LiveSession.channels = list
                nav.push(Screen.LivePlay(i))
            }
            fun toggleFavorite(ch: Channel) {
                Prefs.toggleLiveFavorite(ch.url)
                favorites = Prefs.liveFavorites
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lastChannel != null) item {
                    ActionButton("▶ Last channel: ${lastChannel.name}", {
                        val i = all.indexOf(lastChannel)
                        if (i >= 0) play(all, i)
                    })
                }
                if (favChannels.isNotEmpty()) item { ActionButton("★ Favorites", { group = GROUP_FAV }, primary = sel == GROUP_FAV) }
                if (recentChannels.isNotEmpty()) item { ActionButton("Recent", { group = GROUP_RECENT }, primary = sel == GROUP_RECENT) }
                item { ActionButton("All (${all.size})", { group = null }, primary = sel == null) }
                item {
                    ActionButton("⌕ Search", {
                        searching = !searching
                        if (!searching) query = ""
                    }, primary = searching)
                }
                items(groups) { g -> ActionButton(g, { group = g }, primary = g == sel) }
            }
            if (searching) {
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { searchField.requestFocus() } }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(McdColors.Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text("Channel name… (press OK to type)", color = McdColors.Muted, fontSize = 18.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp).fillMaxWidth().focusRequester(searchField)
                        .background(McdColors.Card, RoundedCornerShape(8.dp)).border(2.dp, McdColors.Cyan, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                if (q.isNotEmpty() && shown.isEmpty()) StatusText("No channels match \"$q\".", Modifier.padding(start = 48.dp))
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(shown) { i, ch ->
                    LiveChannelRow(
                        ch = ch,
                        favorite = ch.url in favSet,
                        guide = guide,
                        now = now,
                        onClick = { play(shown, i) },
                        onMenu = { toggleFavorite(ch) },
                    )
                }
            }
        }
    }
}

/**
 * Playlist channel row: logo, name, group, and (with guide data) now / next plus a progress bar.
 * The remote's Menu key adds or removes the channel from Favorites.
 */
@Composable
private fun LiveChannelRow(
    ch: Channel,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    now: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val guideKey = Epg.keyOf(ch)
    val (cur, next) = remember(guide, guideKey, now) { Epg.nowNext(guideKey, guide, now) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.key == Key.Menu) {
                if (e.type == KeyEventType.KeyUp) onMenu()
                true
            } else {
                false
            }
        },
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(ch.logo, ch.name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (favorite) Text("★ ", color = McdColors.RedBright, fontSize = 16.sp)
                    Text(ch.name, style = broadcastStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    Text(ch.group, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val line = nowNextLine(cur, next)
                if (line.isNotEmpty()) {
                    Text(line, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (cur != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(2.dp).background(McdColors.Red))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogList(nav: Nav, cat: LiveCatalog) {
    val list = remember(cat) { mutableStateListOf<LiveItem>() }
    var page by remember(cat) { mutableIntStateOf(0) }
    var status by remember(cat) { mutableStateOf("Loading…") }
    var more by remember(cat) { mutableStateOf(true) }
    LaunchedEffect(cat, page) {
        runCatching { Addons.catalog(cat, skip = list.size) }
            .onSuccess { got ->
                // Addons can repeat an item within one page or send blank ids; both would crash the keyed list.
                val fresh = got.distinctBy { it.id }.filter { g -> g.id.isNotBlank() && list.none { it.id == g.id } }
                list.addAll(fresh)
                more = fresh.isNotEmpty()
                status = if (list.isEmpty()) "Nothing listed right now." else ""
            }
            .onFailure { status = "Could not load this list: ${it.message}" }
    }
    if (status.isNotBlank()) StatusText(status, Modifier.padding(start = 48.dp))
    LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { item -> LiveItemRow(item) { nav.push(Screen.LiveChannel(item)) } }
        if (list.isNotEmpty() && more) item(key = "loadMore") { ActionButton("Load more", { page++ }) }
    }
}

@Composable
private fun LiveItemRow(item: LiveItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(item.poster, item.name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = broadcastStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.info.isNotBlank()) Text(item.info, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The streams one addon offers for a live channel or event. */
@Composable
fun LiveChannelScreen(nav: Nav, item: LiveItem) {
    val res by rememberLoad(item) { Addons.liveStreams(item) }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Text(item.name.uppercase(), style = broadcastStyle(28.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.info.isNotBlank()) Text(item.info, color = McdColors.Muted, fontSize = 14.sp)
        Spacer(Modifier.size(12.dp))
        when (val r = res) {
            is Load.Loading -> StatusText("Finding streams…")
            is Load.Err -> StatusText("Could not load streams: ${r.message}")
            is Load.Ok -> if (r.value.isEmpty()) StatusText("No playable streams right now. Live events usually list streams shortly before they start.")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.value) { s ->
                    Surface(
                        onClick = { nav.push(Screen.Player(s.url!!, item.name, headers = s.headers)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.title.isNotBlank()) Text(s.title.replace("\n", "  "), color = McdColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
