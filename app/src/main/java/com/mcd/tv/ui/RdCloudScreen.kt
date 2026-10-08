package com.mcd.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.RdCloud
import com.mcd.tv.data.RdItem
import com.mcd.tv.data.RealDebrid
import kotlinx.coroutines.launch

@Composable
private fun RowCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.NavyLight),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = broadcastStyle(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = McdColors.Muted, fontSize = 13.sp)
        }
    }
}

/**
 * Your Real-Debrid cloud: everything in your own RD account, playable here.
 * Add more from your phone (Phone setup > magnet link) or on real-debrid.com.
 */
@Composable
fun RdCloudScreen(nav: Nav) {
    var refresh by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<RdItem?>(null) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val items by rememberLoad(refresh) { RdCloud.list() }

    TabPage(nav, NavTab.Library) {
        Column(Modifier.padding(horizontal = 48.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("REAL-DEBRID CLOUD", style = broadcastStyle(30.sp))
                ActionButton("Refresh", { refresh++ })
                if (opened != null) ActionButton("Back to list", { opened = null })
            }
            if (!RealDebrid.connected) StatusText("Connect Real-Debrid in Settings first.")
            if (status.isNotBlank()) Text(status, color = McdColors.Red, fontSize = 14.sp)
        }
        val item = opened
        if (item != null) {
            val files by rememberLoad(item.id) { RdCloud.files(item.id) }
            when (val f = files) {
                is Load.Loading -> StatusText("Loading files…", Modifier.padding(start = 48.dp))
                is Load.Err -> StatusText(f.message, Modifier.padding(start = 48.dp))
                is Load.Ok -> LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(f.value) { file ->
                        RowCard(file.name, "%.2f GB".format(file.sizeGb)) {
                            status = "Opening…"
                            scope.launch {
                                runCatching { RealDebrid.unrestrict(file.link) }
                                    .onSuccess { status = ""; nav.push(Screen.Player(it, file.name)) }
                                    .onFailure { status = it.message ?: "Could not open" }
                            }
                        }
                    }
                }
            }
        } else when (val l = items) {
            is Load.Loading -> StatusText("Loading your cloud…", Modifier.padding(start = 48.dp))
            is Load.Err -> StatusText(l.message, Modifier.padding(start = 48.dp))
            is Load.Ok -> if (l.value.isEmpty()) StatusText("Your Real-Debrid cloud is empty.", Modifier.padding(start = 48.dp))
            else LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(l.value, key = { it.id }) { it2 ->
                    val ready = it2.status == "downloaded"
                    RowCard(
                        it2.name,
                        "%.2f GB  •  %s  •  added %s".format(it2.sizeGb, if (ready) "Ready" else "${it2.status} ${it2.progress}%", it2.added),
                    ) { if (ready) opened = it2 else status = "Still downloading on Real-Debrid (${it2.progress}%)." }
                }
            }
        }
    }
}
