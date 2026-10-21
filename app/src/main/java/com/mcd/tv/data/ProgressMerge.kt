package com.mcd.tv.data

/**
 * The conflict rules for one title's saved progress when copies from several devices meet (the cloud copy
 * from a phone, the web app's "progress" message, this TV's own save). Pure logic, no Android.
 *
 *  1. Latest wins: the record saved last holds the position, even when it is earlier in the title
 *     (rewinding is real). Two records within [SKEW_MS] are the same save seen twice: the local one stays.
 *  2. Never regress: an older record never replaces a newer one, whatever its position.
 *  3. The length never shrinks to a cut-off file's: when the winner's length is far short of a length
 *     already known for the same title or episode ([SourceHealth.shortSource]), the known one is kept,
 *     so a stop at a cut-off point never reads as "watched".
 *
 * Which source played is not part of this record: source health is kept apart ([BadSources]).
 * TV to TV sync (ProfileSync) has its own newest-wins order with a device tie-break; this is the same rule 1.
 */
object ProgressMerge {
    /** A phone clock a little off from the TV's: saves this close together count as the same save. */
    const val SKEW_MS = 1_000L

    /** One saved spot. [season] and [episode] are 0 for a movie. */
    data class Rec(val season: Int, val episode: Int, val positionMs: Long, val durationMs: Long, val at: Long) {
        fun sameItem(o: Rec) = season == o.season && episode == o.episode
    }

    /** What to keep when [remote] arrives and [local] is what this device has (null: nothing yet). */
    fun merge(local: Rec?, remote: Rec): Rec {
        if (local == null) return remote
        if (remote.at <= local.at + SKEW_MS) return keepLength(local, remote)
        return keepLength(remote, local)
    }

    /** True when [merge] changes what this device has. */
    fun changes(local: Rec?, remote: Rec): Boolean = merge(local, remote) != local

    /** [winner] with the longer length of the two when its own is a cut-off file's (same title or episode only). */
    private fun keepLength(winner: Rec, other: Rec): Rec =
        if (winner.sameItem(other) && SourceHealth.shortSource(winner.durationMs, other.durationMs)) winner.copy(durationMs = other.durationMs)
        else winner
}
