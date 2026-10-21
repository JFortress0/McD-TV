package com.mcd.tv.data

/**
 * Reasons the TV screen must stay on (no Fire OS screensaver), for example "wall" while Jarvis Wall is in the
 * back stack. Each [acquire] returns its own [Hold]; releasing one never clears another, even for the same
 * reason (an old screen let go after a rebuild cannot drop the new screen's hold). Pure logic, no Android.
 */
class KeepAwake {
    private val held = ArrayList<Hold>()

    /** Called with the new value each time [on] changes. */
    @Volatile var listener: ((Boolean) -> Unit)? = null

    inner class Hold internal constructor(val reason: String) {
        /** Lets this hold go. A second call does nothing. */
        fun release() {
            val changed = synchronized(this@KeepAwake) {
                val before = held.isNotEmpty()
                held.remove(this)
                before != held.isNotEmpty()
            }
            if (changed) listener?.invoke(on)
        }
    }

    /** True while any hold is on. */
    val on: Boolean
        @Synchronized get() = held.isNotEmpty()

    @Synchronized
    fun has(reason: String): Boolean = held.any { it.reason == reason }

    /** The reasons held right now, for logs. */
    @Synchronized
    fun reasons(): List<String> = held.map { it.reason }.distinct()

    fun acquire(reason: String): Hold {
        val h = Hold(reason)
        val changed = synchronized(this) {
            val before = held.isNotEmpty()
            held.add(h)
            !before
        }
        if (changed) listener?.invoke(on)
        return h
    }
}

/** The keep-screen-on flag of one activity window. MainActivity wraps FLAG_KEEP_SCREEN_ON in this. */
interface KeepOnWindow {
    var keepOn: Boolean
}

/**
 * Puts [holds] on the activity window and checks that it stays there. MainActivity calls it at each lifecycle
 * step. A rebuilt activity brings a new window: [attach] moves to it, and a late [detach] from the old one is
 * ignored. [check] runs about once a minute while the app is in front: if a hold is on but the window lost the
 * flag, it turns it back on and logs a lapse. [stopped] logs when Jarvis leaves the front while the Wall is up
 * (another app, a system screen or the screensaver covered it). Pure logic, for tests.
 */
class KeepAwakeGuard(private val holds: KeepAwake, private val log: (String) -> Unit) {
    private var window: KeepOnWindow? = null

    /** Times [check] found the flag off while a hold was on. */
    @Volatile var lapses = 0
        private set

    init {
        holds.listener = { on ->
            window?.keepOn = on
            log(if (on) "Keep screen on: on (${holds.reasons().joinToString()})" else "Keep screen on: off")
        }
    }

    /** Activity onCreate. */
    fun attach(w: KeepOnWindow) {
        window = w
        w.keepOn = holds.on
    }

    /** Activity onDestroy. */
    fun detach(w: KeepOnWindow) {
        if (window === w) window = null
    }

    /** Activity onResume: set the flag again in case anything changed while the app was behind. */
    fun resumed() {
        window?.keepOn = holds.on
    }

    /** Activity onStop. [changingConfig] is a rebuild, not a lapse. */
    fun stopped(changingConfig: Boolean) {
        if (!changingConfig && holds.has(ScreenAwake.WALL)) {
            log("Jarvis left the front while the Wall was up. The screensaver may start.")
        }
    }

    /** The self-check. True when the flag had lapsed and was put back. */
    fun check(): Boolean {
        val w = window ?: return false
        if (holds.on && !w.keepOn) {
            lapses++
            w.keepOn = true
            log("Keep screen on had lapsed (${holds.reasons().joinToString()}). Turned it back on. Lapses: $lapses")
            return true
        }
        return false
    }
}

/** The app-wide holds and their guard (MainActivity applies them to its window). */
object ScreenAwake {
    const val WALL = "wall"
    const val PHONE_SETUP = "phone_setup"
    /** How often MainActivity runs [KeepAwakeGuard.check]. Fire OS's shortest screensaver delay is 5 minutes. */
    const val CHECK_EVERY_MS = 60_000L
    val holds = KeepAwake()
    @Volatile var log: (String) -> Unit = {}
    val guard = KeepAwakeGuard(holds) { log(it) }
}
