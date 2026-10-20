package com.mcd.tv.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

/**
 * Writes the Wall's evidence (see [WallWatch]) as it happens, so the next start can say why the Wall stopped.
 * Prefs: wall_beat (the last self-check), wall_left (how Jarvis last left the front), wall_crash (a crash on the
 * way down). Everything also goes to logcat (tag JarvisAwake); causes go to Settings > About and help.
 * MainActivity calls it at each lifecycle step, on screen on and off, and once a minute from the self-check.
 */
object WallRecorder {
    private const val BEAT = "wall_beat"
    private const val LEFT = "wall_left"
    private const val CRASH = "wall_crash"

    /** When a remote key was last pressed (MainActivity.dispatchKeyEvent). Starts at process start. */
    @Volatile var lastKeyAt = System.currentTimeMillis()
    @Volatile private var screenOn = true
    @Volatile private var inFront = false
    @Volatile private var lastTickElapsed = 0L
    @Volatile private var diagnosed = false
    @Volatile private var crashHook = false
    /** This sleep is already noted (by the screen-off broadcast or by onStop, whichever comes first). */
    @Volatile private var sleepNoted = false
    @Volatile private var app: Context? = null

    private fun wallUp() = ScreenAwake.holds.has(ScreenAwake.WALL)

    private fun note(msg: String) {
        ScreenAwake.log(msg)
        RecentErrors.note(msg)
    }

    /** Saves a beat now. Also called when the Wall opens or closes, so a closed Wall never looks like a lost one. */
    fun beat() {
        runCatching {
            Prefs.putJson(BEAT, WallWatch.encode(WallWatch.Beat(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), Process.myPid(),
                wallUp(), inFront, screenOn, lastKeyAt,
            )))
        }
    }

    /** The once-a-minute self-check. A long gap since the last one means the device slept or Jarvis froze. */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val gap = WallWatch.gapSeconds(lastTickElapsed, now, ScreenAwake.CHECK_EVERY_MS)
        if (gap != null && wallUp()) note("Self-check gap of ${WallWatch.span(gap * 1000)}: the TV was asleep or Jarvis was frozen.")
        lastTickElapsed = now
        beat()
    }

    fun resumed() {
        inFront = true
        lastTickElapsed = SystemClock.elapsedRealtime()
        runCatching { Prefs.putJson(LEFT, "") }
        beat()
    }

    fun stopped(context: Context, changingConfig: Boolean) {
        inFront = false
        if (!changingConfig && wallUp()) {
            val on = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)
            runCatching { Prefs.putJson(LEFT, if (on) "screen on" else "screen off") }
            // The screen-off broadcast can come first, later, or not at all (the TV was already asleep when Jarvis
            // started), so whichever comes first notes the sleep.
            if (on) note(WallWatch.leftFrontNote(true, System.currentTimeMillis() - lastKeyAt))
            else noteSleep()
        }
        beat()
    }

    /** ACTION_SCREEN_OFF / ACTION_SCREEN_ON. */
    fun screen(on: Boolean) {
        screenOn = on
        if (on) sleepNoted = false
        if (wallUp()) {
            if (!on) noteSleep() else ScreenAwake.log("The TV woke up")
        }
        beat()
    }

    private fun noteSleep() {
        if (sleepNoted) return
        sleepNoted = true
        val ago = System.currentTimeMillis() - lastKeyAt
        // The settings as they are now, not as they were when the Wall opened.
        val attentive = app?.let { checkSleepSettings(it, warn = false); attentiveMs(it) }
        note(WallWatch.leftFrontNote(false, ago) + " " + WallWatch.sleepCause(ago, attentive))
    }

    /** Records a crash while the Wall is up (written at once: the process is about to end), then the usual crash. */
    fun installCrashHook() {
        if (crashHook) return
        crashHook = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                if (wallUp()) Prefs.putJsonNow(CRASH, "${e.javaClass.simpleName}: ${e.message ?: ""}".take(160))
            }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * Once per process, at start: if the last process had the Wall up, say why it stopped. True when the Wall should
     * open again (the last process ended while the Wall was in front).
     */
    fun diagnoseOnStart(context: Context): Boolean {
        app = context.applicationContext
        if (diagnosed) return false
        diagnosed = true
        val last = WallWatch.decode(Prefs.json(BEAT))
        val crash = Prefs.json(CRASH).ifBlank { null }
        val left = Prefs.json(LEFT).ifBlank { null }
        val nowPid = Process.myPid()
        val exit = if (last != null && last.pid != nowPid && Build.VERSION.SDK_INT >= 30) runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(context.packageName, last.pid, 1)
                .firstOrNull()?.let { WallWatch.exitReason(it.reason) }
        }.getOrNull() else null
        WallWatch.diagnose(last, nowPid, System.currentTimeMillis(), crash, exit, left)?.let { note(it) }
        runCatching { Prefs.putJson(CRASH, "") }
        return WallWatch.reopenWall(last, nowPid, left)
    }

    /** Android TV's attentive_timeout in ms (Android 10+), or null when this TV has none. */
    fun attentiveMs(context: Context): Long? =
        runCatching { Settings.Secure.getLong(context.contentResolver, "attentive_timeout") }.getOrNull()

    /** Logs this TV's sleep settings when the Wall opens, and notes the one the Wall cannot stop. */
    fun checkSleepSettings(context: Context, warn: Boolean = true) {
        val r = context.contentResolver
        val off = runCatching { Settings.System.getLong(r, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
        val sleep = runCatching { Settings.Secure.getLong(r, "sleep_timeout") }.getOrNull()
        val attentive = attentiveMs(context)
        ScreenAwake.log("TV sleep settings (ms): screen_off_timeout=$off sleep_timeout=$sleep attentive_timeout=$attentive")
        if (warn) WallWatch.sleepWarning(attentive)?.let { note(it) }
    }
}
