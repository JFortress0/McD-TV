package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.mcd.tv.R

/**
 * McD TV palette: near-black graphite, signal red with a glow, cool cyan for secondary focus.
 * (Names kept from the first theme so every screen picks up the new look.)
 */
object McdColors {
    val Navy = Color(0xFF07090D)       // page background (darkest)
    val NavyLight = Color(0xFF10141B)  // raised areas, focused rows
    val Card = Color(0xFF161B23)       // cards and fields
    val Line = Color(0xFF262D38)       // hairlines and outlines
    val Red = Color(0xFFE11D2E)
    val RedBright = Color(0xFFFF3B4A)
    val RedDark = Color(0xFF8F0F1A)
    val Cyan = Color(0xFF38BDF8)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9AA3AF)
}

/** Background for every page: soft charcoal fading to black, with a faint red light from the top. */
val ScreenBackground = Brush.verticalGradient(listOf(Color(0xFF12161D), Color(0xFF0A0C11), McdColors.Navy))

/** Exo 2: squared, wide headline face (bundled, SIL Open Font License). */
val HeadingFont = FontFamily(
    Font(R.font.exo2_bold, FontWeight.Bold),
    Font(R.font.exo2_semibold, FontWeight.SemiBold),
)

/** Headlines, section titles, card titles. */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White) = TextStyle(
    fontFamily = HeadingFont,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    color = color,
    letterSpacing = 0.5.sp,
)

/** The intro keeps its heavy italic sports-broadcast lettering. */
fun introStyle(size: TextUnit, color: Color = McdColors.White) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = size,
    color = color,
)

@Composable
fun McdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McdColors.Red,
            onPrimary = McdColors.White,
            background = McdColors.Navy,
            onBackground = McdColors.White,
            surface = McdColors.Card,
            onSurface = McdColors.White,
        ),
        content = content,
    )
}

/** The "McD TV" wordmark for headers: clean white italic. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)) {
        Text("McD", style = introStyle((36 * scale).sp))
        Text("TV", style = introStyle((36 * scale).sp, McdColors.Red))
    }
}

/** Red "TV" badge, used by the intro animation. */
@Composable
fun TvBadge(scale: Float = 1f, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(McdColors.Red, RoundedCornerShape((6 * scale).dp))
            .padding(horizontal = (10 * scale).dp, vertical = (2 * scale).dp),
    ) {
        Text(text = "TV", style = introStyle((30 * scale).sp))
    }
}
