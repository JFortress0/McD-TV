package com.mcd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mcd.tv.data.Prefs
import com.mcd.tv.player.PlayerScreen
import com.mcd.tv.ui.HomeScreen
import com.mcd.tv.ui.IntroScreen
import com.mcd.tv.ui.McdTheme
import com.mcd.tv.ui.SettingsScreen

/** Every screen in the app. Navigation is a simple back stack of these. */
sealed interface Screen {
    data object Intro : Screen
    data object Home : Screen
    data object Settings : Screen
    data class Player(val url: String, val title: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            McdTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    // Start on the intro unless the user turned it off in Settings.
    val backStack = remember {
        mutableStateListOf<Screen>(if (Prefs.playIntro(context)) Screen.Intro else Screen.Home)
    }
    val current = backStack.last()

    fun push(s: Screen) { backStack.add(s) }
    // removeAt instead of removeLast: removeLast crashes on older Android versions.
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun replaceTop(s: Screen) { backStack[backStack.lastIndex] = s }

    // BACK pops one screen. On the bottom screen, BACK falls through and exits the app.
    BackHandler(enabled = backStack.size > 1) { pop() }

    when (current) {
        Screen.Intro -> IntroScreen(onDone = { replaceTop(Screen.Home) })
        Screen.Home -> HomeScreen(
            onPlay = { url, title -> push(Screen.Player(url, title)) },
            onOpenSettings = { push(Screen.Settings) },
        )
        Screen.Settings -> SettingsScreen(
            onPlay = { url, title -> push(Screen.Player(url, title)) },
            onReplayIntro = { push(Screen.Intro) },
            onBack = { pop() },
        )
        is Screen.Player -> PlayerScreen(url = current.url, title = current.title)
    }
}
