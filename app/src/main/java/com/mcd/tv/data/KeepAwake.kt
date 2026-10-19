package com.mcd.tv.data

/**
 * Who needs the TV screen kept on (the Wall, Phone & Computer Setup). Each screen holds its own token, so one
 * screen closing can never clear another screen's hold. While any token is held, MainActivity sets
 * FLAG_KEEP_SCREEN_ON on the window, so the Fire TV screensaver cannot start. Pure logic, no Android.
 */
class HoldSet {
    private val tokens = LinkedHashMap<Int, String>()
    private var next = 1

    /** Starts a hold for [who]. Returns the token to release. */
    @Synchronized
    fun hold(who: String): Int {
        val t = next++
        tokens[t] = who
        return t
    }

    /** Ends one hold. Unknown or already released tokens are ignored. */
    @Synchronized
    fun release(token: Int) {
        tokens.remove(token)
    }

    @get:Synchronized
    val held: Boolean get() = tokens.isNotEmpty()

    /** Who holds the screen now, for logs ("wall"). */
    @Synchronized
    fun holders(): List<String> = tokens.values.toList()
}

/** Where the app opens. Pure, so the Wall cases are unit-tested. */
object StartScreen {
    enum class Start { DeepLink, Wall, Intro, Profiles, Home }

    /**
     * [wallWasUp]: Android re-created the activity (HDMI mode change, low memory) while the Wall was showing.
     * The Wall comes back, so the screen stays held and the screensaver never takes over. It wins over a deep link:
     * a re-created activity gets its first launch intent again, which is old news by then.
     */
    fun pick(hasDeepLink: Boolean, wallWasUp: Boolean, wallStartPref: Boolean, playIntro: Boolean, skipProfiles: Boolean): Start = when {
        wallWasUp -> Start.Wall
        hasDeepLink -> Start.DeepLink
        wallStartPref -> Start.Wall
        playIntro -> Start.Intro
        !skipProfiles -> Start.Profiles
        else -> Start.Home
    }
}
