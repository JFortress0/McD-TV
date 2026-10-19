package com.mcd.tv.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * "tv_link" messages on the house topic: each TV tells the house its current setup link (relay id and key), so a
 * phone whose saved link went stale can find the TV again. A phone asks with {"type":"tv_who"}; a TV answers
 * with {"type":"tv_link","from","name","link":"<relay id>.<relay key>","ts"} plus, since October 18, 2026, who it is:
 * "label" (the name J gave it), "platform" ("Fire TV", "Google TV"), "maker" and "model" (Build values) and
 * "version" (the app). "name" stays the display name (label, else platform) for older phones. Both are encrypted
 * with the house key like every house message. Pure logic (no Android); HouseSync does the sending. Tests in TvLinkTest.
 */
object TvLink {
    /** A TV answers "tv_who" at most this often. */
    const val REPLY_MS = 20_000L
    /** A TV announces again after start when its link changed, or when this long passed (ntfy keeps 12 h). */
    const val ANNOUNCE_MS = 11 * 60 * 60_000L
    private const val NAME_MAX = 40

    private val ID = Regex("[a-z2-9]{10,40}")
    private val KEY = Regex("[A-Za-z0-9_-]{40,50}")

    /** Who a TV is, as sent in "tv_link" (all empty from TVs older than October 18, 2026). */
    data class Who(val label: String = "", val platform: String = "", val maker: String = "", val model: String = "", val version: String = "")

    /** A TV's link as read from a "tv_link" message. */
    data class Info(val from: String, val name: String, val id: String, val key: String, val ts: Long, val who: Who = Who())

    /** The "tv_link" message for this TV. [name] is the display name (label, else platform). */
    fun message(from: String, name: String, relayId: String, relayKey: String, ts: Long, who: Who = Who()): JSONObject =
        JSONObject()
            .put("type", "tv_link")
            .put("from", from)
            .put("name", cleanName(name))
            .put("link", "$relayId.$relayKey")
            .put("ts", ts)
            .put("label", TvIdentity.cleanLabel(who.label))
            .put("platform", who.platform.trim().take(NAME_MAX))
            .put("maker", who.maker.trim().take(NAME_MAX))
            .put("model", who.model.trim().take(NAME_MAX))
            .put("version", who.version.trim().take(20))

    /** A valid "tv_link" message as [Info], or null. */
    fun parse(m: JSONObject): Info? {
        if (m.optString("type") != "tv_link") return null
        val from = m.optString("from")
        if (!ID.matches(from)) return null
        val link = m.optString("link")
        val dot = link.indexOf('.')
        if (dot <= 0) return null
        val id = link.substring(0, dot)
        val key = link.substring(dot + 1)
        if (!ID.matches(id) || !KEY.matches(key)) return null
        val ts = m.optLong("ts", 0L)
        if (ts <= 0L) return null
        val who = Who(
            label = TvIdentity.cleanLabel(m.optString("label")),
            platform = m.optString("platform").trim().take(NAME_MAX),
            maker = m.optString("maker").trim().take(NAME_MAX),
            model = m.optString("model").trim().take(NAME_MAX),
            version = m.optString("version").trim().take(20),
        )
        return Info(from, cleanName(m.optString("name")), id, key, ts, who)
    }

    fun cleanName(name: String): String = name.trim().take(NAME_MAX).trim().ifEmpty { "TV" }

    /** True when a "tv_who" may be answered now ([lastAt]: the last answer, 0 = never). */
    fun replyDue(now: Long, lastAt: Long): Boolean = lastAt <= 0L || now - lastAt >= REPLY_MS || now < lastAt

    /** True when the start-up announce should go out: the link changed, or more than 11 hours passed. */
    fun announceDue(sigNow: String, sigLast: String, now: Long, lastAt: Long): Boolean =
        sigNow != sigLast || lastAt <= 0L || now - lastAt > ANNOUNCE_MS || now < lastAt

    /**
     * A short hash of the relay id (and the TV's label), kept to notice a changed link or name without storing
     * the id twice. A new label makes the TV announce itself again at the next start.
     */
    fun signature(relayId: String, label: String = ""): String {
        val d = MessageDigest.getInstance("SHA-256").digest((relayId + "|" + TvIdentity.cleanLabel(label)).toByteArray())
        return d.take(6).joinToString("") { "%02x".format(it) }
    }
}
