package com.mcd.tv.data

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ======================= Live TV: M3U playlists you supply =======================

data class Channel(val name: String, val logo: String?, val group: String, val url: String)

object M3u {
    private val attr = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")

    /** Parses a standard #EXTM3U playlist. */
    fun parse(text: String): List<Channel> = parseLines(text.lineSequence())

    /**
     * Parses line by line, so very large playlists (100k+ entries) never sit in memory as one string.
     * Movie and series entries (Xtream-style /movie/ and /series/ links) are skipped: Live TV shows channels only.
     */
    fun parseLines(lines: Sequence<String>, max: Int = 25_000): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingName: String? = null
        var logo: String? = null
        var group = "Other"
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null } ?: "Other"
                    pendingName = line.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                }
                line.isNotEmpty() && !line.startsWith("#") && pendingName != null -> {
                    val vod = line.contains("/movie/") || line.contains("/series/")
                    if (!vod) out += Channel(pendingName!!, logo, group, line)
                    pendingName = null
                    if (out.size >= max) break
                }
            }
        }
        return out
    }

    private var cacheUrl = ""
    private var cacheAt = 0L
    private var cache: List<Channel> = emptyList()
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Downloads and parses off the main thread; shared by Live TV and Sports, refreshed every 6 hours. */
    suspend fun load(force: Boolean = false): List<Channel> {
        val url = Prefs.m3uUrl
        if (url.isBlank()) return emptyList()
        return lock.withLock {
            val fresh = url == cacheUrl && System.currentTimeMillis() - cacheAt < 6 * 3600_000L
            if (fresh && !force && cache.isNotEmpty()) return@withLock cache
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 20_000
                    c.readTimeout = 90_000
                    c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                    val code = c.responseCode
                    if (code !in 200..299) throw Exception("Playlist server answered $code. Check the link, or the account may be expired or in use on another screen.")
                    c.inputStream.bufferedReader().useLines { parseLines(it) }
                } finally { c.disconnect() }
            }
            if (list.isEmpty()) throw Exception("The playlist loaded but had no live channels in it.")
            cache = list; cacheUrl = url; cacheAt = System.currentTimeMillis()
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
