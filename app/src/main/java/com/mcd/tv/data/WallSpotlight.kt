package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jarvis Wall: the Heads-up card. It calls out one-off events (a trip, an appointment) that start in the next
 * 4 weeks: "In 3 weeks: Beach trip". Events from a repeating series never show. The feed marks those (r: 1,
 * feed v2); an older feed says nothing about repeats, so the card stays off rather than guess.
 * School calendars are left out: they have the Due soon box, and Brightspace sends each due date as its own event.
 *
 * The card takes the place of the agenda list on the right for one minute in every [EVERY_MIN]. The photo and
 * the month calendar keep their own timing (see [frame]). Pure logic; tests in WallSpotlightTest.
 */
object WallSpotlight {
    /** How far ahead the card looks, in days (4 weeks). */
    const val AHEAD_DAYS = 28

    /** At most this many events take turns on the card, soonest first. */
    const val MAX = 3

    /** The card shows for one minute in every [EVERY_MIN], at minute [AT_MIN] of each step (:02, :07, :12 and so on). */
    const val EVERY_MIN = 5
    const val AT_MIN = 2

    /** One event on the card. [days] is the date line, [countdown] the big words, [location] may be null. */
    data class Spot(val event: Wall.Event, val countdown: String, val days: String, val location: String?) {
        val title: String get() = event.title
    }

    /** The events for the card on [today], soonest first, at most [MAX]. */
    fun pick(feed: Wall.Feed, today: Int): List<Spot> {
        if (!feed.repeatsKnown) return emptyList()
        val last = Day.plus(today, AHEAD_DAYS)
        return feed.events
            .filter { e ->
                !e.repeats && e.firstDay > today && e.firstDay <= last &&
                    feed.sources.getOrNull(e.source)?.kind != "school"
            }
            // The same event on two calendars (a shared trip) shows once.
            .distinctBy { it.title.trim().lowercase(Locale.US) to it.firstDay }
            .sortedWith(compareBy<Wall.Event>({ it.firstDay }, { !it.allDay }, { it.start ?: 0 }, { it.title }))
            .take(MAX)
            .map { Spot(it, countdown(today, it.firstDay), days(it), it.location) }
    }

    /**
     * Plain words for how far off [day] is: "Tomorrow", "This Friday" (later this week), "Next Tuesday"
     * (the next Sunday to Saturday week), else "In a week", "In 2 weeks" (rounded to the nearest week).
     */
    fun countdown(today: Int, day: Int): String {
        val d = daysBetween(today, day)
        val weekday = SimpleDateFormat("EEEE", Locale.US).format(Day.calendar(day).time)
        val nextSunday = Day.plus(today, 8 - Day.weekday(today))
        return when {
            d <= 0 -> "Today"
            d == 1 -> "Tomorrow"
            day < nextSunday -> "This $weekday"
            day < Day.plus(nextSunday, 7) -> "Next $weekday"
            else -> when (val weeks = (d + 3) / 7) {
                1 -> "In a week"
                else -> "In $weeks weeks"
            }
        }
    }

    /** "Wed, Nov 11 to Sat, Nov 14" for a trip, "Sat, Nov 7" for one day, "Thu, Oct 22 at 2:30 PM" for a timed event. */
    fun days(e: Wall.Event): String {
        val fmt = SimpleDateFormat("EEE, MMM d", Locale.US)
        val first = fmt.format(Day.calendar(e.firstDay).time)
        val at = if (!e.allDay && e.start != null) " at " + SimpleDateFormat("h:mm a", Locale.US).format(Date(e.start)) else ""
        return if (e.lastDay > e.firstDay) "$first$at to " + fmt.format(Day.calendar(e.lastDay).time) else "$first$at"
    }

    /** What the Wall shows at one minute: the month calendar or not, and which card (index into [pick]) or none. */
    data class Frame(val month: Boolean, val spot: Int?)

    /**
     * The month calendar follows [WallCycle] alone, so the card never adds calendar time. The card only replaces
     * the agenda list beside the photo; the photo shows exactly as it would without it.
     */
    fun frame(cycle: WallCycle.Cycle, spots: Int, minuteOfDay: Int): Frame {
        val month = WallCycle.showCalendar(cycle, minuteOfDay)
        val spot = if (!month && spots > 0 && minuteOfDay % EVERY_MIN == AT_MIN) (minuteOfDay / EVERY_MIN) % spots else null
        return Frame(month, spot)
    }

    private fun daysBetween(a: Int, b: Int): Int {
        // Noon to noon, so a clock change never shifts the count.
        val ms = Day.calendar(b).timeInMillis - Day.calendar(a).timeInMillis
        return Math.round(ms / 86_400_000.0).toInt()
    }
}
