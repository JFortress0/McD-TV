package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Addons
import com.mcd.tv.data.LocalWeb
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.RealDebrid
import kotlinx.coroutines.delay

private const val PLAYER_TEST_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

@Composable
fun SettingsScreen(nav: Nav) {
    var usOnly by remember { mutableStateOf(Prefs.usOnly) }
    var origin by remember { mutableStateOf(Prefs.origin) }
    var slow by remember { mutableStateOf(Prefs.slowConnection) }
    var intro by remember { mutableStateOf(Prefs.playIntro) }
    var maxMovie by remember { mutableIntStateOf(Prefs.maxMovieGb) }
    var maxEpisode by remember { mutableIntStateOf(Prefs.maxEpisodeGb) }
    val rd by rememberLoad(Prefs.rdAccessToken) { if (RealDebrid.connected) RealDebrid.accountSummary() else "Not connected" }
    val addons by rememberLoad { Prefs.addonUrls.map { u -> runCatching { Addons.manifest(u).name }.getOrDefault(u.take(40)) } }

    TabPage(nav, NavTab.Settings) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Jarvis Control")
                Text(
                    "Paste your TMDB key, addon links, playlist and stream links from your phone instead of typing with the remote.",
                    color = McdColors.Muted, fontSize = 16.sp,
                )
                ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Profiles")
                var watching by remember { mutableStateOf(Prefs.activeProfileName) }
                Text(
                    "Watching as $watching. Each profile has its own history, Continue Watching, My List, favorites and Live TV favorites. " +
                        "Settings, keys, addons and the playlist are shared by the whole TV.",
                    color = McdColors.White, fontSize = 16.sp,
                )
                ActionButton("Switch profile", { nav.push(Screen.Profiles) }, primary = true)
                Text("Rename: select a name, press OK to type, then Save.", color = McdColors.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Prefs.PROFILE_IDS.forEach { id ->
                        ProfileNameEditor(id) { watching = Prefs.activeProfileName }
                    }
                }
                Text(
                    "Taste: pick the genres and topics each person likes or wants to skip. Jarvis also learns from what they watch.",
                    color = McdColors.Muted, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Prefs.PROFILE_IDS.forEach { id ->
                        ActionButton("${Prefs.profileName(id)}'s taste", { nav.push(Screen.Taste(id)) })
                    }
                }
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Account")
                Text(
                    if (com.mcd.tv.data.Account.signedIn) "Signed in as ${Prefs.accountName}" else "Not signed in. Optional: sign in to sync across TVs.",
                    color = McdColors.White, fontSize = 16.sp,
                )
                ActionButton("Account", { nav.push(Screen.AccountPage) })
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Real-Debrid")
                Text(
                    when (val r = rd) { is Load.Ok -> r.value; is Load.Err -> "Error: ${r.message}"; else -> "Checking…" },
                    color = McdColors.White, fontSize = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(if (RealDebrid.connected) "Reconnect Real-Debrid" else "Connect Real-Debrid", { nav.push(Screen.RdConnect) }, primary = !RealDebrid.connected)
                    if (RealDebrid.connected) ActionButton("Disconnect", { Prefs.clearRealDebrid(); nav.replace(Screen.Settings) })
                }
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Sources")
                Text(
                    "TMDB: " + (if (Prefs.tmdbKey.isNotBlank()) "connected" else "add a key on the Control page") +
                        "\nAddons: " + when (val a = addons) { is Load.Ok -> a.value.joinToString(", ").ifBlank { "none" }; else -> "…" } +
                        "\nLive TV playlist: " + (if (Prefs.m3uUrl.isNotBlank()) "set" else "none") +
                        "\nWebsites: " + Prefs.websites.joinToString(", ") { it.first }.ifBlank { "none" } +
                        "\nControl page link: " + com.mcd.tv.data.Relay.status,
                    color = McdColors.White, fontSize = 16.sp,
                )
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Preferences")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Titles: ${origin.label}", {
                        val all = com.mcd.tv.data.OriginFilter.entries
                        origin = all[(all.indexOf(origin) + 1) % all.size]
                        Prefs.origin = origin
                    }, primary = true)
                    ActionButton("Replay intro", { nav.push(Screen.Intro) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("US release dates: ${if (usOnly) "ON" else "OFF"}", { usOnly = !usOnly; Prefs.usOnly = usOnly })
                    ActionButton("Slow connection: ${if (slow) "ON" else "OFF"}", { slow = !slow; Prefs.slowConnection = slow })
                    ActionButton("Intro on launch: ${if (intro) "ON" else "OFF"}", { intro = !intro; Prefs.playIntro = intro })
                }
                // Source list hides bigger files (a "Show hidden" row reveals them). 0 = no limit.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Movie size limit: ${sizeLimitLabel(maxMovie)}", {
                        maxMovie = nextLimit(maxMovie, listOf(20, 40, 60, 0))
                        Prefs.maxMovieGb = maxMovie
                    })
                    ActionButton("Episode size limit: ${sizeLimitLabel(maxEpisode)}", {
                        maxEpisode = nextLimit(maxEpisode, listOf(4, 8, 12, 20, 0))
                        Prefs.maxEpisodeGb = maxEpisode
                    })
                }
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("App updates")
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val up = com.mcd.tv.data.Updater.available
                Text(
                    "Version ${com.mcd.tv.data.Updater.installedName(ctx)}" + (if (up != null) ". Version ${up.name} is ready to install." else ". Jarvis checks for updates when it starts."),
                    color = McdColors.White, fontSize = 16.sp,
                )
                ActionButton(if (up != null) "Update now" else "Check for updates", { nav.push(Screen.Update) }, primary = up != null)
            }

            HudPanel(Modifier.fillMaxWidth()) {
                RailHeader("Player test")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Play test stream", { nav.push(Screen.Player(PLAYER_TEST_URL, "Player test")) })
                    if (Prefs.customUrl.isNotBlank()) ActionButton("Play My Stream", { nav.push(Screen.Player(Prefs.customUrl, "My Stream")) })
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun sizeLimitLabel(gb: Int) = if (gb <= 0) "No limit" else "$gb GB"

/** The next value in [cycle] after [current] (the first one if [current] is not in the list). */
private fun nextLimit(current: Int, cycle: List<Int>): Int = cycle[(cycle.indexOf(current) + 1) % cycle.size]

/** Setup from a phone or computer: QR code for the internet Control page, plus the home-network page. */
@Composable
fun PhoneSetupScreen(nav: Nav) {
    // The home-network setup page only runs while this screen is open.
    var webRunning by remember { mutableStateOf(LocalWeb.running) }
    DisposableEffect(Unit) {
        com.mcd.tv.data.LocalWeb.start()
        webRunning = LocalWeb.running
        onDispose { com.mcd.tv.data.LocalWeb.stop() }
    }
    com.mcd.tv.data.Relay.start()
    val addrs = remember { LocalWeb.addresses() }
    var link by remember { mutableStateOf(com.mcd.tv.data.Relay.controlUrl()) }
    // Keep the TV awake (no screensaver) while this screen is open.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Row(
        Modifier.fillMaxSize().hudBackground().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            McdLogo()
            Text("JARVIS CONTROL", style = broadcastStyle(36.sp))
            Text("1.  Point your phone's camera at the code and tap the link.", fontSize = 20.sp, color = McdColors.White)
            Text("2.  Add addons, websites and keys, then tap Save.", fontSize = 20.sp, color = McdColors.White)
            Text(
                "Works over the internet from any network, at home or away. To use a computer, tap Copy link on your phone's Control page and open it there.",
                fontSize = 16.sp, color = McdColors.Muted,
            )
            Text(com.mcd.tv.data.Relay.status, fontSize = 16.sp, color = McdColors.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("New link (disconnect old devices)", {
                    com.mcd.tv.data.Relay.newLink()
                    link = com.mcd.tv.data.Relay.controlUrl()
                })
            }
            Text(
                "Home Wi-Fi page: ${addrs.first()}   •   ${if (webRunning) "running" else "stopped"}   •   browser requests: ${LocalWeb.requests}",
                fontSize = 13.sp, color = McdColors.Muted,
            )
            Text(LocalWeb.error?.let { "Home page error: $it" } ?: LocalWeb.lastMessage, fontSize = 13.sp, color = McdColors.Muted)
        }
        HudPanel(padding = 16.dp) {
            QrCode(link, 280.dp)
            Text(
                "SCAN TO OPEN JARVIS CONTROL", style = hudLabelStyle(9.sp, McdColors.Accent),
                modifier = Modifier.padding(top = 2.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
            )
        }
    }
}

