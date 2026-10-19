package com.mcd.tv.data

/**
 * How often the Wall switches from photos to the full month calendar, and for how long.
 * Stored per TV as "every/stays" in minutes (pref `wall_cycle`), for example "30/1": the calendar shows
 * for the last minute of every half hour (at :29 and :59). "0/0" means never. The switch follows the clock,
 * so it happens at the same minutes every hour.
 */
object WallCycle {
    /** Minutes between calendar views. 0 = never. Each one divides a day, so the times stay the same every hour. */
    val EVERY = listOf(10, 15, 30, 60, 0)
    /** Minutes the calendar stays. */
    val STAYS = listOf(1, 2, 5)

    const val DEFAULT = "30/1"

    data class Cycle(val every: Int, val stays: Int) {
        val key: String get() = "$every/$stays"
    }

    /** Reads a stored value. Anything unknown or empty gives the default. */
    fun parse(s: String?): Cycle {
        val parts = s.orEmpty().trim().split("/")
        val every = parts.getOrNull(0)?.toIntOrNull()
        val stays = parts.getOrNull(1)?.toIntOrNull()
        if (every == null || every !in EVERY) return parse(DEFAULT)
        if (every == 0) return Cycle(0, 0)
        val s2 = if (stays != null && stays in STAYS) stays else 1
        // The calendar never stays longer than half the gap, so photos always lead.
        return Cycle(every, s2.coerceAtMost(every / 2))
    }

    /** The value to store: always a known pair. */
    fun clean(s: String?): String = parse(s).key

    /** True when the month calendar should show at this minute of the day (0 to 1439). */
    fun showCalendar(c: Cycle, minuteOfDay: Int): Boolean {
        if (c.every <= 0 || c.stays <= 0) return false
        return minuteOfDay % c.every >= c.every - c.stays
    }

    /** Plain words for the TV and the phone, for example "Calendar every 30 min for 1 min". */
    fun label(c: Cycle): String =
        if (c.every <= 0) "Calendar never shows on its own"
        else "Calendar every ${if (c.every == 60) "hour" else "${c.every} min"} for ${c.stays} min"

    /** The next setting in the TV Settings button: steps through the gaps, keeps the stay time. */
    fun next(c: Cycle): Cycle {
        val i = EVERY.indexOf(c.every)
        val every = EVERY[(i + 1) % EVERY.size]
        return parse("$every/${if (c.stays > 0) c.stays else 1}")
    }

    /** The next stay time in the TV Settings button. */
    fun nextStays(c: Cycle): Cycle {
        if (c.every <= 0) return c
        val i = STAYS.indexOf(c.stays)
        // Skip stay times that are too long for this gap.
        for (k in 1..STAYS.size) {
            val s = STAYS[(i + k) % STAYS.size]
            if (s <= c.every / 2) return Cycle(c.every, s)
        }
        return c
    }
}
