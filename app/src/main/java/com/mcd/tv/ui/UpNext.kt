package com.mcd.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Tmdb
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.delay

private const val UP_NEXT_SECONDS = 10

/**
 * Player for a TV episode that rolls into the next one. When the episode ends, the player is
 * closed (freeing the decoder) and an "Up next" card counts down 10 s before [onNext] runs.
 * "Play now" (focused) skips the wait; "Cancel" or BACK runs [onCancel].
 */
@Composable
fun UpNextPlayer(
    url: String,
    title: String,
    meta: PlayMeta,
    headers: Map<String, String>,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onSleep: (() -> Unit)? = null,
) {
    var ended by remember { mutableStateOf(false) }
    if (!ended) {
        PlayerScreen(url = url, title = title, meta = meta, onEnded = { ended = true }, headers = headers, onSleep = onSleep)
    } else {
        UpNextCard(meta, onNext, onCancel)
    }
}

@Composable
private fun UpNextCard(meta: PlayMeta, onNext: () -> Unit, onCancel: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    var remaining by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    var done by remember { mutableStateOf(false) }
    val next by rememberUpdatedState(onNext)
    val cancel by rememberUpdatedState(onCancel)

    fun go(action: () -> Unit) {
        if (done) return
        done = true
        action()
    }

    BackHandler { go(cancel) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        go(next)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            Tmdb.img(meta.backdrop, "w1280"), meta.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(
            Modifier
                .align(Alignment.Center)
                .background(McdColors.Card.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(horizontal = 40.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(meta.name, color = McdColors.Muted, fontSize = 16.sp)
            Text(
                "Up next: S${meta.season}E${meta.episode + 1} in $remaining…",
                style = broadcastStyle(28.sp),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionButton("▶  Play now", { go(next) }, Modifier.focusRequester(playFocus), primary = true)
                ActionButton("Cancel", { go(cancel) })
            }
        }
    }
}
