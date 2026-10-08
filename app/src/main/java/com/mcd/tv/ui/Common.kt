package com.mcd.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mcd.tv.data.Title
import com.mcd.tv.data.Tmdb
import kotlinx.coroutines.CancellationException

// ---------------- Async loading ----------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** Runs [block] off the main thread when the screen opens (or a key changes). */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<Load<T>> =
    produceState<Load<T>>(Load.Loading, *keys) {
        value = try {
            Load.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Err(e.message ?: e.toString())
        }
    }

@Composable
fun StatusText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = McdColors.Muted, fontSize = 16.sp, modifier = modifier.padding(vertical = 12.dp))
}

// ---------------- Cards ----------------

private val cardBorder @Composable get() = CardDefaults.border(focusedBorder = Border(border = BorderStroke(3.dp, McdColors.Red)))
private val cardColors @Composable get() = CardDefaults.colors(containerColor = McdColors.Card, focusedContainerColor = McdColors.Card)

/** Portrait poster with rating badge, like HuberTV's rows. */
@Composable
fun PosterCard(t: Title, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 140.dp) {
    Card(
        onClick = onClick,
        modifier = modifier.width(width).height(width * 1.5f),
        colors = cardColors,
        border = cardBorder,
        scale = CardDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = Tmdb.img(t.poster),
                contentDescription = t.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (t.poster == null) {
                Text(t.name, style = broadcastStyle(16.sp), modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (t.rating > 0) {
                Text(
                    text = "★ %.1f".format(t.rating),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Landscape card with optional progress bar (Continue Watching). */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(280.dp).height(158.dp),
        colors = cardColors,
        border = cardBorder,
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(title, style = broadcastStyle(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = McdColors.Muted, fontSize = 12.sp, maxLines = 1)
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(McdColors.Red))
                    }
                }
            }
        }
    }
}

/** A titled horizontal row of posters. */
@Composable
fun TitleRow(label: String, items: List<Title>, onOpen: (Title) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        RailHeader(label, Modifier.padding(start = 48.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { "${it.type}-${it.id}" }) { t -> PosterCard(t, onClick = { onOpen(t) }) }
        }
    }
}

/** Compact red-accent button used for actions on detail pages. */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (primary) ButtonDefaults.colors(containerColor = McdColors.Red, contentColor = Color.White)
        else ButtonDefaults.colors(containerColor = McdColors.Card, contentColor = Color.White),
    ) { Text(text, fontSize = 14.sp) }
}

/** Round cast photo. */
@Composable
fun CastBubble(name: String, role: String, photo: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        AsyncImage(
            model = Tmdb.img(photo, "w185"),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(40.dp)).background(McdColors.Card),
        )
        Text(name, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, fontSize = 11.sp, color = McdColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Top navigation, like HuberTV's icon bar, as focusable text tabs. */
enum class NavTab(val label: String) { Home("Home"), Search("Search"), Library("My List"), Sports("Sports"), Live("Live TV"), Services("Services"), Noise("Background Noise"), Calendar("Calendar"), Settings("Settings") }

@Composable
fun TopNav(current: NavTab, onSelect: (NavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        McdLogo(scale = 0.6f)
        Spacer(Modifier.width(16.dp))
        NavTab.entries.forEach { tab ->
            Button(
                onClick = { onSelect(tab) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.colors(
                    containerColor = if (tab == current) McdColors.Red else Color.Transparent,
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = McdColors.Navy,
                ),
            ) { Text(tab.label, fontSize = 13.sp) }
        }
    }
}
