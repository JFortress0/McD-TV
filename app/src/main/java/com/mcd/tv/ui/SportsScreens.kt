package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Game
import com.mcd.tv.data.League
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Scores
import com.mcd.tv.data.TeamLine
import kotlinx.coroutines.delay

private fun teamColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor("#" + (hex ?: "333333"))) }.getOrDefault(McdColors.Card)

// ============================== Sports hub ==============================

/**
 * Broadcast-style scoreboard: live games first, then today's schedule.
 * Select a game to see matching channels from your own Live TV playlist.
 */
@Composable
fun SportsScreen(nav: Nav) {
    var league by remember { mutableStateOf(League.NFL) }
    var tick by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Game?>(null) }
    val games by rememberLoad(league, tick) { Scores.scoreboard(league) }
    val channels by rememberLoad(Prefs.m3uUrl) { runCatching { M3u.load() }.getOrDefault(emptyList()) }

    // Live scores refresh every 30 seconds.
    LaunchedEffect(league) { while (true) { delay(30_000); tick++ } }

    TabPage(nav, NavTab.Sports) {
        Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("McD TV ", style = broadcastStyle(30.sp))
            Text("SPORTS CENTER", style = broadcastStyle(30.sp, McdColors.Red))
        }
        // Websites you added (Control page or home setup page). Re-read every few seconds so a save shows up right away.
        var sites by remember { mutableStateOf(Prefs.websites) }
        LaunchedEffect(Unit) { while (true) { sites = Prefs.websites; delay(3000) } }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item { Text("WEBSITES  ", style = broadcastStyle(16.sp, McdColors.Muted)) }
            if (sites.isEmpty()) {
                item { Text("None yet. Add them in McD TV Control (Settings > Phone & Computer Setup).", color = McdColors.Muted, fontSize = 14.sp) }
            } else {
                items(sites) { (name, url) -> ActionButton("🌐 $name", { nav.push(Screen.Web(url, name)) }, primary = true) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(League.entries) { l -> ActionButton(l.label, { league = l; picked = null }, primary = l == league) }
        }

        val p = picked
        if (p != null) {
            val list = (channels as? Load.Ok<List<Channel>>)?.value ?: emptyList()
            GameChannels(p, list, onPlay = { ch -> nav.push(Screen.Player(ch.url, "${p.away.short} @ ${p.home.short}  •  ${ch.name}")) }, onClose = { picked = null })
        } else when (val g = games) {
            is Load.Loading -> StatusText("Loading scores…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(g.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> if (g.value.isEmpty()) StatusText("No ${league.label} games today.", Modifier.padding(start = 48.dp))
            else LazyVerticalGrid(
                columns = GridCells.Adaptive(380.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(g.value, key = { it.id }) { game -> GameCard(game, onClick = { picked = game }) }
            }
        }
    }
}

@Composable
private fun TeamRow(t: TeamLine, winning: Boolean, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(t.logo, t.name, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(t.name, style = broadcastStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.record.isNotBlank()) Text(t.record, color = McdColors.Muted, fontSize = 12.sp)
        }
        if (showScore) Text(t.score, style = broadcastStyle(30.sp, if (winning) Color.White else McdColors.Muted))
    }
}

@Composable
private fun GameCard(g: Game, onClick: () -> Unit) {
    val awayLead = (g.away.score.toIntOrNull() ?: 0) > (g.home.score.toIntOrNull() ?: 0)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(170.dp),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.fillMaxSize()) {
            // Team color stripe, like a broadcast score bug.
            Column(Modifier.width(8.dp).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().background(teamColor(g.away.color)))
                Box(Modifier.weight(1f).fillMaxWidth().background(teamColor(g.home.color)))
            }
            Column(Modifier.padding(14.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                TeamRow(g.away, awayLead, g.state != "pre")
                TeamRow(g.home, !awayLead, g.state != "pre")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (g.live) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(McdColors.Red))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", style = broadcastStyle(13.sp, McdColors.Red))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(g.detail, color = Color.White, fontSize = 13.sp)
                    if (g.broadcasts.isNotEmpty()) Text("   •   " + g.broadcasts.joinToString(", "), color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GameChannels(g: Game, all: List<Channel>, onPlay: (Channel) -> Unit, onClose: () -> Unit) {
    val matches = remember(g, all) { M3u.matchesFor(g, all) }
    Column(Modifier.padding(horizontal = 48.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Brush.horizontalGradient(listOf(teamColor(g.away.color), McdColors.Navy, teamColor(g.home.color))))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(g.away.logo, g.away.name, modifier = Modifier.size(64.dp))
                Text("  ${g.away.short}  ${g.away.score}  @  ${g.home.score}  ${g.home.short}  ", style = broadcastStyle(34.sp))
                AsyncImage(g.home.logo, g.home.name, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(24.dp))
                Text(g.detail + if (g.broadcasts.isNotEmpty()) "  •  " + g.broadcasts.joinToString(", ") else "", color = Color.White)
            }
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Back to scores", onClose)
        }
        when {
            Prefs.m3uUrl.isBlank() -> StatusText("Add your Live TV playlist (Settings > Phone setup) to watch from here.")
            matches.isEmpty() -> StatusText("No channel in your playlist matches this game. Open Live TV to browse all channels.")
            else -> {
                RailHeader("Channels for this game")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(matches) { ch -> ChannelRow(ch) { onPlay(ch) } }
                }
            }
        }
    }
}

@Composable
fun ChannelRow(ch: Channel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(ch.logo, ch.name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 40.dp))
            Spacer(Modifier.width(16.dp))
            Text(ch.name, style = broadcastStyle(18.sp))
            Spacer(Modifier.width(12.dp))
            Text(ch.group, color = McdColors.Muted, fontSize = 13.sp)
        }
    }
}

