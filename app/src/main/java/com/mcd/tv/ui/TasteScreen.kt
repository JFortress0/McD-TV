package com.mcd.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mcd.tv.Nav
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.Recommender
import org.json.JSONArray

/** Genres a person can like or avoid (TMDB movie ids; TV genres map onto these). */
private val TASTE_GENRES = listOf(
    10752 to "War", 80 to "Crime", 35 to "Comedy", 36 to "History", 28 to "Action", 53 to "Thriller",
    18 to "Drama", 9648 to "Mystery", 99 to "Documentary", 37 to "Western", 878 to "Sci-Fi", 12 to "Adventure",
    27 to "Horror", 10749 to "Romance", 14 to "Fantasy", 10751 to "Family", 16 to "Animation", 10402 to "Music",
    10764 to "Reality", 10767 to "Talk shows", 10763 to "News",
)

private enum class Pick { NONE, LIKE, AVOID }

/**
 * Settings > Taste for one profile: genres to like or avoid, and topics (plot keywords such as
 * "stand-up comedy" or "mafia") to look for or skip. Jarvis also learns from what the profile watches;
 * these choices steer it from day one. Synced to the house's other TVs with the profile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasteScreen(nav: Nav, profile: String) {
    val saved = remember(profile) { Recommender.choicesJson(profile) }
    fun ids(key: String): Set<Int> = saved.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()
    fun words(key: String): String = saved.optJSONArray(key)?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""
    val picks = remember(profile) {
        mutableStateMapOf<Int, Pick>().apply {
            ids("likeGenres").forEach { put(it, Pick.LIKE) }
            ids("avoidGenres").forEach { put(it, Pick.AVOID) }
        }
    }
    var likeText by remember(profile) { mutableStateOf(words("likeWords")) }
    var avoidText by remember(profile) { mutableStateOf(words("avoidWords")) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }

    fun save() {
        Recommender.saveChoices(
            profile,
            likeGenres = picks.filterValues { it == Pick.LIKE }.keys,
            avoidGenres = picks.filterValues { it == Pick.AVOID }.keys,
            likeWords = likeText.split(',', ';'),
            avoidWords = avoidText.split(',', ';'),
        )
        nav.back()
    }

    Column(
        Modifier.fillMaxSize().hudBackground().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        McdLogo()
        Text("WHAT ${Prefs.profileName(profile).uppercase()} LIKES", style = broadcastStyle(32.sp))
        Text(
            "Press OK on a genre: once to like it, again to skip it, again to clear. Jarvis also learns from what you finish, " +
                "what you stop early, ♡ Favorite, \"I like this\" and \"Not for me\".",
            color = McdColors.Muted, fontSize = 16.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TASTE_GENRES.forEachIndexed { i, (id, name) ->
                val p = picks[id] ?: Pick.NONE
                ActionButton(
                    when (p) { Pick.LIKE -> "✓ $name"; Pick.AVOID -> "✕ $name"; Pick.NONE -> name },
                    {
                        picks[id] = when (p) { Pick.NONE -> Pick.LIKE; Pick.LIKE -> Pick.AVOID; Pick.AVOID -> Pick.NONE }
                    },
                    if (i == 0) Modifier.focusRequester(first) else Modifier,
                    primary = p == Pick.LIKE,
                )
            }
        }
        Text("Topics you like (comma separated), for example: stand-up comedy, poker, mafia, navy seals", color = McdColors.White, fontSize = 16.sp)
        TasteField(likeText, { likeText = it })
        Text("Topics to skip (comma separated). Titles and live shows about these are left out of your suggestions.", color = McdColors.White, fontSize = 16.sp)
        TasteField(avoidText, { avoidText = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Save", { save() }, primary = true)
            ActionButton("Cancel", { nav.back() })
        }
    }
}

@Composable
private fun TasteField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(300)) },
        singleLine = true,
        textStyle = TextStyle(color = McdColors.White, fontSize = 18.sp),
        cursorBrush = SolidColor(McdColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onDone = { }),
        modifier = Modifier.fillMaxWidth().background(McdColors.Card, HudShape)
            .border(1.dp, McdColors.Accent.copy(alpha = 0.7f), HudShape).padding(12.dp),
    )
}
