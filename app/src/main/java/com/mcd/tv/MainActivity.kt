package com.mcd.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.lifecycleScope
import com.mcd.tv.data.Account
import com.mcd.tv.data.LocalWeb
import com.mcd.tv.data.Relay
import com.mcd.tv.data.RemoteNav
import com.mcd.tv.data.PlayMeta
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Service
import com.mcd.tv.player.PlayerScreen
import com.mcd.tv.ui.AccountScreen
import com.mcd.tv.ui.AskJarvisScreen
import com.mcd.tv.ui.BrowseGridScreen
import com.mcd.tv.ui.BrowseScreen
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
import com.mcd.tv.ui.PersonScreen
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
    /** "Who's watching?" profile picker (after the intro, and from the profile chip in the top bar). */
    data object Profiles : Screen
    data object Home : Screen
    data object Search : Screen
    /** Ask Jarvis: describe a title in plain words. [initial] is asked right away when not blank. */
    data class AskJarvis(val initial: String = "") : Screen
    data object Library : Screen
    /** Catalog menu: Trending, Top Rated, New Releases, By Year, By Language, Genres, Collections, services, Sports, Noise. */
    data object Browse : Screen
    /**
     * A full poster grid with "See more" paging. kind: trending, popular, top, new, year, lang, collections, collection.
     * param: kind-specific (type "movie"/"tv" for popular/top, a language code, a collection id…). title: page heading.
     */
    data class BrowseGrid(val kind: String, val param: String = "", val title: String = "") : Screen
    data object Sports : Screen
    data object Live : Screen
    /** Live TV multiview: 1, 2 or 4 playlist channels at once. */
    data object Multiview : Screen
    /** Every game this week for a league, with the playlist channels showing each one. */
    data class Games(val league: com.mcd.tv.data.League = com.mcd.tv.data.League.NFL) : Screen
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
    data class Person(val id: Int) : Screen
    /** openSources = go straight to the source list once the title loads (Services catalog). */
    data class Detail(val type: String, val id: Int, val openSources: Boolean = false) : Screen
    /** autoPlay = the Play button: pick the best source and start immediately. */
    data class Sources(val meta: PlayMeta, val imdbId: String, val autoPlay: Boolean) : Screen
    data class Web(val url: String, val name: String) : Screen
    data class LiveChannel(val item: com.mcd.tv.data.LiveItem) : Screen
    /** A playlist channel: [index] into com.mcd.tv.ui.LiveSession.channels (channel up/down moves through that list). */
    data class LivePlay(val index: Int) : Screen
    /** imdbId lets a TV episode roll into the next one when it ends. */
    data class Player(
        val url: String,
        val title: String,
        val meta: PlayMeta? = null,
        val imdbId: String? = null,
        /** Extra HTTP headers some streams need (Referer, Origin, User-Agent), e.g. video found by the browser. */
        val headers: Map<String, String> = emptyMap(),
    ) : Screen
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
        if (intent?.getBooleanExtra("qa_mode", false) == true) { LocalWeb.qaMode = true; Prefs.qaMode = true }
        // Automated QA launch: no profile picker, the last used profile is used.
        com.mcd.tv.ui.ProfileStart.skip = intent?.hasExtra("qa") == true
        // The home-network setup page (port 8642) normally runs only while Phone & Computer Setup is open
        // (PhoneSetupScreen starts and stops it). The automated QA run talks to it directly, so a launch
        // with a QA extra ("--ez qa true", or "--es screen ...") starts it here too.
        if (intent?.hasExtra("qa") == true || !intent?.getStringExtra("screen").isNullOrEmpty()) LocalWeb.start()
        Relay.start() // internet setup link (works from any network)
        // Signed in to a Jarvis account? Pick up changes made on other TVs.
        lifecycleScope.launch {
            // Pick up changes made on the web page or another TV: now, then every 2 minutes.
            while (true) {
                runCatching { Account.pull() }
                kotlinx.coroutines.delay(120_000)
            }
        }
        // QA hook: "--es screen settings" opens a screen directly (used by the automated emulator test).
        val screenExtra = intent?.getStringExtra("screen") ?: ""
        // "--es screen ask --es ask 'that movie where…'" opens Ask Jarvis and asks right away.
        val askExtra = intent?.getStringExtra("ask") ?: ""
        val startScreen: Screen? = if (screenExtra.startsWith("detail:")) {
            // "detail:movie:603"
            screenExtra.split(":").let { p -> p.getOrNull(2)?.toIntOrNull()?.let { Screen.Detail(p[1], it) } }
        } else if (screenExtra.startsWith("grid:")) {
            // "grid:top:movie", "grid:lang:ko", "grid:collection:2344"
            screenExtra.split(":").let { p -> Screen.BrowseGrid(p.getOrElse(1) { "trending" }, p.getOrElse(2) { "" }) }
        } else if (screenExtra.startsWith("person:")) {
            screenExtra.substringAfter(":").toIntOrNull()?.let { Screen.Person(it) }
        } else when (screenExtra) {
            "settings" -> Screen.Settings
            "phone" -> Screen.PhoneSetup
            "genres" -> Screen.Genres
            "sports" -> Screen.Sports
            "profiles" -> Screen.Profiles
            "noise" -> Screen.Noise
            "library" -> Screen.Library
            "browse" -> Screen.Browse
            "live" -> Screen.Live
            "multiview" -> Screen.Multiview
            "games" -> Screen.Games(com.mcd.tv.data.League.NFL)
            "services" -> Screen.Services
            "search" -> Screen.Search
            "ask" -> Screen.AskJarvis(askExtra)
            else -> if (askExtra.isNotBlank()) Screen.AskJarvis(askExtra) else null
        }
        setContent { McdTheme { App(startScreen) } }
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
            !com.mcd.tv.ui.ProfileStart.skip -> mutableStateListOf<Screen>(Screen.Profiles)
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
                    NavTab.Browse -> Screen.Browse
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

    // "Open on TV" / "Play on TV" from the Jarvis web app. In the background this still runs,
    // so the new screen is simply there when the app comes back to the front.
    LaunchedEffect(Unit) {
        Relay.navRequests.collect { r ->
            if (stack.last() == Screen.Intro) stack[stack.lastIndex] = Screen.Home
            when (r) {
                is RemoteNav.Open -> {
                    val target = Screen.Detail(r.type, r.id)
                    if (stack.last() != target) stack.add(target)
                }
                is RemoteNav.Play -> {
                    val detail = Screen.Detail(r.meta.type, r.meta.tmdbId)
                    if (stack.last() != detail) stack.add(detail)
                    stack.add(Screen.Sources(r.meta, r.imdbId, autoPlay = true))
                }
            }
        }
    }

    // Each back-stack entry gets its own saved state (scroll positions, typed search, picked genre…),
    // keyed by its position and screen: Detail -> Detail starts fresh, and Back restores the page as it was.
    val holder = rememberSaveableStateHolder()
    val entryKey = "${stack.lastIndex}:${stack.last()}"
    val shownKeys = remember { mutableSetOf<String>() }
    SideEffect {
        // Drop saved state for entries that left the stack (popped, replaced, or cleared by a tab switch).
        shownKeys.add(entryKey)
        val live = stack.mapIndexed { i, sc -> "$i:$sc" }.toSet()
        val gone = shownKeys.filter { it !in live }
        gone.forEach { holder.removeState(it) }
        shownKeys.removeAll(gone.toSet())
    }
    key(entryKey) {
        holder.SaveableStateProvider(entryKey) {
            ScreenContent(stack.last(), nav, isOnlyEntry = stack.size == 1)
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen, nav: Nav, isOnlyEntry: Boolean) {
    when (val s = screen) {
        // "Replay intro" pushes Intro on top of Settings: go back there instead of stacking a second Home.
        Screen.Intro -> IntroScreen(onDone = {
            if (isOnlyEntry) nav.replace(if (com.mcd.tv.ui.ProfileStart.skip) Screen.Home else Screen.Profiles) else nav.back()
        })
        // Picked at start: on to Home. Picked from the top bar or Settings: back there (Home reloads for the new profile).
        Screen.Profiles -> com.mcd.tv.ui.ProfilePickerScreen(onPicked = { if (isOnlyEntry) nav.replace(Screen.Home) else nav.back() })
        Screen.Home -> HomeScreen(nav)
        Screen.Search -> SearchScreen(nav)
        is Screen.AskJarvis -> AskJarvisScreen(nav, s.initial)
        Screen.Library -> LibraryScreen(nav)
        Screen.Browse -> BrowseScreen(nav)
        is Screen.BrowseGrid -> BrowseGridScreen(nav, s.kind, s.param, s.title)
        Screen.Sports -> SportsScreen(nav)
        Screen.Live -> LiveTvScreen(nav)
        Screen.Multiview -> com.mcd.tv.ui.MultiviewScreen(nav)
        is Screen.Games -> com.mcd.tv.ui.GamesScreen(nav, s.league)
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
        is Screen.Person -> PersonScreen(nav, s.id)
        is Screen.Detail -> DetailScreen(nav, s.type, s.id, s.openSources)
        is Screen.Sources -> SourcesScreen(nav, s.meta, s.imdbId, s.autoPlay)
        is Screen.LiveChannel -> com.mcd.tv.ui.LiveChannelScreen(nav, s.item)
        is Screen.LivePlay -> com.mcd.tv.ui.LivePlayerScreen(nav, s.index)
        is Screen.Web -> WebScreen(s.url) { url, headers, title -> nav.push(Screen.Player(url, title, headers = headers)) }
        is Screen.Player -> {
            val m = s.meta
            val next: (() -> Unit)? = if (m != null && m.type == "tv" && s.imdbId != null) {
                { nav.replace(Screen.Sources(m.copy(episode = m.episode + 1), s.imdbId, autoPlay = true)) }
            } else null
            androidx.compose.runtime.key(s.url) {
                if (m != null && next != null) {
                    // TV episode: "Up next" countdown card before rolling into the next episode.
                    com.mcd.tv.ui.UpNextPlayer(
                        url = s.url, title = s.title, meta = m, headers = s.headers,
                        onNext = next, onCancel = { nav.back() }, onSleep = { nav.back() },
                    )
                } else {
                    PlayerScreen(url = s.url, title = s.title, meta = m, onEnded = next, headers = s.headers, onSleep = { nav.back() })
                }
            }
        }
    }
}
