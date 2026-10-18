package com.mcd.tv.data

import org.json.JSONObject

/**
 * Movies and series from the IPTV provider's on-demand library (Xtream Codes player_api), matched to
 * Jarvis titles by TMDB id. Pure code (no Android): parsing, the compact index, episode lookup, links
 * and labels. IptvVod.kt does the downloads and caching on the TV.
 *
 * Links built here hold the playlist's username and password. Never log or show them.
 */
object XtreamVod {
    /** Addon name of every IPTV source in the source list. */
    const val ADDON = "Your IPTV provider"

    /** At most this many copies of one title are offered (providers list several per title). */
    const val MAX_PER_TITLE = 8

    fun isIptv(s: StreamSource): Boolean = s.addon == ADDON && s.infoHash == null

    // ------------------------------------------------------------------ account

    /** Server, username and password of an Xtream Codes link (.../get.php?username=..&password=..). */
    class Account(val base: String, val user: String, val pass: String) {
        /** Identifies the account in the cache file without storing the credentials. */
        val key: String get() = sha256("$base|$user|$pass").take(24)

        fun api(action: String? = null): String {
            val a = "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}"
            return if (action == null) a else "$a&action=$action"
        }

        fun movieUrl(item: Item): String = "$base/movie/$user/$pass/${item.id}.${item.ext.ifBlank { "mp4" }}"
        fun episodeUrl(ep: Episode): String = "$base/series/$user/$pass/${ep.id}.${ep.ext.ifBlank { "mp4" }}"

        override fun toString() = "Account(hidden)"

        companion object {
            fun of(url: String): Account? = runCatching {
                val u = java.net.URI(url.trim())
                val path = u.path.orEmpty()
                if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
                val q = (u.rawQuery ?: return null).split("&").associate {
                    it.substringBefore("=").lowercase() to java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
                }
                val user = q["username"]?.takeIf { it.isNotBlank() } ?: return null
                val pass = q["password"]?.takeIf { it.isNotBlank() } ?: return null
                val port = if (u.port > 0) ":${u.port}" else ""
                val dir = path.substringBeforeLast('/')
                Account("${u.scheme}://${u.host}$port$dir", user, pass)
            }.getOrNull()

            private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        }
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ index

    /** One movie (id = stream_id) or series (id = series_id) in the provider's library. */
    data class Item(val id: String, val name: String, val ext: String)

    /** TMDB id to the provider's copies of that title. */
    class Index(val movies: Map<Int, List<Item>>, val series: Map<Int, List<Item>>) {
        val size: Int get() = movies.size + series.size
    }

    /** The tmdb field is a string or a number, and "" or "0" when missing. */
    fun tmdbOf(v: Any?): Int? {
        val s = v?.toString()?.trim() ?: return null
        val n = s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: return null
        return if (n in 1..Int.MAX_VALUE.toLong()) n.toInt() else null
    }

    private val spaces = Regex("\\s+")
    fun cleanName(name: String): String = name.replace(spaces, " ").trim()

    /**
     * Reads a get_vod_streams ([idKey] "stream_id") or get_series ([idKey] "series_id") reply as a stream,
     * keeping only titles with a TMDB id. The 10+ MB reply never sits in memory as one string.
     */
    fun readList(reader: java.io.Reader, idKey: String, into: HashMap<Int, MutableList<Item>> = HashMap()): HashMap<Int, MutableList<Item>> {
        val keys = setOf(idKey, "name", "tmdb", "container_extension")
        JsonScan(reader).eachObject(keys) { o ->
            val tmdb = tmdbOf(o["tmdb"]) ?: return@eachObject
            val id = o[idKey]?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@eachObject
            val list = into.getOrPut(tmdb) { ArrayList(2) }
            if (list.size < MAX_PER_TITLE * 2) {
                list += Item(id, cleanName(o["name"].orEmpty()), o["container_extension"].orEmpty().trim().lowercase())
            }
        }
        return into
    }

