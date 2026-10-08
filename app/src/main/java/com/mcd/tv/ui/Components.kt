package com.mcd.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * A D-pad friendly HUD tile. Focus = glowing cyan border, corner brackets, slight grow.
 * [tag] is an optional accent label in the corner (e.g. "PLAY" or "PHASE 3").
 */
@Composable
fun TileCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    dimmed: Boolean = false,
) {
    HudCard(onClick = onClick, modifier = modifier.width(300.dp).height(168.dp)) { focused ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            if (dimmed) McdColors.Card else McdColors.Raised,
                            if (dimmed) McdColors.Navy else McdColors.Card,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = broadcastStyle(18.sp, if (dimmed) McdColors.Muted else if (focused) McdColors.AccentBright else McdColors.White),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = McdColors.Muted,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tag != null) {
            Text(
                text = tag.uppercase(),
                style = hudLabelStyle(9.sp, McdColors.Ink),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(McdColors.Accent, HudShapeTiny)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Small tile for the "More" row on Home (Browse, Websites, Background Noise). */
@Composable
fun CompactTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HudCard(onClick = onClick, modifier = modifier.width(220.dp).height(84.dp)) { focused ->
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            Text(
                title.uppercase(), style = broadcastStyle(14.sp, if (focused) McdColors.AccentBright else McdColors.White),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = McdColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Section header: "▸ TRENDING THIS WEEK" in small Orbitron, followed by a short cyan line segment. */
@Composable
fun RailHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▸", color = McdColors.Accent, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(text = text.uppercase(), style = hudLabelStyle(12.sp, McdColors.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(40.dp).height(1.dp).background(McdColors.Accent))
        Box(Modifier.size(3.dp).clip(CircleShape).background(McdColors.AccentBright))
    }
}
