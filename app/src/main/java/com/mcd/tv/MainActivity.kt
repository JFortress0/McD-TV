package com.mcd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.mcd.tv.data.PlayMeta
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Service
import com.mcd.tv.player.PlayerScreen
import com.mcd.tv.ui.CalendarScreen
import com.mcd.tv.ui.DetailScreen
import com.mcd.tv.ui.HomeScreen
import com.mcd.tv.ui.IntroScreen
import com.mcd.tv.ui.LibraryScreen
import com.mcd.tv.ui.LiveTvScreen
import com.mcd.tv.ui.McdTheme
import com.mcd.tv.ui.NoiseRunScreen
import com.mcd.tv.ui.NoiseScreen
import com.mcd.tv.ui.NavTab
import com.mcd.tv.ui.PhoneSetupScreen
import com.mcd.tv.ui.RdConnectScreen
import com.mcd.tv.ui.SearchScreen
import com.mcd.tv.ui.ServiceGridScreen
import com.mcd.tv.ui.ServicesScreen
import com.mcd.tv.ui.SettingsScreen
import com.mcd.tv.ui.SourcesScreen
import com.mcd.tv.ui.SportsScreen
import com.mcd.tv.ui.WebScreen

/** Every screen in the app. Navigation is a simple back stack of these. */
sealed interface Screen {
    data object Intro : Screen
    data object Home : Screen
    data object Search : Screen
    data object Library : Screen
    data object Sports : Screen
    data object Live : Screen
    data object Services : Screen
    data class ServiceGrid(val service: Service) : Screen
    data object Noise : Screen
    data object NoiseRun : Screen
    data object Calendar : Screen
    data object Settings : Screen
    data object PhoneSetup : Screen
    data object RdConnect : Screen
    data class Detail(val type: String, val id: Int) : Screen
    /** autoPlay = the Play button: pick the best source and start immediately. */
    data class Sources(val meta: PlayMeta, val imdbId: String, val autoPlay: Boolean) : Screen
    data class Web(val url: String, val name: String) : Screen
    data class Player(val url: String, val title: String, val meta: PlayMeta? = null) : Screen
}

/** Navigation actions handed to every screen. */
class Nav(
    val push: (Screen) -> Unit,
    val back: () -> Unit,
    val replace: (Screen) -> Unit,
    val tab: (NavTab) -> Unit,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent { McdTheme { App() } }
    }
}

@Composable
private fun App() {
    val stack = remember { mutableStateListOf<Screen>(if (Prefs.playIntro) Screen.Intro else Screen.Home) }
    // removeAt instead of removeLast: removeLast crashes on older Android versions.
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val nav = remember {
        Nav(
            push = { stack.add(it) },
            back = { pop() },
            replace = { stack[stack.lastIndex] = it },
            tab = { t ->
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack[0] = Screen.Home
                val s = when (t) {
                    NavTab.Home -> null
                    NavTab.Search -> Screen.Search
                    NavTab.Library -> Screen.Library
                    NavTab.Sports -> Screen.Sports
                    NavTab.Live -> Screen.Live
                    NavTab.Services -> Screen.Services
                    NavTab.Noise -> Screen.Noise
                    NavTab.Calendar -> Screen.Calendar
                    NavTab.Settings -> Screen.Settings
                }
                if (s != null) stack.add(s)
            },
        )
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = stack.last()) {
        Screen.Intro -> IntroScreen(onDone = { nav.replace(Screen.Home) })
        Screen.Home -> HomeScreen(nav)
        Screen.Search -> SearchScreen(nav)
        Screen.Library -> LibraryScreen(nav)
        Screen.Sports -> SportsScreen(nav)
        Screen.Live -> LiveTvScreen(nav)
        Screen.Services -> ServicesScreen(nav)
        is Screen.ServiceGrid -> ServiceGridScreen(nav, s.service)
        Screen.Noise -> NoiseScreen(nav)
        Screen.NoiseRun -> NoiseRunScreen(nav)
        Screen.Calendar -> CalendarScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        Screen.PhoneSetup -> PhoneSetupScreen(nav)
        Screen.RdConnect -> RdConnectScreen(nav)
        is Screen.Detail -> DetailScreen(nav, s.type, s.id)
        is Screen.Sources -> SourcesScreen(nav, s.meta, s.imdbId, s.autoPlay)
        is Screen.Web -> WebScreen(s.url)
        is Screen.Player -> PlayerScreen(url = s.url, title = s.title, meta = s.meta)
    }
}
