package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Sources that stop far short of the title's length (a cut-off file, or a copy that dies at one spot).
 * Pure logic, no Android. The web app has the same rules (docs/app/index.html, SH); keep them in step.
 *
 * Where you are in a title and which source plays it are separate: the position belongs to the title
 * (Library, ResumeCloud), the flag belongs to one file of that title. Any device resumes at the position
 * with any source, and puts flagged files last so they aren't picked again.
 */
object SourceHealth {
    /** Stopped before this share of the expected length: the source is incomplete. */
    const val SHORT_RATIO = 0.7

    /** ...and at least this much short (so a short episode with a loose runtime is not flagged). */
    const val SHORT_MS = 5 * 60_000L

    /** Flags kept per title, newest first. */
    const val MAX_FLAGS = 8

    /** Flags older than this are dropped. */
    const val KEEP_MS = 90 * 86_400_000L

    private val HASH40 = Regex("[a-f0-9]{40}")

    /**
     * The same key on every device for one file. A torrent hash (field or in the link) when there is one;
     * otherwise a hash of the link, so a link that holds a login or token is never stored as text.
     */
    fun key(s: StreamSource): String = keyOf(s.infoHash, s.url, s.addon, s.name, s.title)

    fun keyOf(infoHash: String?, url: String?, addon: String, name: String, title: String): String {
        val h = infoHash?.lowercase()?.takeIf { HASH40.matches(it) } ?: url?.let { hashInLink(it) }
        return when {
            h != null -> "h:$h"
            !url.isNullOrBlank() -> "u:" + sha256(url).take(16)
            else -> "n:" + sha256("$addon|$name|$title").take(16)
        }
    }

    /** A 40-character torrent hash in the path of [url] (e.g. an addon's Real-Debrid link), lower case. */
    fun hashInLink(url: String): String? {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path.split('/').firstOrNull { HASH40.matches(it.lowercase()) }?.lowercase()
    }

    /** Title key for flags: "movie:603", "tv:1399:1:2" (one episode). */
    fun titleKey(type: String, tmdbId: Int, season: Int = 0, episode: Int = 0): String =
        if (type == "tv" && season > 0) "tv:$tmdbId:$season:$episode" else "$type:$tmdbId"

    /** The length to expect: the longest of this source's length, the title's runtime and the length another device saved. */
    fun expectedMs(sourceDurMs: Long, runtimeMs: Long, savedDurMs: Long = 0L): Long =
        maxOf(sourceDurMs, runtimeMs, savedDurMs, 0L)

    /** True when playback stopped for good at [stoppedAtMs], far short of [expectedMs]. Unknown length: false. */
    fun incomplete(stoppedAtMs: Long, expectedMs: Long): Boolean {
        if (expectedMs <= 0) return false
        val at = stoppedAtMs.coerceAtLeast(0L)
        return at < expectedMs * SHORT_RATIO && expectedMs - at >= SHORT_MS
    }

    /** True when this source's own length is far short of the title's (a cut-off file). */
    fun shortSource(sourceDurMs: Long, expectedMs: Long): Boolean = sourceDurMs > 0 && incomplete(sourceDurMs, expectedMs)

    /**
     * The length saved with a position. A cut-off source reports its own short length, which would make
     * the stop look like the end of the title ("watched") on every device. Then the title's length is saved.
     */
    fun progressDurationMs(sourceDurMs: Long, runtimeMs: Long, savedDurMs: Long = 0L): Long {
        val expected = expectedMs(0L, runtimeMs, savedDurMs)
        return if (sourceDurMs > 0 && !shortSource(sourceDurMs, expected)) sourceDurMs else expected.takeIf { it > 0 } ?: sourceDurMs
    }

    /**
     * One flagged file: [key] (see [key]), where it stopped, when it was last reported, and its score [n]:
     * how many times a device in the house saw it stop short. The score is shared, so a file reported on
     * the web goes last on every TV too, and a file reported twice goes behind one reported once.
     */
    data class Flag(val key: String, val stoppedMs: Long, val at: Long, val n: Int = 1)

    /**
     * Both lists together, one flag per file: the newest report's spot and time, the higher score (a
     * score never drops in a merge, so reading and writing back can't lose a report). Old flags are
     * dropped; the newest [MAX_FLAGS] stay.
     */
    fun merge(a: List<Flag>, b: List<Flag>, now: Long): List<Flag> =
        (a + b).filter { now - it.at < KEEP_MS && it.key.isNotBlank() }
            .groupBy { it.key }.values.map { g -> g.maxBy { it.at }.copy(n = g.maxOf { it.n }.coerceAtLeast(1)) }
            .sortedByDescending { it.at }
            .take(MAX_FLAGS)

    /** A new report of [key] on this device: one more than the score the house already has. */
    fun report(existing: List<Flag>, key: String, stoppedMs: Long, now: Long): List<Flag> {
        val n = (existing.firstOrNull { it.key == key }?.n ?: 0) + 1
        return merge(listOf(Flag(key, stoppedMs, now, n)), existing, now)
    }

    /** [list] with flagged files moved to the end, in their order. */
    fun <T> demote(list: List<T>, flagged: Set<String>, keyOf: (T) -> String): List<T> =
        demote(list, flagged.associateWith { 1 }, keyOf)

    /** [list] with flagged files moved to the end, lowest score first, otherwise in their order. */
    fun <T> demote(list: List<T>, scores: Map<String, Int>, keyOf: (T) -> String): List<T> {
        if (scores.isEmpty()) return list
        val (bad, good) = list.partition { keyOf(it) in scores }
        return good + bad.sortedBy { scores[keyOf(it)] ?: 0 }
    }

    /** {"f":[{"k","s","at","n"}],"at"}: the same JSON the web app writes ("n" missing reads as 1). */
    fun encode(flags: List<Flag>, now: Long): String = JSONObject()
        .put("f", JSONArray().apply { flags.forEach { put(JSONObject().put("k", it.key).put("s", it.stoppedMs).put("at", it.at).put("n", it.n)) } })
        .put("at", now)
        .toString()

    fun decode(json: String?): List<Flag> = runCatching {
        val a = JSONObject(json ?: return emptyList()).optJSONArray("f") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val k = o.optString("k")
            if (k.isBlank() || k.length > 80) null else Flag(k, o.optLong("s"), o.optLong("at"), o.optInt("n", 1).coerceAtLeast(1))
        }
    }.getOrDefault(emptyList())

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
