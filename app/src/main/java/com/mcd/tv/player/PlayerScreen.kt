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
import com.mcd.tv.data.NowPlaying
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.BadSources
import com.mcd.tv.data.PlaybackRescue
import com.mcd.tv.data.SourceHealth
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
    /** SourceHealth.key of the playing file: a file that stops far short of the title is flagged for the house. */
    sourceKey: String? = null,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(autoShowControls) }
    var audioNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Saved position for this title (this TV, another TV or a phone). Read once, before the player starts.
    // It belongs to the title, not to a source: any source resumes here.
    val saved = remember { meta?.let { Library.resumeEntry(it) } }
    val resumeMs = saved?.positionMs ?: 0L
    val savedDurMs = saved?.durationMs ?: 0L
    // The title's runtime (TMDB, usually cached by Detail): tells a cut-off source from the real end.
    var runtimeMs by remember { mutableStateOf(0L) }
    LaunchedEffect(meta) {
        if (meta != null && !live && meta.tmdbId > 0) {
            runtimeMs = runCatching { com.mcd.tv.data.Tmdb.details(meta.type, meta.tmdbId).runtimeMin * 60_000L }.getOrDefault(0L)
        }
    }
    // Non-live: what to do when the video fails or stalls at one spot (see PlaybackRescue).
    val rescue = remember { PlaybackRescue(if (live) 0L else resumeMs) { nextSource?.hasMore() == true } }
    // Saves the title's length, not a cut-off source's, so a stop there never reads as "watched" elsewhere.
    val progress = remember {
        ProgressSaver(meta, { rescue.keepSavedBelow }) { d -> SourceHealth.progressDurationMs(d, runtimeMs, savedDurMs) }
    }
    // What this TV plays, for the web app ("now" on the relay). Cleared when the player leaves.
    val report = remember { NowReporter(title, meta, live) }
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
    // Live: how this channel plays, for Settings > Addons and Live TV.
    val stats = remember { if (live) com.mcd.tv.data.LiveStats.Recorder(title) else null }
    var liveNote by remember { mutableStateOf<String?>(null) }
    // Non-live: a short note when playback had to recover (reconnect, start over, skip, next source).
    var rescueNote by remember { mutableStateOf<String?>(null) }
    var rescueNoteTick by remember { mutableIntStateOf(0) }
    var rescueJob by remember { mutableStateOf<Job?>(null) }

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
                // Resume where you left off (Continue Watching), on this TV or another device.
                if (resumeMs > 0) seekTo(resumeMs)
                prepare()
                playWhenReady = true
            }
    }

    fun showRescueNote(text: String) {
        rescueNote = text
        rescueNoteTick++
    }

    // The length this title should have: the longest of this source's, the runtime and the saved length.
    fun expectedMs(): Long = SourceHealth.expectedMs(player.duration.coerceAtLeast(0L), runtimeMs, savedDurMs)

    // This file stops for good at [atMs], far short of the title: flag it for the whole house and say so.
    fun flagIncomplete(atMs: Long): String {
        val exp = expectedMs()
        if (meta != null && sourceKey != null) {
            BadSources.mark(SourceHealth.titleKey(meta.type, meta.tmdbId, meta.season, meta.episode), sourceKey, atMs)
        }
        val at = com.mcd.tv.data.SeekAccumulator.clock(atMs)
        com.mcd.tv.data.RecentErrors.note("Playback: ${title.ifBlank { "video" }}: source incomplete, stops at $at")
        return "This source appears incomplete. It stops at $at of ${com.mcd.tv.data.SeekAccumulator.clock(exp)}."
    }

    // Leave a broken source: keep the position (it belongs to the title) and switch, or say what to do.
    fun leaveBrokenSource(msg: String) {
        rescueJob?.cancel()
        player.stop()
        // A resume that never played sits at this file's end, before the saved spot: keep the saved spot.
        val keepSaved = rescue.resumePending && player.currentPosition < resumeMs
        if (!keepSaved) progress.save(player, force = true)
        val next = nextSource
        val at = com.mcd.tv.data.SeekAccumulator.clock(if (keepSaved) resumeMs else player.currentPosition)
        if (next != null && next.hasMore()) {
            showRescueNote("$msg Trying the next one at $at.")
            rescueJob = scope.launch {
                val m2 = next.tryNext() ?: return@launch // null: switching to the next source
                error = "$msg $m2"
            }
        } else {
            error = "$msg Pick another source. Jarvis resumes at $at."
        }
    }

    // Non-live video failed or stalled at the current position: ask PlaybackRescue what to do, and do it.
    fun recover(retryable: Boolean, why: String) {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val step = rescue.onFailure(pos, retryable)
        // It played up to here and won't go on, far short of the title: the file is cut off or broken there.
        if (step.action != PlaybackRescue.Action.RECONNECT && !rescue.failedAtResume && SourceHealth.incomplete(pos, expectedMs())) {
            leaveBrokenSource(flagIncomplete(pos))
            return
        }
        val at = com.mcd.tv.data.SeekAccumulator.clock(pos)
        if (step.action != PlaybackRescue.Action.RECONNECT) {
            com.mcd.tv.data.RecentErrors.note("Playback: ${title.ifBlank { "video" }}: $why at $at, ${step.action.name.lowercase().replace('_', ' ')}")
        }
        rescueJob?.cancel()
        when (step.action) {
            PlaybackRescue.Action.RECONNECT -> {
                showRescueNote("Reconnecting…")
                rescueJob = scope.launch {
                    delay(2_000)
                    player.stop() // keeps the position; stops a load that hangs
                    player.prepare()
                }
            }
            PlaybackRescue.Action.FROM_START, PlaybackRescue.Action.SKIP_AHEAD -> {
                step.note?.let { showRescueNote(it) }
                player.stop()
                player.seekTo(step.seekToMs.coerceAtLeast(0L))
                player.prepare()
                player.playWhenReady = true
            }
            PlaybackRescue.Action.NEXT_SOURCE -> {
                val next = nextSource
                if (next == null) {
                    error = step.note
                    return
                }
                step.note?.let { showRescueNote(it) }
                player.stop()
                // The next source resumes here.
                progress.save(player, force = true)
                rescueJob = scope.launch {
                    val msg = next.tryNext() ?: return@launch // null: switching to the next source
                    error = "This source stopped at $at. $msg"
                }
            }
            PlaybackRescue.Action.GIVE_UP -> {
                player.stop()
                error = step.note
            }
        }
    }

    // Checks this source's length once it is known (and again when the runtime arrives). A file far shorter
    // than the title is flagged; if the saved spot is past its end it is left at once, else at its end.
    // Returns true when it moved the player (the caller skips its own handling).
    var shortFlagged by remember { mutableStateOf(false) }
    fun checkSource(): Boolean {
        val dur = player.duration
        if (live || dur <= 0) return false
        val exp = SourceHealth.expectedMs(0L, runtimeMs, savedDurMs)
        val pos = player.currentPosition
        if (SourceHealth.shortSource(dur, exp)) {
            if (shortFlagged) return false
            shortFlagged = true
            val msg = flagIncomplete(dur)
            if (resumeMs > 0 && resumeMs >= dur - 10_000) {
                leaveBrokenSource(msg)
                return true
            }
            showRescueNote(if (nextSource?.hasMore() == true) "$msg Jarvis switches to the next one there." else msg)
            return false
        }
        // The saved spot is at the very end of the title itself: start over.
        if (resumeMs > 0 && rescue.resumePending && PlaybackRescue.resumeTarget(resumeMs, maxOf(dur, exp)) == 0L && pos >= dur - 15_000) {
            rescue.onPlaying(0L)
            player.seekTo(0L)
            player.playWhenReady = true
            showRescueNote("Your saved spot is at the end. Playing from the start.")
            return true
        }
        return false
    }
    LaunchedEffect(runtimeMs) {
        if (runtimeMs > 0 && player.playbackState == Player.STATE_READY) checkSource()
    }

    DisposableEffect(player) {
        var networkRetries = 0
        var retryJob: Job? = null
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
                report.update(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                stats?.onState(state)
                if (state == Player.STATE_READY) {
                    networkRetries = 0 // playing again: reset the retry budget
                    liveNote = null
                    if (rescueNote == "Reconnecting…") rescueNote = null
                    report.update(player)
                    checkSource()
                }
                if (state == Player.STATE_ENDED && !live) {
                    if (checkSource()) return
                    // Ended far short of the title: a cut-off file. Never "watched", never the next episode.
                    val pos = player.currentPosition
                    if (SourceHealth.incomplete(pos, expectedMs())) {
                        leaveBrokenSource(flagIncomplete(pos)) // flagging twice is harmless (one flag per file)
                        return
                    }
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
                // Video (not live): a source that fails at one spot (often the resume point from another device)
                // is reconnected, started over, skipped past or swapped for the next source. Never a dead stop.
                // Network (2xxx) and parsing (3xxx, e.g. a seek into a file the source can't serve right) errors.
                // A decoder error counts only right after the resume seek; otherwise the format just can't play here.
                if (!live && (http == null || http !in 400..499)) {
                    val retryable = e.errorCode in 2000..3999
                    if (retryable || rescue.resumePending) {
                        recover(retryable, e.errorCodeName)
                        return
                    }
                }
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
                com.mcd.tv.data.RecentErrors.note("Playback: ${title.ifBlank { "video" }}: ${RdErrors.stream(http) ?: e.errorCodeName}")
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
            rescueJob?.cancel()
            progress.save(player, force = true)
            report.clear()
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

    // Video (not live): buffering for PlaybackRescue.STALL_MS with no new data is a stall, e.g. a source
    // that hangs after a seek to another device's resume point. Without this it spins forever.
    LaunchedEffect(buffering, paused, live) {
        if (live || !buffering || paused) return@LaunchedEffect
        var loaded = player.bufferedPosition
        var still = 0L
        while (still < PlaybackRescue.STALL_MS) {
            delay(5_000)
            if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING || error != null) return@LaunchedEffect
            val b = player.bufferedPosition
            if (b > loaded + 1_000) {
                loaded = b
                still = 0L
            } else {
                still += 5_000
            }
        }
        recover(retryable = true, why = "stalled")
    }

    // Hide the recovery note after 8 seconds (a reconnect note goes when the picture is back).
    LaunchedEffect(rescueNoteTick) {
        if (rescueNote != null && rescueNote != "Reconnecting…") {
            delay(8_000)
            rescueNote = null
        }
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
            if (player.isPlaying) rescue.onPlaying(player.currentPosition)
            progress.save(player)
            report.update(player)
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

        if (error == null && !live) rescueNote?.let { note ->
            Text(
                text = note,
                color = McdColors.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 28.dp, start = 40.dp, end = 40.dp)
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

/** Keeps [NowPlaying] current for this player: title, play or pause, position. Relay decides when to send it. */
private class NowReporter(private val title: String, private val meta: PlayMeta?, private val live: Boolean) {
    fun update(player: Player) {
        val dur = player.duration
        NowPlaying.set(
            NowPlaying.State(
                media = if (live) "live" else meta?.type ?: "video",
                id = meta?.tmdbId ?: 0,
                name = meta?.name?.ifBlank { null } ?: title,
                season = meta?.season ?: 0,
                episode = meta?.episode ?: 0,
                playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (dur > 0) dur else 0L,
                ts = System.currentTimeMillis(),
            ),
        )
    }

    fun clear() = NowPlaying.clear()
}

/**
 * Writes the resume position, skipping writes when the position moved 5 s or less since the last one.
 * Positions below [keepBelow] are not written: after a resume that failed, the saved spot stays until passed.
 */
private class ProgressSaver(
    private val meta: PlayMeta?,
    private val keepBelow: () -> Long = { 0L },
    /** The length to save for this player's length (SourceHealth.progressDurationMs). */
    private val lengthFor: (Long) -> Long = { it },
) {
    private var lastSavedMs = -1L

    fun save(player: Player, force: Boolean = false) {
        if (meta == null) return
        val dur = player.duration
        if (dur <= 0) return // C.TIME_UNSET is negative too
        val pos = player.currentPosition
        if (pos < keepBelow()) return
        if (!force && lastSavedMs >= 0 && kotlin.math.abs(pos - lastSavedMs) <= 5_000) return
        if (force && pos == lastSavedMs) return
        lastSavedMs = pos
        Library.record(meta, pos, lengthFor(dur), urgent = force)
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
