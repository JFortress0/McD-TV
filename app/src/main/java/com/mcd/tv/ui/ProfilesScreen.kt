package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.sync.ProfileSync

/** Start-up switch for the profile picker. The automated QA launch ("--ez qa true") skips it. */
object ProfileStart {
    @Volatile var skip: Boolean = false
}

/**
 * "Who's watching?": three large HUD cards (Dad, Mom, Kids by default). Picking one makes it the active
 * profile (its own history, My List, favorites and Live TV favorites) and calls [onPicked].
 */
@Composable
fun ProfilePickerScreen(onPicked: () -> Unit) {
    val first = remember { FocusRequester() }
    val active = remember { Prefs.activeProfile }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().hudBackground(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        McdLogo(scale = 0.9f)
        Spacer(Modifier.height(22.dp))
        Text("WHO'S WATCHING?", style = broadcastStyle(30.sp))
        Spacer(Modifier.height(6.dp))
        // Version on the first screen, so it's clear at a glance whether this TV has the latest update.
        Text("Jarvis v${com.mcd.tv.BuildConfig.VERSION_NAME}", color = McdColors.Muted, fontSize = 14.sp)
        val syncLine = remember { ProfileSync.statusLine() }
        if (syncLine.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(syncLine, color = McdColors.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Prefs.PROFILE_IDS.forEachIndexed { i, id ->
                ProfileCard(
                    name = Prefs.profileName(id),
                    kids = id == Prefs.KIDS_PROFILE,
                    current = id == active,
                    onClick = { Prefs.activeProfile = id; onPicked() },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(name: String, kids: Boolean, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HudCard(onClick = onClick, modifier = modifier.width(220.dp).height(250.dp), focusedScale = 1.08f) { focused ->
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ring = if (focused) McdColors.AccentBright else if (kids) McdColors.Amber else McdColors.Accent
            Box(
                Modifier.size(110.dp)
                    .background(ring.copy(alpha = if (focused) 0.18f else 0.08f), CircleShape)
                    .border(if (focused) 3.dp else 2.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.trim().take(1).uppercase().ifBlank { "?" }, style = broadcastStyle(48.sp, ring))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                name.uppercase(), style = broadcastStyle(20.sp, if (focused) McdColors.AccentBright else McdColors.White),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    current -> "Watching now"
                    kids -> "Family picks"
                    else -> " "
                },
                color = McdColors.Muted, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}

/** Settings: rename one profile. Select the field, press OK to type, then Save (or Done on the keyboard). */
@Composable
fun ProfileNameEditor(id: String, onSaved: (String) -> Unit) {
    var text by remember { mutableStateOf(Prefs.profileName(id)) }
    fun save() {
        Prefs.setProfileName(id, text.trim())
        text = Prefs.profileName(id)
        onSaved(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (id == Prefs.KIDS_PROFILE) "Kids profile" else "Profile ${Prefs.PROFILE_IDS.indexOf(id) + 1}", color = McdColors.Muted, fontSize = 13.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(20) },
            singleLine = true,
            textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
            cursorBrush = SolidColor(McdColors.Accent),
            // No keyboard just for moving focus onto the field; OK opens it.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.width(200.dp).background(McdColors.Card, HudShape)
                .border(1.dp, McdColors.Accent.copy(alpha = 0.7f), HudShape).padding(12.dp),
        )
        ActionButton("Save", { save() })
    }
}
