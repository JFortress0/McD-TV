package com.mcd.tv.data

import org.json.JSONObject

/**
 * What this TV is playing right now, for the web app ("what's on the living room TV?"). The player sets it;
 * Relay sends it on the TV's own topic as {"type":"now","now":{...}|null,"ts"} and inside every "state".
 * Pure logic (no Android). Tests in NowPlayingTest.
 */
object NowPlaying {
    /** Position updates go out at most this often while nothing else changes. */
    const val POSITION_MS = 60_000L

    /** [media]: "movie", "tv", "live" or "video". [id]: TMDB id, 0 when unknown. */
    data class State(
        val media: String,
        val id: Int,
        val name: String,
        val season: Int = 0,
        val episode: Int = 0,
        val playing: Boolean = true,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val ts: Long = 0L,
    ) {
        /** What changed besides the position: used to decide whether a message must go out now. */
        val key: String get() = "$media:$id:$name:$season:$episode:$playing"
    }

    @Volatile var current: State? = null
        private set

    private var listener: ((State?) -> Unit)? = null

    /** Relay registers here to hear about changes. */
    fun onChange(l: ((State?) -> Unit)?) { listener = l }

    fun set(s: State?) {
        current = s
        listener?.invoke(s)
    }

    fun clear() = set(null)

    fun json(s: State?): JSONObject? = s?.let {
        JSONObject()
            .put("media", it.media)
            .put("id", it.id)
            .put("name", it.name.take(120))
            .put("season", it.season)
            .put("episode", it.episode)
            .put("playing", it.playing)
            .put("position_ms", it.positionMs)
            .put("duration_ms", it.durationMs)
            .put("ts", it.ts)
    }

    /**
     * Should a message go out for [next] after [last] went out at [lastAt]? Yes when playback started, stopped,
     * paused or changed title; else only when [POSITION_MS] passed (a position update).
     */
    fun sendDue(last: State?, lastAt: Long, next: State?, now: Long): Boolean {
        if (last == null && next == null) return false
        if (last == null || next == null) return true
        if (last.key != next.key) return true
        return now - lastAt >= POSITION_MS || now < lastAt
    }
}
