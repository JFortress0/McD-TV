package com.mcd.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Typing long keys and URLs with a TV remote is painful. While the Phone Setup screen is open,
 * the TV runs this tiny web page on your home Wi-Fi. Open it on your phone and paste
 * the TMDB key, addon URLs, playlist URL or a stream link. Only reachable on your own network.
 */
class PhoneSetupServer(private val onChange: (String) -> Unit) {
    val port = 8642
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun address(): String {
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: "this-tv-ip"
        return "http://$ip:$port"
    }

    fun start() {
        if (server != null) return
        val s = ServerSocket(port)
        server = s
        scope.launch {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun handle(c: Socket) {
        val input = c.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        var length = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        var message = ""
        if (requestLine.startsWith("POST")) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            message = save(parseForm(String(buf, 0, read)))
            onChange(message)
        }
        val body = page(message).toByteArray()
        val out = c.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun parseForm(s: String): Map<String, String> = s.split("&").filter { it.contains("=") }.associate {
        URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8").trim()
    }

    private fun save(f: Map<String, String>): String {
        val done = mutableListOf<String>()
        f["tmdb"]?.takeIf { it.isNotBlank() }?.let { Prefs.tmdbKey = it; done += "TMDB key saved" }
        f["server"]?.takeIf { it.isNotBlank() }?.let { Prefs.serverUrl = if (it.startsWith("http")) it else "https://$it"; done += "Server address saved" }
        f["magnet"]?.takeIf { it.isNotBlank() }?.let { m ->
            done += runCatching { "Added to Real-Debrid: " + runBlocking { RdCloud.addMagnet(m) } }
                .getOrElse { "Real-Debrid add failed: ${it.message}" }
        }
        f["m3u"]?.takeIf { it.isNotBlank() }?.let { Prefs.m3uUrl = it; done += "Playlist saved" }
        f["stream"]?.takeIf { it.isNotBlank() }?.let { Prefs.customUrl = it; done += "Stream link saved" }
        f["addon"]?.takeIf { it.isNotBlank() }?.let { url ->
            done += runCatching { "Addon added: " + runBlocking { Addons.install(url).name } }
                .getOrElse { "Addon failed: ${it.message}" }
        }
        f["site"]?.takeIf { it.isNotBlank() }?.let { raw ->
            val url = if (raw.startsWith("http")) raw else "https://$raw"
            val name = f["siteName"]?.takeIf { it.isNotBlank() } ?: url.removePrefix("https://").removePrefix("http://").substringBefore("/")
            Prefs.websites = Prefs.websites + (name to url)
            done += "Website added: $name"
        }
        f["removeSite"]?.takeIf { it.isNotBlank() }?.let { u -> Prefs.websites = Prefs.websites.filterNot { it.second == u }; done += "Website removed" }
        f["remove"]?.takeIf { it.isNotBlank() }?.let { Addons.remove(it); done += "Addon removed" }
        return done.joinToString(" • ").ifBlank { "Nothing to save" }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private fun page(msg: String): String {
        val addons = Prefs.addonUrls.joinToString("") { u ->
            "<li><code>${esc(u.take(60))}${if (u.length > 60) "…" else ""}</code>" +
                "<form method=post style='display:inline'><input type=hidden name=remove value=\"${esc(u)}\"><button>Remove</button></form></li>"
        }.ifBlank { "<li>None yet</li>" }
        val sites = Prefs.websites.joinToString("") { (n, u) ->
            "<li>${esc(n)} <code>${esc(u.take(50))}</code>" +
                "<form method=post style='display:inline'><input type=hidden name=removeSite value=\"${esc(u)}\"><button>Remove</button></form></li>"
        }.ifBlank { "<li>None yet</li>" }
        return """
<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>McD TV setup</title>
<style>body{font-family:-apple-system,Helvetica,Arial;background:#060B1A;color:#fff;margin:0;padding:20px}
h1{font-style:italic;font-weight:900}h1 span{background:#D61828;padding:0 8px;border-radius:4px}
label{display:block;margin:18px 0 6px;color:#9AA3C0;font-size:14px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #333;background:#1A2244;color:#fff;font-size:16px}
button{margin-top:12px;background:#D61828;color:#fff;border:0;border-radius:8px;padding:12px 18px;font-weight:700;font-size:16px}
.msg{background:#14304a;padding:10px;border-radius:8px}code{font-size:12px}li{margin:8px 0}</style></head><body>
<h1>McD <span>TV</span> setup</h1>${if (msg.isNotBlank()) "<p class=msg>${esc(msg)}</p>" else ""}
<form method=post>
<label>TMDB API key (posters and info). ${if (Prefs.tmdbKey.isNotBlank()) "✅ set" else "Not set"}</label><input name=tmdb placeholder="Paste key or read token">
<label>Add an addon (manifest URL or stremio:// link)</label><input name=addon placeholder="https://…/manifest.json">
<label>Live TV playlist (M3U URL). ${if (Prefs.m3uUrl.isNotBlank()) "✅ set" else "Not set"}</label><input name=m3u placeholder="https://…/playlist.m3u">
<label>McD TV server address (for accounts). ${if (Prefs.serverUrl.isNotBlank()) "✅ " + esc(Prefs.serverUrl) else "Not set"}</label><input name=server placeholder="https://tv.yourdomain.com">
<label>Add a magnet link to your Real-Debrid cloud (plays under My List &gt; Real-Debrid Cloud)</label><input name=magnet placeholder="magnet:?xt=urn:btih:…">
<label>Direct stream link (shows as My Stream)</label><input name=stream placeholder="https://…/video.m3u8">
<label>Add a website (opens in the TV's built-in browser, under Sports &gt; Websites)</label><input name=site placeholder="https://example.com">
<label>Name for that website (optional)</label><input name=siteName placeholder="My site">
<button>Save to TV</button></form>
<h3>Websites</h3><ul>$sites</ul>
<h3>Installed addons</h3><ul>$addons</ul>
<p style="color:#9AA3C0;font-size:13px">Real-Debrid connects on the TV itself: Settings &gt; Connect Real-Debrid.</p>
</body></html>""".trimIndent()
    }
}
