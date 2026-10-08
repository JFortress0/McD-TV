package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional McD TV account on your own server (see server/README.md).
 * Signed in, your lists, history, addons and Real-Debrid link follow you to any TV.
 */
object Account {
    val signedIn: Boolean get() = Prefs.accountToken.isNotBlank() && Prefs.serverUrl.isNotBlank()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val base = Prefs.serverUrl
        if (base.isBlank()) throw IllegalStateException("Add your server address first (Phone setup)")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json")
            if (Prefs.accountToken.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${Prefs.accountToken}")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw HttpException(code, json.optString("error", text))
            json
        } finally {
            c.disconnect()
        }
    }

    private suspend fun signIn(path: String, user: String, pass: String, invite: String) {
        val r = call("POST", path, JSONObject().put("username", user).put("password", pass).put("invite", invite))
        Prefs.accountToken = r.getString("token")
        Prefs.accountName = r.optString("username", user)
        pull(force = true)
    }

    suspend fun login(user: String, pass: String) = signIn("/api/login", user, pass, "")
    suspend fun register(user: String, pass: String, invite: String) = signIn("/api/register", user, pass, invite)

    data class Pairing(val id: String, val code: String, val expiresSec: Int)

    /** TV sign-in by code: the TV shows this code; you approve it on the web page. */
    suspend fun startPairing(): Pairing {
        val r = call("POST", "/api/pair/start")
        return Pairing(r.getString("id"), r.getString("code"), r.optInt("expiresIn", 600))
    }

    /** Returns true once the code was approved and this TV is signed in. */
    suspend fun pollPairing(p: Pairing): Boolean {
        val r = call("GET", "/api/pair/poll?id=${p.id}")
        if (r.optBoolean("waiting")) return false
        Prefs.accountToken = r.getString("token")
        Prefs.accountName = r.optString("username")
        pull(force = true)
        return true
    }

    suspend fun logout() {
        runCatching { push() }
        runCatching { call("POST", "/api/logout") }
        Prefs.accountToken = ""
        Prefs.accountName = ""
    }

    /** Server copy -> this TV, when the server has something newer (or always, right after sign-in). */
    suspend fun pull(force: Boolean = false) {
        if (!signedIn) return
        val r = call("GET", "/api/sync")
        val data = r.optJSONObject("data")
        val at = r.optLong("updatedAt")
        if (data == null) { push(); return } // new account: upload this TV's setup
        if (force || at > Prefs.lastSyncAt) {
            Prefs.importAll(data)
            Prefs.lastSyncAt = at
        }
    }

    /** This TV -> server. If the web page saved something newer, take that instead. */
    suspend fun push() {
        if (!signedIn) return
        val server = call("GET", "/api/sync")
        if (server.optJSONObject("data") != null && server.optLong("updatedAt") > Prefs.lastSyncAt) {
            pull()
            return
        }
        val now = System.currentTimeMillis()
        call("PUT", "/api/sync", JSONObject().put("data", Prefs.exportAll()).put("updatedAt", now))
        Prefs.lastSyncAt = now
    }
}
