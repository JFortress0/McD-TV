package com.mcd.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Era
import com.mcd.tv.data.HistoryHub
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Title

/**
 * Browse > History: More Like <the latest history title this profile watched>, Ken Burns, Historical Dramas,
 * then one shelf per era (the eras this profile watches most first). Each shelf loads on its own and ends
 * in a See all tile. Keyed on the profile, like Home. QA screen "history".
 */
@Composable
fun HistoryScreen(nav: Nav) {
    key(Prefs.activeProfile) { HistoryContent(nav, Prefs.activeProfile) }
}

@Composable
private fun HistoryContent(nav: Nav, profile: String) {
    val focus = rememberSaveable(saver = FocusMemory.Saver) { FocusMemory() }
    val listState = rememberLazyListState()
    focus.indexOfRow = { k -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.index }
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }
    val more by rememberLoad(profile) { HistoryHub.moreLike(profile) }
    val order by rememberLoad(profile) { HistoryHub.eraOrder(profile) }
    TabPage(nav, NavTab.Browse) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 48.dp)) {
                    Text("HISTORY", style = broadcastStyle(26.sp))
                    Text(
                        "Ken Burns, historical dramas and every era, from the founding to the frontier.",
                        color = McdColors.Muted, fontSize = 14.sp,
                    )
                }
            }
            item(key = "more") {
                (more as? Load.Ok)?.value?.let { (name, list) ->
                    val label = "More Like $name"
                    TitleRail(label, list, { nav.push(Screen.BrowseGrid("history", "more", label)) }, openTitle, focus, "more")
                }
            }
            item(key = "kenburns") { HistoryShelf(nav, profile, "kenburns", "Ken Burns", focus) }
            item(key = "dramas") { HistoryShelf(nav, profile, "dramas", "Historical Dramas", focus) }
            (order as? Load.Ok)?.value?.forEach { era ->
                item(key = "era:${era.id}") { HistoryShelf(nav, profile, "era:${era.id}", era.label, focus) }
            }
        }
    }
}

/** One hub shelf: loads by itself, shows Loading or the problem under its heading, hides when empty. */
@Composable
private fun HistoryShelf(nav: Nav, profile: String, id: String, label: String, focus: FocusMemory) {
    val load by rememberLoad(profile, id) { HistoryHub.shelf(id, profile) }
    when (val l = load) {
        is Load.Loading -> Column(Modifier.padding(start = 48.dp, top = 20.dp)) {
            RailHeader(label)
            StatusText("Loading…")
        }
        is Load.Err -> Column(Modifier.padding(start = 48.dp, top = 20.dp)) {
            RailHeader(label)
            StatusText(l.message)
        }
        is Load.Ok -> TitleRail(
            label, l.value.take(30), { nav.push(Screen.BrowseGrid("history", id, label)) },
            { nav.push(Screen.Detail(it.type, it.id)) }, focus, id,
        )
    }
}

/** The label of a hub shelf id, for its See all grid opened from a QA deep link. */
fun historyShelfLabel(id: String): String = when {
    id == "kenburns" -> "Ken Burns"
    id == "dramas" -> "Historical Dramas"
    id == "more" -> "More Like This"
    id.startsWith("era:") -> Era.fromId(id.substringAfter(':'))?.label ?: "History"
    else -> "History"
}
