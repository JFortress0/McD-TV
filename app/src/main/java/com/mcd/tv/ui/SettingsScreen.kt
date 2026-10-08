package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val rd by rememberLoad(Prefs.rdAccessToken) { if (RealDebrid.connected) RealDebrid.accountSummary() else "Not connected" }
    val addons by rememberLoad { Prefs.addonUrls.map { u -> runCatching { Addons.manifest(u).name }.getOrDefault(u.take(40)) } }

    TabPage(nav, NavTab.Settings) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RailHeader("Phone setup")
            Text(
                "Paste your TMDB key, addon links, playlist and stream links from your phone instead of typing with the remote.",
                color = McdColors.Muted, fontSize = 15.sp,
            )
            ActionButton("Phone & Computer Setup", { nav.push(Screen.PhoneSetup) }, primary = true)

            Spacer(Modifier.height(6.dp))
            RailHeader("Account")
            Text(
                if (com.mcd.tv.data.Account.signedIn) "Signed in as ${Prefs.accountName}" else "Not signed in. Optional: sign in to sync across TVs.",
                color = McdColors.White, fontSize = 16.sp,
            )
            ActionButton("Account", { nav.push(Screen.AccountPage) })

            Spacer(Modifier.height(6.dp))
            RailHeader("Real-Debrid")
            Text(
                when (val r = rd) { is Load.Ok -> r.value; is Load.Err -> "Error: ${r.message}"; else -> "Checking…" },
                color = McdColors.White, fontSize = 16.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (RealDebrid.connected) "Reconnect Real-Debrid" else "Connect Real-Debrid", { nav.push(Screen.RdConnect) }, primary = !RealDebrid.connected)
                if (RealDebrid.connected) ActionButton("Disconnect", { Prefs.clearRealDebrid(); nav.replace(Screen.Settings) })
            }

            Spacer(Modifier.height(6.dp))
            RailHeader("Sources")
            Text(
                "TMDB: " + (if (Prefs.tmdbKey.isNotBlank()) "connected" else "add a key in Phone setup") +
                    "\nAddons: " + when (val a = addons) { is Load.Ok -> a.value.joinToString(", ").ifBlank { "none" }; else -> "…" } +
                    "\nLive TV playlist: " + (if (Prefs.m3uUrl.isNotBlank()) "set" else "none") +
                    "\nWebsites: " + Prefs.websites.joinToString(", ") { it.first }.ifBlank { "none" } +
                    "\nControl page link: " + com.mcd.tv.data.Relay.status,
                color = McdColors.White, fontSize = 15.sp,
            )

            Spacer(Modifier.height(6.dp))
            RailHeader("Preferences")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Titles: ${origin.label}", {
                    val all = com.mcd.tv.data.OriginFilter.entries
                    origin = all[(all.indexOf(origin) + 1) % all.size]
                    Prefs.origin = origin
                }, primary = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("US release dates: ${if (usOnly) "ON" else "OFF"}", { usOnly = !usOnly; Prefs.usOnly = usOnly })
                ActionButton("Slow connection: ${if (slow) "ON" else "OFF"}", { slow = !slow; Prefs.slowConnection = slow })
                ActionButton("Intro on launch: ${if (intro) "ON" else "OFF"}", { intro = !intro; Prefs.playIntro = intro })
                ActionButton("Replay intro", { nav.push(Screen.Intro) })
            }

            Spacer(Modifier.height(6.dp))
            RailHeader("Player test")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Play test stream", { nav.push(Screen.Player(PLAYER_TEST_URL, "Player test")) })
                if (Prefs.customUrl.isNotBlank()) ActionButton("Play My Stream", { nav.push(Screen.Player(Prefs.customUrl, "My Stream")) })
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

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
        Modifier.fillMaxSize().background(ScreenBackground).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            McdLogo()
            Text("MCD TV CONTROL", style = broadcastStyle(36.sp))
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
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            QrCode(link, 300.dp)
            Text("Scan to open McD TV Control", fontSize = 14.sp, color = McdColors.Muted, modifier = Modifier.padding(top = 10.dp))
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
    Column(Modifier.fillMaxSize().background(ScreenBackground).padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McdLogo()
        Text("CONNECT REAL-DEBRID", style = broadcastStyle(36.sp))
        Text("1.  On your phone or computer, open  real-debrid.com/device", fontSize = 20.sp, color = McdColors.White)
        Text("2.  Sign in to Real-Debrid and enter this code:", fontSize = 20.sp, color = McdColors.White)
        Text(
            code?.userCode ?: "……",
            style = broadcastStyle(64.sp),
            modifier = Modifier.background(McdColors.Red, RoundedCornerShape(8.dp)).padding(horizontal = 28.dp, vertical = 8.dp),
        )
        Text(status, fontSize = 18.sp, color = McdColors.Muted)
    }
}
