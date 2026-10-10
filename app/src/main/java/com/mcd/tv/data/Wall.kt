package com.mcd.tv.data

import org.json.JSONObject
import java.util.Calendar

/**
 * Jarvis Wall: the calendar and photo frame mode for a wall TV.
 * Data comes from the jarvis-wall Netlify site (wall/ in the repo). The site holds the calendar links;
 * the TV only knows the site address and a read-only wall key, both set on the Control page.
 */
object Wall {
    data class Source(val name: String, val tag: String, val color: Long, val kind: String, val error: String?)

    /**
     * A calendar day as yyyymmdd (20261010). Plain Ints compare in date order, and java.time is not
     * available on the oldest Fire TV sticks (API 24/25).
     */
    object Day {
        fun of(y: Int, m: Int, d: Int): Int = y * 10000 + m * 100 + d
        fun year(day: Int) = day / 10000
        fun month(day: Int) = day / 100 % 100
        fun dom(day: Int) = day % 100

        /** The device-local day of an instant. */
        fun ofMillis(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let {
            of(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }

        fun today(): Int = ofMillis(System.currentTimeMillis())

        /** "2026-10-10" to 20261010 (null when unreadable). */
        fun parse(s: String): Int? {
            if (s.length < 10 || s[4] != '-' || s[7] != '-') return null
            val y = s.substring(0, 4).toIntOrNull() ?: return null
            val m = s.substring(5, 7).toIntOrNull() ?: return null
            val d = s.substring(8, 10).toIntOrNull() ?: return null
            return of(y, m, d)
        }

        /** A local Calendar at noon on [day] (noon keeps day steps safe across clock changes). */
        fun calendar(day: Int): Calendar = Calendar.getInstance().apply {
            clear()
            set(year(day), month(day) - 1, dom(day), 12, 0, 0)
        }

        fun plus(day: Int, days: Int): Int = calendar(day).apply { add(Calendar.DAY_OF_MONTH, days) }.let {
            of(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }

        /** Calendar.SUNDAY (1) .. Calendar.SATURDAY (7). */
        fun weekday(day: Int): Int = calendar(day).get(Calendar.DAY_OF_WEEK)
    }

    /** [start]/[end] are set for timed events; [day]/[endDay] for all-day events (endDay is the last day). */
    data class Event(
        val source: Int,
        val title: String,
        val start: Long? = null,
        val end: Long? = null,
        val day: Int? = null,
        val endDay: Int? = null,
        val location: String? = null,
    ) {
        val allDay: Boolean get() = day != null

        val firstDay: Int get() = day ?: Day.ofMillis(start ?: 0)

        /** An event ending exactly at midnight belongs to the day before. */
        val lastDay: Int get() = if (day != null) endDay ?: day else Day.ofMillis(maxOf((end ?: start ?: 0) - 1, start ?: 0))

        fun isOn(d: Int): Boolean = d in firstDay..lastDay
    }

    data class WeatherDay(val day: Int, val hi: Int, val lo: Int, val code: Int, val rain: Int?)
    data class Weather(val now: Int, val code: Int, val days: List<WeatherDay>)

    data class Feed(val generated: Long, val sources: List<Source>, val events: List<Event>, val weather: Weather?)

    val configured: Boolean get() = Prefs.wallUrl.isNotBlank() && Prefs.wallKey.isNotBlank()

    private fun base(): String = Prefs.wallUrl.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }

    /** Downloads the merged calendar. Saves a copy so the wall shows something at once after a restart or outage. */
    suspend fun loadFeed(): Feed {
        val body = Http.get("${base()}/api/feed?key=${Http.enc(Prefs.wallKey)}", timeoutMs = 40_000)
        val feed = parseFeed(body)
        Prefs.wallCache = body
        return feed
    }

    fun cachedFeed(): Feed? = Prefs.wallCache.takeIf { it.isNotBlank() }?.let { runCatching { parseFeed(it) }.getOrNull() }

    suspend fun loadPhotos(): List<String> {
        val j = JSONObject(Http.get("${base()}/api/photos?key=${Http.enc(Prefs.wallKey)}"))
        val a = j.optJSONArray("photos") ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    fun photoUrl(id: String): String = "${base()}/api/photos/$id?key=${Http.enc(Prefs.wallKey)}"

    /** The phone upload page (shown as a QR code when the wall has no photos yet). */
    fun uploadPage(): String = base() + "/"

    fun parseFeed(body: String): Feed {
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
        return Feed(j.optLong("generated"), sources, events, weather)
    }

    /** "#RRGGBB" to an opaque ARGB long; cyan when unreadable. */
    fun parseColor(s: String): Long {
        val hex = s.removePrefix("#")
        return if (hex.length == 6) runCatching { 0xFF000000L or hex.toLong(16) }.getOrDefault(0xFF00E5FFL) else 0xFF00E5FFL
    }

    /** Short words for Open-Meteo weather codes (WMO). */
    fun weatherLabel(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 66 -> "Rain"
        65, 67 -> "Heavy rain"
        71, 73, 75, 77 -> "Snow"
        80, 81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Storms"
        else -> ""
    }
}
