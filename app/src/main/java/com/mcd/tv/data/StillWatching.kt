package com.mcd.tv.data

/**
 * Counts episodes that started by themselves (the Up Next countdown ran out) with no key press in between.
 * After [limit] of them, the next Up Next card asks "Still watching?" instead of counting down.
 * Any key press resets the count. Pure logic, no Android.
 */
class AutoPlayCount(val limit: Int = 3) {
    @Volatile var count = 0
        private set

    /** The Up Next countdown ran out and the next episode is starting by itself. */
    fun autoAdvanced() {
        count++
    }

    /** A remote key was pressed: someone is watching. */
    fun keyPressed() {
        count = 0
    }

    /** True when the next episode should wait for "Keep watching". */
    fun shouldAsk(): Boolean = count >= limit
}

/** The app-wide counter (MainActivity resets it on every key press; the Up Next card reads it). */
object StillWatching {
    val counter = AutoPlayCount(3)
}
