package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.data.Channel
import com.mcd.tv.data.FavoriteTeam
import com.mcd.tv.data.Game
import com.mcd.tv.data.HomeGame
import com.mcd.tv.data.HomeRow
import com.mcd.tv.data.HomeSide
import com.mcd.tv.data.HomeTeam
import com.mcd.tv.data.HomeTile
import com.mcd.tv.data.LiveFill
import com.mcd.tv.data.LiveGroup
import com.mcd.tv.data.LiveGroups
import com.mcd.tv.data.LiveHome
import com.mcd.tv.data.LiveIndex
import com.mcd.tv.data.LiveOrganizer
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= Live TV: What's On (the default view) and All Channels A-Z =======================
//
// The rows come from data/LiveHome.kt (the web app builds the same ones). A tile plays its first feed and puts the
// network's other feeds in the player: channel UP / DOWN switches between them.

/** Network groups and the last rows, kept for the loaded playlist so Back from the player redraws at once. */
object LiveHomeData {
    @Volatile private var groupsCache: Pair<LiveIndex, LiveGroups>? = null
    @Volatile private var rowsCache: Triple<LiveIndex, String, List<HomeRow>>? = null

    /** One entry per network for [index]; built off the main thread once per playlist. */
    suspend fun groups(index: LiveIndex): LiveGroups {
        groupsCache?.let { if (it.first === index) return it.second }
        val built = withContext(Dispatchers.Default) {
            val chs = index.channels
            val country = Array(chs.size) { LiveOrganizer.country(chs[it].group, chs[it].name) }
            LiveHome.groups(chs, { country[it] }, { LiveOrganizer.section(chs[it].group, chs[it].name, country[it]) })
        }
        groupsCache = index to built
        return built
    }

    /** The rows last built for this playlist and profile, or null. */
    fun cachedRows(index: LiveIndex, profile: String): List<HomeRow>? = rowsCache?.takeIf { it.first === index && it.second == profile }?.third

    private val HOME_LEAGUES = setOf("NFL", "NCAAF", "NBA", "MLB", "NHL")

    private fun side(t: com.mcd.tv.data.TeamLine) = HomeSide(t.name, t.short, t.nickname)

    /** ESPN games already loaded (no network). */
    fun games(): List<HomeGame> = LiveFill.loadedGames().filter { it.league.name in HOME_LEAGUES }.map { g: Game ->
        HomeGame(g.league.name, g.id, g.state, g.startMs, g.detail, side(g.away), side(g.home), g.broadcasts)
    }

    fun teams(profile: String): List<HomeTeam> = FavoriteTeam.listFrom(Prefs.favoriteTeamNames(profile), profile).map { HomeTeam(it.league.name, it.name) }

    /** The rows now, off the main thread. */
    suspend fun rows(index: LiveIndex, groups: LiveGroups, guide: Map<String, List<Programme>>, profile: String, now: Long): List<HomeRow> {
        val games = games()
        val teams = teams(profile)
        val rows = withContext(Dispatchers.Default) {
            val chs = index.channels
            LiveHome.home(groups, chs, { guide[chs[it].url].orEmpty() }, games, teams, now) { i -> LiveFill.eventProgramme(chs[i].name, chs[i].group, now) }
        }
        rowsCache = Triple(index, profile, rows)
        return rows
    }
}

/** Empty-row text, per row. */
private fun emptyText(id: String, teams: Boolean, guide: Boolean): String = when (id) {
    "myteams" -> if (teams) "No games for your teams in the schedules loaded so far." else "Add your teams in Settings > Addons and Live TV > My teams."
    "games" -> "No games on right now in the NFL, college football, MLB, NHL or NBA."
    "prime" -> "None of these networks are in your playlist."
    else -> if (guide) "Nothing like this on right now." else "Waiting for your provider's program guide…"
}

/**
 * What's On: My Teams, Live Games, History & Docs, Military, Cyber/AI/Tech, Primetime Entertainment. One tile per
 * network or game. The last card opens All Channels A-Z. [firstFocus] goes on the first tile.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WhatsOnPane(
    rows: List<HomeRow>?,
    index: LiveIndex,
    groups: LiveGroups?,
    hasTeams: Boolean,
    hasGuide: Boolean,
    firstFocus: FocusRequester,
    restoreFocus: FocusRequester,
    lastTile: String?,
    onTile: (String, HomeTile) -> Unit,
    onOpenAz: () -> Unit,
    hint: FocusedChannel,
) {
    if (rows == null) {
        StatusText("Finding what's on for ${Prefs.activeProfileName}…")
        return
    }
    val firstKey = rows.firstNotNullOfOrNull { r -> if (r.tiles.isNotEmpty()) "${r.id}:0" else null }
    var firstShown by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(start = 6.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { enter = { if (firstShown) firstFocus else FocusRequester.Default } }
            .focusGroup(),
    ) {
        items(rows, key = { it.id }) { row ->
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(row.title.uppercase(), style = broadcastStyle(16.sp))
                    if (row.tiles.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(countText(row.tiles.size), color = McdColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                if (row.tiles.isEmpty()) {
                    Text(emptyText(row.id, hasTeams, hasGuide), color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp, end = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(row.tiles.size, key = { n -> "${row.id}:$n" }) { n ->
                            val t = row.tiles[n]
                            val key = "${row.id}:$n"
                            if (key == firstKey) {
                                DisposableEffect(Unit) {
                                    firstShown = true
                                    onDispose { firstShown = false }
                                }
                            }
                            HomeTileCard(
                                t = t,
                                logoOf = { i -> index.channels.getOrNull(i)?.logo },
                                onClick = { onTile(key, t) },
                                onFocused = { focused ->
                                    if (focused) { hint.channel = null; hint.name = t.title; hint.home = true } else if (hint.name == t.title) hint.home = false
                                },
                                modifier = Modifier
                                    .then(if (key == firstKey) Modifier.focusRequester(firstFocus) else Modifier)
                                    .then(if (key == lastTile) Modifier.focusRequester(restoreFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }
        item(key = "\u0001az") {
            HudCard(
                onClick = onOpenAz,
                focusedScale = 1.02f,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { focused ->
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("All Channels A–Z", color = if (focused) McdColors.AccentBright else McdColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (groups != null) "${countText(groups.list.size)} networks  ·  ${countText(index.size)} channels" else "${countText(index.size)} channels",
                        color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    Text("›", color = McdColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** A row tile: badge (LIVE, a start time in Central Time, "On now"), title, what's on, how many feeds. */
