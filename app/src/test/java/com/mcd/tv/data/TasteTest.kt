package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    // Keyword ids (made up for the tests).
    private val BOSTON = 1
    private val IRISH_MOB = 2
    private val FBI = 3
    private val TRUE_STORY = 4
    private val UNDERCOVER = 5
    private val MAFIA = 6
    private val TALKING_ANIMALS = 7
    private val WEDDING = 8
    private val NAZI = 9
    private val DEPP = 100
    private val DIRECTOR = 101

    private fun f(key: String, genres: Set<Int>, kw: Set<Int> = emptySet(), people: Set<Int> = emptySet(), year: Int = 2010, rating: Double = 7.0, votes: Int = 5000) =
        Features(key, genres, kw, people, year, rating, votes, "en")

    private val blackMass = f("movie:261023", setOf(Taste.CRIME, Taste.DRAMA, Taste.HISTORY), setOf(BOSTON, IRISH_MOB, FBI, TRUE_STORY), setOf(DEPP, DIRECTOR), 2015, 6.6, 4000)
    private val departed = f("movie:1422", setOf(Taste.CRIME, Taste.DRAMA, Taste.THRILLER), setOf(BOSTON, IRISH_MOB, UNDERCOVER), year = 2006, rating = 8.2, votes = 15000)
    private val goodfellas = f("movie:769", setOf(Taste.CRIME, Taste.DRAMA), setOf(MAFIA, TRUE_STORY), year = 1990, rating = 8.5, votes = 12000)
    private val cartoon = f("movie:9000", setOf(Taste.ANIMATION, Taste.FAMILY, Taste.COMEDY), setOf(TALKING_ANIMALS), year = 2016, rating = 7.7, votes = 15000)
    private val romcom = f("movie:9001", setOf(Taste.ROMANCE, Taste.COMEDY), setOf(WEDDING), year = 2015, rating = 6.8, votes = 3000)
    private val obscure = f("movie:9002", setOf(Taste.CRIME, Taste.DRAMA), setOf(BOSTON, IRISH_MOB), year = 2015, rating = 7.5, votes = 12)

    @Test fun moreLikeThisPutsStoryMatchesFirstAndCartoonsLast() {
        // TMDB's own order: the cartoon and romcom ranked high (what made the old list feel random).
        val cands = listOf(
            Taste.Candidate(cartoon, 0), Taste.Candidate(romcom, 1), Taste.Candidate(departed, 5),
            Taste.Candidate(goodfellas, 10), Taste.Candidate(obscure, 2),
        )
        val ranked = Taste.rankSimilar(blackMass, cands, TasteProfile.EMPTY, exclude = emptySet())
        assertEquals("movie:1422", ranked[0]) // The Departed: Boston, Irish mob
        assertEquals("movie:769", ranked[1]) // Goodfellas: mob, true story
        assertTrue(ranked.indexOf("movie:9000") > ranked.indexOf("movie:769"))
        assertTrue(ranked.indexOf("movie:9001") > ranked.indexOf("movie:769"))
    }

    @Test fun moreLikeThisSkipsExcludedAndTheSeed() {
        val cands = listOf(Taste.Candidate(blackMass, 0), Taste.Candidate(departed, 1), Taste.Candidate(goodfellas, 2))
        val ranked = Taste.rankSimilar(blackMass, cands, TasteProfile.EMPTY, exclude = setOf("movie:1422"))
        assertEquals(listOf("movie:769"), ranked)
    }

    @Test fun watchedTitlesMoveDown() {
        val cands = listOf(Taste.Candidate(departed, 0), Taste.Candidate(goodfellas, 1))
        val ranked = Taste.rankSimilar(blackMass, cands, TasteProfile.EMPTY, emptySet(), watched = setOf("movie:1422"))
        assertEquals("movie:769", ranked[0])
    }

    @Test fun avoidedGenreIsNeverSuggested() {
        val taste = Taste.build(emptyList(), TasteChoices(avoidGenres = setOf(Taste.ROMANCE)), now)
        val ranked = Taste.rankForYou(listOf(Taste.Candidate(romcom), Taste.Candidate(departed)), taste, emptySet())
        assertEquals(listOf("movie:1422"), ranked)
        assertEquals(-1.0, taste.score(romcom), 1e-9)
    }

    @Test fun avoidedTopicIsNeverSuggested() {
        val taste = Taste.build(emptyList(), TasteChoices(avoidKeywords = setOf(WEDDING)), now)
        assertFalse(Taste.rankForYou(listOf(Taste.Candidate(romcom)), taste, emptySet()).contains("movie:9001"))
    }

    @Test fun learnsFromWatchingAndFromDislikes() {
        val war1 = f("movie:1", setOf(Taste.WAR, Taste.DRAMA), setOf(NAZI))
        val war2 = f("movie:2", setOf(Taste.WAR, Taste.HISTORY), setOf(NAZI))
        val taste = Taste.build(
            listOf(Signal(war1, 1.0, now - 10 * day), Signal(war2, 1.0, now - 5 * day), Signal(romcom, -1.0, now - day)),
            TasteChoices(), now,
        )
        assertTrue((taste.genres[Taste.WAR] ?: 0.0) > 0.3)
        assertTrue((taste.genres[Taste.ROMANCE] ?: 0.0) < 0.0)
        val warNew = f("movie:3", setOf(Taste.WAR), setOf(NAZI))
        assertTrue(taste.score(warNew) > taste.score(romcom))
    }

    @Test fun tvWarAndPoliticsCountsAsWar() {
        val taste = Taste.build(listOf(Signal(f("tv:1", setOf(Taste.WAR_POLITICS)), 1.0, now)), TasteChoices(), now)
        assertTrue((taste.genres[Taste.WAR] ?: 0.0) > 0.0)
    }

    @Test fun likedGenresLiftNewProfiles() {
        val taste = Taste.build(emptyList(), TasteChoices(likeGenres = setOf(Taste.CRIME, Taste.WAR)), now)
        val ranked = Taste.rankForYou(listOf(Taste.Candidate(romcom), Taste.Candidate(departed)), taste, emptySet())
        assertEquals("movie:1422", ranked[0])
    }

    @Test fun oldSignalsFade() {
        assertTrue(Taste.decay(now - 300 * day, now) < Taste.decay(now - 10 * day, now))
        assertEquals(0.5, Taste.decay(now - 150 * day, now), 0.01)
    }

    @Test fun qualityPullsFewVotesTowardAverage() {
        assertTrue(Taste.quality(9.5, 10) < Taste.quality(8.0, 20000))
    }

    // ---------------------------------------------------------------- Live TV

    @Test fun networksAreTaggedAndCopiesShareAKey() {
        assertTrue(Taste.Tag.SPORTS in Taste.channelTags("ESPN"))
        assertTrue(Taste.Tag.HISTORY in Taste.channelTags("History Channel"))
        assertTrue(Taste.Tag.CRIME in Taste.channelTags("Investigation Discovery"))
        assertTrue(Taste.Tag.COMEDY in Taste.channelTags("Comedy Central"))
        assertEquals(Taste.networkKey("ESPN HD"), Taste.networkKey("ESPN East"))
        assertNotEquals(Taste.networkKey("ESPN"), Taste.networkKey("ESPN2"))
    }

    private fun ch(i: Int, name: String, section: String = LiveOrganizer.ENTERTAINMENT, domestic: Boolean = true, fav: Boolean = false,
                   minutes: Double = 0.0, last: Long = 0L, nowText: String = "", soonText: String = "") =
        Taste.ChannelInput(i, name, section, domestic, fav, minutes, last, nowText, soonText)

    @Test fun forYouRanksHabitsThenTasteAndCollapsesCopies() {
        val taste = Taste.build(emptyList(), TasteChoices(likeGenres = setOf(Taste.WAR, Taste.CRIME, Taste.COMEDY)), now)
        val picks = Taste.rankChannels(
            listOf(
                ch(0, "HGTV"),
                ch(1, "History"),
                ch(2, "History HD"),
                ch(3, "ESPN", LiveOrganizer.SPORTS, minutes = 300.0, last = now - day),
                ch(4, "Comedy Central"),
                ch(5, "BBC One", LiveOrganizer.INTERNATIONAL, domestic = false),
                ch(6, "Movie Loop 24/7", LiveOrganizer.H24),
                ch(7, "Disney Junior", LiveOrganizer.KIDS),
            ),
            taste, emptyMap(), kids = false, now = now,
        )
        val idx = picks.map { it.index }
        assertEquals(3, idx[0]) // watched a lot
        assertEquals(Taste.Reason.WATCH_AGAIN, picks[0].reason)
        assertTrue(1 in idx || 2 in idx)
        assertFalse(1 in idx && 2 in idx) // one copy of History
        assertTrue(4 in idx)
        assertFalse(5 in idx) // international, never watched
        assertFalse(6 in idx) // 24/7 loop
        assertFalse(7 in idx) // kids channel on a grown-up profile
        assertTrue(idx.indexOf(4) < (idx.indexOf(0).takeIf { it >= 0 } ?: Int.MAX_VALUE))
    }

    @Test fun onNowMatchesLikedTopics() {
        val taste = Taste.build(emptyList(), TasteChoices(likeWords = listOf("stand-up")), now)
        val picks = Taste.rankChannels(
            listOf(ch(0, "Some Channel", nowText = "Late night stand-up special"), ch(1, "Other Channel", nowText = "Home makeover")),
            taste, emptyMap(), kids = false, now = now,
        )
        assertEquals(0, picks.first().index)
        assertEquals(Taste.Reason.ON_NOW, picks.first().reason)
    }

    @Test fun kidsProfileGetsKidsChannels() {
        val picks = Taste.rankChannels(
            listOf(ch(0, "Disney Channel", LiveOrganizer.KIDS), ch(1, "History"), ch(2, "Nickelodeon", LiveOrganizer.KIDS)),
            TasteProfile.EMPTY, emptyMap(), kids = true, now = now,
        )
        assertEquals(setOf(0, 2), picks.map { it.index }.toSet())
    }

    @Test fun pokerLaterTonightLiftsAChannel() {
        val taste = Taste.build(emptyList(), TasteChoices(likeWords = listOf("poker")), now)
        val picks = Taste.rankChannels(
            listOf(
                ch(0, "Some Sports Channel", LiveOrganizer.SPORTS, nowText = "College volleyball"),
                ch(1, "Other Sports Channel", LiveOrganizer.SPORTS, nowText = "College volleyball", soonText = "World Series of Poker Main Event"),
            ),
            taste, emptyMap(), kids = false, now = now,
        )
        assertEquals(1, picks.first().index)
    }

    @Test fun programmeMatcherScoresTopicsKindsAndSkips() {
        val taste = Taste.build(
            emptyList(),
            TasteChoices(likeGenres = setOf(Taste.WAR), likeWords = listOf("poker"), avoidWords = listOf("makeover")),
            now,
        )
        val m = Taste.ProgrammeMatcher(taste)
        assertEquals(1.0, m.score("High Stakes Poker"), 1e-9)
        assertTrue(m.score("WWII in Color: Road to Victory") > 0.3)
        assertEquals(-1.0, m.score("Extreme Home Makeover"), 1e-9)
        assertEquals(0.0, m.score("Cooking with Ana"), 1e-9)
    }

    @Test fun tvGenresCountForTheMovieGenresYouLike() {
        // TV uses combined genres: "War & Politics" (10768), "Sci-Fi & Fantasy" (10765).
        val dad = Taste.build(emptyList(), TasteChoices(likeGenres = setOf(Taste.WAR)), now)
        val band = f("tv:4613", setOf(Taste.WAR_POLITICS, Taste.DRAMA))
        val plain = f("tv:1", setOf(Taste.DRAMA))
        assertTrue(dad.score(band) > dad.score(plain))
        val mom = Taste.build(emptyList(), TasteChoices(likeGenres = setOf(14)), now)
        val thrones = f("tv:1399", setOf(10765, Taste.DRAMA))
        assertTrue(mom.score(thrones) > mom.score(plain))
        assertTrue(Taste.genreIn(10765, setOf(14)))
        assertFalse(Taste.genreIn(Taste.DRAMA, setOf(14)))
    }

    @Test fun learnedTvTasteCarriesOverToTvShows() {
        val watched = f("tv:2", setOf(Taste.WAR_POLITICS), setOf(NAZI))
        val taste = Taste.build(listOf(Signal(watched, 1.0, now)), TasteChoices(), now)
        assertTrue(taste.score(f("tv:3", setOf(Taste.WAR_POLITICS))) > 0.0)
    }
}
