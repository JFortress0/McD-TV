package com.mcd.tv.player

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.mcd.tv.data.Library
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.ui.McdColors
import kotlinx.coroutines.delay
import com.mcd.tv.ui.broadcastStyle

private const val SEEK_MS = 10_000L

/** Builds a MediaItem, hinting the format when the URL makes it obvious. */
private fun mediaItemFor(url: String): MediaItem {
    val lower = url.lowercase()
    val mime = when {
        ".m3u8" in lower -> MimeTypes.APPLICATION_M3U8
        ".mpd" in lower -> MimeTypes.APPLICATION_MPD
        else -> null // let ExoPlayer detect MP4 / MKV / etc. from the file itself
    }
    return MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, meta: PlayMeta? = null, onEnded: (() -> Unit)? = null) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }

    val player = remember {
        // Follow http->https redirects (common with debrid and CDN links).
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("McDTV/0.1 (Android TV)")
        val dataSource = DefaultDataSource.Factory(context, http)

        // Decoder fallback: if the Fire Stick's main decoder refuses a stream,
        // try the next one instead of failing.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
            .apply {
                setMediaItem(mediaItemFor(url))
                // Resume where you left off (Continue Watching).
                meta?.let { m -> Library.resumePosition(m).takeIf { it > 0 }?.let { seekTo(it) } }
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveProgress(player, meta)
                    onEnded?.invoke()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}\n${e.cause?.message ?: e.message ?: ""}"
            }
        }
        player.addListener(listener)
        onDispose {
            saveProgress(player, meta)
            player.removeListener(listener)
            player.release() // frees the hardware decoder; important on low-RAM Fire Sticks
        }
    }

    // Save the position every 15 seconds so Continue Watching survives a crash or power-off.
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            saveProgress(player, meta)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TvPlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowSubtitleButton(true) // CC button appears when the stream has subtitles
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE },
                    )
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() } // remote key presses go to the player
                }
            },
        )

        // Title strip, shown with the controls (Media3's control bar has no title).
        if (controlsVisible && error == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(horizontal = 48.dp, vertical = 24.dp),
            ) {
                Text(text = title, style = broadcastStyle(28.sp))
            }
        }

        error?.let { msg ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(McdColors.Card.copy(alpha = 0.95f))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "CAN'T PLAY THIS STREAM", style = broadcastStyle(28.sp, McdColors.Red))
                Text(text = msg, color = McdColors.White, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = "Press BACK to return",
                    color = McdColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

private fun saveProgress(player: Player, meta: PlayMeta?) {
    if (meta == null) return
    val dur = player.duration
    if (dur <= 0) return
    Library.record(meta, player.currentPosition, dur)
}
