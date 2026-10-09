package com.mcd.tv.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProfileStoreTest {
    private val day = SyncSpec.DAY_MS
    private val t0 = 1_760_000_000_000L // Oct 2025

    private fun copy(s: ProfileStore): ProfileStore = ProfileStore(s.profile).also { it.mergeFrom(s) }

    private fun copy(c: CollectionState): CollectionState = CollectionState(c.name).also { it.mergeFrom(c) }

    private fun snapshot(s: ProfileStore): Map<String, Pair<Long, List<Entry>>> =
        s.collections().mapValues { (_, c) -> c.floor to c.entries() }

    private fun assertSame(a: ProfileStore, b: ProfileStore, msg: String = "") {
        assertEquals(msg, snapshot(a), snapshot(b))
        assertEquals(msg, a.digest(), b.digest())
    }

    // ---------------- basic LWW and tombstones ----------------

    @Test
    fun deleteBeatsOlderAdd() {
        val c = CollectionState(SyncSpec.WATCHLIST)
        c.offer(Entry.of("movie:1", "{a}", t0, false, "A"))
        assertTrue(c.offer(Entry.of("movie:1", null, t0 + 5, true, "B")))
        assertTrue(c.get("movie:1")!!.deleted)
        // An older add arriving late does not bring it back.
        assertFalse(c.offer(Entry.of("movie:1", "{a}", t0 + 1, false, "C")))
        assertTrue(c.live().isEmpty())
    }

    @Test
    fun newerReAddBeatsDelete() {
        val c = CollectionState(SyncSpec.WATCHLIST)
        c.offer(Entry.of("movie:1", null, t0 + 5, true, "B"))
        assertTrue(c.offer(Entry.of("movie:1", "{a}", t0 + 6, false, "A")))
        assertEquals(listOf("movie:1"), c.live().map { it.key })
    }

    @Test
    fun sameTimestampTieIsDeterministic() {
        val add = Entry.of("k", "v", t0, false, "A")
        val del = Entry.of("k", null, t0, true, "B")
        val x = CollectionState(SyncSpec.FAVORITES).apply { offer(add); offer(del) }
        val y = CollectionState(SyncSpec.FAVORITES).apply { offer(del); offer(add) }
        assertEquals(x.get("k"), y.get("k"))
        assertEquals("B", x.get("k")!!.origin) // higher origin wins the tie
        // Same ts and origin: the tombstone wins.
        val a2 = Entry.of("k", "v", t0, false, "A")
        val d2 = Entry.of("k", null, t0, true, "A")
        val z = CollectionState(SyncSpec.FAVORITES).apply { offer(a2); offer(d2) }
        assertTrue(z.get("k")!!.deleted)
    }

    @Test
    fun localWriteAlwaysWinsOverSeenVersionEvenWithSlowClock() {
        val c = CollectionState(SyncSpec.WATCHLIST)
        c.offer(Entry.of("movie:1", "{a}", t0 + 1000, false, "FAST"))
        val e = c.localWrite("movie:1", null, now = t0, origin = "SLOW")!!
        assertTrue(e.ts > t0 + 1000)
        assertTrue(c.get("movie:1")!!.deleted)
        // No-ops do not create versions.
        assertNull(c.localWrite("movie:1", null, t0, "SLOW"))
        assertNotNull(c.localWrite("movie:1", "{a}", t0, "SLOW"))
        assertNull(c.localWrite("movie:1", "{a}", t0, "SLOW"))
    }

    @Test
    fun seedNeverOverridesExisting() {
        val c = CollectionState(SyncSpec.WATCHLIST)
        c.localWrite("movie:1", null, t0, "A")
        assertFalse(c.seed("movie:1", "{a}", SyncSpec.SEED_TS, "A"))
        assertTrue(c.seed("movie:2", "{b}", SyncSpec.SEED_TS, "A"))
        // Nothing at or under the floor is seeded back.
        c.raiseFloor(SyncSpec.SEED_TS + 5)
        assertFalse(c.seed("movie:3", "{c}", SyncSpec.SEED_TS + 5, "A"))
    }

    @Test
    fun deltaSince() {
        val c = CollectionState(SyncSpec.HISTORY)
        c.offer(Entry.of("a", "1", t0, false, "A"))
        c.offer(Entry.of("b", "1", t0 + 10, false, "A"))
        c.offer(Entry.of("c", "1", t0 + 20, false, "A"))
        assertEquals(listOf("c", "b"), c.since(t0).map { it.key })
        assertTrue(c.since(t0 + 20).isEmpty())
    }

    // ---------------- merge laws ----------------

    private fun randomStore(r: Random, origin: String, ops: Int, clock: Long): ProfileStore {
        val s = ProfileStore("p1")
        var now = clock
        repeat(ops) {
            now += r.nextLong(1, 5000)
            randomOp(r, s, origin, now)
        }
        if (r.nextInt(3) == 0) s.compact(now + r.nextLong(0, 400 * day))
        return s
    }

    private val smallCols = listOf(SyncSpec.WATCHLIST, SyncSpec.FAVORITES, SyncSpec.HISTORY, SyncSpec.EPISODES, SyncSpec.META, SyncSpec.LIVE_FAVORITES)

    private fun randomOp(r: Random, s: ProfileStore, origin: String, now: Long): Entry? {
        val col = s.collection(smallCols[r.nextInt(smallCols.size)])!!
        val key = "k${r.nextInt(40)}"
        val value = if (r.nextInt(3) == 0 && col.name != SyncSpec.HISTORY && col.name != SyncSpec.EPISODES) null else "v${r.nextInt(5)}"
        return col.localWrite(key, value, now, origin)
    }

    @Test
    fun mergeIsCommutativeAssociativeIdempotent() {
        val r = Random(42)
        repeat(200) { round ->
            val a = randomStore(r, "devA", r.nextInt(0, 120), t0)
            val b = randomStore(r, "devB", r.nextInt(0, 120), t0 + r.nextLong(-day, day))
            val c = randomStore(r, "devC", r.nextInt(0, 120), t0 + r.nextLong(-day, day))

            val ab = copy(a).also { it.mergeFrom(b) }
            val ba = copy(b).also { it.mergeFrom(a) }
            assertSame(ab, ba, "commutative, round $round")

            val abC = copy(ab).also { it.mergeFrom(c) }
            val bc = copy(b).also { it.mergeFrom(c) }
            val aBC = copy(a).also { it.mergeFrom(bc) }
            assertSame(abC, aBC, "associative, round $round")

            val aa = copy(a).also { it.mergeFrom(a) }
            assertSame(a, aa, "idempotent, round $round")
            val abab = copy(ab).also { it.mergeFrom(ab); it.mergeFrom(a); it.mergeFrom(b) }
            assertSame(ab, abab, "absorbing, round $round")
        }
    }

    /**
     * 3 to 5 TVs make random changes with skewed clocks. Deltas go through a lossy network that
     * reorders and duplicates; TVs compact at random times. Then a final anti-entropy round (full
     * states, as pstate does) must leave every TV identical.
     */
    @Test
    fun randomizedDevicesConverge() {
        repeat(60) { seed ->
            val r = Random(1000 + seed)
            val n = r.nextInt(3, 6)
            val devices = List(n) { ProfileStore("p2") }
            val skew = List(n) { r.nextLong(-2 * day, 2 * day) }
            val network = ArrayList<Pair<Int, List<Pair<String, Entry>>>>() // (sender, [(collection, entry)])
            var now = t0
            repeat(r.nextInt(100, 600)) {
                now += r.nextLong(1, 60_000)
                val d = r.nextInt(n)
                val batch = ArrayList<Pair<String, Entry>>()
                repeat(r.nextInt(1, 4)) {
                    val col = smallCols[r.nextInt(smallCols.size)]
                    val c = devices[d].collection(col)!!
                    val key = "k${r.nextInt(30)}"
                    val value = if (r.nextInt(3) == 0) null else "v${r.nextInt(4)}"
                    c.localWrite(key, value, now + skew[d], "dev$d")?.let { batch.add(col to it) }
                }
                if (batch.isNotEmpty()) {
                    network.add(d to batch)
                    if (r.nextInt(5) == 0) network.add(d to batch) // duplicate
                }
                // Deliver some messages in random order; some are lost.
                if (network.isNotEmpty() && r.nextBoolean()) {
                    val (from, msg) = network.removeAt(r.nextInt(network.size))
                    for (to in 0 until n) {
                        if (to == from || r.nextInt(4) == 0) continue // 25% loss per receiver
                        msg.forEach { (col, e) -> devices[to].collection(col)!!.merge(listOf(e)) }
                    }
                }
                if (r.nextInt(200) == 0) devices[r.nextInt(n)].compact(now + r.nextLong(0, 300 * day))
            }
            // Leftover messages: some delivered late, most lost (ntfy cache expired).
            network.shuffle(r)
            network.take(network.size / 3).forEach { (from, msg) ->
                for (to in 0 until n) if (to != from) msg.forEach { (col, e) -> devices[to].collection(col)!!.merge(listOf(e)) }
            }
            // Anti-entropy: each TV sends its full state to every other TV (twice covers what came in during the first pass).
            repeat(2) {
                for (i in 0 until n) for (j in 0 until n) if (i != j) devices[j].mergeFrom(devices[i])
            }
            for (i in 1 until n) assertSame(devices[0], devices[i], "seed $seed device $i")
            // Bucketed anti-entropy (what pstate sends) agrees too.
            for (col in SyncSpec.COLLECTIONS) {
                val x = devices[0].collection(col)!!
                assertEquals(x.bucketHashes().toList(), devices[n - 1].collection(col)!!.bucketHashes().toList())
            }
        }
    }

    /** Same as above but converging only through bucket plans (the real pdigest/pstate exchange). */
    @Test
    fun bucketAntiEntropyConverges() {
        repeat(40) { seed ->
            val r = Random(77 + seed)
            val a = randomStore(r, "devA", r.nextInt(50, 400), t0)
            val b = randomStore(r, "devB", r.nextInt(50, 400), t0 + 3)
            var rounds = 0
            while (a.digest() != b.digest() && rounds < 6) {
                rounds++
                // b advertises its digest; a sends the buckets b lacks; then b sends back what a lacks.
                for (col in SyncSpec.COLLECTIONS) {
                    val ca = a.collection(col)!!
                    val cb = b.collection(col)!!
                    val plan = AntiEntropy.plan(ca, cb.digest(), AntiEntropy.bucketString(cb))
                    if (plan.needed) cb.merge(ca.entriesIn(plan.buckets), ca.floor)
                    val back = AntiEntropy.plan(cb, ca.digest(), AntiEntropy.bucketString(ca))
                    if (back.needed) ca.merge(cb.entriesIn(back.buckets), cb.floor)
                }
            }
            assertSame(a, b, "seed $seed")
            assertTrue("rounds $rounds", rounds <= 1)
        }
    }

    // ---------------- pruning ----------------

    @Test
    fun historyCapKeepsNewestAndIgnoresOldArrivals() {
        val c = CollectionState(SyncSpec.HISTORY)
        repeat(400) { i -> c.offer(Entry.of("movie:$i", "{}", t0 + i, false, "A")) }
        val removed = c.compact(t0 + 1000)
        assertEquals(100, removed.size)
        assertEquals(300, c.size)
        assertEquals(t0 + 99, c.floor)
        // A TV that still has an evicted entry cannot bring it back.
        assertFalse(c.offer(Entry.of("movie:5", "{}", t0 + 5, false, "B")))
        // Newer data still comes in.
        assertTrue(c.offer(Entry.of("movie:5", "{}", t0 + 500, false, "B")))
    }

    @Test
    fun pruningCannotResurrect() {
        // TV A has a live item; TV B deleted it long ago and then made many more deletes, so B prunes it.
        val a = CollectionState(SyncSpec.WATCHLIST)
        val b = CollectionState(SyncSpec.WATCHLIST)
        a.offer(Entry.of("movie:old", "{x}", t0, false, "A"))
        b.offer(Entry.of("movie:old", "{x}", t0, false, "A"))
        b.localWrite("movie:old", null, t0 + 10, "B")
        repeat(250) { i -> b.offer(Entry.of("movie:t$i", null, t0 + 100 + i, true, "B")) }
        val removed = b.compact(t0 + 400 * day)
        assertTrue("movie:old" in removed)
        assertNull(b.get("movie:old"))
        assertTrue(b.floor >= t0 + 10)
        // A (offline the whole time) comes back with its live copy: B ignores it...
        val r1 = b.mergeFrom(copy(a))
        assertFalse(r1.changedKeys.contains("movie:old"))
        assertNull(b.get("movie:old"))
        // ...and A drops it when it gets B's state (floor).
        a.mergeFrom(b)
        assertNull(a.get("movie:old"))
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun pruningKeepsOldLiveItemsAndRecentTombstones() {
        val c = CollectionState(SyncSpec.WATCHLIST)
        c.offer(Entry.of("movie:keep", "{k}", t0, false, "A")) // old but still in My List
        repeat(300) { i -> c.offer(Entry.of("movie:d$i", null, t0 + 1000 + i, true, "A")) }
        c.compact(t0 + 1000 * day)
        assertNotNull(c.get("movie:keep"))
        assertTrue(c.floor < t0)
        // Tombstones younger than the TTL are never pruned.
        val d = CollectionState(SyncSpec.WATCHLIST)
        repeat(300) { i -> d.offer(Entry.of("movie:d$i", null, t0 + i, true, "A")) }
        assertTrue(d.compact(t0 + 10 * day).isEmpty())
        assertEquals(300, d.size)
        // After the TTL, only the newest 200 tombstones stay.
        d.compact(t0 + 400 * day)
        assertEquals(200, d.size)
    }

    @Test
    fun newTvGetsOldItems() {
        val a = ProfileStore("p1")
        a.collection(SyncSpec.WATCHLIST)!!.seed("movie:1", "{a}", SyncSpec.SEED_TS, "A")
        a.compact(t0 + 2000 * day)
        val fresh = ProfileStore("p1")
        fresh.mergeFrom(a)
        assertEquals(listOf("movie:1"), fresh.collection(SyncSpec.WATCHLIST)!!.live().map { it.key })
    }

    @Test
    fun seededDataFromTwoTvsIsUnion() {
        val a = ProfileStore("p1")
        val b = ProfileStore("p1")
        a.collection(SyncSpec.WATCHLIST)!!.seed("movie:1", "{1}", SyncSpec.SEED_TS + 2, "A")
        a.collection(SyncSpec.WATCHLIST)!!.seed("movie:2", "{2}", SyncSpec.SEED_TS + 1, "A")
        b.collection(SyncSpec.WATCHLIST)!!.seed("movie:3", "{3}", SyncSpec.SEED_TS + 1, "B")
        a.mergeFrom(b)
        b.mergeFrom(a)
        assertSame(a, b)
        assertEquals(setOf("movie:1", "movie:2", "movie:3"), a.collection(SyncSpec.WATCHLIST)!!.live().map { it.key }.toSet())
        // A real change made after sync started wins over seeded data.
        b.collection(SyncSpec.WATCHLIST)!!.localWrite("movie:1", null, t0, "B")
        a.mergeFrom(b)
        assertTrue(a.collection(SyncSpec.WATCHLIST)!!.get("movie:1")!!.deleted)
    }

    // ---------------- digests ----------------

    @Test
    fun digestIsStableAndOrderIndependent() {
        val entries = (0 until 50).map { Entry.of("tv:$it", "{\"n\":$it}", t0 + it, it % 7 == 0, "dev${it % 3}") }
            .map { if (it.deleted) Entry.of(it.key, null, it.ts, true, it.origin) else it }
        val x = CollectionState(SyncSpec.FAVORITES).apply { merge(entries) }
        val y = CollectionState(SyncSpec.FAVORITES).apply { merge(entries.shuffled(Random(3))) }
        assertEquals(x.digest(), y.digest())
        assertEquals(16, x.digest().length)
        // Fixed value: the digest must never change between app versions (TVs on different versions compare them).
        val fixed = CollectionState(SyncSpec.WATCHLIST).apply {
            merge(listOf(Entry.of("movie:1", "{}", 1_700_000_000_000L, false, "abc"), Entry.of("tv:2", null, 1_700_000_000_001L, true, "xyz")))
        }
        assertEquals(EXPECTED_FIXED_DIGEST, fixed.digest())
        assertEquals(CollectionState(SyncSpec.META).digest(), CollectionState(SyncSpec.META).digest())
        // Any change shows.
        val z = copy(x)
        z.offer(Entry.of("tv:1", "{}", t0 + 999, false, "dev9"))
        assertNotEquals(x.digest(), z.digest())
        val f = copy(x)
        f.raiseFloor(1)
        assertNotEquals(x.digest(), f.digest())
    }

    @Test
    fun antiEntropyPlan() {
        val a = CollectionState(SyncSpec.HISTORY)
        repeat(100) { a.offer(Entry.of("movie:$it", "{}", t0 + it, false, "A")) }
        val b = copy(a)
        assertFalse(AntiEntropy.plan(a, b.digest(), AntiEntropy.bucketString(b)).needed)
        a.offer(Entry.of("movie:7", "{x}", t0 + 1000, false, "A"))
        val plan = AntiEntropy.plan(a, b.digest(), AntiEntropy.bucketString(b))
        assertEquals(setOf(CollectionState.bucketOf("movie:7")), plan.buckets)
        // Peer with no bucket hashes (small or empty): all buckets, so the peer can answer with what it has.
        val empty = CollectionState(SyncSpec.HISTORY)
        val all = AntiEntropy.plan(a, empty.digest(), "")
        assertEquals((0 until SyncSpec.BUCKETS).toSet(), all.buckets)
        // Empty here, small there: still a full plan (the header carries our empty bucket hashes).
        val small = CollectionState(SyncSpec.WATCHLIST).apply { offer(Entry.of("movie:1", "{}", t0, false, "B")) }
        assertEquals(16, AntiEntropy.plan(CollectionState(SyncSpec.WATCHLIST), small.digest(), AntiEntropy.bucketString(small)).buckets.size)
        // Floor-only difference.
        val c = copy(b)
        c.raiseFloor(1)
        val fp = AntiEntropy.plan(c, b.digest(), AntiEntropy.bucketString(b))
        assertTrue(fp.floorOnly)
        assertTrue(fp.buckets.isEmpty())
    }

    // ---------------- packing ----------------

    @Test
    fun packerRespectsBudgetAndOrder() {
        val r = Random(9)
        val items = List(500) { r.nextInt(1, 120) }
        val budget = 1000
        val packed = Packer.pack(items, { it }, rawTarget = 1500, fits = { it.sum() <= budget })
        assertEquals(0, packed.skipped)
        assertTrue(packed.parts.all { it.sum() <= budget })
        assertEquals(items, packed.parts.flatten()) // nothing lost, order kept
        // Parts are as full as possible: the next item would not have fit.
        var at = 0
        packed.parts.forEach { part ->
            at += part.size
            if (at < items.size) assertTrue((part + items[at]).sum() > budget)
        }
        // A bad first guess (tiny or huge raw target) gives the same parts.
        assertEquals(packed.parts, Packer.pack(items, { it }, rawTarget = 1, fits = { it.sum() <= budget }).parts)
        assertEquals(packed.parts, Packer.pack(items, { it }, rawTarget = 1_000_000, fits = { it.sum() <= budget }).parts)
        // Items too big alone are skipped; the rest still go.
        val withBig = listOf(10, 5000, 20)
        val p2 = Packer.pack(withBig, { it }, 1500, { it.sum() <= budget })
        assertEquals(1, p2.skipped)
        assertEquals(listOf(10, 20), p2.parts.flatten())
        // maxParts truncates the tail (oldest data) only.
        val p3 = Packer.pack(items, { it }, 1500, { it.sum() <= budget }, maxParts = 3)
        assertEquals(3, p3.parts.size)
        assertEquals(items.take(p3.parts.flatten().size), p3.parts.flatten())
        assertTrue(p3.skipped > 0)
        assertTrue(Packer.pack(emptyList<Int>(), { it }, 100, { true }).parts.isEmpty())
    }

    companion object {
        const val EXPECTED_FIXED_DIGEST = "b980d35bfdba8bf4"
    }
}
