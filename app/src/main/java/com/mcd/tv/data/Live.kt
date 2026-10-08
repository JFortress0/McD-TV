package com.mcd.tv.data

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ======================= Live TV: M3U playlists you supply =======================

/** tvgId / tvgName come from the playlist's tvg-id and tvg-name attributes and link the channel to the program guide. */
data class Channel(
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
)

object M3u {
    private val attr = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")

    /** Parses a standard #EXTM3U playlist. */
    fun parse(text: String): List<Channel> = parseLines(text.lineSequence())

    /**
     * Parses line by line, so very large playlists (100k+ entries) never sit in memory as one string.
     * Movie and series entries (Xtream-style /movie/ and /series/ links) are skipped: Live TV shows channels only.
     */
    fun parseLines(lines: Sequence<String>, max: Int = 25_000, onGuideUrl: ((String) -> Unit)? = null): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingName: String? = null
        var logo: String? = null
        var group = "Other"
        var tvgId: String? = null
        var tvgName: String? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    // Header can name the XMLTV guide: url-tvg="a.xml.gz,b.xml" (or x-tvg-url). Use the first one.
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val guide = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"] ?: "")
                        .split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    if (guide != null) onGuideUrl?.invoke(guide)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null } ?: "Other"
                    tvgId = attrs["tvg-id"]?.trim()?.ifBlank { null }
                    tvgName = attrs["tvg-name"]?.trim()?.ifBlank { null }
                    pendingName = line.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                }
                line.isNotEmpty() && !line.startsWith("#") && pendingName != null -> {
                    val vod = line.contains("/movie/") || line.contains("/series/")
                    if (!vod) out += Channel(pendingName!!, logo, group, line, tvgId, tvgName)
                    pendingName = null
                    if (out.size >= max) break
                }
            }
        }
        return out
    }

    /** XMLTV guide link from the last loaded playlist's #EXTM3U header ("" when it has none). */
    @Volatile var guideUrl: String = ""
        private set

    private var cacheUrl = ""
    private var cacheAt = 0L
    private var cache: List<Channel> = emptyList()
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Downloads and parses off the main thread; shared by Live TV and Sports, refreshed every 6 hours. */
    /** User agents tried for playlist servers, most widely accepted first. */
    private val USER_AGENTS = listOf(
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "VLC/3.0.20 LibVLC/3.0.20",
        "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    )

    /** The user agent the playlist server last accepted; Live TV streams are requested with it too. */
    @Volatile var userAgent: String = USER_AGENTS.first()
        private set

    suspend fun load(force: Boolean = false): List<Channel> {
        val url = Prefs.m3uUrl
        if (url.isBlank()) return emptyList()
        return lock.withLock {
            val fresh = url == cacheUrl && System.currentTimeMillis() - cacheAt < 6 * 3600_000L
            if (fresh && !force && cache.isNotEmpty()) return@withLock cache
            var guide = ""
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Some playlist servers only answer the user agents of common IPTV players,
                // so try a few in turn and remember the one that works (the live player uses it too).
                var lastCode = 0
                var result: List<Channel>? = null
                for (ua in (listOf(userAgent) + USER_AGENTS).distinct()) {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        c.connectTimeout = 20_000
                        c.readTimeout = 90_000
                        c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", ua)
                        c.setRequestProperty("Accept", "*/*")
                        val code = c.responseCode
                        if (code !in 200..299) { lastCode = code; continue }
                        result = c.inputStream.bufferedReader().useLines { parseLines(it) { g -> guide = g } }
                        userAgent = ua
                        break
                    } finally { c.disconnect() }
                }
                result ?: throw Exception(
                    "The playlist server refused the link (code $lastCode). Check that the link is the full M3U link from your provider, " +
                        "that the account is active, and that it is not playing on another screen. Some providers also need you to ask them to allow a new device."
                )
            }
            if (list.isEmpty()) throw Exception("The playlist loaded but had no live channels in it.")
            cache = list; cacheUrl = url; cacheAt = System.currentTimeMillis(); guideUrl = guide
            list
        }
    }

    /** Channels whose name or group mentions this game's teams, league or network. */
    fun matchesFor(game: Game, all: List<Channel>): List<Channel> {
        val words = (listOf(game.home.name, game.away.name, game.home.short, game.away.short, game.league.label) + game.broadcasts)
            .flatMap { it.split(" ") }.map { it.lowercase() }.filter { it.length >= 3 }.toSet()
        return all.filter { ch -> val n = (ch.name + " " + ch.group).lowercase(); words.any { n.contains(it) } }
    }
}

// ======================= Sports: live scores + schedule =======================

enum class League(val label: String, val path: String) {
    NFL("NFL", "football/nfl"),
    NCAAF("College Football", "football/college-football"),
    NBA("NBA", "basketball/nba"),
    MLB("MLB", "baseball/mlb"),
    NHL("NHL", "hockey/nhl"),
    NCAAM("College Hoops", "basketball/mens-college-basketball"),
    EPL("Premier League", "soccer/eng.1"),
}

data class TeamLine(val name: String, val short: String, val logo: String?, val score: String, val record: String, val color: String?)

data class Game(
    val id: String,
    val league: League,
    val state: String, // "pre", "in", "post"
    val detail: String, // "Q3 4:12", "Final", "Sun 3:25 PM"
    val startMs: Long,
    val home: TeamLine,
    val away: TeamLine,
    val broadcasts: List<String>,
) {
    val live: Boolean get() = state == "in"
}

/** Reads the public ESPN scoreboard feed (scores and start times only; no video). */
object Scores {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    suspend fun scoreboard(league: League): List<Game> {
        val o = JSONObject(Http.get("https://site.api.espn.com/apis/site/v2/sports/${league.path}/scoreboard"))
        val events = o.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            runCatching {
                val e = events.getJSONObject(i)
                val comp = e.getJSONArray("competitions").getJSONObject(0)
                val teams = comp.getJSONArray("competitors")
                fun line(homeAway: String): TeamLine {
                    val c = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it.optString("homeAway") == homeAway }
                    val t = c.getJSONObject("team")
                    val rec = c.optJSONArray("records")?.optJSONObject(0)?.s("summary") ?: ""
                    return TeamLine(
                        name = t.s("displayName") ?: "",
                        short = t.s("abbreviation") ?: "",
                        logo = t.s("logo"),
                        score = c.s("score") ?: "",
                        record = rec,
                        color = t.s("color"),
                    )
                }
                val st = e.getJSONObject("status").getJSONObject("type")
                val casts = comp.optJSONArray("broadcasts")?.let { b ->
                    (0 until b.length()).flatMap { k ->
                        val names = b.getJSONObject(k).optJSONArray("names")
                        if (names == null) emptyList() else (0 until names.length()).map { names.getString(it) }
                    }
                } ?: emptyList()
                Game(
                    id = e.optString("id"),
                    league = league,
                    state = st.optString("state"),
                    detail = st.s("shortDetail") ?: "",
                    startMs = runCatching { iso.parse(e.optString("date"))?.time ?: 0L }.getOrDefault(0L),
                    home = line("home"),
                    away = line("away"),
                    broadcasts = casts,
                )
            }.getOrNull()
        }.sortedWith(compareBy<Game> { when (it.state) { "in" -> 0; "pre" -> 1; else -> 2 } }.thenBy { it.startMs })
    }
}
