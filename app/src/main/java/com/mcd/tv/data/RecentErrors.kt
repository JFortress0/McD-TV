package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few problems Jarvis showed on this TV (Settings > About and help): playback errors, sources that
 * would not open, failed update checks. This TV only, never synced. The same text twice in a row is kept once,
 * with a count, so one bad channel cannot push everything else out.
 */
object RecentErrors {
    private const val KEY = "recent_errors"
    const val KEEP = 10

    data class Entry(val text: String, val at: Long, val count: Int = 1)

    /** [list] with [text] added at the front (newest first). Pure, for tests. */
    fun add(list: List<Entry>, text: String, at: Long): List<Entry> {
        val t = text.trim().replace(Regex("\\s+"), " ").take(200)
        if (t.isEmpty()) return list
        val first = list.firstOrNull()
        if (first != null && first.text == t) return listOf(first.copy(at = at, count = first.count + 1)) + list.drop(1)
        return (listOf(Entry(t, at)) + list).take(KEEP)
    }

    fun encode(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("t", it.text).put("at", it.at).put("n", it.count)) }
    }.toString()

    fun decode(s: String): List<Entry> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("t")
            if (t.isBlank()) null else Entry(t, o.optLong("at"), o.optInt("n", 1).coerceAtLeast(1))
        }.take(KEEP)
    }.getOrDefault(emptyList())

    fun recent(): List<Entry> = runCatching { decode(Prefs.json(KEY)) }.getOrDefault(emptyList())

    /** Notes a problem the user saw. Safe to call from any thread; never throws. */
    fun note(text: String) {
        runCatching { synchronized(this) { Prefs.putJson(KEY, encode(add(recent(), text, System.currentTimeMillis()))) } }
    }

    fun clear() {
        runCatching { Prefs.putJson(KEY, "[]") }
    }
}
