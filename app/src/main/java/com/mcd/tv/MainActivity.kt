package com.mcd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.mcd.tv.data.Account
import com.mcd.tv.data.LocalWeb
import com.mcd.tv.data.Relay
import com.mcd.tv.data.PlayMeta
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Service
import com.mcd.tv.player.PlayerScreen
import com.mcd.tv.ui.AccountScreen
import com.mcd.tv.ui.GenresScreen
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
import com.mcd.tv.ui.RdCloudScreen
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
    data object Genres : Screen
    data object Settings : Screen
    data object PhoneSetup : Screen
    data object RdConnect : Screen
    data object AccountPage : Screen
    data object RdCloud : Screen
    data class Detail(val type: String, val id: Int) : Screen
    /** autoPlay = the Play button: pick the best source and start immediately. */
    data class Sources(val meta: PlayMeta, val imdbId: String, val autoPlay: Boolean) : Screen
    data class Web(val url: String, val name: String) : Screen
    /** imdbId lets a TV episode roll into the next one when it ends. */
    data class Player(val url: String, val title: String, val meta: PlayMeta? = null, val imdbId: String? = null) : Screen
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
        LocalWeb.start()
        Relay.start() // internet setup link (works from any network)
        // Signed in to a McD TV account? Pick up changes made on other TVs.
        lifecycleScope.launch {
            // Pick up changes made on the web page or another TV: now, then every 2 minutes.
            while (true) {
                runCatching { Account.pull() }
                kotlinx.coroutines.delay(120_000)
            }
        }
        // QA hook: "--es screen settings" opens a screen directly (used by the automated emulator test).
        val startScreen: Screen? = when (intent?.getStringExtra("screen")) {
            "settings" -> Screen.Settings
            "phone" -> Screen.PhoneSetup
            "genres" -> Screen.Genres
            "sports" -> Screen.Sports
            "noise" -> Screen.Noise
            "library" -> Screen.Library
            "live" -> Screen.Live
            "services" -> Screen.Services
            "search" -> Screen.Search
            else -> null
        }
        setContent { McdTheme { App(startScreen) } }
    }

    override fun onStart() {
        super.onStart()
        // The home-network setup page keeps running while McD TV is in memory,
        // including when the Fire TV screensaver comes on. start() does nothing if it already runs.
        LocalWeb.start()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button, TV off): save lists and history to the account.
        if (Account.signedIn) Thread { runCatching { runBlocking { Account.push() } } }.start()
    }
}

@Composable
private fun App(startScreen: Screen? = null) {
    val stack = remember {
        when {
            startScreen != null -> mutableStateListOf<Screen>(Screen.Home, startScreen)
            Prefs.playIntro -> mutableStateListOf<Screen>(Screen.Intro)
            else -> mutableStateListOf<Screen>(Screen.Home)
        }
    }
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
                    NavTab.Genres -> Screen.Genres
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
        Screen.Genres -> GenresScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        Screen.PhoneSetup -> PhoneSetupScreen(nav)
        Screen.RdConnect -> RdConnectScreen(nav)
        Screen.AccountPage -> AccountScreen(nav)
        Screen.RdCloud -> RdCloudScreen(nav)
        is Screen.Detail -> DetailScreen(nav, s.type, s.id)
        is Screen.Sources -> SourcesScreen(nav, s.meta, s.imdbId, s.autoPlay)
        is Screen.Web -> WebScreen(s.url)
        is Screen.Player -> {
            val m = s.meta
            val next: (() -> Unit)? = if (m != null && m.type == "tv" && s.imdbId != null) {
                { nav.replace(Screen.Sources(m.copy(episode = m.episode + 1), s.imdbId, autoPlay = true)) }
            } else null
            androidx.compose.runtime.key(s.url) { PlayerScreen(url = s.url, title = s.title, meta = m, onEnded = next) }
        }
    }
}
