package com.mcd.tv.data

/**
 * Live TV favorites shared by the TVs and phones of a house, as channel stream numbers. A three-way merge,
 * so two devices with different lists end up with both lists, and a channel removed on one device is
 * removed on all. Pure logic; tests in LiveFavMergeTest.
 *
 * [base]: the list this TV and the shared copy last agreed on (empty the first time).
 * [local]: this TV's list now. [remote]: the shared copy now.
 */
object LiveFavMerge {
    fun merge(base: Set<String>, local: Set<String>, remote: Set<String>): Set<String> {
        val removedHere = base - local
        val removedThere = base - remote
        return (local + remote) - removedHere - removedThere
    }

    /** "1,2,3" to a set, and back. */
    fun parse(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun format(ids: Set<String>): String = ids.sorted().joinToString(",")
}
