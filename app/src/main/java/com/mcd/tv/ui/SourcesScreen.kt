package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mcd.tv.data.Resolver
import com.mcd.tv.data.ArrangedStreams
import com.mcd.tv.data.RankedStream
import com.mcd.tv.data.StreamInfo
import com.mcd.tv.data.StreamSource
import kotlinx.coroutines.launch

/**
 * Source picker (HuberTV's "Select Source" panel): every stream your addons return,
 * with quality, size and a CACHED badge. autoPlay picks the best one by itself.
 */
@Composable
fun SourcesScreen(nav: Nav, meta: PlayMeta, imdbId: String, autoPlay: Boolean) {
    val streamId = if (meta.type == "tv") "$imdbId:${meta.season}:${meta.episode}" else imdbId
    var retry by remember { mutableIntStateOf(0) }
    val sources by rememberLoad(streamId, retry) { Addons.streams(meta.type, streamId) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    fun start(s: StreamSource) {
        if (busy) return
        busy = true
        status = "Getting stream from ${s.addon}…"
        scope.launch {
            runCatching { Resolver.resolve(s) }
                .onSuccess { url -> nav.replace(Screen.Player(url, meta.label, meta, imdbId, headers = s.headers)) }
                .onFailure { status = it.message ?: "Could not open this source"; busy = false }
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
                busy = true
                status = "Finding the best source…"
                runCatching { Resolver.best(l.value, meta) }
                    .onSuccess { (src, url) -> nav.replace(Screen.Player(url, meta.label, meta, imdbId, headers = src.headers)) }
                    .onFailure {
                        status = "${it.message} Pick one below."
                        busy = false
                        withFrameNanos { }
                        runCatching { firstFocus.requestFocus() }
                    }
            } else {
                // Wait one frame so the list is laid out before focusing its first row.
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(ScreenBackground).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Text("SELECT SOURCE", style = broadcastStyle(30.sp))
        Text(meta.label, color = McdColors.Muted, fontSize = 16.sp)
        if (Prefs.slowConnection) Text("Slow connection mode: smaller files listed first", color = McdColors.Muted, fontSize = 13.sp)
        if (status.isNotBlank()) Text(status, color = McdColors.Red, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))

        when (l) {
            is Load.Loading -> StatusText("Asking your addons…")
            is Load.Err -> {
                StatusText(l.message)
                ActionButton("Retry", { retry++ }, primary = true)
            }
            is Load.Ok -> if (l.value.isEmpty()) {
                StatusText(
                    if (Prefs.addonUrls.isEmpty()) "No addons installed. Add one from your phone: Settings > Phone setup."
                    else "Your addons found no sources for this title.",
                )
            } else LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val a = arranged ?: ArrangedStreams(emptyList(), emptyList())
                val rows = if (showHidden) a.visible + a.hidden else a.visible
                itemsIndexed(rows, key = { _, r -> r.info.dedupeKey }) { i, r ->
                    val m = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (showHidden && i == a.visible.size) Modifier.focusRequester(hiddenFocus) else Modifier)
                    SourceRow(r, onClick = { start(r.source) }, modifier = m)
                }
                if (a.hidden.isNotEmpty() && !showHidden) item(key = "show-hidden") {
                    ShowHiddenRow(
                        a.hidden.size,
                        onClick = { showHidden = true },
                        modifier = if (rows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 12.sp,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Quality tier as five stars: filled in the accent blue, empty dimmed. */
private fun stars(n: Int): AnnotatedString = buildAnnotatedString {
    val k = n.coerceIn(0, 5)
    withStyle(SpanStyle(color = McdColors.RedBright)) { append("★".repeat(k)) }
    withStyle(SpanStyle(color = Color.White.copy(alpha = 0.18f))) { append("★".repeat(5 - k)) }
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stars(info.stars), fontSize = 13.sp, maxLines = 1)
            if (info.resolution.isNotBlank()) Badge(info.resolution, if (info.resolution == "4K" || info.resolution == "1080p") McdColors.RedDark else Color(0xFF3A4466))
            info.hdr.forEach { Badge(it, Color(0xFF2A3550)) }
            if (info.audio.isNotBlank()) Badge(info.audio, Color(0xFF2A3550))
            if (info.source.isNotBlank()) Text(info.source, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
            if (info.sizeLabel.isNotBlank()) Text(info.sizeLabel, color = Color.White, fontSize = 13.sp, maxLines = 1)
            when {
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Text(
            "Show $count hidden source${if (count == 1) "" else "s"}  (CAM releases, files over the size limit)",
            color = McdColors.Muted, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
