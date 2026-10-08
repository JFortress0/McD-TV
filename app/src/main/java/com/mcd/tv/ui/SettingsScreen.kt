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
import com.mcd.tv.data.PhoneSetupServer
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.RealDebrid
import kotlinx.coroutines.delay

private const val PLAYER_TEST_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

@Composable
fun SettingsScreen(nav: Nav) {
    var usOnly by remember { mutableStateOf(Prefs.usOnly) }
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
            ActionButton("Open Phone Setup", { nav.push(Screen.PhoneSetup) }, primary = true)

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
                    "\nLive TV playlist: " + (if (Prefs.m3uUrl.isNotBlank()) "set" else "none"),
                color = McdColors.White, fontSize = 15.sp,
            )

            Spacer(Modifier.height(6.dp))
            RailHeader("Preferences")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("US only: ${if (usOnly) "ON" else "OFF"}", { usOnly = !usOnly; Prefs.usOnly = usOnly })
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

/** Shows the phone-setup web address while the screen is open. */
@Composable
fun PhoneSetupScreen(nav: Nav) {
    var last by remember { mutableStateOf("Waiting for your phone…") }
    val server = remember { PhoneSetupServer { msg -> last = msg } }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(server) {
        runCatching { server.start() }.onFailure { error = it.message }
        onDispose { server.stop() }
    }
    Column(Modifier.fillMaxSize().background(ScreenBackground).padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McdLogo()
        Text("PHONE SETUP", style = broadcastStyle(36.sp))
        Text("1.  Connect your phone to the same Wi-Fi as this TV.", fontSize = 20.sp, color = McdColors.White)
        Text("2.  Open this address in your phone's browser:", fontSize = 20.sp, color = McdColors.White)
        Text(
            server.address(),
            style = broadcastStyle(44.sp),
            modifier = Modifier.background(McdColors.Red, RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text("3.  Paste your keys and links, then tap Save to TV.", fontSize = 20.sp, color = McdColors.White)
        Text(error?.let { "Could not start: $it" } ?: last, fontSize = 18.sp, color = McdColors.Muted)
        Text("Press BACK when done. The page stops when you leave this screen.", fontSize = 14.sp, color = McdColors.Muted)
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
