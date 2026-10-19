package com.mcd.tv.data

/**
 * Reasons the TV screen must stay on (no Fire OS screensaver), for example "wall" while Jarvis Wall is in the
 * back stack. Each reason is set and cleared on its own, so one screen closing never clears another's hold.
 * MainActivity turns [on] into FLAG_KEEP_SCREEN_ON on its window. Pure logic, no Android.
 */
class KeepAwake {
    private val reasons = LinkedHashSet<String>()

    /** Called with the new value each time [on] changes. */
    @Volatile var listener: ((Boolean) -> Unit)? = null

    /** True while any reason holds the screen on. */
    val on: Boolean
        @Synchronized get() = reasons.isNotEmpty()

    @Synchronized
    fun has(reason: String): Boolean = reason in reasons

    /** Holds the screen on for [reason] when [keep] is true, else lets that reason go. */
    fun set(reason: String, keep: Boolean) {
        val changed = synchronized(this) {
            val before = reasons.isNotEmpty()
            if (keep) reasons.add(reason) else reasons.remove(reason)
            before != reasons.isNotEmpty()
        }
        if (changed) listener?.invoke(on)
    }
}

/** The app-wide holds (MainActivity applies them to its window). */
object ScreenAwake {
    const val WALL = "wall"
    const val PHONE_SETUP = "phone_setup"
    val holds = KeepAwake()
}
