package com.mcd.tv.data

import java.util.Locale

/**
 * Settings > Playback > Quality (pref `quality_mode`, this TV only, never synced).
 * "auto": step down from 4K to 1080p when playback struggles. "4k": never step down. "1080": always cap at 1080p.
 */
object QualityMode {
    const val AUTO = "auto"
    const val PREFER_4K = "4k"
    const val MAX_1080 = "1080"
    val ALL = listOf(AUTO, PREFER_4K, MAX_1080)

    fun clean(v: String?): String = if (v in ALL) v!! else AUTO

    fun label(v: String): String = when (clean(v)) {
        PREFER_4K -> "Prefer 4K"
        MAX_1080 -> "Max 1080p"
        else -> "Auto"
    }
}

/**
 * Notices when playback struggles, so a 4K stream can step down to 1080p. Pure logic: the player feeds it
 * events with a monotonic clock (ms). Same thresholds as the web app.
 *  - A stall = buffering after playback had started, not right after a seek.
 *  - Struggling: 3 stalls start within 60 s, or 12 s stalled within the last 60 s, or one stall lasts 8 s,
 *    or the bandwidth estimate stays below the video bitrate on 4 samples at least 5 s apart.
 *  - The first 10 s after a (re)start are ignored. Fires at most once.
 */
class StallWatch(startMs: Long) {
    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_STALLS = 3
        const val MAX_STALLED_MS = 12_000L
        const val LONG_STALL_MS = 8_000L
        const val LOW_SAMPLES = 4
        const val SAMPLE_GAP_MS = 5_000L
        const val STARTUP_MS = 10_000L
    }

    private var startAt = startMs
    private var started = false
    private var seekPending = false
    /** Start of the stall in progress, or -1. */
    private var stallStart = -1L
    /** Stalls as [start, end]; end = -1 while it lasts. */
    private val stalls = ArrayList<LongArray>()
    private var lowCount = 0
    private var lastSampleAt = Long.MIN_VALUE

    var fired = false
        private set

    /** Why it fired ("stalls", "stalled time", "long stall", "bandwidth"), for logs. */
    var reason = ""
        private set

    /** The player started again (retry, reconnect): ignore the next [STARTUP_MS]. Past stalls still count. */
    fun restart(now: Long) {
        endStall(now)
        startAt = now
        started = false
        seekPending = false
        lowCount = 0
        lastSampleAt = Long.MIN_VALUE
    }

    /** The viewer seeked: the buffering that follows is not a stall. */
    fun onSeek() {
        seekPending = true
    }

    /** Playing (STATE_READY). */
    fun onReady(now: Long) {
        started = true
        seekPending = false
        endStall(now)
    }

    /** Buffering (STATE_BUFFERING) while the player wants to play. */
    fun onBuffering(now: Long) {
        if (!started || seekPending || now - startAt < STARTUP_MS || stallStart >= 0) return
        stallStart = now
        stalls.add(longArrayOf(now, -1L))
    }

    /** Stopped, ended or failed: a stall in progress ends here. */
    fun onStop(now: Long) = endStall(now)

    /** A bandwidth estimate (bits per second) and the bitrate of the video playing (<= 0 = unknown). */
    fun onBandwidth(now: Long, estimateBps: Long, videoBitrate: Int) {
        if (now - startAt < STARTUP_MS || estimateBps <= 0 || videoBitrate <= 0) return
        if (lastSampleAt != Long.MIN_VALUE && now - lastSampleAt < SAMPLE_GAP_MS) return
        lastSampleAt = now
        lowCount = if (estimateBps < videoBitrate) lowCount + 1 else 0
    }

    /** True once, when playback is struggling. Call after events and about once a second while buffering. */
    fun check(now: Long): Boolean {
        if (fired) return false
        val from = now - WINDOW_MS
        stalls.removeAll { it[1] in 0 until from }
        val recent = stalls.count { it[0] >= from }
        var stalledMs = 0L
        for (s in stalls) {
            val end = if (s[1] < 0) now else minOf(s[1], now)
            stalledMs += (end - maxOf(s[0], from)).coerceAtLeast(0L)
        }
        reason = when {
            recent >= MAX_STALLS -> "stalls"
            stallStart >= 0 && now - stallStart >= LONG_STALL_MS -> "long stall"
            stalledMs >= MAX_STALLED_MS -> "stalled time"
            lowCount >= LOW_SAMPLES -> "bandwidth"
            else -> ""
        }
        if (reason.isEmpty()) return false
        fired = true
        return true
    }

    private fun endStall(now: Long) {
        if (stallStart < 0) return
        stalls.lastOrNull()?.let { if (it[1] < 0) it[1] = now }
        stallStart = -1L
    }
}

