package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.mcd.tv.data.Prefs

@Composable
fun SettingsScreen(
    onPlay: (url: String, title: String) -> Unit,
    onReplayIntro: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(Prefs.customUrl(context)) }
    var editing by remember { mutableStateOf(false) }
    var introOn by remember { mutableStateOf(Prefs.playIntro(context)) }

    val editButton = remember { FocusRequester() }
    val field = remember { FocusRequester() }

    // Editing on: jump into the text box (the TV keyboard opens).
    // Editing off (and on first open): land on the "Edit URL" button.
    LaunchedEffect(editing) {
        withFrameNanos { }
        runCatching { if (editing) field.requestFocus() else editButton.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            McdLogo(scale = 0.7f)
            Spacer(modifier = Modifier.padding(start = 20.dp))
            Text(text = "SETTINGS", style = broadcastStyle(34.sp))
        }
        Spacer(modifier = Modifier.height(8.dp))

        // ---------- Custom stream ----------
        RailHeader("Custom Stream URL")
        Text(
            text = "Paste any direct stream link (HLS .m3u8, DASH .mpd, MP4, MKV). It shows on the home screen as My Stream.",
            color = McdColors.Muted,
            fontSize = 15.sp,
        )

        if (editing) {
            BasicTextField(
                value = url,
                onValueChange = {
                    url = it
                    Prefs.setCustomUrl(context, it) // save as you type; nothing lost if BACK is pressed
                },
                singleLine = true,
                textStyle = TextStyle(color = McdColors.White, fontSize = 20.sp),
                cursorBrush = SolidColor(McdColors.Red),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .background(McdColors.Card, RoundedCornerShape(8.dp))
                    .border(2.dp, McdColors.Red, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            )
        } else {
            Text(
                text = if (url.isBlank()) "No URL set" else url,
                color = if (url.isBlank()) McdColors.Muted else McdColors.White,
                fontSize = 18.sp,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(McdColors.Card, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { editing = !editing },
                modifier = Modifier.focusRequester(editButton),
            ) { Text(if (editing) "Done" else "Edit URL") }
            Button(
                onClick = { if (url.isNotBlank()) onPlay(url.trim(), "My Stream") },
                enabled = url.isNotBlank(),
            ) { Text("Play URL") }
            OutlinedButton(
                onClick = {
                    url = ""
                    Prefs.setCustomUrl(context, "")
                },
                enabled = url.isNotBlank(),
            ) { Text("Clear") }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---------- Intro ----------
        RailHeader("Intro")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = {
                introOn = !introOn
                Prefs.setPlayIntro(context, introOn)
            }) { Text(if (introOn) "Play intro on launch: ON" else "Play intro on launch: OFF") }
            OutlinedButton(onClick = onReplayIntro) { Text("Replay intro now") }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---------- Sources (later phases) ----------
        RailHeader("Sources")
        Text(
            text = "McD TV ships with no content sources. Real-Debrid (Phase 3), Live TV playlists (Phase 4) " +
                "and Stremio addons (Phase 5) get added here as they are built.",
            color = McdColors.Muted,
            fontSize = 15.sp,
        )

        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onBack) { Text("Back to Home") }
    }
}
