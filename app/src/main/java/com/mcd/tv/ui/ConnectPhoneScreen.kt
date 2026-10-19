package com.mcd.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.data.HouseCloud
import kotlinx.coroutines.delay

/**
 * "Connect a phone": an 8-digit code the phone types in Jarvis > Settings > House and devices > Connect with a code. The phone then
 * gets the house (settings, every profile's lists and Live TV favorites) from the Jarvis relay, so it stays in
 * sync with every TV, whichever ones are on. A new code every 15 minutes; each code works once.
 */
@Composable
fun ConnectPhoneScreen(nav: Nav) {
    var round by remember { mutableIntStateOf(0) }
    val code = remember(round) { HouseCloud.newCode() }
    // 0 = saving, 1 = ready, 2 = couldn't reach the relay
    var state by remember(round) { mutableIntStateOf(0) }
    var left by remember(round) { mutableStateOf(15) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    LaunchedEffect(round) {
        state = if (HouseCloud.offerCode(code)) 1 else 2
        if (state == 1) {
            while (left > 0) { delay(60_000); left-- }
            round++
        }
    }
    Column(
        Modifier.fillMaxSize().hudBackground().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        McdLogo()
        Text("CONNECT A PHONE", style = broadcastStyle(32.sp))
        Text(
            "So a phone has the same profiles, lists, Live TV favorites and settings as every TV in the house, whichever TVs are on:",
            color = McdColors.White, fontSize = 20.sp,
        )
        Text("1.  On the phone, open the Jarvis app and go to Settings > House and devices > Connect with a code.", color = McdColors.White, fontSize = 20.sp)
        Text("2.  Type this code:", color = McdColors.White, fontSize = 20.sp)
        Text(
            when (state) {
                0 -> "…"
                2 -> "No connection"
                else -> code.chunked(4).joinToString("  ")
            },
            style = broadcastStyle(64.sp, if (state == 1) McdColors.Accent else McdColors.Muted),
        )
        Text(
            when (state) {
                0 -> "Getting a code…"
                2 -> "This TV couldn't reach the Jarvis relay. Check the internet connection, then try again."
                else -> "Works once, for the next $left minute${if (left == 1) "" else "s"}."
            },
            color = McdColors.Muted, fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Done", { nav.back() }, Modifier.focusRequester(first))
            ActionButton(if (state == 2) "Try again" else "New code", { round++ })
        }
    }
}
