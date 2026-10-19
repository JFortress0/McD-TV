package com.mcd.tv.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mcd.tv.data.HoldSet
import kotlinx.coroutines.delay

/**
 * Keeps the TV screen on, so the Fire TV screensaver cannot start. Screens call [KeepScreenOn]; the window flag
 * (FLAG_KEEP_SCREEN_ON) stays set while any of them is showing. One owner sets the flag: [KeepScreenWindow] in
 * MainActivity. Screens never touch a shared view flag, so one screen closing cannot clear another's hold.
 */
object KeepScreen {
    private val holds = HoldSet()

    /** True while any screen holds the screen on. */
    var held by mutableStateOf(false)
        private set

    fun hold(who: String): Int = holds.hold(who).also { held = holds.held }

    fun release(token: Int) {
        holds.release(token)
        held = holds.held
    }
}

/** Holds the screen on while the calling screen is showing. */
@Composable
fun KeepScreenOn(who: String) {
    DisposableEffect(Unit) {
        val token = KeepScreen.hold(who)
        onDispose { KeepScreen.release(token) }
    }
}

/**
 * Sets or clears FLAG_KEEP_SCREEN_ON on the activity window from [KeepScreen.held]. While held it checks the flag
 * again every minute and puts it back if anything cleared it. Runs again after the activity is re-created.
 */
@Composable
fun KeepScreenWindow() {
    val activity = LocalContext.current as? Activity ?: return
    val held = KeepScreen.held
    LaunchedEffect(held) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (!held) {
            activity.window.clearFlags(flag)
            return@LaunchedEffect
        }
        while (true) {
            if (activity.window.attributes.flags and flag == 0) activity.window.addFlags(flag)
            delay(60_000)
        }
    }
}
