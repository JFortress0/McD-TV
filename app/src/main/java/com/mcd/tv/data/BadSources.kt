package com.mcd.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Files flagged as incomplete (see [SourceHealth]), shared by the whole house: kept on this TV (pref
 * `bad_sources`) and on the relay's resume store with the house key, so the phones and the other TVs
 * skip them too. Profile tag "badsrc", key tag "title|<titleKey>", value [SourceHealth.encode].
 * The web app reads and writes the same entries (docs/app/index.html, RC.badGet and RC.badPut).
 */
object BadSources {
    private const val PREF = "bad_sources"
    private const val ENDPOINT = "https://jarvis-rd.netlify.app/resume"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Goes up when a flag was added or merged in (the source list redraws). */
    var version by mutableIntStateOf(0)
        private set

    private fun all(): JSONObject = runCatching { JSONObject(Prefs.json(PREF).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    fun flags(titleKey: String): List<SourceHealth.Flag> = SourceHealth.decode(all().optString(titleKey).ifBlank { null })

    fun flagged(titleKey: String): Set<String> = flags(titleKey).map { it.key }.toSet()

    private fun saveLocal(titleKey: String, flags: List<SourceHealth.Flag>) = synchronized(lock) {
        val o = all()
        o.put(titleKey, SourceHealth.encode(flags, System.currentTimeMillis()))
        // Keep the pref small: the 200 most recently touched titles.
        if (o.length() > 200) {
            val keys = o.keys().asSequence().toList()
            keys.sortedBy { SourceHealth.decode(o.optString(it)).maxOfOrNull { f -> f.at } ?: 0L }.take(o.length() - 200).forEach { o.remove(it) }
        }
        Prefs.putJson(PREF, o.toString())
    }

    /** This file stopped for good at [stoppedMs]: flag it here, then on the shared copy. */
    fun mark(titleKey: String, sourceKey: String, stoppedMs: Long) {
        val now = System.currentTimeMillis()
        val merged = SourceHealth.merge(listOf(SourceHealth.Flag(sourceKey, stoppedMs, now)), flags(titleKey), now)
        saveLocal(titleKey, merged)
        version++
        if (Prefs.qaMode) return
        scope.launch {
            val key = ResumeCloud.houseKey() ?: return@launch
            runCatching {
                val shared = fetch(key, titleKey)
                push(key, titleKey, SourceHealth.merge(merged, shared, System.currentTimeMillis()))
            }
        }
    }

    /** Merges the shared flags for [titleKey] into this TV's (one request). Call when a source list opens. */
    suspend fun refresh(titleKey: String) {
        if (Prefs.qaMode) return
        val key = ResumeCloud.houseKey() ?: return
        val shared = withContext(Dispatchers.IO) { runCatching { fetch(key, titleKey) }.getOrDefault(emptyList()) }
        if (shared.isEmpty()) return
        val local = flags(titleKey)
        val merged = SourceHealth.merge(local, shared, System.currentTimeMillis())
        if (merged.map { it.key }.toSet() != local.map { it.key }.toSet()) {
            saveLocal(titleKey, merged)
            withContext(Dispatchers.Main) { version++ }
        }
    }

    private fun url(key: ByteArray) =
        "$ENDPOINT?h=${ResumeCloud.enc(ResumeCloud.tag(key, "house"))}&p=${ResumeCloud.enc(ResumeCloud.tag(key, "badsrc"))}"

    private fun fetch(key: ByteArray, titleKey: String): List<SourceHealth.Flag> {
        val c = URL(url(key)).openConnection() as HttpURLConnection
        val text = try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (c.responseCode != 200) return emptyList()
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val rec = JSONObject(text).optJSONObject("e")?.optJSONObject(ResumeCloud.tag(key, "title|$titleKey")) ?: return emptyList()
        return SourceHealth.decode(ResumeCloud.open(key, rec.optString("v")))
    }

    private fun push(key: ByteArray, titleKey: String, flags: List<SourceHealth.Flag>) {
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("k", ResumeCloud.tag(key, "title|$titleKey"))
            .put("t", now)
            .put("v", ResumeCloud.seal(key, SourceHealth.encode(flags, now)))
            .toString()
        val c = URL(url(key)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    }
}
