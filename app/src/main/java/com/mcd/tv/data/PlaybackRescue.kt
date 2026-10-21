package com.mcd.tv.data

/**
 * What the player does when a video (not live TV) stops at one spot: an error, or buffering that never ends.
 * Pure logic, no Android, so it can be tested. PlayerScreen reports progress and failures; this decides.
 *
 * The order at one spot:
 *  1. Reconnect at the same position (twice for network-type failures, not for decoder errors).
 *  2. Another source is left: switch to it. It resumes at the same spot (the position belongs to the
 *     title, not to the source).
 *  3. The spot is the resume point (another device's or this TV's saved position), it never played and
 *     no other source is left: this source can't jump there. Play from the start and say so. The saved
 *     position is kept (see [keepSavedBelow]) so it isn't lost on the other devices.
 *  4. Otherwise skip [SKIP_MS] past the bad spot once and say so.
 *  5. Still failing: give up with a message that names the time.
 *
 * A failure more than [SPOT_MS] away from the last one is a new spot and starts at step 1 again.
 */
class PlaybackRescue(resumeMs: Long, private val hasNextSource: () -> Boolean) {
    enum class Action { RECONNECT, FROM_START, NEXT_SOURCE, SKIP_AHEAD, GIVE_UP }

    /** [seekToMs] < 0: stay where the player is. [note] is shown on screen (GIVE_UP: the error text). */
    data class Step(val action: Action, val seekToMs: Long = -1L, val note: String? = null)

    private val resumeMs = resumeMs.coerceAtLeast(0L)

    /** True when the last failure was at the resume point before it ever played (set by [onFailure]). */
    var failedAtResume = false
        private set

    /** True until the resume point has really played (or the viewer moved away from it). */
    var resumePending = this.resumeMs > 0
        private set

    /** While > 0, saves below this position are skipped: a resume that failed must not wipe the saved spot. */
    var keepSavedBelow = 0L
        private set

    private var spotMs = -1L
    private var failsHere = 0
    private var skippedHere = false

    /** Called while the video is playing (picture moving), with the current position. */
    fun onPlaying(positionMs: Long) {
        if (resumePending && kotlin.math.abs(positionMs - resumeMs) > PLAYED_MS) resumePending = false
        if (keepSavedBelow > 0 && positionMs > keepSavedBelow) keepSavedBelow = 0L
        // Played well past the last bad spot: it was fixed, so a later failure there starts over.
        if (spotMs >= 0 && positionMs > spotMs + SPOT_MS) {
            spotMs = -1L
            failsHere = 0
            skippedHere = false
        }
    }

    /**
     * The player failed or stalled at [positionMs]. [retryable]: a network or stall failure, where a
     * reconnect can help (decoder errors are not). Returns what to do next.
     */
    fun onFailure(positionMs: Long, retryable: Boolean): Step {
        val pos = positionMs.coerceAtLeast(0L)
        if (spotMs < 0 || kotlin.math.abs(pos - spotMs) > SPOT_MS) {
            spotMs = pos
            failsHere = 0
            skippedHere = false
        }
        failsHere++
        if (retryable && failsHere <= RECONNECTS) return Step(Action.RECONNECT)

        val at = SeekAccumulator.clock(pos)
        val atResume = resumePending && resumeMs > 0 && kotlin.math.abs(pos - resumeMs) <= SPOT_MS
        failedAtResume = atResume
        if (hasNextSource()) {
            return Step(
                Action.NEXT_SOURCE,
                note = if (atResume) "This source couldn't start at $at. Trying the next one at $at." else "This source stopped at $at. Trying the next one.",
            )
        }
        if (atResume) {
            resumePending = false
            keepSavedBelow = resumeMs
            spotMs = -1L
            failsHere = 0
            return Step(
                Action.FROM_START, 0L,
                "This source couldn't start at ${SeekAccumulator.clock(resumeMs)}. Playing from the start. " +
                    "Hold RIGHT to skip ahead, or pick another source.",
            )
        }
        if (!skippedHere) {
            skippedHere = true
            return Step(
                Action.SKIP_AHEAD, pos + SKIP_MS,
                "This source stopped at $at. Skipped ahead ${SKIP_MS / 1000} seconds.",
            )
        }
        return Step(Action.GIVE_UP, note = "This source stops at $at every time. Try another source.")
    }

    companion object {
        /** Reconnects at one spot before trying something else. */
        const val RECONNECTS = 2

        /** Failures this close together count as the same spot. */
        const val SPOT_MS = 15_000L

        /** How far from the resume point the picture must play before the resume counts as done. */
        const val PLAYED_MS = 5_000L

        /** How far past a bad spot to jump when no other source is left. */
        const val SKIP_MS = 30_000L

        /** Buffering this long with no new data (non-live) counts as a stall. */
        const val STALL_MS = 30_000L

        /**
         * Where to start, given a saved position and the title's expected length ([SourceHealth.expectedMs]:
         * the longest of the source's, the runtime and the saved length, so a cut-off source can't shorten it).
         * Only a spot at the very end of the title starts at 0. [expectedMs] <= 0: length not known yet.
         */
        fun resumeTarget(savedMs: Long, expectedMs: Long): Long {
            if (savedMs <= 0) return 0L
            if (expectedMs <= 0) return savedMs
            return if (savedMs >= expectedMs - END_MARGIN_MS) 0L else savedMs
        }

        private const val END_MARGIN_MS = 10_000L
    }
}
