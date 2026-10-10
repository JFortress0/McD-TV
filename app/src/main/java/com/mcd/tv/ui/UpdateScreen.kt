package com.mcd.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.data.Updater
import kotlinx.coroutines.launch

/** "Update Jarvis": shows the new version and installs it with one press. Opens by itself when one is out. */
@Composable
fun UpdateScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // Re-read when coming back from the "install unknown apps" switch.
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    var noSwitchScreen by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION") // same LocalLifecycleOwner as PlayerScreen (always on the classpath with this BOM)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { canInstall = Updater.canInstall(ctx) }
    }
    LaunchedEffect(Unit) {
        Updater.check(ctx, force = true)
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val r = Updater.available

    Column(Modifier.fillMaxSize().hudBackground().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        McdLogo()
        Text("UPDATE JARVIS", style = broadcastStyle(36.sp))
        Text("Installed: version ${Updater.installedName(ctx)}", fontSize = 20.sp, color = McdColors.White)
        if (r == null) {
            Text(if (Updater.status.isNotBlank()) Updater.status else "You have the latest version.", fontSize = 20.sp, color = McdColors.Muted)
            ActionButton("Back", { nav.back() }, Modifier.focusRequester(first), primary = true)
            return@Column
        }
        Text("New: version ${r.name}" + (if (r.bytes > 0) "  (${r.bytes / 1_048_576} MB)" else ""), fontSize = 20.sp, color = McdColors.Accent)
        if (!canInstall) {
            Text(
                "One time only: let Jarvis install its updates." +
                    if (noSwitchScreen) {
                        "\nOn this TV: Settings > My Fire TV > Developer options > Install unknown apps > Jarvis: On. Then come back here."
                    } else {
                        "\nPress Allow, turn Jarvis on, then press Back."
                    },
                fontSize = 18.sp, color = McdColors.White,
            )
        } else {
            Text("Press Update now, then Install when the TV asks. Your settings and profiles stay.", fontSize = 18.sp, color = McdColors.White)
        }
        if (Updater.status.isNotBlank()) Text(Updater.status, fontSize = 18.sp, color = McdColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!canInstall && !noSwitchScreen) {
                ActionButton("Allow", { if (!Updater.openInstallPermission(ctx)) noSwitchScreen = true }, Modifier.focusRequester(first), primary = true)
            } else {
                ActionButton(
                    if (Updater.busy) "Downloading…" else "Update now",
                    { if (!Updater.busy) scope.launch { Updater.downloadAndInstall(ctx, r) } },
                    Modifier.focusRequester(first),
                    primary = true,
                )
            }
            ActionButton("Later", { nav.back() })
        }
    }
}
