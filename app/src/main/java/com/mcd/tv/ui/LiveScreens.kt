package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Epg
import com.mcd.tv.data.LiveCatalog
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveItem
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live TV. With addon live catalogs installed, a source row across the top picks "My Playlist" or a catalog.
 * Without them the playlist browser shows directly: a rail of sections on the left, channel cards on the right.
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
        if (cats.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPlaylist) item { ActionButton("My Playlist", { source = null }, primary = source == null) }
                items(cats.size) { i -> ActionButton(cats[i].label, { source = i }, primary = source == i) }
            }
        }
        val idx = source
        when {
            idx != null && idx < cats.size -> CatalogList(nav, cats[idx])
            hasPlaylist -> PlaylistBrowser(nav)
            catalogs is Load.Loading -> StatusText("Loading…", Modifier.padding(start = 48.dp))
            else -> Column(Modifier.padding(horizontal = 48.dp)) {
                StatusText("No playlist yet. Add your M3U playlist (or an addon with live channels) on the Control page.")
                ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
            }
        }
    }
}

// ============================== Playlist browser ==============================

/** Rail keys besides sections ("sec:<name>"). */
private const val KEY_SEARCH = "search"
private const val KEY_FAV = "fav"
private const val KEY_RECENT = "recent"
private const val KEY_ALL = "all"
private const val SEC = "sec:"

private class RailEntry(val key: String, val label: String, val count: Int?)

/** The channel card that has focus, for the hint bar (only the hint bar reads it, so focus moves stay cheap). */
private class FocusedChannel {
    var channel by mutableStateOf<Channel?>(null)
    var name by mutableStateOf("")
}

private val InkOnWhite = Color(0xFF05070D)
private val StarGold = Color(0xFFFFC94D)

private fun countText(n: Int): String = "%,d".format(n)

@Composable
private fun PlaylistBrowser(nav: Nav) {
    // Organizing runs on Dispatchers.Default and is cached for the loaded playlist.
    val res by rememberLoad(Prefs.m3uUrl) { LiveOrganizer.indexFor(M3u.load()) }
    when (val r = res) {
        is Load.Loading -> StatusText("Loading your channels… big playlists can take up to a minute the first time.", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> if (r.value.size == 0) {
            StatusText("Your playlist has no live channels.", Modifier.padding(start = 48.dp))
        } else {
            LiveBrowser(nav, r.value)
        }
    }
}

@Composable
private fun LiveBrowser(nav: Nav, index: LiveIndex) {
    var favorites by remember { mutableStateOf(Prefs.liveFavorites) }
    val recents = remember { Prefs.liveRecents }
    val favItems = remember(index, favorites) { index.indicesOf(favorites) }
    val recentItems = remember(index, recents) { index.indicesOf(recents) }
    val favSet = remember(favorites) { favorites.toHashSet() }
    // Saveable, so Back from a channel returns to the same section, chip and card.
    var selected by rememberSaveable { mutableStateOf("") }
    var lastPlayed by rememberSaveable { mutableStateOf<String?>(null) }

    val entries = remember(index, favItems.size, recentItems.size) {
        buildList {
            add(RailEntry(KEY_SEARCH, "⌕  Search", null))
            add(RailEntry(KEY_FAV, "★  Favorites", favItems.size))
            if (recentItems.isNotEmpty()) add(RailEntry(KEY_RECENT, "Recent", recentItems.size))
            index.sections.forEach { add(RailEntry(SEC + it.name, it.name, it.count)) }
            add(RailEntry(KEY_ALL, "All channels", index.size))
        }
    }
    val sel = when {
        selected.isNotEmpty() && entries.any { it.key == selected } -> selected
        favItems.isNotEmpty() -> KEY_FAV
        recentItems.isNotEmpty() -> KEY_RECENT
        else -> index.sections.firstOrNull()?.let { SEC + it.name } ?: KEY_ALL
    }
    // Favorites as listed when the section opened: un-starring a card keeps it in place (so focus does not jump).
    val favView = remember(index, sel == KEY_FAV) { index.indicesOf(Prefs.liveFavorites) }

    val railRequesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor: (String) -> FocusRequester = { k -> railRequesters.getOrPut(k) { FocusRequester() } }
    val restoreFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hint = remember { FocusedChannel() }

    // Ticks every minute so now/next and progress bars stay current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    // Program guide (XMLTV from the playlist or the Xtream API): loads in the background, cached 6 h.
    LaunchedEffect(index) {
        val url = M3u.guideUrl
        if (url.isNotBlank()) {
            val g = Epg.load(url, index.source)
            if (g.isNotEmpty()) LiveSession.guide = g
        }
    }
    // Start on the card last played (Back from the player), otherwise on the selected rail entry.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val restored = lastPlayed != null && runCatching { restoreFocus.requestFocus() }.isSuccess
        if (!restored) runCatching { requesterFor(sel).requestFocus() }
    }

    val play: (IntArray, Int) -> Unit = { list, pos ->
        LiveSession.channels = index.channelsOf(list)
        lastPlayed = index.channels[list[pos]].url
        nav.push(Screen.LivePlay(pos))
    }
    val toggleFavorite: (Channel) -> Unit = { ch ->
        Prefs.toggleLiveFavorite(ch.url)
        favorites = Prefs.liveFavorites
    }
    val guide = LiveSession.guide

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
            LiveRail(
                entries = entries,
                selected = sel,
                requesterFor = requesterFor,
                onSelect = { selected = it },
                onOpen = { focusManager.moveFocus(FocusDirection.Right) },
            )
            Spacer(Modifier.width(16.dp))
            // Back inside the content returns to the rail (Back on the rail leaves Live TV as usual).
            Box(
                Modifier.weight(1f).fillMaxHeight().onPreviewKeyEvent { e ->
                    if (e.key == Key.Back) {
                        if (e.type == KeyEventType.KeyUp) runCatching { requesterFor(sel).requestFocus() }
                        true
                    } else {
                        false
                    }
                },
            ) {
                if (sel == KEY_SEARCH) {
                    SearchPane(index, favSet, guide, now, hint, lastPlayed, restoreFocus, play, toggleFavorite)
                } else {
                    val section = if (sel.startsWith(SEC)) index.sections.firstOrNull { SEC + it.name == sel } else null
                    val (title, base) = when (sel) {
                        KEY_FAV -> "Favorites" to favView
                        KEY_RECENT -> "Recent" to recentItems
                        KEY_ALL -> "All channels" to index.all
                        else -> (section?.name ?: "Channels") to (section?.items ?: IntArray(0))
                    }
                    SectionPane(
                        paneKey = sel,
                        title = title,
                        base = base,
                        subgroups = section?.subgroups ?: emptyList(),
                        emptyText = if (sel == KEY_FAV) "No favorites yet. Focus a channel and press ☰ (Menu) to add it." else "No channels here.",
                        index = index,
                        favSet = favSet,
                        guide = guide,
                        now = now,
                        hint = hint,
                        lastPlayed = lastPlayed,
                        restoreFocus = restoreFocus,
                        onPlay = play,
                        onMenu = toggleFavorite,
                    )
                }
            }
        }
        HintBar(hint, favSet)
    }
}

