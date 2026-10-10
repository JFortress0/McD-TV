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
import com.mcd.tv.data.HouseJoin
import kotlinx.coroutines.delay

/**
 * "Join my other TVs": shows an 8-digit code to type on a phone (Jarvis > Settings > Add a TV). The phone
 * sends this house's link; the TV joins and gets the settings, Real-Debrid and every profile. Opens by
 * itself once on a new TV, and from Settings > Profiles.
 */
@Composable
fun JoinHouseScreen(nav: Nav) {
    val code = remember { HouseJoin.newCode() }
    val since = remember { System.currentTimeMillis() / 1000 - 5 }
    // 0 = waiting, 1 = joined, 2 = the code ran out
    var state by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    LaunchedEffect(code) {
        state = if (HouseJoin.await(code, since)) 1 else 2
        if (state == 1) {
            delay(6_000)
            nav.back()
        }
    }
    Column(
        Modifier.fillMaxSize().hudBackground().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        McdLogo()
        Text("JOIN YOUR OTHER TVS", style = broadcastStyle(32.sp))
        when (state) {
            1 -> {
                Text("Joined.", style = broadcastStyle(28.sp, McdColors.Accent))
                Text(
                    "Your settings, Real-Debrid and profiles (Continue Watching, My List, favorites, taste) are arriving now. " +
                        "Give it a minute.",
                    color = McdColors.White, fontSize = 20.sp,
                )
            }
            else -> {
                Text(
                    "So this TV has the same profiles, Continue Watching, settings and Real-Debrid as your other Jarvis TVs:",
                    color = McdColors.White, fontSize = 20.sp,
                )
                Text("1.  On your phone, open Jarvis and go to Settings > Add a TV.", color = McdColors.White, fontSize = 20.sp)
                Text("2.  Type this code:", color = McdColors.White, fontSize = 20.sp)
                Text(
                    if (state == 2) "Code expired" else code.chunked(4).joinToString("  "),
                    style = broadcastStyle(64.sp, if (state == 2) McdColors.Muted else McdColors.Accent),
                )
                Text(
                    if (state == 2) "Go back and open this screen again (Settings > Profiles > Join my other TVs) for a new code."
                    else "Waiting for your phone… The code works for 15 minutes.",
                    color = McdColors.Muted, fontSize = 16.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                if (state == 1) "Done" else "Skip: this is my only TV",
                { HouseJoin.markSeen(); nav.back() },
                Modifier.focusRequester(first),
            )
        }
    }
}
