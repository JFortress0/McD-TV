package com.mcd.tv.data

import org.json.JSONObject

/**
 * What this TV is playing right now, for the web app ("what's on the living room TV?"). The player sets it;
 * HouseSync posts it on the house topic as "tv_status" on play, pause and stop only, at most once per 10 s, and
 * Relay puts it inside every "state" and "web_init" as "now_playing". Pure logic (no Android). Tests in NowPlayingTest.
 */
object NowPlaying {
    /** "tv_status" goes out at most this often. A change inside the gap is sent when the gap ends. */
    const val MIN_GAP_MS = 10_000L

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

    /** True when [next] differs from the last sent [last] in a way phones must hear: play, pause, stop or a new title. */
    fun changed(last: State?, next: State?): Boolean {
        if (last == null && next == null) return false
        if (last == null || next == null) return true
        return last.key != next.key
    }

    /** How long to wait before sending, given the last send at [lastAt] (0 = never): 0 when the gap has passed. */
    fun waitMs(lastAt: Long, now: Long): Long {
        if (lastAt <= 0L || now < lastAt) return 0L
        return (MIN_GAP_MS - (now - lastAt)).coerceAtLeast(0L)
    }
}
