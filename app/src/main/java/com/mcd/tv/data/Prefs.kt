package com.mcd.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * All app settings. MainActivity calls [init] once, so the rest of the app can read
 * settings without passing a Context around.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("mcdtv_settings", Context.MODE_PRIVATE)
    }

    private fun str(key: String) = sp.getString(key, "") ?: ""
    private fun put(key: String, value: String) = sp.edit().putString(key, value.trim()).apply()

    // ---- General ----
    var customUrl: String
        get() = str("custom_stream_url")
        set(v) = put("custom_stream_url", v)

    var playIntro: Boolean
        get() = sp.getBoolean("play_intro", true)
        set(v) = sp.edit().putBoolean("play_intro", v).apply()

    /** Like HuberTV's "US Only": limit lists to US releases / US streaming providers. */
    var usOnly: Boolean
        get() = sp.getBoolean("us_only", true)
        set(v) = sp.edit().putBoolean("us_only", v).apply()

    /** Like HuberTV's "Slow connection": prefer smaller files and 720p when picking a source. */
    var slowConnection: Boolean
        get() = sp.getBoolean("slow_connection", false)
        set(v) = sp.edit().putBoolean("slow_connection", v).apply()

    // ---- Metadata ----
    var tmdbKey: String
        get() = str("tmdb_key")
        set(v) = put("tmdb_key", v)

    // ---- Live TV ----
    var m3uUrl: String
        get() = str("m3u_url")
        set(v) = put("m3u_url", v)

    // ---- Addons (Stremio protocol): list of manifest URLs ----
    var addonUrls: List<String>
        get() = runCatching {
            val a = JSONArray(sp.getString("addon_urls", "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("addon_urls", JSONArray(v.distinct()).toString()).apply()

    // ---- Websites you add (opened in the built-in browser): list of name|url ----
    var websites: List<Pair<String, String>>
        get() = runCatching {
            val a = JSONArray(sp.getString("websites", "[]"))
            List(a.length()) { a.getString(it) }.map { it.substringBefore("|") to it.substringAfter("|") }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("websites", JSONArray(v.distinctBy { it.second }.map { "${it.first}|${it.second}" }).toString()).apply()

    // ---- Real-Debrid (device-code OAuth; nothing typed on the TV) ----
    var rdClientId: String
        get() = str("rd_client_id")
        set(v) = put("rd_client_id", v)
    var rdClientSecret: String
        get() = str("rd_client_secret")
        set(v) = put("rd_client_secret", v)
    var rdAccessToken: String
        get() = str("rd_access")
        set(v) = put("rd_access", v)
    var rdRefreshToken: String
        get() = str("rd_refresh")
        set(v) = put("rd_refresh", v)
    var rdExpiresAt: Long
        get() = sp.getLong("rd_expires", 0L)
        set(v) = sp.edit().putLong("rd_expires", v).apply()

    fun clearRealDebrid() {
        sp.edit().remove("rd_client_id").remove("rd_client_secret").remove("rd_access")
            .remove("rd_refresh").remove("rd_expires").apply()
    }

    // ---- Raw JSON blobs used by Library ----
    fun json(key: String): String = sp.getString(key, "") ?: ""
    fun putJson(key: String, value: String) = sp.edit().putString(key, value).apply()
}
