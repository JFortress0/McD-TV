package com.mcd.tv.data

import android.content.Context

/**
 * Tiny settings store. SharedPreferences is enough for Phase 1;
 * Room arrives in Phase 2 for watch progress and favorites.
 */
object Prefs {
    private const val FILE = "mcdtv_settings"
    private const val KEY_URL = "custom_stream_url"
    private const val KEY_INTRO = "play_intro"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun customUrl(c: Context): String = sp(c).getString(KEY_URL, "") ?: ""
    fun setCustomUrl(c: Context, url: String) = sp(c).edit().putString(KEY_URL, url.trim()).apply()

    fun playIntro(c: Context): Boolean = sp(c).getBoolean(KEY_INTRO, true)
    fun setPlayIntro(c: Context, on: Boolean) = sp(c).edit().putBoolean(KEY_INTRO, on).apply()
}
