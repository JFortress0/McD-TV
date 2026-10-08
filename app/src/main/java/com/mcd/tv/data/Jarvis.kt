package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** One Ask Jarvis result: the TMDB title, Jarvis's one-line reason and its confidence (0..100). */
data class JarvisMatch(val title: Title, val why: String, val confidence: Int)

/** Everything Jarvis answered: matched titles (most likely first) and an optional follow-up question. */
data class JarvisAnswer(val matches: List<JarvisMatch>, val clarify: String)

/** An Ask Jarvis failure whose message is ready to show on screen. */
class JarvisException(message: String) : Exception(message)

/**
 * Ask Jarvis: the user describes a movie or show in plain words and gets back best guesses.
 * Calls the Anthropic Messages API directly with the key saved on this TV (Prefs.jarvisKey), then
 * resolves each guess to a TMDB title. The UI only ever calls this feature "Jarvis".
 */
object Jarvis {
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    private const val FALLBACK_MODEL = "claude-haiku-5-5"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_GUESSES = 6
    private const val MAX_QUESTION = 600

    const val NO_KEY = "Add your Jarvis key on the Control page to use Ask Jarvis."
    private const val BAD_KEY = "Your Jarvis key was rejected. Check it on the Control page."
    private const val BUSY = "Jarvis is busy. Try again in a moment."
    private const val OFFLINE = "Jarvis could not be reached. Check the TV's internet connection."
    private const val SLOW = "Jarvis took too long to answer. Try again."
    private const val DOWN = "Jarvis is unavailable right now. Try again in a moment."
    private const val CONFUSED = "Jarvis could not read that answer. Try again, or describe it another way."

    private val SYSTEM_PROMPT = """
        You identify films and TV series from vague descriptions: half-remembered plots, actors, scenes, quotes, settings.
        Return ONLY a JSON object, no prose and no code fences, exactly in this shape:
        {"guesses":[{"title":"Iron Man","year":2008,"type":"movie","confidence":92,"why":"one short sentence"}],"clarify":""}
        Rules:
        - Up to 6 guesses, ordered from most to least likely.
        - "title" is the official English release title. "year" is the release year (first air year for TV).
        - "type" is "movie" or "tv". "confidence" is an integer from 0 to 100.
        - "why" is one short sentence saying how the title matches the description.
        - Never invent titles. Only list real, released films and series.
        - If the description names a person or asks for a genre or mood, return the most fitting titles.
        - "clarify" is an optional short follow-up question when the description is too vague, otherwise "".
    """.trimIndent()

    /** True when a key is saved on this TV. */
    val configured: Boolean get() = Prefs.jarvisKey.isNotBlank()

    /** Set after the configured model was refused once, so later questions go straight to the fallback. */
    @Volatile private var modelOverride: String? = null

