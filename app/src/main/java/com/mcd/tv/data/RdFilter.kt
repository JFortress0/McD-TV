package com.mcd.tv.data

/**
 * Real-Debrid refuses some release names with "infringing_file" (error 35). The rule changes often.
 * As of October 14, 2026 it refuses names that contain one of [TAGS], in that exact case
 * ("web-dl" and "WEB.h264" pass). Sources that match sort below the rest, and sources RD refused
 * on this TV in the last [KEEP_MS] sort last and are skipped by Play best source.
 * Pure logic; tests in RdFilterTest.
 */
object RdFilter {
    val TAGS = listOf("WEB-DL", "WEB.x264", "WEB.H264", "HDTV.x264", "HDTV.XviD")
    const val KEEP_MS = 6L * 60 * 60 * 1000
    private val refused = HashMap<String, Long>()

    /** Goes through Real-Debrid: a torrent hash, or an addon link marked RD. */
    fun viaRd(s: StreamSource): Boolean =
        !s.infoHash.isNullOrBlank() || s.name.contains("RD") || (s.url?.contains("real-debrid", ignoreCase = true) == true)

    /** The release name has a tag RD refuses right now. */
    fun nameBlocked(text: String): Boolean = TAGS.any { it in text }

    /** RD is likely to refuse [s]: a refused tag in its name, or RD refused it on this TV recently. */
    fun likelyBlocked(s: StreamSource, now: Long = System.currentTimeMillis()): Boolean =
        viaRd(s) && (nameBlocked(s.name + "\n" + s.title) || wasRefused(s, now))

    fun key(s: StreamSource): String = when {
        !s.infoHash.isNullOrBlank() -> "h:" + s.infoHash.lowercase() + ":" + (s.fileIdx ?: -1)
        !s.url.isNullOrBlank() -> "u:" + s.url
        else -> "n:" + s.addon + "|" + s.name + "|" + s.title
    }

    @Synchronized
    fun markRefused(s: StreamSource, now: Long = System.currentTimeMillis()) {
        refused[key(s)] = now
        refused.entries.removeAll { now - it.value > KEEP_MS }
    }

    @Synchronized
    fun wasRefused(s: StreamSource, now: Long = System.currentTimeMillis()): Boolean =
        refused[key(s)]?.let { now - it <= KEEP_MS } == true

    @Synchronized
    fun clear() = refused.clear()
}
