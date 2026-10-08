package com.mcd.tv.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ======================= Live TV: M3U playlists you supply =======================

data class Channel(val name: String, val logo: String?, val group: String, val url: String)

object M3u {
    private val attr = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")

    /** Parses a standard #EXTM3U playlist. */
    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var pendingName: String? = null
        var logo: String? = null
        var group = "Other"
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null } ?: "Other"
                    pendingName = line.substringAfterLast(",").trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                }
                line.isNotEmpty() && !line.startsWith("#") && pendingName != null -> {
                    out += Channel(pendingName!!, logo, group, line)
                    pendingName = null
                }
            }
        }
        return out
    }

    suspend fun load(): List<Channel> {
        val url = Prefs.m3uUrl
        if (url.isBlank()) return emptyList()
        return parse(Http.get(url))
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