    // ---- Small LRU of the last 20 questions -> answers (in memory only) ----
    private const val CACHE_MAX = 20
    private val cache = object : LinkedHashMap<String, JarvisAnswer>(CACHE_MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JarvisAnswer>?): Boolean = size > CACHE_MAX
    }

    private fun cacheKey(q: String) = q.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()

    /**
     * Asks Jarvis about [question]. Throws [JarvisException] with a friendly message on any failure.
     * The origin setting is not applied to the results (the user asked for them); adult titles never appear.
     */
    suspend fun ask(question: String): JarvisAnswer {
        val q = question.trim().take(MAX_QUESTION)
        if (q.isEmpty()) throw JarvisException("Describe a movie or show first.")
        val key = Prefs.jarvisKey
        if (key.isBlank()) throw JarvisException(NO_KEY)
        val ck = cacheKey(q)
        synchronized(cache) { cache[ck] }?.let { return it }

        val text = call(key, q)
        val parsed = parse(text)
        val matches = resolve(parsed.first)
        val answer = JarvisAnswer(matches, parsed.second)
        if (matches.isNotEmpty() || answer.clarify.isNotBlank()) synchronized(cache) { cache[ck] = answer }
        return answer
    }

    // ---------------- API call ----------------

    private suspend fun call(key: String, q: String): String {
        var model = modelOverride ?: Prefs.jarvisModel.ifBlank { DEFAULT_MODEL }
        var withTemperature = true
        var triedFallback = false
        var triedNoTemp = false
        while (true) {
            try {
                return post(key, q, model, withTemperature)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val msg = e.message.orEmpty().lowercase(Locale.US)
                // Unknown / retired model: fall back once to the smaller model.
                if ((e.code == 404 || e.code == 400) && "model" in msg && !triedFallback && model != FALLBACK_MODEL) {
                    triedFallback = true
                    model = FALLBACK_MODEL
                    modelOverride = FALLBACK_MODEL
                    continue
                }
                // A model that does not take a temperature setting: retry once without it.
                if (e.code == 400 && "temperature" in msg && !triedNoTemp) {
                    triedNoTemp = true
                    withTemperature = false
                    continue
                }
                throw JarvisException(friendly(e.code))
            } catch (e: SocketTimeoutException) {
                throw JarvisException(SLOW)
            } catch (e: UnknownHostException) {
                throw JarvisException(OFFLINE)
            } catch (e: IOException) {
                throw JarvisException(OFFLINE)
            }
        }
    }

    private fun friendly(code: Int): String = when (code) {
        401, 403 -> BAD_KEY
        429, 529 -> BUSY
        in 500..599 -> DOWN
        else -> "Jarvis could not answer that (error $code). Try describing it another way."
    }

    private suspend fun post(key: String, q: String, model: String, withTemperature: Boolean): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 800)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", q)))
        if (withTemperature) body.put("temperature", 0.2)
        val reply = Http.postJson(
            API_URL,
            body.toString(),
            headers = mapOf("x-api-key" to key, "anthropic-version" to API_VERSION),
            timeoutMs = TIMEOUT_MS,
        )
        val o = runCatching { JSONObject(reply) }.getOrNull() ?: throw JarvisException(CONFUSED)
        val content = o.optJSONArray("content") ?: throw JarvisException(CONFUSED)
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    // ---------------- Parsing ----------------

    private data class Guess(val title: String, val year: Int?, val type: String?, val confidence: Int, val why: String)

    /** The first balanced {...} block in [s] (braces inside JSON strings are skipped), or null. */
    internal fun firstJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parse(text: String): Pair<List<Guess>, String> {
        val json = firstJsonObject(text) ?: throw JarvisException(CONFUSED)
        val o = runCatching { JSONObject(json) }.getOrNull() ?: throw JarvisException(CONFUSED)
        val arr = o.optJSONArray("guesses") ?: JSONArray()
        val guesses = (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = g.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            val year = g.opt("year")?.toString()?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2100 }
            val type = when (g.optString("type").trim().lowercase(Locale.US)) {
                "movie", "film" -> "movie"
                "tv", "show", "series", "tv series", "tv show", "miniseries" -> "tv"
                else -> null
            }
            val raw = g.optDouble("confidence", 50.0).let { if (it.isNaN()) 50.0 else it }
            val conf = (if (raw > 0.0 && raw <= 1.0) raw * 100.0 else raw).toInt().coerceIn(0, 100)
            Guess(title, year, type, conf, g.optString("why").trim().take(200))
        }.take(MAX_GUESSES)
        val clarify = o.optString("clarify").trim().take(240).let { if (it.equals("null", ignoreCase = true)) "" else it }
        return guesses to clarify
    }

    // ---------------- TMDB lookup ----------------

    private suspend fun resolve(guesses: List<Guess>): List<JarvisMatch> {
        if (guesses.isEmpty()) return emptyList()
        var firstError: Throwable? = null
        val found = coroutineScope {
            guesses.map { g ->
                async {
                    try {
                        val types = if (g.type != null) listOf(g.type) else listOf("movie", "tv")
                        var t: Title? = null
                        for (type in types) {
                            t = Tmdb.findTitle(type, g.title, g.year)
                            if (t != null) break
                        }
                        t?.let { JarvisMatch(it, g.why, g.confidence) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(this@Jarvis) { if (firstError == null) firstError = e }
                        null
                    }
                }
            }.awaitAll()
        }
        val matches = found.filterNotNull().distinctBy { "${it.title.type}:${it.title.id}" }
        // Every lookup failed (no TMDB key, no internet): say why instead of "no matches".
        if (matches.isEmpty()) firstError?.let { throw JarvisException(it.message ?: OFFLINE) }
        return matches
    }
}
