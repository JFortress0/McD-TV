package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdErrorsTest {
    @Test
    fun vpnBlock() {
        val m = RdErrors.api(403, """{"error":"ip_not_allowed","error_code":22}""")
        assertTrue(m, m.startsWith("Real-Debrid blocks the network this TV is on (error 22)."))
        assertTrue(m.contains("VPN"))
    }

    @Test
    fun otherKnownErrors() {
        assertTrue(RdErrors.api(429, "").contains("too many requests (HTTP 429)"))
        assertTrue(RdErrors.api(503, """{"error":"infringing_file","error_code":35}""").contains("removed this file"))
        assertTrue(RdErrors.api(503, """{"error":"fair_usage_limit","error_code":36}""").contains("fair use"))
        assertTrue(RdErrors.api(403, """{"error":"permission_denied","error_code":9}""").contains("refused this account"))
    }

    @Test
    fun unknownErrors() {
        assertEquals(
            "Real-Debrid said \"hoster unavailable\" (error 19). Try another source.",
            RdErrors.api(503, """{"error": "hoster_unavailable", "error_code": 19}"""),
        )
        assertEquals("Real-Debrid didn't accept the request (HTTP 500). Try another source.", RdErrors.api(500, "<html>"))
    }

    @Test
    fun streams() {
        assertTrue(RdErrors.stream(403)!!.contains("VPN"))
        assertTrue(RdErrors.stream(404)!!.contains("gone"))
        assertNull(RdErrors.stream(500))
        assertNull(RdErrors.stream(null))
    }
}
