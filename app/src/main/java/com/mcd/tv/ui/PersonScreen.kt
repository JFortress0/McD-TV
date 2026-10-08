package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.Nav
import com.mcd.tv.Screen
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb

/** An actor's (or director's) page: photo, short bio, and every movie and show they've done. */
@Composable
fun PersonScreen(nav: Nav, id: Int) {
    var retry by remember { mutableIntStateOf(0) }
    val load by rememberLoad(id, retry) { Tmdb.person(id) }
    var kind by remember { mutableStateOf("all") } // all | movie | tv
    var popular by remember { mutableStateOf(false) }
    val openTitle: (Title) -> Unit = { nav.push(Screen.Detail(it.type, it.id)) }

    Column(Modifier.fillMaxSize().background(ScreenBackground)) {
        when (val l = load) {
            is Load.Loading -> StatusText("Loading…", Modifier.padding(48.dp))
            is Load.Err -> Column(Modifier.padding(48.dp)) {
                StatusText(l.message)
                ActionButton("Retry", { retry++ }, primary = true)
            }
            is Load.Ok -> {
                val p = l.value
                fun List<Title>.shown() = filter { kind == "all" || it.type == kind }
                    .let { list -> if (popular) list.sortedByDescending { it.rating * (if (it.poster != null) 1.0 else 0.5) } else list }
                val acting = p.acting.shown()
                val crew = p.crew.filter { kind == "all" || it.first.type == kind }
                LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                    item {
                        Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
                            AsyncImage(
                                Tmdb.img(p.photo, "w342"), p.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(160.dp).clip(CircleShape).background(McdColors.Card),
                            )
                            Spacer(Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name.uppercase(), style = broadcastStyle(38.sp))
                                Text(
                                    listOfNotNull(
                                        p.department.ifBlank { null },
                                        p.birthday.ifBlank { null }?.let { "Born $it" },
                                        p.placeOfBirth.ifBlank { null },
                                        "${p.acting.size + p.crew.size} titles",
                                    ).joinToString("   •   "),
                                    color = McdColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp),
                                )
                                Text(p.bio, color = Color.White, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ActionButton("All", { kind = "all" }, primary = kind == "all")
                                    ActionButton("Movies", { kind = "movie" }, primary = kind == "movie")
                                    ActionButton("Shows", { kind = "tv" }, primary = kind == "tv")
                                    ActionButton(if (popular) "Sort: Top rated" else "Sort: Newest", { popular = !popular })
                                }
                            }
                        }
                    }
                    if (acting.isNotEmpty()) {
                        item { RailHeader("Acting (${acting.size})", Modifier.padding(start = 48.dp, top = 24.dp)) }
                        items(acting.chunked(5)) { row ->
                            Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { t -> PosterCard(t, onClick = { openTitle(t) }) }
                            }
                        }
                    }
                    if (crew.isNotEmpty()) {
                        item { RailHeader("Behind the camera (${crew.size})", Modifier.padding(start = 48.dp, top = 24.dp)) }
                        items(crew.chunked(5)) { row ->
                            Row(Modifier.padding(horizontal = 48.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { (t, job) ->
                                    Column {
                                        PosterCard(t, onClick = { openTitle(t) })
                                        Text(job, color = McdColors.Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(140.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
