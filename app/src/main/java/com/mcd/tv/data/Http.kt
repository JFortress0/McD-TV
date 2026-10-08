package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, body: String) : IOException("HTTP $code ${body.take(160)}")

/** Minimal HTTP client on top of HttpURLConnection (built into Android; no extra library). */
object Http {
    private const val UA = "McDTV/0.2 (Android TV)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    /** Largest response body we will read into memory. Anything bigger is not an API reply. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** timeoutMs (optional) shortens the connect and read timeouts for this one call. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int? = null): String =
        withContext(Dispatchers.IO) { request("GET", url, headers, null, timeoutMs = timeoutMs) }

    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { request("DELETE", url, headers, null) }

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { request("POST", url, headers, form(fields)) }

    /** POST a plain-text body (used for the ntfy relay). */
    suspend fun postText(url: String, body: String): String =
        withContext(Dispatchers.IO) { request("POST", url, mapOf("Content-Type" to "text/plain"), body, raw = true) }

    /**
     * POST a JSON body with extra [headers] (API keys etc.). [timeoutMs] sets the read timeout for this call.
     * Same 8 MB reply cap and HttpException on non-2xx as every other call.
     */
    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int? = null): String =
        withContext(Dispatchers.IO) {
            request("POST", url, headers + ("Content-Type" to "application/json"), body, raw = true, timeoutMs = timeoutMs)
        }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        raw: Boolean = false,
        timeoutMs: Int? = null,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = if (timeoutMs != null) minOf(15_000, timeoutMs) else 15_000
            c.readTimeout = timeoutMs ?: 25_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (!raw) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            if (code !in 200..299) {
                val err = runCatching { stream?.let { readCapped(it, 64 * 1024, url, truncate = true) } }.getOrNull() ?: ""
                throw HttpException(code, err)
            }
            return stream?.let { readCapped(it, MAX_BYTES, url, truncate = false) } ?: ""
        } finally {
            c.disconnect()
        }
    }

    /** Reads a body as UTF-8, refusing (or, for error bodies, truncating) anything over max bytes. */
    private fun readCapped(input: InputStream, max: Int, url: String, truncate: Boolean): String = input.use { s ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > max) {
                if (truncate) {
                    out.write(buf, 0, max - out.size())
                    break
                }
                val host = runCatching { URL(url).host }.getOrDefault("server")
                throw IOException("Response from $host is too large (over ${max / (1024 * 1024)} MB)")
            }
            out.write(buf, 0, n)
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }
}
