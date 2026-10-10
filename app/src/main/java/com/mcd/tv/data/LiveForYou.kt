package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live TV > For You: a short channel list picked for the active profile out of the whole playlist.
 * Three groups: channels the profile already watches, channels showing something it likes right now,
 * and the kinds of channels its movie and show taste points to (war and history, crime, comedy...).
 * One copy per network; international feeds, 24/7 loops and PPV are left out unless watched.
 */
object LiveForYou {
    const val WATCH_AGAIN = "Watch again"
    const val ON_NOW = "On now for you"
    const val YOUR_KIND = "Your kind of channels"

    /** How far ahead My Guide looks (and how far "coming up" counts for the ranking). */
    const val WINDOW_MS = 3 * 60 * 60_000L

    /** The picked channels, plus the matcher My Guide uses to highlight shows for this profile. */
    class Result(val section: LiveSection, val matcher: Taste.ProgrammeMatcher)

    suspend fun build(
        index: LiveIndex,
        favorites: Set<String>,
        guide: Map<String, List<Programme>>,
        profile: String = Prefs.activeProfile,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val taste = runCatching { Recommender.taste(profile) }.getOrDefault(TasteProfile.EMPTY)
        val stats = Prefs.liveStats(profile)
        return withContext(Dispatchers.Default) { rank(index, favorites, guide, taste, stats, profile == Prefs.KIDS_PROFILE, now) }
    }

    private fun rank(
        index: LiveIndex,
        favorites: Set<String>,
        guide: Map<String, List<Programme>>,
        taste: TasteProfile,
        stats: Map<String, Pair<Double, Long>>,
        kids: Boolean,
        now: Long,
    ): Result {
        val n = index.size
        val section = arrayOfNulls<String>(n)
        for (s in index.sections) for (i in s.items) if (i in 0 until n && section[i] == null) section[i] = s.name

        // Minutes per network tag, so Live TV habits feed back into the kinds of channels shown.
        val tagMinutes = HashMap<Taste.Tag, Double>()
        for ((url, st) in stats) {
            val i = index.indexOf(url)
            if (i < 0) continue
            for (t in Taste.channelTags(index.names[i])) tagMinutes[t] = (tagMinutes[t] ?: 0.0) + st.first
        }

        val inputs = ArrayList<Taste.ChannelInput>(n)
        for (i in 0 until n) {
            val ch = index.channels[i]
            val country = LiveOrganizer.country(ch.group, ch.name)
            val st = stats[ch.url]
            val progs = if (guide.isEmpty()) null else guide[Epg.keyOf(ch)]
            val cur = progs?.firstOrNull { it.start <= now && it.end > now }
            val soon = progs?.filter { it.start > now && it.start < now + WINDOW_MS }?.take(6)
                ?.joinToString(" | ") { it.title + " " + it.desc.take(120) } ?: ""
            inputs.add(
                Taste.ChannelInput(
                    index = i,
                    cleanName = index.names[i],
                    section = section[i] ?: "",
                    domestic = country == "US" || country == "United States" || country == LiveOrganizer.UNKNOWN_COUNTRY,
                    favorite = ch.url in favorites,
                    minutes = st?.first ?: 0.0,
                    lastWatched = st?.second ?: 0L,
                    nowText = cur?.let { it.title + " " + it.desc.take(200) } ?: "",
                    soonText = soon,
                ),
            )
        }
        val picks = Taste.rankChannels(inputs, taste, tagMinutes, kids, now)
        val items = picks.map { it.index }.toIntArray()
        val groups = listOf(
            WATCH_AGAIN to Taste.Reason.WATCH_AGAIN,
            ON_NOW to Taste.Reason.ON_NOW,
            YOUR_KIND to Taste.Reason.YOUR_KIND,
        ).mapNotNull { (label, r) ->
            val g = picks.filter { it.reason == r }.map { it.index }.toIntArray()
            if (g.isEmpty()) null else LiveSubgroup(label, g)
        }
        return Result(
            LiveSection("My Guide", items, if (groups.size > 1) groups else emptyList()),
            Taste.ProgrammeMatcher(taste, tagMinutes, kids),
        )
    }
}
