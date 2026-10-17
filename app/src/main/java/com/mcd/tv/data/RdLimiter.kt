package com.mcd.tv.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale

/** Real-Debrid said "too many requests" (HTTP 429 / error 34), or this TV is still backing off after it did. */
class RdBusy(message: String = RdLimiter.BUSY_MESSAGE) : IllegalStateException(message)

/**
 * Keeps this TV's Real-Debrid API calls well under the account limit. All TVs in a house share one RD account,
 * so each TV stays far below it on its own. Every RD API call goes through [acquire]:
 *  - at most one call per [minGapMs];
 *  - at most [budget] calls in any [windowMs] (sliding window). Over budget, [acquire] waits instead of failing;
 *  - after a 429 / error 34 ([onBusy]) every call fails at once with [RdBusy] until the backoff ends.
 *    The backoff is RD's Retry-After when it sends one, else [backoffMs] step by step (reset by [onSuccess]).
 * Pure logic (no Android); tests in RdLimiterTest with a fake clock.
 */
class RdLimiter(
    private val clock: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    val minGapMs: Long = 250,
    val budget: Int = 120,
    val windowMs: Long = 60_000,
    val backoffMs: List<Long> = listOf(5_000, 15_000, 30_000),
) {
    private val gate = Mutex()
    private val sent = ArrayDeque<Long>()
    private var last = Long.MIN_VALUE / 2
    @Volatile private var pausedUntil = 0L
    @Volatile private var step = 0

    /** Waits for a free slot, then counts one call. Throws [RdBusy] while a backoff is running. */
    suspend fun acquire() {
        gate.withLock {
            while (true) {
                val now = clock()
                if (now < pausedUntil) throw RdBusy()
                while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
                val wait = maxOf(
                    if (sent.size >= budget) sent.first() + windowMs - now else 0L,
                    last + minGapMs - now,
                )
                if (wait > 0) {
                    sleep(wait)
                    continue
                }
                sent.addLast(now)
                last = now
                return
            }
        }
    }

    /** RD answered normally (any reply that is not "too many requests"): the next backoff starts small again. */
    fun onSuccess() {
        step = 0
    }

    /** RD said "too many requests". Pauses every RD call on this TV and returns the pause in ms. */
    fun onBusy(retryAfter: String?): Long {
        val now = clock()
        val ms = parseRetryAfter(retryAfter, now) ?: backoffMs[step.coerceAtMost(backoffMs.size - 1)]
        step = (step + 1).coerceAtMost(backoffMs.size - 1)
        pausedUntil = maxOf(pausedUntil, now + ms)
        return ms
    }

    /** How long the current backoff still runs, in ms (0 = none). */
    fun pausedForMs(): Long = (pausedUntil - clock()).coerceAtLeast(0)

    /** Calls counted in the current window (for tests). */
    fun inWindow(): Int {
        val now = clock()
        return sent.count { now - it < windowMs }
    }

    companion object {
        const val BUSY_MESSAGE = "Real-Debrid is busy. Wait a moment and try again."
        private const val MIN_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 120_000L

        /** True when an RD reply means "too many requests": HTTP 429 or error_code 34. */
        fun isBusy(status: Int, body: String): Boolean = status == 429 || RdErrors.code(body) == 34

        /**
         * Retry-After as ms from [now]: delta seconds ("30") or an HTTP date. Kept between 1 s and 2 minutes.
         * Null when missing or unreadable.
         */
        fun parseRetryAfter(value: String?, now: Long): Long? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            val ms = v.toLongOrNull()?.let { if (it < 0) return null else it * 1000 }
                ?: runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(v)!!.time - now
                }.getOrNull()
                ?: return null
            return ms.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
        }
    }
}

/**
 * This TV's copy of the first page of your RD torrent list, so finding a torrent you already have does not
 * list the account on every attempt. Fresh for [ttlMs]; adds, deletes and status checks update it in place.
 * Pure logic; tests in RdLimiterTest.
 */
class RdTorrentCache(private val clock: () -> Long, private val ttlMs: Long = 60_000) {
    data class Entry(val id: String, val hash: String, val status: String, val progress: Int)

    private var items: MutableList<Entry>? = null
    private var at = 0L

    /** The cached list, or null when it is missing or older than [ttlMs]. */
    @Synchronized
    fun get(): List<Entry>? = items?.takeIf { clock() - at < ttlMs }?.toList()

    @Synchronized
    fun put(list: List<Entry>) {
        items = list.toMutableList()
        at = clock()
    }

    /** A torrent this TV just added goes first (newest first, like RD's list). */
    @Synchronized
    fun add(e: Entry) {
        items?.let { l -> l.removeAll { it.id == e.id }; l.add(0, e) }
    }

    @Synchronized
    fun remove(id: String) {
        items?.removeAll { it.id == id }
    }

    @Synchronized
    fun update(id: String, status: String, progress: Int) {
        val l = items ?: return
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) l[i] = l[i].copy(status = status, progress = progress)
    }

    @Synchronized
    fun clear() {
        items = null
    }
}

/**
 * Real-Debrid throttles new torrents: an addMagnet sent a few seconds after another one is refused with
 * 451 error 35, the same answer as a truly refused file, and the throttle remembers for 30 s or more.
 * Adds spaced 25 s or more apart behave normally. So every addMagnet on this TV goes through [acquireAdd]:
 * one add per [gapMs], process-wide. The last add time comes from [load] and goes to [save] (a pref on
 * this TV), so an app restart keeps the gap. Pure logic; tests in RdLimiterTest with a fake clock.
 */
class RdAddGate(
    private val clock: () -> Long,
    private val load: () -> Long,
    private val save: (Long) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    val gapMs: Long = ADD_GAP_MS,
) {
    /**
     * One add slot. [fullGap] is true when the add goes out at least [gapMs] after the previous one.
     * Only then is a 451 / error 35 a real refusal of the file.
     */
    data class Ticket(val fullGap: Boolean)

    private val gate = Mutex()

    /** How long until the next add may go out, in ms (0 = now). */
    fun waitMs(): Long {
        val now = clock()
        val last = load()
        return if (last > now) gapMs else (last + gapMs - now).coerceAtLeast(0)
    }

    /** Waits until [gapMs] has passed since the last add (cancellable), then records this add. */
    suspend fun acquireAdd(): Ticket = gate.withLock {
        while (true) {
            val now = clock()
            val last = load()
            if (last > now) { // the clock moved back or the saved time is bad: start a full gap from now
                save(now)
                continue
            }
            val wait = last + gapMs - now
            if (wait > 0) {
                sleep(wait)
                continue
            }
            save(now)
            return@withLock Ticket(fullGap = now - last >= gapMs)
        }
        @Suppress("UNREACHABLE_CODE")
        Ticket(false)
    }

    companion object {
        const val ADD_GAP_MS = 25_000L
    }
}