@Composable
private fun HomeTileCard(t: HomeTile, logoOf: (Int) -> String?, onClick: () -> Unit, onFocused: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val badgeColor = when {
        t.mine -> MyTeamGold
        t.live -> McdColors.Accent
        else -> McdColors.Muted
    }
    HudCard(
        onClick = { if (t.feeds.isNotEmpty()) onClick() },
        focusedScale = 1.04f,
        modifier = modifier.width(268.dp).height(128.dp).onFocusChanged { onFocused(it.isFocused) },
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.badge.uppercase(), style = hudLabelStyle(10.sp, badgeColor), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val logo = t.feeds.firstOrNull()?.let(logoOf)
                if (logo != null) AsyncImage(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 52.dp, height = 22.dp))
            }
            Text(
                t.title, color = if (focused) McdColors.AccentBright else McdColors.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            Text(t.sub, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.weight(1f))
            if (t.feeds.size > 1) Text("${countText(t.feeds.size)} feeds  ·  ▲ ▼ in the player", color = McdColors.Muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * All Channels A-Z: one row per network (its best feed), RIGHT opens the other feeds under it; folders (event
 * channels, free channels, a country) open the same way. Kind chips filter. OK plays, with the network's feeds
 * behind channel UP / DOWN.
 */
@Composable
internal fun AzPane(
    groups: LiveGroups,
    index: LiveIndex,
    favSet: Set<String>,
    guide: Map<String, List<Programme>>,
    now: Long,
    hint: FocusedChannel,
    lastPlayed: String?,
    restoreFocus: FocusRequester,
    initialKind: String,
    onPlayGroup: (LiveGroup, Int) -> Unit,
    onMenu: (Channel) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    val counts = remember(groups) { groups.list.groupingBy { it.kind }.eachCount() }
    val shown = remember(groups, kind) { if (kind == "all") groups.list else groups.list.filter { it.kind == kind } }
    // The entry whose other feeds (or channels) are open under it.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val byPrimary = remember(shown) { HashMap<Int, LiveGroup>().also { m -> shown.forEach { m[it.feeds[0]] = it } } }
    val (list, nested) = remember(shown, openKey) {
        val out = ArrayList<Int>(shown.size + 8)
        val alt = HashSet<Int>()
        for (g in shown) {
            out.add(g.feeds[0])
            if (g.id == openKey) for (k in 1 until minOf(g.feeds.size, 400)) { out.add(g.feeds[k]); alt.add(g.feeds[k]) }
        }
        out.toIntArray() to alt
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("ALL CHANNELS A–Z", style = broadcastStyle(18.sp))
            Spacer(Modifier.width(10.dp))
            Text(
                "${countText(shown.size)} networks  ·  ${countText(shown.sumOf { it.feeds.size })} channels",
                color = McdColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(start = 2.dp, end = 32.dp, top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LiveHome.KINDS.filter { it.first == "all" || (counts[it.first] ?: 0) > 0 }, key = { it.first }) { (id, label) ->
                val n = if (id == "all") groups.list.size else counts[id] ?: 0
                ActionButton("$label  ${countText(n)}", { kind = id; openKey = null }, primary = kind == id)
            }
        }
        androidx.compose.runtime.key(kind) {
            ChannelGrid(
                list, index, favSet, guide, now, hint, lastPlayed, restoreFocus,
                onPlay = { l, pos -> val i = l[pos]; onPlayGroup(groups.list[groups.groupOf[i]], i) },
                onMenu = onMenu,
                feeds = { i -> byPrimary[i]?.let { it.feeds.size - 1 } ?: 0 },
                feedsOpen = { i -> byPrimary[i]?.id == openKey },
                nested = { i -> i in nested },
                onFeeds = { i ->
                    val k = byPrimary[i]?.id
                    if (k != null) {
                        openKey = if (openKey == k) null else k
                        hint.feedsOpen = openKey == k
                    }
                },
                nameOf = { i -> byPrimary[i]?.label },
            )
        }
    }
}