/** Pure picks for the step down from 4K: is it 4K, can the player cap itself, which channel or source to use. */
object QualityFallback {
    const val NOTICE = "Switched to 1080p for smoother playback"
    const val UHD_MIN_HEIGHT = 1600

    private val UHD_NAME = Regex("(?<![a-z0-9])(?:4k|uhd|2160p?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val FHD_NAME = Regex("(?<![a-z0-9])(?:fhd|1080[pi]?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HD_NAME = Regex("(?<![a-z0-9])hd(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HD720_NAME = Regex("(?<![a-z0-9])720p?(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val SD_NAME = Regex("(?<![a-z0-9])(?:sd|480p|576p|360p)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val LETTERS = Regex("[a-z]+")

    /** "ESPN 4K", "Sky UHD", "NBC 2160p". */
    fun is4kName(name: String): Boolean = UHD_NAME.containsMatchIn(name)

    /** 4K by the picture height (video format or size, 0 = unknown) or by the channel or source name. */
    fun is4k(height: Int, name: String): Boolean = height >= UHD_MIN_HEIGHT || is4kName(name)

    /** A multi-variant stream with a track at 1080p or lower: cap the track selector instead of switching. */
    fun canCapTo1080(supportedHeights: List<Int>): Boolean =
        supportedHeights.size >= 2 && supportedHeights.any { it in 1..1080 }

    /** How good a step-down sibling is by its name: 4 = 1080p / FHD, 3 = HD, 2 = no label, 1 = 720p, -1 = never (4K, SD). */
    fun siblingTier(name: String): Int = when {
        UHD_NAME.containsMatchIn(name) -> -1
        FHD_NAME.containsMatchIn(name) -> 4
        HD720_NAME.containsMatchIn(name) -> 1
        HD_NAME.containsMatchIn(name) -> 3
        SD_NAME.containsMatchIn(name) -> -1
        else -> 2
    }

    /**
     * A cheap filter before the guide key: a letter run of [key] every sibling's lowercase name must contain.
     * Skips "and" and "west", which the guide key can add ("A&E", "(W)", "Pacific"). Null = no filter.
     */
    internal fun keyProbe(key: String): String? =
        LETTERS.findAll(key).map { it.value }.filter { it != "and" && it != "west" }.maxByOrNull { it.length }

    /**
     * The 1080p version of [current] in [channels]: same guide key (quality words removed; West stays apart
     * from East), a different stream. Prefers 1080p / FHD, then HD, then no label, then 720p. Never 4K or SD.
     * Ties keep playlist order, so a backup feed only wins when nothing else matches.
     */
    fun pickSibling(current: Channel, channels: List<Channel>): Channel? {
        val key = LiveFill.guideKey(current.name)
        if (key.isBlank()) return null
        val probe = keyProbe(key)
        var best: Channel? = null
        var bestScore = Int.MIN_VALUE
        for (ch in channels) {
            if (ch.url == current.url) continue
            if (probe != null && !ch.name.lowercase(Locale.ROOT).contains(probe)) continue
            val tier = siblingTier(ch.name)
            if (tier < 0) continue
            if (LiveFill.guideKey(ch.name) != key) continue
            val backup = ch.name.contains("backup", ignoreCase = true)
            val score = tier * 2 + if (backup) 0 else 1
            if (score > bestScore) {
                best = ch
                bestScore = score
            }
        }
        return best
    }

    /**
     * Index of a 1080p source for the same title in a ranked source list, or null. Looks after [current] first
     * (sources above it were usually tried already), then above it. Skips the playing file.
     */
    fun pick1080Source(list: List<StreamSource>, current: Int): Int? {
        val cur = list.getOrNull(current)
        fun same(s: StreamSource): Boolean = cur != null && (
            (!s.url.isNullOrBlank() && s.url == cur.url) ||
                (!s.infoHash.isNullOrBlank() && s.infoHash.equals(cur.infoHash, ignoreCase = true) && s.fileIdx == cur.fileIdx)
            )
        val order = ((current + 1) until list.size) + (0 until current.coerceIn(0, list.size))
        return order.firstOrNull { i ->
            val s = list[i]
            !same(s) && StreamInfo.parse(s).let { it.resolution == "1080p" && !it.cam }
        }
    }
}
