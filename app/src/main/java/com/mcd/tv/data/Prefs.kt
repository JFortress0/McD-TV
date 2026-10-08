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

    /** Which countries' titles to show (see OriginFilter). Default hides Asian-made titles. */
    var origin: OriginFilter
        get() = OriginFilter.entries.firstOrNull { it.key == str("origin_filter") } ?: OriginFilter.NO_ASIA
        set(v) = put("origin_filter", v.key)

    /** Like HuberTV's "Slow connection": prefer smaller files and 720p when picking a source. */
    var slowConnection: Boolean
        get() = sp.getBoolean("slow_connection", false)
        set(v) = sp.edit().putBoolean("slow_connection", v).apply()

    // ---- Metadata ----
    var tmdbKey: String
        get() = str("tmdb_key").ifBlank { com.mcd.tv.BuildConfig.TMDB_KEY }
        set(v) = put("tmdb_key", v)

    /** MDBList key for IMDb / Rotten Tomatoes scores. A key saved in the app wins over the built-in one. */
    var mdblistKey: String
        get() = str("mdblist_key").ifBlank { com.mcd.tv.BuildConfig.MDBLIST_KEY }
        set(v) = put("mdblist_key", v)

    // ---- Live TV ----
    var m3uUrl: String
        get() = str("m3u_url")
        set(v) = put("m3u_url", v)

    /** Guards the JSON list settings, which are read-modify-written from several threads. */
    private val listLock = Any()

    private fun strList(key: String): List<String> = synchronized(listLock) {
        runCatching {
            val a = JSONArray(sp.getString(key, "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun putStrList(key: String, v: List<String>) = synchronized(listLock) {
        sp.edit().putString(key, JSONArray(v.distinct()).toString()).apply()
    }

    /** Favorite Live TV channels, by stream URL. */
    val liveFavorites: List<String> get() = strList("live_favorites")

    /** Adds or removes a favorite channel. Returns true when it is now a favorite. */
    fun toggleLiveFavorite(url: String): Boolean = synchronized(listLock) {
        val cur = strList("live_favorites")
        val nowFav = url !in cur
        putStrList("live_favorites", if (nowFav) cur + url else cur - url)
        nowFav
    }

    /** Recently watched Live TV channels (stream URLs), most recent first, at most 20. */
    val liveRecents: List<String> get() = strList("live_recents")

    /** Records a channel as watched: moves it to the front of Recent and makes it the last channel. */
    fun addLiveRecent(url: String) = synchronized(listLock) {
        putStrList("live_recents", (listOf(url) + strList("live_recents").filter { it != url }).take(20))
        put("live_last_url", url)
    }

    /** The Live TV channel watched last ("" if none). */
    var liveLastUrl: String
        get() = str("live_last_url")
        set(v) = put("live_last_url", v)

    // ---- Addons (Stremio protocol): list of manifest URLs ----
    var addonUrls: List<String>
        get() = synchronized(listLock) {
            runCatching {
                val a = JSONArray(sp.getString("addon_urls", "[]"))
                List(a.length()) { a.getString(it) }
            }.getOrDefault(emptyList())
        }
        set(v) = synchronized(listLock) { sp.edit().putString("addon_urls", JSONArray(v.distinct()).toString()).apply() }

    /** Atomically changes the addon list (no lost updates between threads). Returns the new list. */
    fun updateAddonUrls(change: (List<String>) -> List<String>): List<String> = synchronized(listLock) {
        val next = change(addonUrls).distinct()
        addonUrls = next
        next
    }

    // ---- Websites you add (opened in the built-in browser): list of name|url ----
    var websites: List<Pair<String, String>>
        get() = synchronized(listLock) {
            runCatching {
                val a = JSONArray(sp.getString("websites", "[]"))
                List(a.length()) { a.getString(it) }.map { it.substringBefore("|") to it.substringAfter("|") }
            }.getOrDefault(emptyList())
        }
        set(v) = synchronized(listLock) {
            sp.edit().putString("websites", JSONArray(v.distinctBy { it.second }.map { "${it.first}|${it.second}" }).toString()).apply()
        }

    /** Atomically changes the website list. Returns the new list. */
    fun updateWebsites(change: (List<Pair<String, String>>) -> List<Pair<String, String>>): List<Pair<String, String>> =
        synchronized(listLock) {
            val next = change(websites).distinctBy { it.second }
            websites = next
            next
        }

    /**
     * Automated-QA switch. When true, the home-network page answers /qa-relay on loopback (adb forward)
     * with the internet setup link. Off by default; never synced to the account.
     */
    var qaMode: Boolean
        get() = sp.getBoolean("qa_mode", false)
        set(v) = sp.edit().putBoolean("qa_mode", v).apply()

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

    // ---- McD TV account server (optional) ----
    var serverUrl: String
        get() = str("server_url")
        set(v) = put("server_url", v.trimEnd('/'))
    var accountToken: String
        get() = str("account_token")
        set(v) = put("account_token", v)
    var accountName: String
        get() = str("account_name")
        set(v) = put("account_name", v)
    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0L)
        set(v) = sp.edit().putLong("last_sync_at", v).apply()

    /** Keys that belong to this TV only and never sync to the account. */
    private val localOnly = setOf("server_url", "account_token", "account_name", "last_sync_at", "relay_id", "relay_key", "relay_since", "qa_mode")

    // ---- Internet setup link (ntfy relay) ----
    var relayId: String
        get() = str("relay_id")
        set(v) = put("relay_id", v)
    var relayKey: String
        get() = str("relay_key")
        set(v) = put("relay_key", v)
    var relaySince: String
        get() = str("relay_since")
        set(v) = put("relay_since", v)

    /** Every synced setting and list, as JSON, for the account server. */
    fun exportAll(): org.json.JSONObject {
        val o = org.json.JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in localOnly || v == null) return@forEach
            val t = when (v) { is Boolean -> "b"; is Long -> "l"; is Int -> "i"; is Float -> "f"; else -> "s" }
            o.put(k, org.json.JSONObject().put("t", t).put("v", v.toString()))
        }
        return o
    }

    /** Replaces this TV's synced settings and lists with the account's copy. */
    fun importAll(o: org.json.JSONObject) = synchronized(listLock) {
        val e = sp.edit()
        sp.all.keys.filter { it !in localOnly }.forEach { e.remove(it) }
        o.keys().forEach { k ->
            if (k in localOnly) return@forEach
            val item = o.getJSONObject(k)
            val v = item.optString("v")
            when (item.optString("t")) {
                "b" -> e.putBoolean(k, v.toBoolean())
                "l" -> e.putLong(k, v.toLongOrNull() ?: 0L)
                "i" -> e.putInt(k, v.toIntOrNull() ?: 0)
                "f" -> e.putFloat(k, v.toFloatOrNull() ?: 0f)
                else -> e.putString(k, v)
            }
        }
        e.apply()
    }

    // ---- Raw JSON blobs used by Library ----
    fun json(key: String): String = sp.getString(key, "") ?: ""
    fun putJson(key: String, value: String) = sp.edit().putString(key, value).apply()
}
