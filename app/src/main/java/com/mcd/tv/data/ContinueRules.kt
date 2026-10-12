package com.mcd.tv.data

/**
 * Which history rows go in Continue Watching, and which episode Play starts. Pure logic; tests in ContinueRulesTest.
 *
 * History keeps one row per show (its latest episode). A show stays in the row when that episode is in
 * progress, when it was finished (the next episode is up), or when a later episode was only just started.
 */
object ContinueRules {
    /** Less than this and a movie or a first episode counts as not started. */
    const val MIN_POSITION_MS = 60_000L

    /** A show with nothing in progress leaves the row after this many days without watching. */
    const val SHOW_DAYS = 60

    /** Same line as HistoryEntry.finished. */
    fun finished(progress: Float): Boolean = progress > 0.92f

    fun keep(type: String, season: Int, episode: Int, positionMs: Long, progress: Float, at: Long, now: Long): Boolean {
        val done = finished(progress)
        if (!done && positionMs > MIN_POSITION_MS) return true
        if (type != "tv" || season <= 0 || episode <= 0) return false
        if (now - at > SHOW_DAYS * 86_400_000L) return false
        // Finished: the next episode is up. Just started: counts when an earlier episode was watched.
        return done || season > 1 || episode > 1
    }

    /** The episode Play starts for a show: the next one after a finished episode, else the same one. */
    fun playEpisode(season: Int, episode: Int, progress: Float): Pair<Int, Int> =
        if (finished(progress)) season to episode + 1 else season to episode
}
