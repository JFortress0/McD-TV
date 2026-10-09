package com.mcd.tv.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val t0 = 1_760_000_000_000L

    @Test
    fun tokenBucketBurstThenRefill() {
        val b = TokenBucket(capacity = 25.0, refillEveryMs = 20 * min)
        var sent = 0
        while (b.tryTake(1, t0)) sent++
        assertEquals(25, sent)
        assertFalse(b.tryTake(1, t0 + 19 * min))
        assertTrue(b.tryTake(1, t0 + 20 * min))
        assertFalse(b.tryTake(1, t0 + 20 * min))
        // A full day of steady sending: at most 25 + 72.
        val d = TokenBucket(25.0, 20 * min)
        var n = 0
        var t = t0
        while (t < t0 + SyncSpec.DAY_MS) {
            if (d.tryTake(1, t)) n++
            t += min
        }
        assertTrue("sent $n", n in 90..97)
        // Never more than the capacity after a long idle time.
        assertEquals(25.0, d.available(t + 30 * SyncSpec.DAY_MS), 0.0001)
        // A big multi-part send goes out when the bucket is full and leaves a debt.
        val e = TokenBucket(25.0, 20 * min)
        assertTrue(e.tryTake(40, t0))
        assertTrue(e.available(t0) < 0)
        assertFalse(e.canTake(1, t0 + 5 * hour))
    }

    @Test
    fun backoffOn429DoublesUpToSixHours() {
        val b = Backoff()
        assertTrue(b.onResult(200, t0))
        assertFalse(b.onResult(429, t0))
        assertEquals(t0 + 30 * min, b.pausedUntil)
        assertFalse(b.canSend(t0 + 29 * min))
        assertTrue(b.canSend(t0 + 30 * min))
        var now = t0 + 30 * min
        val windows = mutableListOf<Long>()
        repeat(6) {
            b.onResult(429, now)
            windows.add(b.rateWindowMs)
            now = b.pausedUntil
        }
        assertEquals(listOf(60 * min, 120 * min, 240 * min, 360 * min, 360 * min, 360 * min), windows)
        // A success soon after does not reset the window (no retry loop); a day without 429 does.
        b.onResult(200, now)
        assertEquals(360 * min, b.rateWindowMs)
        b.onResult(200, now + SyncSpec.DAY_MS + 1)
        assertEquals(0L, b.rateWindowMs)
    }

    @Test
    fun backoffOnServerAndNetworkErrors() {
        val b = Backoff()
        b.onResult(503, t0)
        assertEquals(t0 + 30_000L, b.pausedUntil)
        b.onResult(-1, t0 + 30_000L)
        assertEquals(t0 + 90_000L, b.pausedUntil)
        repeat(20) { b.onResult(500, b.pausedUntil) }
        val last = b.pausedUntil
        b.onResult(500, last)
        assertEquals(last + 30 * min, b.pausedUntil) // capped
        b.onResult(200, b.pausedUntil)
        val ok = b.pausedUntil
        b.onResult(502, ok)
        assertEquals(ok + 30_000L, b.pausedUntil) // reset after success
        // Jitter is added.
        val j = Backoff()
        j.onResult(429, t0, jitterMs = 1234)
        assertEquals(t0 + 30 * min + 1234, j.pausedUntil)
    }

    @Test
    fun flushCoalescesListChanges() {
        val f = FlushPolicy()
        assertFalse(f.pending)
        f.noteList(t0)
        f.noteList(t0 + 3_000)
        f.noteProgress(t0 + 4_000)
        assertFalse(f.due(t0 + 7_000)) // still typing / toggling
        assertTrue(f.due(t0 + 8_000)) // 5 s after the last change
        assertTrue(f.hasListChange)
        f.sent()
        assertFalse(f.pending)
        // Someone toggling every 4 s: sent after 30 s at most, all in one go.
        val g = FlushPolicy()
        var t = t0
        var firstDue = -1L
        while (t < t0 + 60_000) {
            g.noteList(t)
            if (firstDue < 0 && g.due(t)) firstDue = t
            t += 4_000
        }
        assertEquals(t0 + 32_000, firstDue)
    }

    @Test
    fun flushProgressOnPauseAndAtMostEvery25MinWhilePlaying() {
        val f = FlushPolicy()
        // Playing: the player saves every ~10 s.
        var t = t0
        val sends = mutableListOf<Long>()
        while (t < t0 + 2 * hour) {
            f.noteProgress(t)
            if (f.due(t)) { sends.add(t); f.sent() }
            t += 10_000
        }
        assertEquals(4, sends.size) // a 2 hour movie: one send per 25 minutes while playing
        assertTrue(sends.zipWithNext().all { (a, b) -> b - a >= 25 * min })
        // Pause (last save was at t - 10 s): one send a minute after it.
        assertFalse(f.due(t + 49_000))
        assertTrue(f.due(t + 50_000))
        assertFalse(f.hasListChange)
    }

    @Test
    fun houseBudgetSoftAndHardLimits() {
        val h = HouseBudget(softLimit = 150, hardLimit = 200)
        h.record(t0, 149)
        assertTrue(h.allows(1, urgent = false, now = t0))
        h.record(t0, 1)
        assertFalse(h.allows(1, urgent = false, now = t0))
        assertTrue(h.allows(1, urgent = true, now = t0))
        h.record(t0, 50)
        assertFalse(h.allows(1, urgent = true, now = t0))
        // A day later the window is empty again.
        assertEquals(0, h.count(t0 + SyncSpec.DAY_MS))
        assertTrue(h.allows(10, urgent = false, now = t0 + SyncSpec.DAY_MS))
        // Save and restore.
        val g = HouseBudget()
        g.record(t0, 3)
        g.record(t0 + hour, 2)
        val copy = HouseBudget().apply { restore(g.snapshot(t0 + hour)) }
        assertEquals(5, copy.count(t0 + hour))
        assertEquals(2, copy.count(t0 + SyncSpec.DAY_MS + 1))
    }

    @Test
    fun fillOrderIsMostUsefulFirst() {
        val units = listOf(
            Fill.Unit("p1/episodes", SyncSpec.EPISODES, 0, t0 + 999, 100),
            Fill.Unit("p1/watchlist", SyncSpec.WATCHLIST, 3, t0 + 5, 100),
            Fill.Unit("p1/history", SyncSpec.HISTORY, 2, t0 + 10, 100),
            Fill.Unit("p1/history", SyncSpec.HISTORY, 7, t0 + 50, 100),
            Fill.Unit("p2/meta", SyncSpec.META, 4, t0, 10),
        )
        assertEquals(
            listOf("p2/meta#4", "p1/history#7", "p1/history#2", "p1/watchlist#3", "p1/episodes#0"),
            Fill.order(units).map { "${it.pcKey}#${it.bucket}" },
        )
        // Across profiles, the newest history comes first.
        val mixed = listOf(
            Fill.Unit("p1/history", SyncSpec.HISTORY, 0, t0 + 1, 10),
            Fill.Unit("p2/history", SyncSpec.HISTORY, 0, t0 + 9, 10),
            Fill.Unit("p3/history", SyncSpec.HISTORY, 0, t0 + 5, 10),
        )
        assertEquals(listOf("p2", "p3", "p1"), Fill.order(mixed).map { it.pcKey.substringBefore('/') })
        // One message per round: units that fit together.
        val round = Fill.pickRound(units, 1000) { part -> part.sumOf { it.rawSize } <= 250 }
        assertEquals(listOf("p2/meta#4", "p1/history#7", "p1/history#2"), round.map { "${it.pcKey}#${it.bucket}" })
        // A unit too big for one message goes alone.
        val big = listOf(Fill.Unit("p1/history", SyncSpec.HISTORY, 1, t0, 10_000), Fill.Unit("p1/episodes", SyncSpec.EPISODES, 1, t0, 10))
        assertEquals(listOf(1), Fill.pickRound(big, 1000) { part -> part.sumOf { it.rawSize } <= 250 }.map { it.bucket })
        assertTrue(Fill.pickRound(emptyList(), 1000) { true }.isEmpty())
    }
}
