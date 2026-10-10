package com.mcd.tv.data

/**
 * The ranked source list a video was started from, so the player can offer "Try next source" when a
 * stream fails. Screen.Player keeps only a small id and a position, so the back stack and its saved
 * state stay small. Lists live in memory; the last [KEEP] lists are kept (the back stack itself is not
 * saved across a process restart either).
 */
object SourceQueue {
    private const val KEEP = 6
    private val lists = LinkedHashMap<Int, List<StreamSource>>()
    private var nextId = 1

    /** Stores [list] and returns its id (always > 0). 0 means "no list". */
    @Synchronized
    fun put(list: List<StreamSource>): Int {
        val id = nextId++
        lists[id] = list
        while (lists.size > KEEP) lists.remove(lists.keys.first())
        return id
    }

    @Synchronized
    fun get(id: Int): List<StreamSource>? = if (id > 0) lists[id] else null

    /**
     * Index of the next source to try after [current] in [list], or null when none is left.
     * Skips sources that point at the same file as one already tried (same link, or same torrent and file),
     * so a duplicate never plays the same broken stream again. [current] = -1 starts at the top.
     */
    fun next(list: List<StreamSource>, current: Int): Int? {
        if (list.isEmpty()) return null
        val tried = list.subList(0, (current + 1).coerceIn(0, list.size)).map { sameFileKey(it) }.toHashSet()
        for (i in (current + 1).coerceAtLeast(0) until list.size) {
            if (sameFileKey(list[i]) !in tried) return i
        }
        return null
    }

    /** How many sources are left to try after [current] (same rules as [next]). */
    fun remaining(list: List<StreamSource>, current: Int): Int {
        var n = 0
        var i = current
        while (true) {
            i = next(list, i) ?: return n
            n++
        }
    }

    private fun sameFileKey(s: StreamSource): String = when {
        !s.url.isNullOrBlank() -> "u:" + s.url
        !s.infoHash.isNullOrBlank() -> "h:" + s.infoHash.lowercase() + ":" + (s.fileIdx ?: -1)
        else -> "n:" + s.addon + "|" + s.name + "|" + s.title
    }
}
