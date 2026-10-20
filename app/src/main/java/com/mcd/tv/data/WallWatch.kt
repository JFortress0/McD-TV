package com.mcd.tv.data

import org.json.JSONObject

/**
 * Evidence for why the Wall stopped showing, so the next incident names its cause instead of a guess.
 * MainActivity writes a [Beat] at each keep-screen-on self-check and when the app leaves the front, records screen
 * on and off, crashes and Android's own exit reason, and calls [diagnose] when Jarvis starts again.
 * The causes it tells apart:
 *  - Fire OS inactivity sleep: the screen turns off with no key press for hours. Keep-screen-on stops the normal
 *    timeout and sleep_timeout, but not Android TV's attentive_timeout, so [sleepWarning] names that one up front.
 *  - The screensaver or another app covered Jarvis: it left the front with the screen still on.
 *  - Android ended Jarvis (low memory, a crash, a freeze): a new process starts and the old one never said goodbye.
 *  - A gap between self-checks: the device was asleep or the app was frozen.
 * Pure logic, for tests.
 */
object WallWatch {
    /** One self-check. [elapsed] is SystemClock.elapsedRealtime (it keeps counting while the device sleeps). */
    data class Beat(
        val at: Long,
        val elapsed: Long,
        val pid: Int,
        val wallUp: Boolean,
        val inFront: Boolean,
        val screenOn: Boolean,
        val lastKeyAt: Long,
    )

    fun encode(b: Beat): String = JSONObject()
        .put("at", b.at).put("el", b.elapsed).put("pid", b.pid).put("wall", b.wallUp)
        .put("front", b.inFront).put("screen", b.screenOn).put("key", b.lastKeyAt).toString()

    fun decode(s: String): Beat? = runCatching {
        val o = JSONObject(s)
        Beat(o.getLong("at"), o.optLong("el"), o.getInt("pid"), o.optBoolean("wall"), o.optBoolean("front"),
            o.optBoolean("screen", true), o.optLong("key"))
    }.getOrNull()

    /** Seconds between two self-checks when it is far more than [everyMs] (asleep or frozen), else null. */
    fun gapSeconds(prevElapsed: Long, nowElapsed: Long, everyMs: Long): Long? {
        if (prevElapsed <= 0 || nowElapsed < prevElapsed) return null
        val d = nowElapsed - prevElapsed
        return if (d > everyMs * 3) d / 1000 else null
    }

    /** "2 h 5 min", "12 min", "40 s". */
    fun span(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min"
            else -> "$s s"
        }
    }

    /** Android's ApplicationExitInfo reason codes (Android 11+), as what happened to Jarvis. */
    fun exitReason(code: Int): String = when (code) {
        1 -> "exited by itself"
        2 -> "was stopped by a signal"
        3 -> "was ended by Android for low memory"
        4 -> "crashed"
        5 -> "crashed in native code"
        6 -> "froze (not responding) and was closed"
        7 -> "failed to start"
        8 -> "was closed when a permission changed"
        9 -> "was closed for using too much"
        10 -> "was closed by a person (force stop or swipe away)"
        11 -> "was closed by Android"
        12 -> "was closed by another app or the system"
        13 -> "was closed because it was turned off"
        14 -> "was closed when it was updated or a setting changed"
        else -> "ended for an unknown reason"
    }

    /**
     * Why the Wall stopped, when this start follows one where the Wall was up. Null when there is nothing to say:
     * no earlier beat, the Wall was not up, or the same process is still running (an activity rebuild).
     * [crash]: the crash Jarvis recorded on its way down. [exit]: Android's exit reason in words, if it has one.
     * [leftFront]: the last "left the front" record ("screen on" or "screen off"), if any came after the last beat.
     */
    fun diagnose(last: Beat?, nowPid: Int, now: Long, crash: String?, exit: String?, leftFront: String?): String? {
        if (last == null || !last.wallUp || last.pid == nowPid) return null
        val ago = span(now - last.at)
        val noKey = if (last.lastKeyAt > 0) " No remote key for ${span(last.at - last.lastKeyAt)} before that." else ""
        val why = when {
            crash != null -> "Jarvis crashed: $crash"
            exit != null && leftFront == null -> "Jarvis was in front and $exit."
            leftFront == "screen off" -> "The TV went to sleep first (Fire OS inactivity sleep), then Jarvis ${exit ?: "was ended in the background"}."
            leftFront == "screen on" -> "Something covered Jarvis first (the screensaver, another app, or HOME), then Jarvis ${exit ?: "was ended in the background"}."
            exit != null -> "Jarvis $exit."
            else -> "Jarvis stopped while the Wall was in front, with no goodbye: Android most likely ended it."
        }
        return "The Wall stopped. Last check $ago ago. $why$noKey"
    }

    /**
     * Open the Wall again on this start: the Wall was up in another process that ended while it was still in front
     * (killed or crashed, with no "left the front" record). Not after HOME, the screensaver or sleep.
     */
    fun reopenWall(last: Beat?, nowPid: Int, leftFront: String?): Boolean =
        last != null && last.wallUp && last.inFront && last.pid != nowPid && leftFront.isNullOrEmpty()

    /** What the screen did when Jarvis left the front with the Wall up. */
    fun leftFrontNote(screenOn: Boolean, lastKeyAgoMs: Long): String =
        if (screenOn) "Jarvis left the front while the Wall was up, screen on (screensaver, another app or HOME). Last remote key ${span(lastKeyAgoMs)} ago."
        else "The TV went to sleep while the Wall was up. Last remote key ${span(lastKeyAgoMs)} ago."

    /**
     * Why the TV went to sleep, from the inactivity setting read at that moment. [attentiveMs]: null or not above 0
     * means this TV has no inactivity sleep, so something else turned it off.
     */
    fun sleepCause(lastKeyAgoMs: Long, attentiveMs: Long?): String {
        val a = attentiveMs?.takeIf { it > 0 }
            ?: return "Not the inactivity sleep (attentive_timeout is off): the TV, HDMI-CEC or a power button turned it off."
        return if (lastKeyAgoMs >= a - 60_000) "Cause: this TV sleeps after ${span(a)} with no remote use (attentive_timeout). Keep-screen-on cannot stop that. Turn it off on the TV."
        else "attentive_timeout is ${span(a)}, but a key came ${span(lastKeyAgoMs)} ago, so something else turned the TV off."
    }

    /**
     * Warning when this TV will sleep on its own after hours with no remote use, whatever the Wall does. Android
     * TV's attentive_timeout (Android 10+, so Fire OS 8) ignores keep-screen-on; sleep_timeout and the screen
     * timeout do not, so only attentive_timeout warns. Value in ms; null or not above 0 means off or unknown.
     */
    fun sleepWarning(attentiveMs: Long?): String? {
        val ms = attentiveMs?.takeIf { it > 0 } ?: return null
        return "This TV turns itself off after ${span(ms)} with no remote use, even with the Wall up. Turn that off on the TV."
    }
}
