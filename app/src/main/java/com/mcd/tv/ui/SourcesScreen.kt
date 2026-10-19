package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.RealDebrid
import com.mcd.tv.data.Resolver
import com.mcd.tv.data.ArrangedStreams
import com.mcd.tv.data.RankedStream
import com.mcd.tv.data.StreamInfo
import com.mcd.tv.data.SourceQueue
import com.mcd.tv.data.StreamSource
import com.mcd.tv.data.XtreamVod
import com.mcd.tv.player.NextSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Source picker (HuberTV's "Select Source" panel): every stream your addons return,
 * with quality, size and a CACHED badge. autoPlay picks the best one by itself.
 */
@Composable
fun SourcesScreen(nav: Nav, meta: PlayMeta, imdbId: String, autoPlay: Boolean) {
    val streamId = if (meta.type == "tv") "$imdbId:${meta.season}:${meta.episode}" else imdbId
    var retry by remember { mutableIntStateOf(0) }
    val sources by rememberLoad(streamId, retry) { Addons.streams(meta.type, streamId, meta.tmdbId) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    /** [order] is the list on screen, so "Try next source" in the player goes down the same list. */
    fun start(s: StreamSource, order: List<StreamSource>, index: Int) {
        if (busy) return
        busy = true
        status = "Getting stream from ${s.addon}…"
        scope.launch {
            runCatching { Resolver.resolve(s) }
                .onSuccess { url ->
                    val queue = SourceQueue.put(order)
                    nav.replace(Screen.Player(url, meta.label, meta, imdbId, headers = s.headers, queue = queue, queuePos = index))
                }
                .onFailure { status = it.message ?: "Could not open this source"; busy = false; if (it !is kotlinx.coroutines.CancellationException) com.mcd.tv.data.RecentErrors.note("Source: $status") }
        }
    }

    /** The Play button's pick: try the sources best first and play the first that opens. */
    suspend fun playBest(all: List<StreamSource>) {
        if (busy) return
        busy = true
        status = "Finding the best source…"
        // Back leaves the screen and cancels this, including a wait between Real-Debrid adds.
        runCatching { Resolver.best(all, meta, onStatus = { status = it }) }
            .onSuccess { (src, url) ->
                // Same order Resolver.best tried, so "Try next source" picks up after the one that worked.
                val order = StreamInfo.playOrder(all, episode = meta.type == "tv" || all.any { it.episode != null })
                val queue = SourceQueue.put(order)
                nav.replace(Screen.Player(url, meta.label, meta, imdbId, headers = src.headers, queue = queue, queuePos = order.indexOf(src)))
            }
            .onFailure {
                if (it is CancellationException) throw it
                status = "${it.message} Pick one below."
                busy = false
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
    }

    val l = sources
    // Sorted, de-duplicated rows; CAM releases and oversized files are hidden until "Show N hidden sources".
    val arranged = remember(l) { (l as? Load.Ok<List<StreamSource>>)?.value?.let { StreamInfo.arrange(it, episode = meta.type == "tv") } }
    var showHidden by remember { mutableStateOf(false) }
    // "Show N hidden sources" disappears when pressed: move focus to the first revealed row.
    val hiddenFocus = remember { FocusRequester() }
    LaunchedEffect(showHidden) {
        if (showHidden) { withFrameNanos { }; runCatching { hiddenFocus.requestFocus() } }
    }
    LaunchedEffect(l) {
        if (l is Load.Ok && l.value.isEmpty() && autoPlay && meta.type == "tv" && meta.episode > 1) {
            // Auto-advance ran past the last episode of the season: try the next season's first episode.
            // Only from episode > 1, and the new screen asks for episode 1, so this rolls over at most once.
            nav.replace(Screen.Sources(meta.copy(season = meta.season + 1, episode = 1), imdbId, true))
        } else if (l is Load.Ok && l.value.isNotEmpty()) {
            if (autoPlay) {
                playBest(l.value)
            } else {
                // Wait one frame so the list is laid out before focusing its first row.
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
        }
    }

    Column(Modifier.fillMaxSize().hudBackground().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text("SELECT SOURCE", style = broadcastStyle(30.sp))
        Text(meta.label, color = McdColors.Muted, fontSize = 16.sp)
        if (Prefs.slowConnection) Text("Slow connection mode: smaller files listed first", color = McdColors.Muted, fontSize = 13.sp)
        if (status.isNotBlank()) Text(status, color = McdColors.Amber, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))

        when (l) {
            is Load.Loading -> StatusText("Asking your addons…")
            is Load.Err -> {
                StatusText(l.message)
                ActionButton("Retry", { retry++ }, primary = true)
            }
            is Load.Ok -> if (l.value.isEmpty()) {
                // Say why: no addons, addons that did not answer (often a VPN), or nothing listed for this title.
                val failed = remember(l) { Addons.lastFailed }
                val all = Prefs.addonUrls.size
                StatusText(
                    when {
                        all == 0 -> "No addons installed. Add one on the Control page: Settings > Addons and Live TV."
                        failed.size >= all -> "Your addons did not answer (${failed.joinToString()}). A VPN on this TV or the router can block them. Turn it off and press Retry."
                        failed.isNotEmpty() -> "No sources found. ${failed.joinToString()} did not answer, so press Retry. A VPN can block addons."
                        else -> "Your addons answered but list no sources for this title yet. New talk shows and specials often have none. " +
                            "If you added it to Real-Debrid yourself, it is in Real-Debrid Cloud."
                    },
                )
                if (all > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Retry", { retry++ }, Modifier.focusRequester(firstFocus), primary = true)
                        if (RealDebrid.connected) ActionButton("Real-Debrid Cloud", { nav.push(Screen.RdCloud) })
                    }
                    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
                }
            } else LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val a = arranged ?: ArrangedStreams(emptyList(), emptyList())
                val rows = if (showHidden) a.visible + a.hidden else a.visible
                val order = rows.map { it.source }
                // Episodes and "Choose Source" open this list: one press still plays the best source.
                item(key = "play-best") {
                    ActionButton("⚡  Play best source", { scope.launch { playBest(l.value) } }, Modifier.focusRequester(firstFocus), primary = true)
                }
                itemsIndexed(rows, key = { _, r -> r.info.dedupeKey }) { i, r ->
                    val m = if (showHidden && i == a.visible.size) Modifier.focusRequester(hiddenFocus) else Modifier
                    SourceRow(r, onClick = { start(r.source, order, i) }, modifier = m)
                }
                if (a.hidden.isNotEmpty() && !showHidden) item(key = "show-hidden") {
                    ShowHiddenRow(
                        a.hidden.size,
                        onClick = { showHidden = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = McdColors.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color, HudShapeTiny).border(1.dp, McdColors.Line, HudShapeTiny).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Quality tier as five stars: filled in the accent blue, empty dimmed. */
private fun stars(n: Int): AnnotatedString = buildAnnotatedString {
    val k = n.coerceIn(0, 5)
    withStyle(SpanStyle(color = McdColors.RedBright)) { append("★".repeat(k)) }
    withStyle(SpanStyle(color = McdColors.White.copy(alpha = 0.18f))) { append("★".repeat(5 - k)) }
}

/** One clean line: stars, resolution, HDR, audio, size, Instant / Download, then the addon name. */
@Composable
private fun SourceRow(r: RankedStream, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val info = r.info
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = McdColors.Card,
            focusedContainerColor = McdColors.NavyLight,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stars(info.stars), fontSize = 13.sp, maxLines = 1)
            if (info.resolution.isNotBlank()) Badge(info.resolution, if (info.resolution == "4K" || info.resolution == "1080p") McdColors.RedDark else McdColors.Raised)
            info.hdr.forEach { Badge(it, McdColors.Raised) }
            if (info.audio.isNotBlank()) Badge(info.audio, McdColors.Raised)
            if (XtreamVod.isIptv(r.source)) Badge(r.source.name, McdColors.RedDark)
            if (info.source.isNotBlank()) Text(info.source, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
            if (info.sizeLabel.isNotBlank()) Text(info.sizeLabel, color = McdColors.White, fontSize = 13.sp, maxLines = 1)
            when {
                info.rdBlocked -> Text("May be blocked", color = McdColors.Muted, fontSize = 13.sp, maxLines = 1)
                info.instant -> Text("⚡ Instant", color = McdColors.RedBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                info.download -> Text("⏳ Download", color = McdColors.Muted, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(r.source.addon, color = McdColors.Muted.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Last row when some sources are hidden (CAM releases, files over the size limit). */
@Composable
private fun ShowHiddenRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = McdColors.NavyLight,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(HudShape),
        border = hudSurfaceBorder(),
    ) {
        Text(
            "Show $count hidden source${if (count == 1) "" else "s"}  (CAM releases, files over the size limit)",
            color = McdColors.Muted, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * "Try next source" for a player started from a source list ([Screen.Player.queue]), or null when the
 * video did not come from one (Live TV, websites, Background Noise).
 */
@Composable
fun rememberNextSource(nav: Nav, screen: Screen.Player): NextSource? =
    remember(screen) { SourceQueue.get(screen.queue)?.let { QueueNextSource(nav, screen, it) } }

private class QueueNextSource(
    private val nav: Nav,
    private val from: Screen.Player,
    private val list: List<StreamSource>,
) : NextSource {
    /** Last source tried. Sources that fail to open are skipped on the next press. */
    private var cursor = from.queuePos

    override fun hasMore(): Boolean = SourceQueue.next(list, cursor) != null

    override suspend fun tryNext(): String? {
        var lastError: String? = null
        // A few per press: an uncached torrent can take 15 s to fail on Real-Debrid.
        repeat(3) {
            val i = SourceQueue.next(list, cursor) ?: return if (lastError != null) "$lastError No more sources." else "No more sources."
            cursor = i
            val src = list[i]
            try {
                val url = Resolver.resolve(src, from.meta)
                currentCoroutineContext().ensureActive() // left the player meanwhile: open nothing
                nav.replace(from.copy(url = url, headers = src.headers, queuePos = i))
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "Could not open that source."
                // Real-Debrid needs reconnecting: the other torrents would fail the same way.
                if (e is IllegalStateException && e.message?.startsWith("Real-Debrid needs reconnecting") == true) return lastError
            }
        }
        return lastError
    }
}
