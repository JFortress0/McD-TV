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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

// ======================= Live TV: XMLTV program guide =======================

/**
 * One programme. [start] / [end] are epoch milliseconds. [untimed]: an event channel that names its game but no
 * start time (shown as "Live event"; [start] is the top of the hour).
 */
data class Programme(val title: String, val start: Long, val end: Long, val desc: String, val untimed: Boolean = false)

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

    /** A failed or empty download is tried again after this, instead of being cached for [CACHE_MS]. */
    private const val RETRY_MS = 90_000L

    private val lock = Mutex()
    private var cacheKey = ""
    private var cacheAt = 0L
    private var failKey = ""
    private var failAt = 0L

    /** The most recently loaded guide (empty until a load finishes). */
    @Volatile var current: Map<String, List<Programme>> = emptyMap()
        private set

    /** The last good guide when it was for the same playlist, else nothing. */
    private fun lastGood(key: String): Map<String, List<Programme>> = if (key == cacheKey) current else emptyMap()

    /** Key a channel is filed under in the guide map. */
    fun keyOf(ch: Channel): String = ch.url

    suspend fun load(url: String, channels: List<Channel>): Map<String, List<Programme>> {
        if (url.isBlank() || channels.isEmpty()) return emptyMap()
        val key = "$url|${channels.size}"
        return try {
            lock.withLock {
                if (key == cacheKey && System.currentTimeMillis() - cacheAt < CACHE_MS) return@withLock current
                if (key == failKey && System.currentTimeMillis() - failAt < RETRY_MS) return@withLock lastGood(key)
                val guide = try {
                    withContext(Dispatchers.IO) { download(url, channels) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyMap() // OutOfMemoryError included: a guide is never worth a crash
                }
                if (guide.isEmpty()) {
                    // Failed, refused or empty: keep the last good guide and try again in [RETRY_MS].
                    failKey = key
                    failAt = System.currentTimeMillis()
                    return@withLock lastGood(key)
                }
                current = guide
                cacheKey = key
                cacheAt = System.currentTimeMillis()
                failKey = ""
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
        // Normalized names ([LiveFill.guideKey]): a copy without a guide id borrows the id of a copy of the same
        // network that has one ("ESPN 2 FHD" uses "ESPN2"'s guide; West feeds keep their own).
        val byKey = HashMap<String, MutableList<String>>()
        val donor = HashMap<String, String>()
        val keyOfCh = arrayOfNulls<String>(channels.size)
        for ((n, ch) in channels.withIndex()) {
            val gk = LiveFill.guideKey(ch.name)
            if (gk.isEmpty()) continue
            keyOfCh[n] = gk
            byKey.getOrPut(gk) { ArrayList(1) }.add(keyOf(ch))
            val id = ch.tvgId?.takeIf { it.isNotBlank() }?.lowercase()
            if (id != null && gk !in donor) donor[gk] = id
        }
        for ((n, ch) in channels.withIndex()) {
            if (!ch.tvgId.isNullOrBlank()) continue
            val id = donor[keyOfCh[n] ?: continue] ?: continue
            val k = keyOf(ch)
            byTvgId.getOrPut(id) { ArrayList(1) }.let { if (k !in it) it.add(k) }
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
                        "channel" -> readChannel(p, byTvgId, byName, byKey)?.let { (id, keys) -> idMap[id] = keys }
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
        byKey: Map<String, List<String>>,
    ): Pair<String, List<String>>? {
        val id = p.getAttributeValue(null, "id") ?: run { skip(p); return null }
        var keys: List<String>? = byTvgId[id.lowercase()]
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "display-name") {
                val name = readText(p).trim().lowercase()
                if (keys == null && name.isNotEmpty()) keys = byName[name] ?: byKey[LiveFill.guideKey(name)]
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

// ======================= Filling the guide where the playlist has none =======================
//
// Mirrors the web app's LV helpers (docs/app/index.html: gkey, homeRank, netOf, eventProg, gameProg), so the TV
// and the web app show the same rows. Pure functions; unit tests in LiveFillTest.

object LiveFill {
    // ---------------------------------------------------------------- guide keys

    private val BACKUP_TAG = Regex("[(\\[]\\s*(?:backup|bk|alt|alternate)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val WEST_TAG = Regex("[(\\[]\\s*w\\s*[)\\]]")
    private val EAST_TAG = Regex("[(\\[]\\s*e\\s*[)\\]]")
    private val PACIFIC = Regex("\\bpacific\\b")
    private val DROP = Regex("\\b(?:hd|fhd|uhd|sd|4k|east|eastern|feed|backup|hevc|raw|1080p|720p)\\b")
    private val NON_KEY = Regex("[^a-z0-9+!]+")
    private val TRAIL_US = Regex(" (?:us|usa)$")
    private val NUM_JOIN = Regex("\\b([a-z]+) (\\d{1,2})\\b")

    /**
     * Key a channel borrows a guide by: "US| ESPN 2 FHD" and "ESPN2 (backup)" -> "espn2", "A&E" -> "a and e".
     * East and plain feeds share a key; West ("HBO West", "HBO (Pacific)", "HBO (W)") stays apart, 3 hours off.
     */
    fun guideKey(name: String): String {
        var t = LiveOrganizer.cleanName(BACKUP_TAG.replace(name, " ")).lowercase(Locale.ROOT)
        t = t.replace("&", " and ")
        t = WEST_TAG.replace(t, " west ")
        t = EAST_TAG.replace(t, " ")
        t = PACIFIC.replace(t, "west")
        t = DROP.replace(t, " ")
        t = NON_KEY.replace(t, " ").trim()
        t = TRAIL_US.replace(t, "")
        return NUM_JOIN.replace(t, "$1$2")
    }

    // ---------------------------------------------------------------- Huntsville and networks

    /** Huntsville stations, in the order Local and Games list them: call sign to network. */
    val HOME_LOCALS: List<Pair<String, String>> = listOf("whnt" to "CBS", "waay" to "ABC", "waff" to "NBC", "wzdx" to "FOX", "whdf" to "CW")
    private val HOME_RE = Regex("(?:^|[^a-z0-9])(whnt|waay|waff|wzdx|whdf)(?:-?(?:tv|dt|cd|ld)?\\d{0,2})?(?![a-z0-9])")

    /** Position of a Huntsville station in [HOME_LOCALS], or -1. */
    fun homeRank(name: String): Int {
        val m = HOME_RE.find(name.lowercase(Locale.ROOT)) ?: return -1
        val sign = m.groupValues[1]
        return HOME_LOCALS.indexOfFirst { it.first == sign }
    }

    /** Network of a Huntsville station's call sign, or null. */
    fun homeNetwork(name: String): String? = homeRank(name).let { if (it >= 0) HOME_LOCALS[it].second else null }

    private val NET_EXCL = Regex("\\b(?:news|sports?|business|deportes|weather|kids|family|life|soul|movies|classic|plus|now|live|reality|drama|mystery|comedy|soccer|nation|extra|college|latino|ocho|espanol)\\b")
    private val SECN = Regex("^(?:sec network|secn|sec)$")
    private val ACCN = Regex("^(?:acc network|accn|acc)$")
    private val BTN = Regex("^(?:big ten network|big ten|big10 network|big10|btn)$")
    private val ESPN = Regex("^(?:espn|espn2|espnu)$")
    private val BROADCAST = Regex("(?:^| )(cbs|fox|abc|nbc|cw)\\d{0,2}(?= |$)")

    /** Network of a plain network or affiliate channel ("ESPN", "FOX 54 Huntsville", "SEC Network"), or "". */
    fun networkOf(name: String): String {
        homeNetwork(name)?.let { return it }
        val k = guideKey(name)
        return when {
            SECN.matches(k) -> "SECN"
            ACCN.matches(k) -> "ACCN"
            BTN.matches(k) -> "BTN"
            ESPN.matches(k) -> k.uppercase(Locale.ROOT)
            NET_EXCL.containsMatchIn(k) || k.contains('+') -> ""
            else -> BROADCAST.find(k)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
        }
    }

    /** ESPN broadcast name in the same codes ("SEC Network" -> "SECN", "The CW" -> "CW"). */
    fun castNetwork(b: String): String {
        val u = b.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }
        return when (u) {
            "SECNETWORK" -> "SECN"
            "ACCNETWORK" -> "ACCN"
            "BIGTENNETWORK", "BIGTEN" -> "BTN"
            "THECW" -> "CW"
            else -> u
        }
    }

    // ---------------------------------------------------------------- event channels

    private val FOOTBALL = Regex("\\b(?:nfl|ncaaf|cfb|football|redzone|xfl|ufl|cfl)\\b")
    private val LONG_EVENT = Regex("\\b(?:ufc|boxing|ppv|mma|pfl|wwe|aew|bellator|golf|pga)\\b")
    private val BASEBALL = Regex("\\b(?:mlb|baseball|milb)\\b")
    private val SHORT_GAME = Regex("\\b(?:nba|wnba|ncaab|ncaam|basketball|nhl|hockey|ahl)\\b")
    private val SOCCER = Regex("\\b(?:mls|epl|soccer|premier league|la ?liga|serie a|bundesliga|uefa|champions league|futbol|liga mx)\\b")

    /** Typical length in minutes for an event named [text] (lower case). */
    internal fun eventMinutes(text: String): Int = when {
        FOOTBALL.containsMatchIn(text) -> 210
        LONG_EVENT.containsMatchIn(text) -> 240
        BASEBALL.containsMatchIn(text) -> 180
        SHORT_GAME.containsMatchIn(text) -> 150
        SOCCER.containsMatchIn(text) -> 120
        else -> 180
    }

    private val ZONES = mapOf(
        "et" to "America/New_York", "est" to "America/New_York", "edt" to "America/New_York",
        "ct" to "America/Chicago", "cst" to "America/Chicago", "cdt" to "America/Chicago",
        "mt" to "America/Denver", "mst" to "America/Denver", "mdt" to "America/Denver",
        "pt" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
        "uk" to "Europe/London", "bst" to "Europe/London", "gmt" to "UTC", "utc" to "UTC",
    )
    private const val ZONE = "(et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|uk|gmt|bst|utc)"
    private val AMPM = Regex("(?:^|[^\\d:])(\\d{1,2})(?::([0-5]\\d))?\\s*([ap])\\.?\\s*m\\b\\.?\\s*$ZONE?\\b", RegexOption.IGNORE_CASE)
    private val CLK24 = Regex("(?:^|[^\\d:])([01]?\\d|2[0-3]):([0-5]\\d)\\s*$ZONE?\\b", RegexOption.IGNORE_CASE)
    private val DATES = Regex(
        "\\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day)?\\b\\.?,?|\\b\\d{1,2}[/.]\\d{1,2}(?:[/.]\\d{2,4})?\\b|" +
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_MD = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/\\d{2,4})?\\b")
    private val DATE_NAMED = Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE)
    private val EMPTY_BRACKETS = Regex("[(\\[]\\s*[)\\]]")
    private val SPACES = Regex("\\s+")
    private val JUNK = Regex("^[\\s|:;,.\\-–()\\[\\]@]+|[\\s|:;,.\\-–()\\[\\]@]+$")
    private val NO_EVENT = Regex("\\b(?:no (?:event|game|stream)s?|off ?air|offline|tba|tbd|coming soon|not started|starts soon)\\b", RegexOption.IGNORE_CASE)
    private val SEP = Regex("\\s(?:vs\\.?|v\\.?|versus|at)\\s|\\s?@\\s?", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^([^:|]{1,30}?)\\s*(?:[:|]|\\s[-–]\\s)\\s*(?=\\S)")
    private val NUMBERED = Regex("^([A-Za-z+]+(?: [A-Za-z+]+){0,2} ?\\d{1,3})\\s+(?=\\S.*(?:\\s(?:vs\\.?|v\\.?|versus|at)\\s|@))", RegexOption.IGNORE_CASE)
    private val VERSUS = Regex("^(.+?)(?:\\s(vs\\.?|v\\.?|versus|at)\\s|\\s?(@)\\s?)(.+)$", RegexOption.IGNORE_CASE)
    private val CHANNEL_NO = Regex("^[A-Za-z+ ]+ ?\\d{1,2}$")
    private val LETTER = Regex("[A-Za-z]")
    private val TWO_LETTERS = Regex("[A-Za-z]{2}")
    private const val MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec"

    /**
     * An event channel's own listing: "NCAAF 05: Alabama vs Tennessee 3:30PM ET" -> "Alabama vs Tennessee" from
     * 3:30 PM Eastern for 3.5 hours. The start is on the date in the name, else the nearest day with that time.
     * Finished events give null. A name with a game but no time gives an [Programme.untimed] row.
     * Needs a numbered or labelled prefix ("NFL 03:") or a time, so "Alien vs Predator" is not an event.
     */
    fun eventProgramme(name: String, group: String, now: Long): Programme? {
        var s = SPACES.replace(LiveOrganizer.cleanName(name), " ")
        var hh = -1
        var mm = 0
        var tz: String? = null
        var last = AMPM.findAll(s).lastOrNull()
        if (last != null) {
            hh = (last.groupValues[1].toInt() % 12) + if (last.groupValues[3].equals("p", true)) 12 else 0
            mm = last.groupValues[2].toIntOrNull() ?: 0
            tz = ZONES[last.groupValues[4].lowercase(Locale.ROOT)]
        } else {
            last = CLK24.findAll(s).lastOrNull()
            if (last != null) {
                hh = last.groupValues[1].toInt()
                mm = last.groupValues[2].toInt()
                tz = ZONES[last.groupValues[3].lowercase(Locale.ROOT)]
            }
        }
        if (last != null) {
            val lead = if (last.value.first().isDigit()) 0 else 1
            s = s.substring(0, last.range.first + lead) + " " + s.substring(last.range.last + 1)
        }
        // A date in the name ("10/11", "Oct 11") picks the day.
        var md: IntArray? = null
        val dm = DATE_MD.find(s)
        val dn = DATE_NAMED.find(s)
        if (dm != null && dm.groupValues[1].toInt() in 1..12 && dm.groupValues[2].toInt() in 1..31) {
            md = intArrayOf(dm.groupValues[1].toInt(), dm.groupValues[2].toInt())
        } else if (dn != null && dn.groupValues[2].toInt() in 1..31) {
            md = intArrayOf(MONTHS.indexOf(dn.groupValues[1].lowercase(Locale.ROOT)) / 3 + 1, dn.groupValues[2].toInt())
        }
        s = DATES.replace(s, " ")
        s = EMPTY_BRACKETS.replace(s, " ")
        s = JUNK.replace(SPACES.replace(s, " "), "")
        var prefix = ""
        val p = PREFIX.find(s)
        if (p != null && !SEP.containsMatchIn(" " + p.groupValues[1] + " ")) {
            prefix = p.groupValues[1].trim()
            s = s.substring(p.range.last + 1)
        } else {
            NUMBERED.find(s)?.let { q ->
                prefix = q.groupValues[1]
                s = s.substring(q.range.last + 1)
            }
        }
        s = JUNK.replace(s, "")
        if (s.isEmpty() || NO_EVENT.containsMatchIn(s) || (prefix.isEmpty() && last == null)) return null
        val v = VERSUS.find(s)
        val title = if (v != null) {
            val a = JUNK.replace(v.groupValues[1], "")
            val b = JUNK.replace(v.groupValues[4], "")
            if (!LETTER.containsMatchIn(a) || !LETTER.containsMatchIn(b)) return null
            val at = v.groupValues[3].isNotEmpty() || v.groupValues[2].equals("at", true)
            a + (if (at) " at " else " vs ") + b
        } else {
            if (prefix.isEmpty() || last == null || !TWO_LETTERS.containsMatchIn(s)) return null
            if (CHANNEL_NO.matches(prefix)) s else "$prefix: $s"
        }
        val dur = eventMinutes("$name $group".lowercase(Locale.ROOT)) * 60_000L
        if (hh < 0) {
            val top = now / 3_600_000L * 3_600_000L
            return Programme(title, top, top + dur, "", untimed = true)
        }
        val zone = tz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        var bestStart = 0L
        var bestGap = Long.MAX_VALUE
        for (k in -1..1) {
            val c = Calendar.getInstance(zone, Locale.US)
            c.timeInMillis = now
            if (md != null) {
                val y = c.get(Calendar.YEAR) + k
                c.clear()
                c.set(y, md[0] - 1, md[1], hh, mm, 0)
            } else {
                c.add(Calendar.DAY_OF_MONTH, k)
                c.set(Calendar.HOUR_OF_DAY, hh)
                c.set(Calendar.MINUTE, mm)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
            }
            val start = c.timeInMillis
            val end = start + dur
            val gap = if (now < start) start - now else if (now >= end) now - end + 1 else 0L
            if (gap < bestGap) { bestGap = gap; bestStart = start }
        }
        if (now >= bestStart + dur || bestStart - now > 18 * 3_600_000L) return null
        return Programme(title, bestStart, bestStart + dur, "")
    }

    // ---------------------------------------------------------------- the network's game

    /** The live or next game on [net] from [games] (schedules already loaded), as a programme. */
    fun gameProgramme(net: String, games: List<Game>, now: Long): Programme? {
        if (net.isEmpty()) return null
        var live: Game? = null
        var next: Game? = null
        for (g in games) {
            if (g.final || g.broadcasts.none { castNetwork(it) == net }) continue
            if (g.live) {
                if (live == null || g.startMs > live.startMs) live = g
            } else if (g.startMs > now - 30 * 60_000L && g.startMs - now < 12 * 3_600_000L && (next == null || g.startMs < next.startMs)) {
                next = g
            }
        }
        val g = live ?: next ?: return null
        val dur = when (g.league.sport) {
            Sport.FOOTBALL -> 210
            Sport.BASEBALL -> 180
            Sport.BASKETBALL, Sport.HOCKEY -> 150
            Sport.SOCCER -> 120
        } * 60_000L
        val sep = if (g.league.sport == Sport.SOCCER) " vs " else " at "
        val title = g.away.short.ifEmpty { g.away.name } + sep + g.home.short.ifEmpty { g.home.name }
        val end = maxOf(g.startMs + dur, if (live != null) now + 30 * 60_000L else 0L)
        return Programme(title, g.startMs, end, if (live != null && g.detail.isNotEmpty()) "Live now. ${g.detail}" else "")
    }

    /** Games in the schedules already loaded (no network). */
    fun loadedGames(): List<Game> = League.entries.flatMap { Games.cached(it)?.games.orEmpty() }

    /**
     * Now and next for a channel: the real guide first; with none, the event channel's own title, then the live
     * or next game on its network.
     */
    fun nowNext(ch: Channel, guide: Map<String, List<Programme>>, now: Long, games: () -> List<Game> = ::loadedGames): Pair<Programme?, Programme?> {
        val real = Epg.nowNext(Epg.keyOf(ch), guide, now)
        if (real.first != null || real.second != null) return real
        val fb = eventProgramme(ch.name, ch.group, now) ?: gameProgramme(networkOf(ch.name), games(), now) ?: return real
        return if (fb.untimed || fb.start <= now) fb to null else null to fb
    }

    /**
     * A channel's listings for grids and ranking (My Guide, For You): the real guide when it has anything not yet
     * over; else the same fallback as [nowNext] (event title, then the network's game) as a one-row list.
     * No network: [games] reads schedules already loaded.
     */
    fun programmes(ch: Channel, guide: Map<String, List<Programme>>, now: Long, games: () -> List<Game> = ::loadedGames): List<Programme> {
        val real = guide[Epg.keyOf(ch)].orEmpty()
        if (real.any { it.end > now }) return real
        val fb = eventProgramme(ch.name, ch.group, now) ?: gameProgramme(networkOf(ch.name), games(), now)
        return if (fb != null) listOf(fb) else real
    }
}
