package com.mcd.tv.player

import android.view.KeyEvent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.C
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
fun PlayerScreen(
    url: String,
    title: String,
    meta: PlayMeta? = null,
    onEnded: (() -> Unit)? = null,
    headers: Map<String, String> = emptyMap(),
    /** Sees every remote key before the player does (with whether the controls are showing); return true to consume it. */
    onKeyEvent: ((android.view.KeyEvent, Boolean) -> Boolean)? = null,
    /** false: the control bar appears only when a key asks for it (Live TV, so channel surfing isn't interrupted). */
    autoShowControls: Boolean = true,
    /** MENU cycles a sleep timer (Off / 30 / 60 / 90 min / end of this video) on non-live video. */
    sleepTimerEnabled: Boolean = true,
    /** Called when the sleep timer fires, after pausing (e.g. leave the player). Null: just pause. */
    onSleep: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(autoShowControls) }
    var audioNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val progress = remember { ProgressSaver(meta) }
    @Suppress("DEPRECATION") // androidx.compose.ui.platform.LocalLifecycleOwner: always on the classpath with this BOM
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnSleep by rememberUpdatedState(onSleep)
    val currentOnEnded by rememberUpdatedState(onEnded)
    // Sleep timer: index into SLEEP_STEPS (0 = off). Each MENU press moves to the next step.
    var sleepStep by remember { mutableIntStateOf(0) }
    var sleepToast by remember { mutableStateOf<String?>(null) }
    var sleepToastTick by remember { mutableIntStateOf(0) }

    val player = remember {
        // Follow http->https redirects (common with debrid and CDN links).
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"] ?: "McDTV/0.1 (Android TV)")
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
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
        var networkRetries = 0
        var retryJob: Job? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) networkRetries = 0 // playing again: reset the retry budget
                if (state == Player.STATE_ENDED) {
                    progress.save(player, force = true)
                    if (SLEEP_STEPS[sleepStep] == SLEEP_END_OF_VIDEO) {
                        // "End of this video": stop here instead of rolling into the next episode.
                        sleepStep = 0
                        player.pause()
                        currentOnSleep?.invoke()
                    } else {
                        currentOnEnded?.invoke()
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Audio tracks exist but none can be decoded here (e.g. DTS / TrueHD on a Fire Stick).
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                audioNote = if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                    "This file's audio format isn't supported by this device. Try another source."
                } else {
                    null
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                // Live stream fell behind the window: jump back to the live edge.
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                // Network hiccup (IO error codes 2000..2999): retry a few times before giving up.
                if (e.errorCode in 2000..2999 && networkRetries < 3) {
                    networkRetries++
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(2_000)
                        player.prepare() // keeps the current position
                    }
                    return
                }
                error = "${e.errorCodeName}\n${e.cause?.message ?: e.message ?: ""}"
            }
        }
        player.addListener(listener)
        onDispose {
            retryJob?.cancel()
            progress.save(player, force = true)
            player.removeListener(listener)
            player.release() // frees the hardware decoder; important on low-RAM Fire Sticks
        }
    }

    // Leaving the app (Home button, screensaver, TV off): pause so audio does not play behind the launcher.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                progress.save(player, force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Save the position every 15 seconds so Continue Watching survives a crash or power-off.
    LaunchedEffect(player) {
        while (true) {
            delay(15_000)
            progress.save(player)
        }
    }

    // Sleep timer countdown (restarts whenever the setting changes).
    LaunchedEffect(sleepStep) {
        val minutes = SLEEP_STEPS[sleepStep]
        if (minutes > 0) {
            delay(minutes * 60_000L)
            sleepStep = 0
            player.pause()
            progress.save(player, force = true)
            currentOnSleep?.invoke()
        }
    }

    // Hide the "Sleep timer: ..." note 2.5 s after the last MENU press.
    LaunchedEffect(sleepToastTick) {
        if (sleepToast != null) {
            delay(2_500)
            sleepToast = null
        }
    }

    // MENU cycles the sleep timer on non-live video. Consumes both down and up so Media3 ignores it.
    val handleSleepKey: (KeyEvent) -> Boolean = { e ->
        if (e.keyCode != KeyEvent.KEYCODE_MENU || player.isCurrentMediaItemLive) {
            false
        } else {
            if (e.action == KeyEvent.ACTION_UP) {
                sleepStep = (sleepStep + 1) % SLEEP_STEPS.size
                val step = SLEEP_STEPS[sleepStep]
                sleepToast = when {
                    step == 0 -> "Sleep timer: Off"
                    step == SLEEP_END_OF_VIDEO -> "Sleep timer: End of this video"
                    else -> "Sleep timer: $step min"
                }
                sleepToastTick++
            }
            true
        }
    }
    val keyHandler: (KeyEvent, Boolean) -> Boolean = { e, showing ->
        onKeyEvent?.invoke(e, showing) == true || (sleepTimerEnabled && handleSleepKey(e))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TvPlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    controllerAutoShow = autoShowControls
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
            update = { view -> view.keyInterceptor = keyHandler },
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

        if (error == null) audioNote?.let { note ->
            Text(
                text = note,
                color = McdColors.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(McdColors.Card.copy(alpha = 0.9f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        sleepToast?.let { msg ->
            Text(
                text = msg,
                color = McdColors.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 40.dp)
                    .background(McdColors.Card.copy(alpha = 0.9f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
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

/** Sleep timer steps in minutes; 0 = off, [SLEEP_END_OF_VIDEO] = stop when this video ends. */
private const val SLEEP_END_OF_VIDEO = -1
private val SLEEP_STEPS = intArrayOf(0, 30, 60, 90, SLEEP_END_OF_VIDEO)

/** Writes the resume position, skipping writes when the position moved 5 s or less since the last one. */
private class ProgressSaver(private val meta: PlayMeta?) {
    private var lastSavedMs = -1L

    fun save(player: Player, force: Boolean = false) {
        if (meta == null) return
        val dur = player.duration
        if (dur <= 0) return // C.TIME_UNSET is negative too
        val pos = player.currentPosition
        if (!force && lastSavedMs >= 0 && kotlin.math.abs(pos - lastSavedMs) <= 5_000) return
        if (force && pos == lastSavedMs) return
        lastSavedMs = pos
        Library.record(meta, pos, dur)
    }
}
