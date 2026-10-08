package com.mcd.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.mcd.tv.data.Channel
import com.mcd.tv.data.Epg
import com.mcd.tv.data.M3u
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Programme
import com.mcd.tv.player.PlayerScreen
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The channel list the Live TV page last opened a channel from, plus the program guide.
 * Kept in memory so Screen.LivePlay only carries an index.
 */
object LiveSession {
    var channels: List<Channel> = emptyList()
    var guide by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
}

private val guideTimeFormat: DateFormat by lazy { DateFormat.getTimeInstance(DateFormat.SHORT) }

/** "8:00 PM" (device locale). Main thread only. */
internal fun guideTime(ms: Long): String = guideTimeFormat.format(Date(ms))

/** 0..1 through a programme, for the progress bar. */
internal fun programmeProgress(p: Programme, now: Long): Float {
    val len = (p.end - p.start).coerceAtLeast(1L)
    return ((now - p.start).toFloat() / len).coerceIn(0f, 1f)
}

/** "Now: Title  •  Next 8:00 PM Title", or "" without guide data. */
internal fun nowNextLine(cur: Programme?, next: Programme?): String = buildString {
    if (cur != null) append("Now: ").append(cur.title)
    if (next != null) {
        if (isNotEmpty()) append("  •  ")
        append("Next ").append(guideTime(next.start)).append(' ').append(next.title)
    }
}

/**
 * Plays channel [index] of [LiveSession.channels]. Channel up / down:
 *  - CHANNEL_UP / PAGE_UP, or D-pad UP while the control bar is hidden: previous channel.
 *  - CHANNEL_DOWN / PAGE_DOWN, or D-pad DOWN while the control bar is hidden: next channel. Wraps around.
 *  - MENU: add / remove the channel shown in the overlay from Favorites.
 * Quick presses only move the overlay; the stream switches once you stop for a moment.
 */
@Composable
fun LivePlayerScreen(nav: Nav, index: Int) {
    val channels = remember { LiveSession.channels }
    if (channels.isEmpty()) {
        // Channel list is gone (should not happen): go back to the Live TV page.
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val count = channels.size
    // target = the channel the overlay shows; playing = the stream that is open.
    var target by remember { mutableIntStateOf(index.coerceIn(0, count - 1)) }
    var playing by remember { mutableIntStateOf(target) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    var favorites by remember { mutableStateOf(Prefs.liveFavorites.toSet()) }

    // Guide: normally loaded by the channel list already (shared, cached); load it here too if not.
    LaunchedEffect(Unit) {
        if (LiveSession.guide.isEmpty()) {
            val url = M3u.guideUrl
            if (url.isNotBlank()) {
                val all = runCatching { M3u.load() }.getOrDefault(emptyList())
                val g = Epg.load(url, all)
                if (g.isNotEmpty()) LiveSession.guide = g
            }
        }
    }

    // Channel overlay: shown for 4 s after opening, each channel change and each favorite toggle.
    LaunchedEffect(overlayTick) {
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    // Switch the stream once channel presses settle (fast zapping does not open every stream on the way).
    LaunchedEffect(target) {
        if (target != playing) {
            delay(450)
            playing = target
        }
    }

    val playingChannel = channels[playing.coerceIn(0, count - 1)]
    LaunchedEffect(playingChannel.url) { Prefs.addLiveRecent(playingChannel.url) }

    val onKey: (KeyEvent, Boolean) -> Boolean = remember(count) {
        { e: KeyEvent, controlsShowing: Boolean ->
            val step = when (e.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> if (controlsShowing) 0 else -1
                KeyEvent.KEYCODE_DPAD_DOWN -> if (controlsShowing) 0 else 1
                else -> 0
            }
            when {
                step != 0 -> {
                    // Consume both down and up so the player does not also pop its control bar.
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        target = (target + step).mod(count)
                        overlayTick++
                    }
                    true
                }
                e.keyCode == KeyEvent.KEYCODE_MENU -> {
                    if (e.action == KeyEvent.ACTION_UP) {
                        Prefs.toggleLiveFavorite(channels[target].url)
                        favorites = Prefs.liveFavorites.toSet()
                        overlayTick++
                    }
                    true
                }
                else -> false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(playingChannel.url) {
            PlayerScreen(
                url = playingChannel.url,
                title = playingChannel.name,
                onKeyEvent = onKey,
                autoShowControls = false,
                sleepTimerEnabled = false,
            )
        }
        if (overlayVisible) {
            val ch = channels[target.coerceIn(0, count - 1)]
            ChannelOverlay(
                number = target + 1,
                total = count,
                ch = ch,
                favorite = ch.url in favorites,
                guide = LiveSession.guide,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 40.dp),
            )
        }
    }
}

@Composable
private fun ChannelOverlay(
    number: Int,
    total: Int,
    ch: Channel,
    favorite: Boolean,
    guide: Map<String, List<Programme>>,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val (cur, next) = remember(guide, ch.url, now / 60_000L) { Epg.nowNext(Epg.keyOf(ch), guide, now) }
    Column(
        modifier
            .width(540.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(McdColors.Card.copy(alpha = 0.93f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number", style = broadcastStyle(30.sp, McdColors.Red))
            Spacer(Modifier.width(14.dp))
            AsyncImage(ch.logo, ch.name, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 84.dp, height = 52.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = broadcastStyle(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${ch.group}  •  $number of $total", color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (cur != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Now  ${guideTime(cur.start)} – ${guideTime(cur.end)}  ${cur.title}",
                color = McdColors.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(programmeProgress(cur, now)).height(3.dp).background(McdColors.Red))
            }
            if (cur.desc.isNotBlank()) {
                Text(cur.desc, color = McdColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (next != null) {
            Text(
                "Next  ${guideTime(next.start)}  ${next.title}",
                color = McdColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (favorite) "★ Favorite" else "☆ Favorite") + "  (Menu ☰ to change)    ▲ ▼ change channel",
            color = if (favorite) McdColors.RedBright else McdColors.Muted,
            fontSize = 12.sp,
        )
    }
}
