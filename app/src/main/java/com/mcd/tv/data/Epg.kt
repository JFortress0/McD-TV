package com.mcd.tv.data

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

// ======================= Live TV: XMLTV program guide =======================

/** One programme. [start] / [end] are epoch milliseconds. */
data class Programme(val title: String, val start: Long, val end: Long, val desc: String)

/**
 * Reads the playlist's XMLTV guide (plain or .gz), keeping only what Live TV shows:
 * programmes for channels in the playlist that are on now or start in the next 8 hours.
 * Guide map keys are [keyOf] (the channel's stream URL). Any failure gives an empty guide.
 */
object Epg {
    private const val WINDOW_MS = 8 * 3600_000L
    private const val CACHE_MS = 6 * 3600_000L
    private const val MAX_KEPT = 60_000
    private const val MAX_DESC = 160

    private val lock = Mutex()
    private var cacheKey = ""
    private var cacheAt = 0L

    /** The most recently loaded guide (empty until a load finishes). */
    @Volatile var current: Map<String, List<Programme>> = emptyMap()
        private set

    /** Key a channel is filed under in the guide map. */
    fun keyOf(ch: Channel): String = ch.url

    suspend fun load(url: String, channels: List<Channel>): Map<String, List<Programme>> {
        if (url.isBlank() || channels.isEmpty()) return emptyMap()
        val key = "$url|${channels.size}"
        return try {
            lock.withLock {
                if (key == cacheKey && System.currentTimeMillis() - cacheAt < CACHE_MS) return@withLock current
                val guide = withContext(Dispatchers.IO) { download(url, channels) }
                current = guide
                cacheKey = key
                cacheAt = System.currentTimeMillis()
                guide
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap() // OutOfMemoryError included: a guide is never worth a crash
        }
    }

    /** What's on now and next for a channel key, from [guide] (defaults to the last loaded guide). */
    fun nowNext(key: String, guide: Map<String, List<Programme>> = current, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = guide[key] ?: return null to null
        var cur: Programme? = null
        var next: Programme? = null
        for (p in list) { // sorted by start
            if (p.end <= now) continue
            if (p.start <= now && cur == null) { cur = p; continue }
            if (p.start > now) { next = p; break }
        }
        return cur to next
    }

    private suspend fun download(url: String, channels: List<Channel>): Map<String, List<Programme>> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            if (c.responseCode !in 200..299) return emptyMap()
            val raw = BufferedInputStream(c.inputStream, 64 * 1024)
            raw.mark(4)
            val b0 = raw.read()
            val b1 = raw.read()
            raw.reset()
            val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw
            return input.use { parse(it, channels) }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Programme>> {
        // Playlist lookups: tvg-id first, then tvg-name / channel name (all case-insensitive).
        val byTvgId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        for (ch in channels) {
            val k = keyOf(ch)
            ch.tvgId?.lowercase()?.let { byTvgId.getOrPut(it) { ArrayList(1) }.add(k) }
            ch.tvgName?.lowercase()?.let { byName.getOrPut(it) { ArrayList(1) }.add(k) }
            byName.getOrPut(ch.name.lowercase()) { ArrayList(1) }.let { if (k !in it) it.add(k) }
        }
        // XMLTV channel id -> channel keys (filled from <channel> elements).
        val idMap = HashMap<String, List<String>>()
        fun keysFor(xmlId: String): List<String>? = idMap[xmlId] ?: byTvgId[xmlId.lowercase()]

        val now = System.currentTimeMillis()
        val until = now + WINDOW_MS
        val out = HashMap<String, ArrayList<Programme>>()
        var kept = 0
        val ctx = coroutineContext

        val p = Xml.newPullParser()
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var event = p.eventType
            var counter = 0
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (++counter % 2000 == 0) ctx.ensureActive()
                    when (p.name) {
                        "channel" -> readChannel(p, byTvgId, byName)?.let { (id, keys) -> idMap[id] = keys }
                        "programme" -> {
                            val keys = keysFor(p.getAttributeValue(null, "channel") ?: "")
                            val start = parseTime(p.getAttributeValue(null, "start"))
                            val end = parseTime(p.getAttributeValue(null, "stop"))
                            if (keys == null || start == null || end == null || end <= now || start >= until) {
                                skip(p)
                            } else {
                                val (title, desc) = readProgramme(p)
                                val prog = Programme(title, start, end, desc)
                                for (k in keys) out.getOrPut(k) { ArrayList() }.add(prog)
                                if (++kept >= MAX_KEPT) break
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Malformed or cut-off guide: keep what was read so far.
        }
        // Sort each channel's list and drop duplicates (a channel matched by two guide ids).
        val result = HashMap<String, List<Programme>>(out.size)
        for ((k, list) in out) {
            list.sortBy { it.start }
            val clean = ArrayList<Programme>(list.size)
            var lastStart = Long.MIN_VALUE
            for (prog in list) if (prog.start != lastStart) { clean.add(prog); lastStart = prog.start }
            result[k] = clean
        }
        return result
    }

    /** Reads a <channel> element; returns its id and the playlist keys it matches, or null. */
    private fun readChannel(
        p: XmlPullParser,
        byTvgId: Map<String, List<String>>,
        byName: Map<String, List<String>>,
    ): Pair<String, List<String>>? {
        val id = p.getAttributeValue(null, "id") ?: run { skip(p); return null }
        var keys: List<String>? = byTvgId[id.lowercase()]
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "display-name") {
                val name = readText(p).trim().lowercase()
                if (keys == null && name.isNotEmpty()) keys = byName[name]
            }
            event = p.next()
        }
        return keys?.let { id to it }
    }

    private fun readProgramme(p: XmlPullParser): Pair<String, String> {
        var title = ""
        var desc = ""
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> { val t = readText(p).trim(); if (title.isEmpty()) title = t }
                    "desc" -> { val d = readText(p).trim(); if (desc.isEmpty()) desc = if (d.length > MAX_DESC) d.take(MAX_DESC - 1) + "…" else d }
                }
            }
            event = p.next()
        }
        return title.ifEmpty { "Untitled" } to desc
    }

    /** Text inside the current start tag; leaves the parser on its end tag (tolerates nested tags). */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) sb.append(p.text)
            event = p.next()
        }
        return sb.toString()
    }

    /** Skips the current element and everything inside it; leaves the parser on its end tag. */
    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var level = 1
        while (level > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** "20261008183000 +0000" (offset optional) -> epoch ms, or null. */
    internal fun parseTime(s: String?): Long? {
        if (s == null || s.length < 12) return null
        fun num(from: Int, len: Int): Int? {
            var v = 0
            for (i in from until from + len) {
                val c = s.getOrNull(i) ?: return null
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            return v
        }
        val y = num(0, 4) ?: return null
        val mo = num(4, 2) ?: return null
        val d = num(6, 2) ?: return null
        val h = num(8, 2) ?: return null
        val mi = num(10, 2) ?: return null
        val sec = if (s.length >= 14) num(12, 2) ?: 0 else 0
        if (mo !in 1..12 || d !in 1..31) return null
        var offsetMin = 0
        val tz = s.substring(minOf(14, s.length)).trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val oh = tz.substring(1, 3).toIntOrNull()
            val om = tz.substring(3, 5).toIntOrNull()
            if (oh != null && om != null) offsetMin = (oh * 60 + om) * (if (tz[0] == '-') -1 else 1)
        }
        val days = daysFromCivil(y, mo, d)
        val utcSec = days * 86_400L + h * 3600L + mi * 60L + sec - offsetMin * 60L
        return utcSec * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm; no java.time on API 24). */
    private fun daysFromCivil(year: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
