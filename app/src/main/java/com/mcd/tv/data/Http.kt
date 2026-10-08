package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { request("GET", url, headers, null) }

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { request("POST", url, headers, form(fields)) }

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 25_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }
}
