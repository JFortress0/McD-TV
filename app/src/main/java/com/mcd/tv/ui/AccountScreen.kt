package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.data.Account
import com.mcd.tv.data.Prefs
import kotlinx.coroutines.launch

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false) {
    Column {
        Text(label, color = McdColors.Muted, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
            cursorBrush = SolidColor(McdColors.Red),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            // No keyboard just for moving focus onto the field; OK opens it.
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, showKeyboardOnFocus = false),
            modifier = Modifier.width(420.dp).background(McdColors.Card, HudShape)
                .border(1.dp, McdColors.Accent.copy(alpha = 0.7f), HudShape).padding(12.dp),
        )
    }
}

/** Sign in or create a Jarvis account on your own server. */
@Composable
fun AccountScreen(nav: Nav) {
    var signedIn by remember { mutableStateOf(Account.signedIn) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<Account.Pairing?>(null) }
    val scope = rememberCoroutineScope()

    fun run(label: String, block: suspend () -> Unit) {
        status = "$label…"
        scope.launch {
            runCatching { block() }
                .onSuccess { status = "$label: done"; signedIn = Account.signedIn }
                .onFailure { status = it.message ?: "$label failed" }
        }
    }

    TabPage(nav, NavTab.Settings) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("JARVIS ACCOUNT", style = broadcastStyle(32.sp))
            Text(
                "Server: " + Prefs.serverUrl.ifBlank { "not set. Add it in Phone setup." },
                color = McdColors.White, fontSize = 15.sp,
            )
            if (signedIn) {
                Text("Signed in as ${Prefs.accountName}. Your lists, history, addons and Real-Debrid link sync to this account.", color = McdColors.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Sync now", { run("Sync") { Account.push() } }, primary = true)
                    ActionButton("Sign out", { run("Sign out") { Account.logout() } })
                }
            } else if (pairing != null) {
                Text("On your computer or phone, open your Jarvis Control page, sign in, and enter this code under Link a TV:", color = McdColors.White, fontSize = 18.sp)
                Text(
                    pairing!!.code,
                    style = hudDisplayStyle(44.sp, McdColors.Ink, 6.sp),
                    modifier = Modifier.hudGlow(true, HudShape).background(McdColors.Accent, HudShape).padding(horizontal = 28.dp, vertical = 8.dp),
                )
                Text(Prefs.serverUrl, color = McdColors.Muted, fontSize = 16.sp)
                ActionButton("Cancel", { pairing = null })
            } else {
                ActionButton("Sign in with a code (easiest)", {
                    run("Getting a code") {
                        val p = Account.startPairing()
                        pairing = p
                        val until = System.currentTimeMillis() + p.expiresSec * 1000L
                        while (pairing == p && System.currentTimeMillis() < until) {
                            kotlinx.coroutines.delay(3000)
                            if (Account.pollPairing(p)) { pairing = null; break }
                        }
                        if (pairing == p) { pairing = null; throw IllegalStateException("Code expired. Try again.") }
                    }
                }, primary = true)
                Text("Or sign in, or create an account, here. Each person gets their own lists and Real-Debrid link.", color = McdColors.Muted)
                Field("Username", user, { user = it })
                Field("Password", pass, { pass = it }, password = true)
                Field("Invite code (only to create an account)", invite, { invite = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Sign in", { run("Sign in") { Account.login(user.trim(), pass) } }, primary = true)
                    ActionButton("Create account", { run("Create account") { Account.register(user.trim(), pass, invite.trim()) } })
                }
            }
            if (status.isNotBlank()) Text(status, color = McdColors.Red, fontSize = 15.sp)
        }
    }
}
