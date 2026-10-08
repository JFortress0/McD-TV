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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
import com.mcd.tv.data.StreamSource
import kotlinx.coroutines.launch

/**
 * Source picker (HuberTV's "Select Source" panel): every stream your addons return,
 * with quality, size and a CACHED badge. autoPlay picks the best one by itself.
 */
@Composable
fun SourcesScreen(nav: Nav, meta: PlayMeta, imdbId: String, autoPlay: Boolean) {
    val streamId = if (meta.type == "tv") "$imdbId:${meta.season}:${meta.episode}" else imdbId
    val sources by rememberLoad(streamId) { Addons.streams(meta.type, streamId) }
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
                .onSuccess { url -> nav.replace(Screen.Player(url, meta.label, meta)) }
                .onFailure { status = it.message ?: "Could not open this source"; busy = false }
        }
    }

    val l = sources
    LaunchedEffect(l) {
        if (l is Load.Ok && l.value.isNotEmpty()) {
            if (autoPlay) {
                busy = true
                status = "Finding the best source…"
                runCatching { Resolver.best(l.value) }
                    .onSuccess { (_, url) -> nav.replace(Screen.Player(url, meta.label, meta)) }
                    .onFailure { status = "${it.message} Pick one below."; busy = false; runCatching { firstFocus.requestFocus() } }
            } else {
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
            is Load.Err -> StatusText(l.message)
            is Load.Ok -> if (l.value.isEmpty()) {
                StatusText(
                    if (Prefs.addonUrls.isEmpty()) "No addons installed. Add one from your phone: Settings > Phone setup."
                    else "Your addons found no sources for this title.",
                )
            } else LazyColumn(
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(l.value) { i, s ->
                    SourceRow(s, onClick = { start(s) }, modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
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

@Composable
private fun SourceRow(s: StreamSource, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(s.quality, if (s.quality == "4K" || s.quality == "1080p") Color(0xFF1F5FD6) else Color(0xFF3A4466))
                if (s.sizeText.isNotBlank()) Badge(s.sizeText, Color(0xFF3A4466))
                Badge(if (s.cached) "⚡ CACHED" else "NOT CACHED", if (s.cached) Color(0xFF178A3C) else Color(0xFF5A2A2A))
                s.seeders?.let { Badge("👤 $it", Color(0xFF3A4466)) }
                Text(s.name, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                s.title.replace("\n", "  "), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
