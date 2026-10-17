package com.mcd.tv.data

/**
 * Plain messages for Real-Debrid refusals, so a TV shows what to do instead of "HTTP 403 {...}".
 * RD replies with JSON like {"error":"ip_not_allowed","error_code":22}. Pure logic; tests in RdErrorsTest.
 */
/** A Real-Debrid refusal with its error code (35 = RD refused this source). */
class RdRefused(val code: Int?, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object RdErrors {
    private val CODE = Regex("\"error_code\"\\s*:\\s*(\\d+)")
    private val NAME = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")

    /** RD's error_code in a reply [body], or null. */
    fun code(body: String): Int? = CODE.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** RD's refusal as an exception with its code and a plain message. */
    fun refusal(status: Int, body: String, cause: Throwable? = null): RdRefused = RdRefused(code(body), api(status, body), cause)

    /** A message for an RD API refusal with HTTP [status] and reply [body]. */
    fun api(status: Int, body: String): String {
        val code = CODE.find(body)?.groupValues?.get(1)?.toIntOrNull()
        val name = NAME.find(body)?.groupValues?.get(1)?.lowercase().orEmpty()
        val tag = if (code != null) " (error $code)" else " (HTTP $status)"
        return when {
            code == 22 || "ip_not_allowed" in name || "ip_address" in name ->
                "Real-Debrid blocks the network this TV is on$tag. Turn off any VPN on this TV or the router, then try again."
            code == 34 || status == 429 || "too_many" in name ->
                "Real-Debrid is getting too many requests$tag. Wait a minute and try again."
            code == 35 || "infringing" in name ->
                "Real-Debrid refused this source$tag. Try another source."
            code == 36 || "fair_usage" in name ->
                "Real-Debrid's fair use limit is reached$tag. Try again later."
            code == 23 || "traffic" in name ->
                "Real-Debrid's traffic limit is reached$tag. Try again later."
            "locked" in name || code == 9 || "permission" in name || "premium" in name ->
                "Real-Debrid refused this account$tag. Check the account at real-debrid.com."
            name.isNotEmpty() -> "Real-Debrid said \"${name.replace('_', ' ')}\"$tag. Try another source."
            else -> "Real-Debrid didn't accept the request (HTTP $status). Try another source."
        }
    }

    /** A message for a video stream refused with HTTP [status], or null to show the player's own error. */
    fun stream(status: Int?): String? = when (status) {
        403 -> "The video server refused this TV (HTTP 403). Real-Debrid can refuse a VPN, or links used from two networks at once. " +
            "Turn off any VPN on this TV and try again."
        404, 410 -> "This file is gone from the server (HTTP $status). Try another source."
        429 -> "The video server is busy (HTTP 429). Wait a minute and try again."
        else -> null
    }
}
