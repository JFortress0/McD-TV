package com.mcd.tv.ui

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

@Composable
private fun PlaylistList(nav: Nav) {
    val res by rememberLoad(Prefs.m3uUrl) { M3u.load() }
    var group by remember { mutableStateOf<String?>(null) }
    when (val r = res) {
        is Load.Loading -> StatusText("Loading your channels… big playlists can take up to a minute the first time.", Modifier.padding(start = 48.dp))
        is Load.Err -> StatusText(r.message, Modifier.padding(start = 48.dp))
        is Load.Ok -> {
            val groups = remember(r.value) { r.value.map { it.group }.distinct() }
            val shown = remember(r.value, group) { r.value.filter { group == null || it.group == group }.take(500) }
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ActionButton("All (${r.value.size})", { group = null }, primary = group == null) }
                items(groups) { g -> ActionButton(g, { group = g }, primary = g == group) }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown) { ch -> ChannelRow(ch) { nav.push(Screen.Player(ch.url, ch.name)) } }
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
