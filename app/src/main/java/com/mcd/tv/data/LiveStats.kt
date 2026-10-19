package com.mcd.tv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * How live channels played on this TV (Settings > Addons and Live TV > Live TV health): time to the first picture, freezes and
 * how long they lasted, dropped frames, the stream's size and frame rate, the decoder and the speed.
 * Kept on this TV only (never synced), the last [KEEP] channels watched for more than 5 seconds.
 */
object LiveStats {
    private const val KEY = "live_stats"
    private const val KEEP = 12

    data class Session(
        val channel: String,
        val at: Long,
        val startupMs: Long,
        val stalls: Int,
        val stallMs: Long,
        val dropped: Int,
        val video: String,
        val decoder: String,
        val kbps: Int,
        val reconnects: Int,
        val watchedMs: Long,
    ) {
        /** "Started in 2.1 s. 3 freezes (14 s). 120 dropped frames. 1920x1080 60 fps avc. 8.2 Mbps. Watched 12 min." */
        fun summary(): String = buildString {
            append(if (startupMs >= 0) "Started in ${secs(startupMs)}." else "Never started.")
            append(if (stalls == 0) " No freezes." else " $stalls ${if (stalls == 1) "freeze" else "freezes"} (${secs(stallMs)}).")
            if (reconnects > 0) append(" $reconnects ${if (reconnects == 1) "reconnect" else "reconnects"}.")
            if (dropped > 0) append(" $dropped dropped frames.")
            if (video.isNotBlank()) append(" $video.")
            if (kbps > 0) append(" ${"%.1f".format(kbps / 1000.0)} Mbps.")
            append(" Watched ${mins(watchedMs)}.")
        }
    }

    private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)
    private fun mins(ms: Long) = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"

    /** Collects one channel's numbers while it plays; [finish] saves them. */
    class Recorder(private val channel: String) {
        private val opened = SystemClock.elapsedRealtime()
        private var startupMs = -1L
        private var stalls = 0
        private var stallMs = 0L
        private var stallStart = 0L
        var dropped = 0
        var video = ""
        var decoder = ""
        var kbps = 0
        var reconnects = 0
        private var saved = false

        /** Player state: 3 = ready, 2 = buffering (Player.STATE_*). */
        fun onState(state: Int) {
            val now = SystemClock.elapsedRealtime()
            if (state == 3) {
                if (startupMs < 0) startupMs = now - opened
                if (stallStart > 0) { stallMs += now - stallStart; stallStart = 0 }
            } else if (state == 2 && startupMs >= 0 && stallStart == 0L) {
                stalls++
                stallStart = now
            }
        }

        fun finish() {
            if (saved) return
            saved = true
            val now = SystemClock.elapsedRealtime()
            if (stallStart > 0) stallMs += now - stallStart
            val watched = now - opened
            if (watched < 5_000) return
            save(Session(channel, System.currentTimeMillis(), startupMs, stalls, stallMs, dropped, video, decoder, kbps, reconnects, watched))
        }
    }

    @Synchronized
    private fun save(s: Session) {
        val list = (listOf(s) + recent()).take(KEEP)
        val a = JSONArray()
        for (x in list) {
            a.put(
                JSONObject().put("c", x.channel).put("at", x.at).put("st", x.startupMs).put("n", x.stalls).put("sm", x.stallMs)
                    .put("d", x.dropped).put("v", x.video).put("dec", x.decoder).put("k", x.kbps).put("r", x.reconnects).put("w", x.watchedMs),
            )
        }
        runCatching { Prefs.putJson(KEY, a.toString()) }
    }

    /** Newest first. */
    fun recent(): List<Session> = runCatching {
        val a = JSONArray(Prefs.json(KEY).ifBlank { "[]" })
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Session(
                o.optString("c"), o.optLong("at"), o.optLong("st", -1), o.optInt("n"), o.optLong("sm"), o.optInt("d"),
                o.optString("v"), o.optString("dec"), o.optInt("k"), o.optInt("r"), o.optLong("w"),
            )
        }
    }.getOrDefault(emptyList())

    fun clear() = runCatching { Prefs.putJson(KEY, "[]") }

    /** "AFTKA, Android 11, 2.0 GB memory." */
    fun device(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem
        val gb = if (mem > 0) " %.1f GB memory.".format(mem / 1_073_741_824.0) else ""
        return "${Build.MODEL}, Android ${Build.VERSION.RELEASE}.$gb"
    }
}
