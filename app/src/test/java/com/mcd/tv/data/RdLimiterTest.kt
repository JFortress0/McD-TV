package com.mcd.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RdLimiterTest {
    private var now = 1_000_000L
    private val slept = mutableListOf<Long>()

    private fun limiter(budget: Int = 120, gap: Long = 250) = RdLimiter(
        clock = { now },
        sleep = { slept += it; now += it },
        minGapMs = gap,
        budget = budget,
    )

    @Test
    fun spacesCallsAtLeastGapApart() = runBlocking {
        val l = limiter()
        l.acquire()
        l.acquire()
        l.acquire()
        assertEquals(listOf(250L, 250L), slept)
        now += 1_000
        l.acquire() // already past the gap: no wait
        assertEquals(2, slept.size)
    }

    @Test
    fun waitsWhenBudgetIsUsedUp() = runBlocking {
        val l = limiter(budget = 120)
        val start = now
        repeat(120) { l.acquire() }
        assertEquals(120, l.inWindow())
        // 120 calls 250 ms apart took 29.75 s; the 121st waits until the first leaves the 60 s window.
        assertEquals(start + 119 * 250, now)
        l.acquire()
        assertEquals(start + 60_000, now)
        assertEquals(120, l.inWindow())
    }

    @Test
    fun neverMoreThanBudgetInAnyWindow() = runBlocking {
        val l = limiter(budget = 10, gap = 0)
        val times = mutableListOf<Long>()
        repeat(35) { l.acquire(); times += now }
        for (t in times) assertTrue(times.count { it in t until t + 60_000 } <= 10)
    }

    @Test
    fun busyPausesEveryCallWithSteppedBackoff() = runBlocking {
        val l = limiter()
        assertEquals(5_000L, l.onBusy(null))
        assertBusy(l)
        now += 4_999
        assertBusy(l)
        now += 1
        l.acquire() // pause over
        assertEquals(15_000L, l.onBusy(null))
        now += 15_000
        assertEquals(30_000L, l.onBusy(null))
        now += 30_000
        assertEquals(30_000L, l.onBusy(null)) // stays at the last step
        now += 30_000
        l.onSuccess()
        assertEquals(5_000L, l.onBusy(null)) // a normal reply resets the steps
    }

    @Test
    fun retryAfterWins() = runBlocking {
        val l = limiter()
        assertEquals(42_000L, l.onBusy("42"))
        assertEquals(42_000L, l.pausedForMs())
        now += 42_000
        assertEquals(0L, l.pausedForMs())
        l.acquire()
    }

    @Test
    fun parsesRetryAfter() {
        assertEquals(30_000L, RdLimiter.parseRetryAfter("30", now))
        assertEquals(1_000L, RdLimiter.parseRetryAfter("0", now)) // at least 1 s
        assertEquals(120_000L, RdLimiter.parseRetryAfter("9999", now)) // at most 2 minutes
        assertNull(RdLimiter.parseRetryAfter(null, now))
        assertNull(RdLimiter.parseRetryAfter("", now))
        assertNull(RdLimiter.parseRetryAfter("-5", now))
        assertNull(RdLimiter.parseRetryAfter("soon", now))
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val base = 1_791_000_000_000L // whole seconds
        assertEquals(20_000L, RdLimiter.parseRetryAfter(fmt.format(java.util.Date(base + 20_000)), base))
    }

    @Test
    fun recognizesTooManyRequests() {
        assertTrue(RdLimiter.isBusy(429, ""))
        assertTrue(RdLimiter.isBusy(503, """{"error":"too_many_requests","error_code":34}"""))
        assertFalse(RdLimiter.isBusy(451, """{"error":"infringing_file","error_code":35}"""))
        assertFalse(RdLimiter.isBusy(401, """{"error":"bad_token","error_code":8}"""))
    }

    @Test
    fun busyMessageIsPlain() {
        val m = RdBusy().message.orEmpty()
        assertEquals("Real-Debrid is busy. Wait a moment and try again.", m)
        assertFalse(m.contains('—'))
    }

    @Test
    fun torrentCacheExpiresAndUpdatesInPlace() {
        val c = RdTorrentCache(clock = { now }, ttlMs = 60_000)
        assertNull(c.get())
        c.put(listOf(RdTorrentCache.Entry("A", "aa", "downloaded", 100)))
        c.add(RdTorrentCache.Entry("B", "bb", "magnet_conversion", 0))
        assertEquals(listOf("B", "A"), c.get()!!.map { it.id })
        c.update("B", "downloading", 40)
        assertEquals("downloading", c.get()!!.first().status)
        c.remove("A")
        assertEquals(listOf("B"), c.get()!!.map { it.id })
        now += 59_999
        assertTrue(c.get() != null)
        now += 1
        assertNull(c.get())
        c.add(RdTorrentCache.Entry("C", "cc", "x", 0)) // nothing cached: no partial list appears
        c.clear()
        assertNull(c.get())
    }

    private var saved = 0L
    private fun gate() = RdAddGate(clock = { now }, load = { saved }, save = { saved = it }, sleep = { slept += it; now += it })

    @Test
    fun firstAddGoesAtOnceWithFullGap() = runBlocking {
        val g = gate()
        assertEquals(0L, g.waitMs())
        assertTrue(g.acquireAdd().fullGap)
        assertTrue(slept.isEmpty())
        assertEquals(now, saved)
    }

    @Test
    fun addsAreSpaced25sApart() = runBlocking {
        val g = gate()
        val t0 = now
        g.acquireAdd()
        now += 4_000 // a fast second add: RD would refuse it with error 35
        assertEquals(21_000L, g.waitMs())
        val t = g.acquireAdd()
        assertTrue(t.fullGap)
        assertEquals(t0 + RdAddGate.ADD_GAP_MS, now)
        assertEquals(listOf(21_000L), slept)
        g.acquireAdd()
        assertEquals(t0 + 2 * RdAddGate.ADD_GAP_MS, now)
    }

    @Test
    fun savedTimeSurvivesARestart() = runBlocking {
        gate().acquireAdd()
        now += 10_000
        val again = gate() // a new process reads the same saved time
        assertEquals(15_000L, again.waitMs())
        again.acquireAdd()
        assertEquals(listOf(15_000L), slept)
    }

    @Test
    fun clockMovedBackWaitsAFullGap() = runBlocking {
        saved = now + 3_600_000 // saved in the future
        val g = gate()
        assertEquals(RdAddGate.ADD_GAP_MS, g.waitMs())
        val start = now
        assertTrue(g.acquireAdd().fullGap)
        assertEquals(start + RdAddGate.ADD_GAP_MS, now)
    }

    private suspend fun assertBusy(l: RdLimiter) {
        try {
            l.acquire()
            fail("expected RdBusy")
        } catch (e: RdBusy) {
            // expected
        }
    }
}