    /** Compact index file: a header line, then one tab-separated line per item. */
    fun encode(index: Index, accountKey: String, savedAt: Long, out: Appendable) {
        out.append("xv1\t").append(accountKey).append('\t').append(savedAt.toString()).append('\n')
        fun put(kind: Char, m: Map<Int, List<Item>>) {
            for ((tmdb, items) in m) for (it in items) {
                out.append(kind).append('\t').append(tmdb.toString()).append('\t').append(it.id).append('\t')
                    .append(it.ext).append('\t').append(it.name.replace('\t', ' ').replace('\n', ' ')).append('\n')
            }
        }
        put('m', index.movies)
        put('s', index.series)
    }

    class Saved(val accountKey: String, val savedAt: Long, val index: Index)

    fun decode(lines: Sequence<String>): Saved? {
        val it = lines.iterator()
        if (!it.hasNext()) return null
        val head = it.next().split('\t')
        if (head.size < 3 || head[0] != "xv1") return null
        val savedAt = head[2].toLongOrNull() ?: return null
        val movies = HashMap<Int, MutableList<Item>>()
        val series = HashMap<Int, MutableList<Item>>()
        while (it.hasNext()) {
            val p = it.next().split('\t', limit = 5)
            if (p.size < 5) continue
            val tmdb = p[1].toIntOrNull() ?: continue
            val target = when (p[0]) { "m" -> movies; "s" -> series; else -> continue }
            target.getOrPut(tmdb) { ArrayList(2) } += Item(p[2], p[4], p[3])
        }
        return Saved(head[1], savedAt, Index(movies, series))
    }

    // ------------------------------------------------------------------ episodes

    data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val ext: String, val height: Int?)

    /** Episodes of a get_series_info reply. Keys of "episodes" are season numbers. */
    fun parseSeriesInfo(json: String): List<Episode> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val eps = root.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (key in eps.keys()) {
            val arr = eps.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val season = tmdbOrZero(o.opt("season")) ?: tmdbOrZero(key) ?: continue
                val num = tmdbOrZero(o.opt("episode_num")) ?: -1
                val h = o.optJSONObject("info")?.optJSONObject("video")?.optInt("height", 0)?.takeIf { it > 0 }
                out += Episode(id, season, num, cleanName(o.optString("title")), o.optString("container_extension").trim().lowercase(), h)
            }
        }
        return out
    }

    private fun tmdbOrZero(v: Any?): Int? = v?.toString()?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    private val sxe = Regex("\\bS(\\d{1,3})\\s?E(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

    /** The episode by season and episode_num, else by SxxEyy in its title (seasons can start at 0). */
    fun findEpisode(eps: List<Episode>, season: Int, episode: Int): Episode? =
        eps.firstOrNull { it.season == season && it.episode == episode }
            ?: eps.firstOrNull { e ->
                sxe.find(e.title)?.let { it.groupValues[1].toInt() == season && it.groupValues[2].toInt() == episode } == true
            }

    // ------------------------------------------------------------------ labels and sources

    private val prefixRx = Regex("^[A-Z0-9+]{1,6}(-[A-Z0-9+]{1,6}){0,2}$")

    /** Short tags for the source row: name prefix ("EN", "4K", "NF"), resolution from the height, file type. */
    fun tags(name: String, ext: String, height: Int?): List<String> {
        val out = ArrayList<String>()
        val prefix = name.substringBefore(" - ", "").trim()
        if (prefixRx.matches(prefix)) prefix.split('-').filter { it.isNotBlank() }.forEach { out += it }
        val res = when {
            height == null || height <= 0 -> null
            height >= 1900 -> "4K"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            else -> "SD"
        }
        if (res != null && res !in out) out += res
        // "TS" would read as a CAM release, so MPEG-TS files get no file-type tag.
        if (ext.isNotBlank() && ext != "ts") out += ext.uppercase()
        return out.distinct()
    }

    /** Title without the provider's prefix ("EN - The Irishman (2019)" -> "The Irishman (2019)"). */
    fun displayTitle(name: String): String {
        val prefix = name.substringBefore(" - ", "").trim()
        return if (prefixRx.matches(prefix)) name.substringAfter(" - ").trim() else name
    }

    private fun source(name: String, url: String, tags: List<String>, ua: String?, season: Int?, episode: Int?): StreamSource {
        val q = when {
            "4K" in tags -> "4K"
            "1080p" in tags -> "1080p"
            "720p" in tags -> "720p"
            else -> "Unknown"
        }
        return StreamSource(
            addon = ADDON,
            name = (listOf("IPTV") + tags).joinToString(" "),
            title = displayTitle(name),
            url = url,
            infoHash = null,
            fileIdx = null,
            quality = q,
            sizeText = "",
            sizeGb = 0.0,
            seeders = null,
            cached = false,
            headers = if (ua.isNullOrBlank()) emptyMap() else mapOf("User-Agent" to ua),
            season = season,
            episode = episode,
        )
    }

    fun movieSources(acct: Account, items: List<Item>, ua: String?): List<StreamSource> =
        items.take(MAX_PER_TITLE).map { source(it.name, acct.movieUrl(it), tags(it.name, it.ext, null), ua, null, null) }

    fun episodeSource(acct: Account, ep: Episode, ua: String?): StreamSource =
        source(ep.title, acct.episodeUrl(ep), tags(ep.title, ep.ext, ep.height), ua, ep.season, ep.episode)
}

