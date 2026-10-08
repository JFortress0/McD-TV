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
 * McD TV palette: Max-style near-black with deep navy light from the top and a clean blue accent.
 * Property names are kept from the first theme so every screen picks up the new look:
 * Red / RedBright / RedDark now map to the blue accents. The intro keeps its own red constants.
 */
object McdColors {
    val Navy = Color(0xFF05070D)       // page background (darkest)
    val NavyLight = Color(0xFF1A2335)  // raised surfaces, focused rows
    val Card = Color(0xFF111827)       // cards and fields at rest
    val Line = Color(0xFF1F2937)       // hairlines and outlines
    val Red = Color(0xFF3D7BFF)        // accent blue (name kept for compatibility)
    val RedBright = Color(0xFF6EA0FF)  // bright accent: focus rings, glows
    val RedDark = Color(0xFF1E4FC2)    // deep accent
    val Cyan = Color(0xFF6EA0FF)       // secondary focus (same bright blue)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFFA3ADC2)      // secondary text

    /** Accent aliases for new code. */
    val Accent = Red
    val AccentBright = RedBright
    val Raised = NavyLight

    /** Live sports badges stay red. */
    val LiveRed = Color(0xFFE11D2E)

    /** The intro animation keeps the original red broadcast look. */
    val IntroRed = Color(0xFFE11D2E)
    val IntroRedBright = Color(0xFFFF3B4A)
    val IntroRedDark = Color(0xFF8F0F1A)
    val IntroNavy = Color(0xFF07090D)
    val IntroNavyLight = Color(0xFF10141B)
}

/** Background for every page: deep navy light from the top fading into near-black. */
val ScreenBackground = Brush.verticalGradient(
    0f to Color(0xFF0A1430),
    0.35f to Color(0xFF070B18),
    1f to McdColors.Navy,
)

/** Exo 2: squared, wide headline face (bundled, SIL Open Font License). */
val HeadingFont = FontFamily(
    Font(R.font.exo2_bold, FontWeight.Bold),
    Font(R.font.exo2_semibold, FontWeight.SemiBold),
)

/** Headlines, section titles, card titles: clean bold sans, slightly tightened at large sizes. */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    color = color,
    letterSpacing = if (size.isSp && size.value >= 24f) (-0.5).sp else 0.sp,
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

/** The "McD TV" wordmark for headers: white bold Exo 2, "TV" in the accent blue. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val style = TextStyle(fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = (32 * scale).sp, letterSpacing = (-0.5).sp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((5 * scale).dp)) {
        Text("McD", style = style.copy(color = McdColors.White))
        Text("TV", style = style.copy(color = McdColors.Red))
    }
}

/** Red "TV" badge in the intro's broadcast style. */
@Composable
fun TvBadge(scale: Float = 1f, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(McdColors.IntroRed, RoundedCornerShape((6 * scale).dp))
            .padding(horizontal = (10 * scale).dp, vertical = (2 * scale).dp),
    ) {
        Text(text = "TV", style = introStyle((30 * scale).sp))
    }
}