/** Real-Debrid device sign-in: show a code, user approves at real-debrid.com/device. */
@Composable
fun RdConnectScreen(nav: Nav) {
    var code by remember { mutableStateOf<RealDebrid.DeviceCode?>(null) }
    var status by remember { mutableStateOf("Getting a code…") }
    LaunchedEffect(Unit) {
        try {
            val c = RealDebrid.startDeviceLogin()
            code = c
            status = "Waiting for approval…"
            val until = System.currentTimeMillis() + c.expiresSec * 1000L
            while (System.currentTimeMillis() < until) {
                delay(c.intervalSec * 1000L)
                if (runCatching { RealDebrid.pollDeviceLogin(c) }.getOrDefault(false)) {
                    status = "Connected: " + runCatching { RealDebrid.accountSummary() }.getOrDefault("OK")
                    delay(2500)
                    nav.back()
                    return@LaunchedEffect
                }
            }
            status = "Code expired. Press BACK and try again."
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = "Error: ${e.message}"
        }
    }
    Column(Modifier.fillMaxSize().hudBackground().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McdLogo()
        Text("CONNECT REAL-DEBRID", style = broadcastStyle(36.sp))
        Text("1.  On your phone or computer, open  real-debrid.com/device", fontSize = 20.sp, color = McdColors.White)
        Text("2.  Sign in to Real-Debrid and enter this code:", fontSize = 20.sp, color = McdColors.White)
        Text(
            code?.userCode ?: "……",
            style = hudDisplayStyle(44.sp, McdColors.Ink, 6.sp),
            modifier = Modifier.hudGlow(true, HudShape).background(McdColors.Accent, HudShape).padding(horizontal = 28.dp, vertical = 8.dp),
        )
        Text(status, fontSize = 18.sp, color = McdColors.Muted)
    }
}
