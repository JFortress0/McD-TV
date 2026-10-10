package com.mcd.tv.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates, so new versions don't need Downloader.
 * Checks the latest GitHub release (tag "v0.2.N", where N is the CI run number and the app's versionCode),
 * downloads Jarvis.apk into the app's cache and opens the system installer. You press Install once.
 * Android 8+ asks one time to let Jarvis install apps (Fire TV: Settings > My Fire TV > Developer options
 * > Install unknown apps > Jarvis).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/JFortress0/McD-TV/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    data class Release(val code: Long, val name: String, val apkUrl: String, val bytes: Long)

    /** A newer release than the installed app, or null. */
    var available by mutableStateOf<Release?>(null)
        private set

    /** Progress or error text for the update screen. Blank when idle. */
    var status by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** The update screen opens by itself once per app launch. */
    var promptShown = false

    private var lastCheck = 0L

    @Suppress("DEPRECATION")
    fun installedCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(0L)

    fun installedName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Looks for a newer release (at most every 6 hours unless [force]). Errors leave [available] as it was. */
    suspend fun check(ctx: Context, force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return available
        lastCheck = now
        return try {
            val o = JSONObject(Http.get(LATEST, mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 15_000))
            val tag = o.optString("tag_name")
            val code = tag.substringAfterLast('.').toLongOrNull() ?: return available
            val assets = o.optJSONArray("assets")
            var url = ""
            var bytes = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name") == "Jarvis.apk") { url = a.optString("browser_download_url"); bytes = a.optLong("size") }
            }
            if (url.isBlank()) return available
            available = if (code > installedCode(ctx)) Release(code, tag.removePrefix("v"), url, bytes) else null
            available
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (force) status = "Couldn't check for updates. Check the TV's internet and try again."
            available
        }
    }

    /** Android 8+: has the user let Jarvis install apps? */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" switch for Jarvis. False when this TV has no such screen (most Fire TVs). */
    fun openInstallPermission(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** Downloads [r] (progress in [status]) and opens the system installer. */
    suspend fun downloadAndInstall(ctx: Context, r: Release) {
        if (busy) return
        busy = true
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, "Jarvis.apk")
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() }
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = true // GitHub sends release files from another host
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (code ${c.responseCode})")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.bytes
                    c.inputStream.use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var shown = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != shown) {
                                    shown = pct
                                    withContext(Dispatchers.Main) { status = if (pct >= 0) "Downloading… $pct%" else "Downloading…" }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            }
            status = "Opening the installer. Press Install."
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            status = "Update failed: ${e.message ?: "unknown error"}. Try again, or install with Downloader."
        } finally {
            busy = false
        }
    }
}