/**
 * A small streaming JSON reader for one top-level array of objects. Keeps only the scalar fields named in
 * `keys` and skips everything else, so a large reply is read in one pass with little memory.
 */
internal class JsonScan(private val r: java.io.Reader) {
    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var len = 0

    private fun peek(): Int {
        if (pos >= len) {
            len = r.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) { len = 0; return -1 }
        }
        return buf[pos].code
    }

    private fun next(): Int { val c = peek(); if (c >= 0) pos++; return c }

    private fun ws(): Int {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) pos++ else return c
        }
    }

    /** Calls [onObject] for each object in the top-level array. Anything else (an error object) yields nothing. */
    fun eachObject(keys: Set<String>, onObject: (Map<String, String?>) -> Unit) {
        if (ws() != '['.code) return
        next()
        while (true) {
            when (ws()) {
                -1, ']'.code -> return
                ','.code -> next()
                '{'.code -> onObject(readObject(keys))
                else -> skipValue()
            }
        }
    }

    private fun readObject(keys: Set<String>): Map<String, String?> {
        next() // {
        val out = HashMap<String, String?>(8)
        while (true) {
            when (ws()) {
                -1 -> return out
                '}'.code -> { next(); return out }
                ','.code -> next()
                '"'.code -> {
                    val key = readString(keep = true)!!
                    if (ws() == ':'.code) next()
                    if (key in keys) out[key] = readScalar() else skipValue()
                }
                else -> next()
            }
        }
    }

    /** A string, number, true, false or null as text; objects and arrays are skipped (null). */
    private fun readScalar(): String? {
        return when (ws()) {
            '"'.code -> readString(keep = true)
            '{'.code, '['.code -> { skipValue(); null }
            else -> {
                val sb = StringBuilder()
                while (true) {
                    val c = peek()
                    if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
                    sb.append(c.toChar()); pos++
                }
                sb.toString().takeIf { it != "null" }
            }
        }
    }

    private fun readString(keep: Boolean): String? {
        next() // opening quote
        val sb = if (keep) StringBuilder() else null
        while (true) {
            val c = next()
            when (c) {
                -1, '"'.code -> return sb?.toString()
                '\\'.code -> {
                    val e = next()
                    when (e) {
                        'n'.code -> sb?.append('\n')
                        't'.code -> sb?.append('\t')
                        'r'.code -> sb?.append('\r')
                        'b'.code -> sb?.append('\b')
                        'f'.code -> sb?.append('\u000c')
                        'u'.code -> {
                            var v = 0
                            repeat(4) { v = v * 16 + Character.digit(next().coerceAtLeast(0), 16).coerceAtLeast(0) }
                            sb?.append(v.toChar())
                        }
                        -1 -> return sb?.toString()
                        else -> sb?.append(e.toChar())
                    }
                }
                else -> sb?.append(c.toChar())
            }
        }
    }

    private fun skipValue() {
        when (ws()) {
            '"'.code -> readString(keep = false)
            '{'.code, '['.code -> {
                var depth = 0
                while (true) {
                    when (peek()) {
                        -1 -> return
                        '"'.code -> { readString(keep = false); continue }
                        '{'.code, '['.code -> depth++
                        '}'.code, ']'.code -> { depth--; if (depth == 0) { pos++; return } }
                    }
                    pos++
                }
            }
            else -> readScalar()
        }
    }
}
