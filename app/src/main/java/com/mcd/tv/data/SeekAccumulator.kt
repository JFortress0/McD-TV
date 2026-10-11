package com.mcd.tv.data

/**
 * Adds up LEFT / RIGHT presses on the player into one seek target, so several presses (or a held key)
 * make one jump instead of many small ones. Pure logic, no Android: the view calls [press] on each key,
 * shows [label], and calls [commit] after a short pause.
 */
class SeekAccumulator(private val stepMs: Long = 10_000L, private val fastStepMs: Long = 30_000L) {
    private var startMs = 0L
    private var offsetMs = 0L

    /** True between the first press and [commit] / [cancel]. */
    var pending = false
        private set

    /** Step for one key event: 10 s per press, 30 s once a key has been held for a while (20 repeats). */
    fun stepFor(repeatCount: Int): Long = if (repeatCount >= 20) fastStepMs else stepMs

    /**
     * One LEFT ([forward] false) or RIGHT press. [positionMs] is the player's position (used on the first press
     * only), [durationMs] its length (0 or less when unknown). Returns the new target.
     */
    fun press(forward: Boolean, repeatCount: Int, positionMs: Long, durationMs: Long): Long {
        if (!pending) {
            pending = true
            startMs = positionMs.coerceAtLeast(0L)
            offsetMs = 0L
        }
        val step = stepFor(repeatCount)
        offsetMs += if (forward) step else -step
        // Keep the offset inside the video, so pressing back after hitting an end moves away from it at once.
        val target = target(durationMs)
        offsetMs = target - startMs
        return target
    }

    /** The target position, kept inside 0..[durationMs] (no upper limit when the length is unknown). */
    fun target(durationMs: Long): Long {
        val t = (startMs + offsetMs).coerceAtLeast(0L)
        return if (durationMs > 0) t.coerceAtMost(durationMs) else t
    }

    /** How far the target is from where the seek started, in ms (negative: back). */
    fun delta(): Long = offsetMs

    /** Ends the run and returns the target to seek to (null when nothing was pending). */
    fun commit(durationMs: Long): Long? {
        if (!pending) return null
        val t = target(durationMs)
        cancel()
        return t
    }

    fun cancel() {
        pending = false
        startMs = 0L
        offsetMs = 0L
    }

    /** Text shown while adding up, e.g. "12:34 / 45:00  (+0:30)". */
    fun label(durationMs: Long): String {
        val t = target(durationMs)
        val d = delta()
        val sign = if (d < 0) "-" else "+"
        val total = if (durationMs > 0) " / ${clock(durationMs)}" else ""
        return "${clock(t)}$total  ($sign${clock(kotlin.math.abs(d))})"
    }

    companion object {
        /** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0L)) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
            else String.format(java.util.Locale.US, "%d:%02d", m, sec)
        }
    }
}