/** Left rail. Focus picks an entry; OK moves into the channels. Coming back always lands on the picked entry. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LiveRail(
    entries: List<RailEntry>,
    selected: String,
    requesterFor: (String) -> FocusRequester,
    onSelect: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .focusProperties { enter = { requesterFor(selected) } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.forEach { e ->
            RailItem(e.label, e.count, e.key == selected, requesterFor(e.key), onFocus = { onSelect(e.key) }, onClick = onOpen)
        }
    }
}

@Composable
private fun RailItem(label: String, count: Int?, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> McdColors.NavyLight
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected && !focused) McdColors.Red else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = when {
                focused -> InkOnWhite
                selected -> Color.White
                else -> McdColors.Muted
            },
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(countText(count), color = if (focused) Color(0xFF3A4255) else McdColors.Muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** A section (or Favorites / Recent / All): optional sub-group chips above the channel grid. */
@Composable
private fun SectionPane(
    paneKey: String,
    title: String,
    base: IntArray,
    subgroups: List<com.mcd.tv.data.LiveSubgroup>,
    emptyText: String,
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    // null = All. Resets when the section changes.
    var chip by rememberSaveable(paneKey) { mutableStateOf<String?>(null) }
    val current = subgroups.firstOrNull { it.label == chip }
    val shown = current?.items ?: base
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = broadcastStyle(20.sp))
            Spacer(Modifier.width(10.dp))
            Text("${countText(shown.size)} channels", color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (subgroups.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(start = 2.dp, end = 32.dp, top = 4.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "\u0001all") { ActionButton("All  ${countText(base.size)}", { chip = null }, primary = current == null) }
                items(subgroups.size) { k ->
                    val g = subgroups[k]
                    ActionButton("${g.label}  ${countText(g.items.size)}", { chip = g.label }, primary = current === g)
                }
            }
        }
        if (shown.isEmpty()) {
            StatusText(emptyText)
        } else {
            // Keyed by section + chip: each list starts at the top, and Back restores its scroll position.
            androidx.compose.runtime.key(paneKey, current?.label) {
                ChannelGrid(shown, index, favSet, guide, now, hint, lastPlayed, restoreFocus, onPlay, onMenu)
            }
        }
    }
}

