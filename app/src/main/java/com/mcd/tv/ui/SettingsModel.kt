package com.mcd.tv.ui

/*
 * Settings logic with no Android or Compose in it, so the remote moves and the text are unit-tested
 * (app/src/test/java/com/mcd/tv/ui/SettingsModelTest.kt). SettingsScreen.kt draws it.
 */

/** The categories in the left rail, top to bottom. App updates is first: one press of RIGHT, then OK, checks. */
enum class SettingsCategory(val id: String, val title: String) {
    Updates("updates", "App updates"),
    ThisTv("tv", "This TV"),
    Profiles("profiles", "Profiles"),
    Playback("playback", "Playback"),
    Sources("sources", "Addons and Live TV"),
    Wall("wall", "Jarvis Wall"),
    Network("network", "Network"),
    About("about", "About and help");

    companion object {
        fun fromId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where the remote is in Settings: the category, whether focus is in the rail or the content, and the last
 * focused row of each category (so RIGHT and a return from another screen land where you were).
 */
data class SettingsFocus(
    val category: SettingsCategory = SettingsCategory.Updates,
    val inContent: Boolean = false,
    val rows: Map<SettingsCategory, Int> = emptyMap(),
) {
    /** The last focused row of the current category (0 when none yet). */
    val row: Int get() = rows[category] ?: 0

    /** Focus moved onto a rail item: that category shows on the right. */
    fun onRail(c: SettingsCategory) = copy(category = c, inContent = false)

    /** Focus moved onto row [index] of the current category. */
    fun onRow(index: Int) = copy(inContent = true, rows = rows + (category to index.coerceAtLeast(0)))

    /** RIGHT or OK on the rail: the row to focus, or null when the category has no focusable rows. */
    fun enterRow(rowCount: Int): Int? = if (rowCount <= 0) null else row.coerceIn(0, rowCount - 1)

    /** BACK: from the content to the rail. Null means leave Settings. */
    fun back(): SettingsFocus? = if (inContent) copy(inContent = false) else null

    /** Short text for rememberSaveable: "updates|1|tv=2,profiles=0". */
    fun encode(): String =
        "${category.id}|${if (inContent) 1 else 0}|" + rows.entries.joinToString(",") { "${it.key.id}=${it.value}" }

    companion object {
        fun decode(s: String?): SettingsFocus? {
            val p = s?.split('|') ?: return null
            if (p.size != 3) return null
            val cat = SettingsCategory.fromId(p[0]) ?: return null
            val rows = p[2].split(',').mapNotNull { e ->
                val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val c = SettingsCategory.fromId(k) ?: return@mapNotNull null
                v.toIntOrNull()?.takeIf { it >= 0 }?.let { c to it }
            }.toMap()
            return SettingsFocus(cat, p[1] == "1", rows)
        }

        /**
         * Where Settings opens. Coming back from a screen Settings opened restores [saved]. A fresh open starts
         * on [requested] (a QA deep link) or App updates, in the rail.
         */
        fun open(saved: SettingsFocus?, requested: SettingsCategory?): SettingsFocus =
            saved ?: SettingsFocus(requested ?: SettingsCategory.Updates)
    }
}

/** What the App updates page says, from the update state. */
data class UpdateView(
    val headline: String,
    val detail: String,
    val button: String,
    /** A newer version is ready: the button installs it and the headline is highlighted. */
    val ready: Boolean,
    /** True while a check runs: the button does nothing. */
    val busy: Boolean,
)

fun updateView(installed: String, available: String?, checking: Boolean, error: String, checkedAt: Long): UpdateView = when {
    available != null -> UpdateView("Version $available is ready", "Your settings and profiles stay.", "Update now", ready = true, busy = false)
    checking -> UpdateView("Checking for updates…", "This takes a few seconds.", "Checking…", ready = false, busy = true)
    error.isNotBlank() -> UpdateView("Couldn't check", error, "Try again", ready = false, busy = false)
    checkedAt <= 0L -> UpdateView("Version $installed", "Jarvis also checks by itself every 6 hours.", "Check for updates", ready = false, busy = false)
    else -> UpdateView("You're up to date", "Version $installed is the latest.", "Check for updates", ready = false, busy = false)
}

/** "Never", "Just now", "5 minutes ago", "1 hour ago", "3 days ago". */
fun agoLabel(then: Long, now: Long): String {
    if (then <= 0L) return "Never"
    val s = ((now - then) / 1000L).coerceAtLeast(0L)
    fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"} ago"
    return when {
        s < 60 -> "Just now"
        s < 3600 -> n(s / 60, "minute")
        s < 86_400 -> n(s / 3600, "hour")
        else -> n(s / 86_400, "day")
    }
}

/** Largest file the source list shows by default. 0 = no limit. */
val MOVIE_LIMITS = listOf(20, 40, 60, 0)
val EPISODE_LIMITS = listOf(4, 8, 12, 20, 0)

fun sizeLimitLabel(gb: Int) = if (gb <= 0) "No limit" else "$gb GB"

fun onOff(on: Boolean) = if (on) "On" else "Off"
