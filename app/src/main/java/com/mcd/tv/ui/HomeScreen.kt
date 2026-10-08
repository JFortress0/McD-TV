package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.TestStreams

/** Shown on the "Coming Soon" rail so the roadmap is visible on the TV. */
private data class Upcoming(val title: String, val subtitle: String, val phase: String)

private val upcoming = listOf(
    Upcoming("Continue Watching", "Pick up where you left off", "PHASE 2"),
    Upcoming("Network Files", "Your SMB / WebDAV media", "PHASE 2"),
    Upcoming("Real-Debrid", "Your debrid account", "PHASE 3"),
    Upcoming("Live TV", "Playlists, guide, channels", "PHASE 4"),
    Upcoming("Addons", "Stremio-compatible addons", "PHASE 5"),
)

@Composable
fun HomeScreen(
    onPlay: (url: String, title: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    // Read fresh each time Home appears, so a URL saved in Settings shows up immediately.
    val customUrl = remember { Prefs.customUrl(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: ""
    }
    val firstCard = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { } // wait one frame so the card is on screen before focusing it
        runCatching { firstCard.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            // TV "safe area": keep content away from screen edges that some TVs crop.
            .padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        // Top bar: logo left, settings right.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            McdLogo(scale = 0.9f)
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onOpenSettings) { Text("Settings") }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "WELCOME TO McD TV", style = broadcastStyle(34.sp))
        Text(
            text = "Your streams. Your rules.  •  v$version",
            color = McdColors.Muted,
            fontSize = 15.sp,
        )
        Spacer(modifier = Modifier.height(28.dp))

        RailHeader("Test Streams")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TestStreams.all.forEachIndexed { i, item ->
                TileCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    tag = "PLAY",
                    onClick = { onPlay(item.url, item.title) },
                    modifier = if (i == 0) Modifier.focusRequester(firstCard) else Modifier,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        RailHeader("My Stream")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (customUrl.isNotBlank()) {
                TileCard(
                    title = "My Stream",
                    subtitle = customUrl,
                    tag = "PLAY",
                    onClick = { onPlay(customUrl, "My Stream") },
                )
                TileCard(title = "Change URL", subtitle = "Opens Settings", onClick = onOpenSettings)
            } else {
                TileCard(
                    title = "Add a stream URL",
                    subtitle = "Paste any HLS, DASH, MP4 or MKV link in Settings",
                    onClick = onOpenSettings,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        RailHeader("Coming Soon")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            upcoming.forEach { u ->
                TileCard(title = u.title, subtitle = u.subtitle, tag = u.phase, dimmed = true, onClick = { })
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