/** Search across every channel's clean and original name (debounced, at most 200 results). */
@Composable
private fun SearchPane(
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // First result list is computed right away so Back from a channel can focus it again.
    var results by remember(index) { mutableStateOf(index.search(query, 200)) }
    LaunchedEffect(index, query) {
        delay(300)
        results = withContext(Dispatchers.Default) { index.search(query, 200) }
    }
    Column(Modifier.fillMaxSize()) {
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
            modifier = Modifier.padding(top = 4.dp, end = 32.dp, bottom = 8.dp).fillMaxWidth()
                .background(McdColors.Card, RoundedCornerShape(8.dp)).border(2.dp, McdColors.Cyan, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        val q = query.trim()
        when {
            q.isEmpty() -> StatusText("Search all ${countText(index.size)} channels by name.")
            results.isEmpty() -> StatusText("No channels match \"$q\".")
            else -> ChannelGrid(results, index, favSet, guide, now, hint, lastPlayed, restoreFocus, onPlay, onMenu)
        }
    }
}

/** Four channel cards per row. Lazy, keyed by stream URL. */
@Composable
private fun ChannelGrid(
    list: IntArray,
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    onPlay: (IntArray, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(start = 6.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = list.size, key = { pos -> index.channels[list[pos]].url }) { pos ->
            val i = list[pos]
            val ch = index.channels[i]
            val name = index.names[i]
            LiveChannelCard(
                ch = ch,
                name = name,
                favorite = ch.url in favSet,
                guide = guide,
                now = now,
                onClick = { onPlay(list, pos) },
                onMenu = { onMenu(ch) },
                onFocused = { focused ->
                    if (focused) {
                        hint.channel = ch
                        hint.name = name
                    } else if (hint.channel === ch) {
                        hint.channel = null
                    }
                },
                modifier = if (ch.url == lastPlayed) Modifier.focusRequester(restoreFocus) else Modifier,
            )
        }
    }
}

private val LiveCardShape = RoundedCornerShape(8.dp)

/**
 * Channel card: logo centered on a dark tile, clean name and what's on now underneath, a thin progress bar
 * and a gold star on favorites. Menu (☰) toggles the favorite; so does a long press where the remote supports it.
 */
@Composable
private fun LiveChannelCard(
    ch: Channel,
    name: String,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    now: Long,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cur = remember(guide, ch.url, now) { Epg.nowNext(Epg.keyOf(ch), guide, now).first }
    Column(modifier) {
        Card(
            onClick = onClick,
            onLongClick = onMenu,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { onFocused(it.isFocused) }
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Menu) {
                        if (e.type == KeyEventType.KeyUp) onMenu()
                        true
                    } else {
                        false
                    }
                },
            shape = CardDefaults.shape(shape = LiveCardShape),
            colors = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
            border = CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, McdColors.Line), shape = LiveCardShape),
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = LiveCardShape),
            ),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (ch.logo != null) {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                } else {
                    Text(
                        name,
                        style = broadcastStyle(15.sp),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    )
                }
                if (favorite) {
                    Text("★", color = StarGold, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (cur != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Red))
                    }
                }
            }
        }
        Text(
            name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        // Always present (even blank) so every grid row has the same height.
        Text(
            if (cur != null) cur.title else "",
            color = McdColors.Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bottom line: what OK and Menu do on the focused card. */
@Composable
private fun HintBar(hint: FocusedChannel, favSet: Set<String>) {
    val ch = hint.channel
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(Color.Black.copy(alpha = 0.35f)).padding(start = 32.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ch != null) {
            val fav = ch.url in favSet
            Text(hint.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            KeyHint("OK", "Play")
            Spacer(Modifier.width(18.dp))
            KeyHint("☰", if (fav) "★ Favorite (press to remove)" else "☆ Add to favorites")
        } else {
            Text(
                "Pick a section on the left, then press ► to browse its channels.  ☰ on a channel adds it to Favorites.",
                color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun KeyHint(keyLabel: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            keyLabel,
            color = InkOnWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(action, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

// ============================== Home row ==============================

/**
 * Home row of playlist channels (favorites, or recent channels): wide logo cards with the clean name and
 * what's on now. OK plays the channel straight away; channel up / down moves through this row's channels.
 */
@Composable
fun LiveChannelsRow(label: String, channels: List<Channel>, nav: Nav) {
    if (channels.isEmpty()) return
    val guide = LiveSession.guide
    Column(Modifier.padding(top = 20.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.url }) { i, ch ->
                LiveWideCard(ch, guide) {
                    LiveSession.channels = channels
                    nav.push(Screen.LivePlay(i))
                }
            }
        }
    }
}

@Composable
private fun LiveWideCard(ch: Channel, guide: Map<String, List<Programme>>, onClick: () -> Unit) {
    val name = remember(ch.name) { LiveOrganizer.cleanName(ch.name) }
    val now = System.currentTimeMillis()
    val cur = remember(guide, ch.url, now / 60_000L) { Epg.nowNext(Epg.keyOf(ch), guide, now).first }
    Column(Modifier.width(220.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.width(220.dp).height(124.dp),
            shape = CardDefaults.shape(shape = LiveCardShape),
            colors = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
            border = CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, McdColors.Line), shape = LiveCardShape),
                focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = LiveCardShape),
            ),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (ch.logo != null) {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    Text(
                        name, style = broadcastStyle(17.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp),
                    )
                }
                Text(
                    "LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(McdColors.LiveRed, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                if (cur != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Red))
                    }
                }
            }
        }
        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(if (cur != null) "Now: ${cur.title}" else "", color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================== Addon catalogs ==============================

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
