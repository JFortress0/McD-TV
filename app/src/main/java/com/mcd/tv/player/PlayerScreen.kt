package com.mcd.tv.player

import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.mcd.tv.data.RdErrors
import com.mcd.tv.ui.ActionButton
import com.mcd.tv.ui.McdColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mcd.tv.ui.broadcastStyle

private const val SEEK_MS = 10_000L

/** "Try next source" for a video picked from a source list (see com.mcd.tv.ui.rememberNextSource). */
interface NextSource {
    /** True while another source is left to try. */
    fun hasMore(): Boolean

    /** Opens the next source that works and switches the player to it (returns null), or returns what to tell the viewer. */
    suspend fun tryNext(): String?
}

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
    /** When set, a failed stream shows "Try next source" and Back instead of a dead end. */
    nextSource: NextSource? = null,
    /** The panel's Back button (BACK on the remote works either way). */
    onBack: (() -> Unit)? = null,
    /** A live channel: live-edge, buffer and stall-recovery settings for IPTV (see [LiveTuning]). */
    live: Boolean = false,
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
    // Target time while LEFT / RIGHT presses add up (TvPlayerView seeks once they stop).
    var seekLabel by remember { mutableStateOf<String?>(null) }
    // Paused (playWhenReady off): after SCREEN_ON_WHILE_PAUSED_MS the screen may sleep (screensaver).
    var paused by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(true) }
    // Live: buffering right now (the stall watchdog below), and a short "Reconnecting" note.
    var buffering by remember { mutableStateOf(false) }
    // Live: how this channel plays, for Settings > Live TV health.
    val stats = remember { if (live) com.mcd.tv.data.LiveStats.Recorder(title) else null }
    var liveNote by remember { mutableStateOf<String?>(null) }

    val player = remember {
        // Follow http->https redirects (common with debrid and CDN links).
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(headers["User-Agent"] ?: "McDTV/0.1 (Android TV)")
            .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
            // IPTV servers are often slow to send the first byte of a segment; 8 s (the default) cut them off.
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
        val dataSource = DefaultDataSource.Factory(context, http)

        // Decoder fallback: if the Fire Stick's main decoder refuses a stream,
        // try the next one instead of failing.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .apply { if (live) setLoadControl(LiveTuning.loadControl()) }
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
            .apply {
                if (live) setMediaSource(LiveTuning.mediaSource(dataSource, url)) else setMediaItem(mediaItemFor(url))
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
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                stats?.onState(state)
                if (state == Player.STATE_READY) {
                    networkRetries = 0 // playing again: reset the retry budget
                    liveNote = null
                }
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
                // HTTP status of a refused stream (403 and friends): retrying won't help, so say why at once.
                val http = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                // Network hiccup (IO error codes 2000..2999): retry a few times before giving up.
                // Live channels get more tries, further apart (2, 4, 8, 10 s...), and rejoin at the live edge.
                val maxRetries = if (live) 8 else 3
                if (e.errorCode in 2000..2999 && (http == null || http !in 400..499) && networkRetries < maxRetries) {
                    networkRetries++
                    val wait = if (live) minOf(10_000L, 1_000L shl networkRetries) else 2_000L
                    if (live) liveNote = "Reconnecting…"
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(wait)
                        if (live) player.seekToDefaultPosition()
                        player.prepare() // keeps the current position
                    }
                    return
                }
                error = RdErrors.stream(http) ?: "${e.errorCodeName}\n${e.cause?.message ?: e.message ?: ""}"
            }
        }
        player.addListener(listener)
        val analytics = stats?.let { st ->
            object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    st.dropped += droppedFrames
                }

                override fun onVideoInputFormatChanged(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                ) {
                    val fps = if (format.frameRate > 0) " ${"%.0f".format(format.frameRate)} fps" else ""
                    val codec = format.sampleMimeType?.substringAfter('/').orEmpty()
                    st.video = "${format.width}x${format.height}$fps $codec".trim()
                }

                override fun onVideoDecoderInitialized(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    st.decoder = decoderName
                }

                override fun onBandwidthEstimate(
                    eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    totalLoadTimeMs: Int,
                    totalBytesLoaded: Long,
                    bitrateEstimate: Long,
                ) {
                    st.kbps = (bitrateEstimate / 1000).toInt()
                }
            }
        }
        analytics?.let { player.addAnalyticsListener(it) }
        onDispose {
            stats?.finish()
            analytics?.let { player.removeAnalyticsListener(it) }
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

    // Live: a channel stuck buffering for STALL_MS is reconnected at the live edge (a frozen picture often never
    // recovers by itself). After MAX_RECOVERIES in a row with no picture, say so instead of spinning forever.
    var recoveries by remember { mutableIntStateOf(0) }
    LaunchedEffect(buffering, live) {
        if (!live) return@LaunchedEffect
        if (!buffering) {
            if (player.playbackState == Player.STATE_READY) recoveries = 0
            return@LaunchedEffect
        }
        delay(LiveTuning.STALL_MS)
        if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING || error != null) return@LaunchedEffect
        if (recoveries >= LiveTuning.MAX_RECOVERIES) {
            error = "This channel keeps freezing. The provider may be busy. Try another channel or come back in a minute."
            return@LaunchedEffect
        }
        recoveries++
        stats?.let { it.reconnects++ }
        liveNote = "Reconnecting…"
        player.seekToDefaultPosition()
        player.prepare()
    }

    // Keep the screen on while playing. Paused for 10 minutes: let it go, so the screensaver can run.
    LaunchedEffect(paused) {
        if (paused) {
            delay(SCREEN_ON_WHILE_PAUSED_MS)
            keepScreenOn = false
        } else {
            keepScreenOn = true
        }
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

    // With a next-source panel, the player view leaves the screen so its focus guard can't pull focus
    // away from the panel's buttons. The player is already stopped by the error.
    val showNextPanel = error != null && nextSource != null

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!showNextPanel) AndroidView(
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
                        PlayerView.ControllerVisibilityListener { v ->
                            controlsVisible = v == View.VISIBLE
                            if (v != View.VISIBLE) keepFocus() // the hidden buttons can't take remote keys
                        },
                    )
                    this.keepScreenOn = true
                    seekPreview = { label -> seekLabel = label }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() } // remote key presses go to the player
                }
            },
            update = { view ->
                view.keyInterceptor = keyHandler
                view.keepScreenOn = keepScreenOn
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

        if (error == null && live) liveNote?.let { note ->
            Text(
                text = note,
                color = McdColors.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 40.dp)
                    .background(McdColors.Card.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (error == null) seekLabel?.let { label ->
            Text(
                text = label,
                color = McdColors.White,
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(McdColors.Card.copy(alpha = 0.9f))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
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

        val failed = error
        if (failed != null && nextSource != null) {
            NextSourcePanel(failed, nextSource, onBack, Modifier.align(Alignment.Center))
        } else if (failed != null) {
            val msg = failed
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

/** How long the screen stays on while the player is paused. */
private const val SCREEN_ON_WHILE_PAUSED_MS = 10 * 60_000L

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
        Library.record(meta, pos, dur, urgent = force)
    }
}

/** Error panel with "Try next source" (focused) and Back. Says "No more sources." when the list is used up. */
@Composable
private fun NextSourcePanel(detail: String, next: NextSource, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(next.hasMore()) }
    val nextFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var tryJob by remember { mutableStateOf<Job?>(null) }
    // Leaving stops a lookup still running, so it can't open a player on top of the page you went back to.
    fun leave() {
        tryJob?.cancel()
        onBack?.invoke()
    }
    BackHandler(enabled = onBack != null) { leave() }
    // Focus the main choice; when the list runs out, the Try button goes away and Back takes focus.
    LaunchedEffect(more) {
        withFrameNanos { }
        runCatching { if (more) nextFocus.requestFocus() else backFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .background(McdColors.Card.copy(alpha = 0.95f))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "CAN'T PLAY THIS STREAM", style = broadcastStyle(28.sp, McdColors.Red))
        Text(text = detail, color = McdColors.White, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        val line = when {
            busy -> "Getting the next source…"
            note != null -> note
            !more -> "No more sources."
            else -> null
        }
        if (line != null) {
            Text(text = line, color = McdColors.Amber, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (more) {
                ActionButton(
                    "Try next source",
                    {
                        if (!busy) {
                            busy = true
                            note = null
                            tryJob = scope.launch {
                                val msg = next.tryNext()
                                // null: the player is switching to the next source.
                                if (msg != null) {
                                    note = msg
                                    more = next.hasMore()
                                    busy = false
                                }
                            }
                        }
                    },
                    Modifier.focusRequester(nextFocus),
                    primary = true,
                )
            }
            ActionButton("Back", { leave() }, Modifier.focusRequester(backFocus))
        }
    }
}
