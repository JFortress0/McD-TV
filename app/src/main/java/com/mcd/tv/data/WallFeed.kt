package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import com.mcd.tv.data.Wall.Event
import com.mcd.tv.data.Wall.Feed
import com.mcd.tv.data.Wall.Source
import com.mcd.tv.data.Wall.Weather
import com.mcd.tv.data.Wall.WeatherDay
import org.json.JSONObject

/**
 * Reads the Jarvis Wall feed (wall/netlify/functions/feed.mts). Pure, so unit tests can read a real feed body
 * without touching Prefs (WallSpotlightTest reads tools/wallfeed/spotlight-feed.json).
 */
object WallFeed {
    fun parse(body: String): Feed {
        val j = JSONObject(body)
        val src = j.optJSONArray("sources")
        val sources = List(src?.length() ?: 0) { i ->
            val s = src!!.getJSONObject(i)
            Source(
                name = s.optString("name"),
                tag = s.optString("tag").ifBlank { s.optString("name") },
                color = parseColor(s.optString("color")),
                kind = s.optString("kind", "personal"),
                error = s.optString("error").takeIf { !s.isNull("error") && it.isNotBlank() },
            )
        }
        val ev = j.optJSONArray("events")
        val events = List(ev?.length() ?: 0) { i ->
            val e = ev!!.getJSONObject(i)
            Event(
                source = e.optInt("s"),
                title = e.optString("t"),
                start = if (e.has("start")) e.getLong("start") else null,
                end = if (e.has("end")) e.getLong("end") else null,
                day = Day.parse(e.optString("day")),
                endDay = Day.parse(e.optString("endDay")),
                location = e.optString("loc").takeIf { it.isNotBlank() },
                repeats = e.optInt("r") == 1,
            )
        }.filter { it.start != null || it.day != null }
        val w = j.optJSONObject("weather")
        val weather = w?.let { o ->
            val d = o.optJSONArray("days")
            Weather(
                now = o.optInt("now"),
                code = o.optInt("code"),
                days = List(d?.length() ?: 0) { i ->
                    val x = d!!.getJSONObject(i)
                    WeatherDay(
                        Day.parse(x.optString("day")) ?: 0, x.optInt("hi"), x.optInt("lo"), x.optInt("code"),
                        if (x.isNull("rain")) null else x.optInt("rain"),
                    )
                },
            )
        }
        return Feed(j.optLong("generated"), sources, events, weather, repeatsKnown = j.optInt("v") >= 2)
    }

    /** "#RRGGBB" to an opaque ARGB long; cyan when unreadable. */
    fun parseColor(s: String): Long {
        val hex = s.removePrefix("#")
        return if (hex.length == 6) runCatching { 0xFF000000L or hex.toLong(16) }.getOrDefault(0xFF00E5FFL) else 0xFF00E5FFL
    }
}
