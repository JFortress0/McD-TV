package com.mcd.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Prefs
import kotlinx.coroutines.delay

// ============================== Websites ==============================

/**
 * Websites you added (Jarvis Control page or the home setup page), opened in the built-in browser.
 * Reached from Home > More and Browse > More. (The route is still called Sports for the QA hook "--es screen sports".)
 */
@Composable
fun SportsScreen(nav: Nav) {
    // Re-read every few seconds so a save from the phone shows up right away.
    var sites by remember { mutableStateOf(Prefs.websites) }
    LaunchedEffect(Unit) { while (true) { sites = Prefs.websites; delay(3000) } }

    TabPage(nav, NavTab.Sports) {
        Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS ", style = broadcastStyle(30.sp))
            Text("WEBSITES", style = broadcastStyle(30.sp, McdColors.Accent))
        }
        if (sites.isEmpty()) {
            StatusText("None yet. Add them in Jarvis Control (Settings > Phone & Computer Setup).", Modifier.padding(start = 48.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(sites, key = { it.second }) { (name, url) ->
                    CompactTile(name, url.removePrefix("https://").removePrefix("http://").removePrefix("www."), { nav.push(Screen.Web(url, name)) })
                }
            }
        }
    }
}
