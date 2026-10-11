package com.mcd.tv.data

import java.util.Calendar

/**
 * Jarvis Wall: the countdown to a baby's due date. Days are yyyymmdd Ints, like Wall.Day (no java.time on
 * the oldest Fire TV sticks). Pure logic; unit tests in BabyCountdownTest.
 */
object BabyCountdown {
    /** A full pregnancy, counted from the first day of the last period: 40 weeks. */
    const val TERM_DAYS = 280

    /** Days after the due date that the Wall keeps the card up (then it hides until the date is cleared). */
    const val AFTER_DAYS = 21

    /**
     * [daysLeft]: days until the due date (0 on the day, negative after it). [week] and [day]: how far along
     * ("Week 20, day 4" means 20 weeks and 4 days). [progress]: 0 to 1 across the 40 weeks.
     */
    data class State(val due: Int, val daysLeft: Int, val week: Int, val day: Int, val progress: Float) {
        /** The big line: "136 days to go", "Due today", "3 days past the due date". */
        val headline: String
            get() = when {
                daysLeft > 1 -> "$daysLeft days to go"
                daysLeft == 1 -> "1 day to go"
                daysLeft == 0 -> "Due today"
                daysLeft == -1 -> "1 day past the due date"
                else -> "${-daysLeft} days past the due date"
            }

        /** The big line with the baby's name: "137 days until Sam", "Sam is due today". Blank name: [headline]. */
        fun headlineFor(name: String): String {
            val n = name.trim()
            if (n.isEmpty()) return headline
            return when {
                daysLeft > 1 -> "$daysLeft days until $n"
                daysLeft == 1 -> "1 day until $n"
                daysLeft == 0 -> "$n is due today"
                daysLeft == -1 -> "$n is 1 day past due"
                else -> "$n is ${-daysLeft} days past due"
            }
        }

        /** The small line: "Week 20, day 4. Due Feb 24." */
        val detail: String
            get() = (if (day == 0) "Week $week. " else "Week $week, day $day. ") + "Due ${shortDate(due)}."
    }

    /** The countdown for [today], or null when the date is too far off, too long past or unreadable. */
    fun state(today: Int, due: Int): State? {
        val left = daysBetween(today, due) ?: return null
        if (left > TERM_DAYS || left < -AFTER_DAYS) return null
        val along = TERM_DAYS - left
        return State(due, left, along / 7, along % 7, (along.toFloat() / TERM_DAYS).coerceIn(0f, 1f))
    }

    /** "2027-02-24" to 20270224, or null. */
    fun parse(s: String?): Int? {
        val t = s?.trim() ?: return null
        if (t.length != 10 || t[4] != '-' || t[7] != '-') return null
        val y = t.substring(0, 4).toIntOrNull() ?: return null
        val m = t.substring(5, 7).toIntOrNull() ?: return null
        val d = t.substring(8, 10).toIntOrNull() ?: return null
        if (y < 2000 || m !in 1..12 || d !in 1..31) return null
        return y * 10000 + m * 100 + d
    }

    private val DUE_TITLE = Regex("\\bbaby\\b.*\\bdue\\b|\\bdue\\b.*\\bbaby\\b|^\\W*due date\\W*$", RegexOption.IGNORE_CASE)

    /** True for a calendar event that marks the due date: "Due date" alone, or "baby" with "due" ("Baby due!"). */
    fun isDueEvent(title: String): Boolean = DUE_TITLE.containsMatchIn(title)

    /** Whole days from [a] to [b] (yyyymmdd), or null when either is not a real date. */
    fun daysBetween(a: Int, b: Int): Int? {
        val ca = noon(a) ?: return null
        val cb = noon(b) ?: return null
        return Math.round((cb.timeInMillis - ca.timeInMillis) / 86_400_000.0).toInt()
    }

    private fun noon(day: Int): Calendar? {
        val y = day / 10000
        val m = day / 100 % 100
        val d = day % 100
        if (m !in 1..12 || d !in 1..31) return null
        val c = Calendar.getInstance()
        c.isLenient = false
        c.clear()
        c.set(y, m - 1, d, 12, 0, 0)
        return runCatching { c.timeInMillis; c }.getOrNull()
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun shortDate(day: Int): String = "${MONTHS[(day / 100 % 100 - 1).coerceIn(0, 11)]} ${day % 100}"
}
