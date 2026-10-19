package com.mcd.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    @Test
    fun freshOpenStartsOnAppUpdatesInTheRail() {
        val f = SettingsFocus.open(saved = null, requested = null)
        assertEquals(SettingsCategory.Updates, f.category)
        assertFalse(f.inContent)
        // App updates is the first rail item, so RIGHT lands on its first row (the Check button): two presses with OK.
        assertEquals(SettingsCategory.Updates, SettingsCategory.entries.first())
        assertEquals(0, f.enterRow(rowCount = 1))
    }

    @Test
    fun deepLinkPicksTheCategory() {
        assertEquals(SettingsCategory.Wall, SettingsFocus.open(null, SettingsCategory.fromId("wall")).category)
        assertNull(SettingsCategory.fromId("nope"))
    }

    @Test
    fun returnRestoresCategoryAndRow() {
        val saved = SettingsFocus().onRail(SettingsCategory.Profiles).onRow(4)
        val back = SettingsFocus.open(SettingsFocus.decode(saved.encode()), requested = SettingsCategory.Updates)
        assertEquals(SettingsCategory.Profiles, back.category)
        assertTrue(back.inContent)
        assertEquals(4, back.row)
    }

    @Test
    fun eachCategoryRemembersItsOwnRow() {
        val f = SettingsFocus()
            .onRail(SettingsCategory.Profiles).onRow(3)
            .onRail(SettingsCategory.Playback).onRow(1)
            .onRail(SettingsCategory.Profiles)
        assertFalse(f.inContent)
        assertEquals(3, f.enterRow(rowCount = 8))
        assertEquals(1, f.onRail(SettingsCategory.Playback).row)
        assertEquals(0, f.onRail(SettingsCategory.Network).row)
    }

    @Test
    fun enterRowClampsAndSkipsEmptyCategories() {
        val f = SettingsFocus().onRail(SettingsCategory.ThisTv).onRow(9)
        assertEquals(2, f.copy(inContent = false).enterRow(rowCount = 3))
        assertNull(f.enterRow(rowCount = 0))
        assertEquals(0, SettingsFocus().onRow(-5).row)
    }

    @Test
    fun backGoesContentThenRailThenOut() {
        val inRow = SettingsFocus().onRail(SettingsCategory.About).onRow(2)
        val rail = inRow.back()!!
        assertFalse(rail.inContent)
        assertEquals(SettingsCategory.About, rail.category)
        assertEquals(2, rail.row) // RIGHT again goes back to the same row
        assertNull(rail.back())
    }

    @Test
    fun decodeRejectsJunk() {
        assertNull(SettingsFocus.decode(null))
        assertNull(SettingsFocus.decode(""))
        assertNull(SettingsFocus.decode("zzz|0|"))
        val f = SettingsFocus.decode("tv|0|tv=x,profiles=2,bad=1,wall=-1")!!
        assertEquals(mapOf(SettingsCategory.Profiles to 2), f.rows)
    }

    @Test
    fun updateTexts() {
        val ready = updateView("0.2.199", "0.2.201", checking = false, error = "", checkedAt = 5)
        assertTrue(ready.ready)
        assertEquals("Update now", ready.button)
        assertEquals("Version 0.2.201 is ready", ready.headline)

        val checking = updateView("0.2.199", null, checking = true, error = "", checkedAt = 0)
        assertTrue(checking.busy)

        val failed = updateView("0.2.199", null, checking = false, error = "No internet.", checkedAt = 5)
        assertEquals("Couldn't check", failed.headline)
        assertEquals("Try again", failed.button)

        val never = updateView("0.2.199", null, checking = false, error = "", checkedAt = 0)
        assertEquals("Check for updates", never.button)

        val upToDate = updateView("0.2.199", null, checking = false, error = "", checkedAt = 5)
        assertEquals("You're up to date", upToDate.headline)
        assertFalse(upToDate.ready)
    }

    @Test
    fun appTextHasNoEmDashes() {
        val all = SettingsCategory.entries.map { it.title } + listOf(
            updateView("1", "2", false, "", 1), updateView("1", null, true, "", 0),
            updateView("1", null, false, "x", 1), updateView("1", null, false, "", 0), updateView("1", null, false, "", 1),
        ).flatMap { listOf(it.headline, it.detail, it.button) }
        all.forEach { assertFalse(it, it.contains('—')) }
    }

    @Test
    fun agoLabels() {
        val now = 10_000_000_000L
        assertEquals("Never", agoLabel(0, now))
        assertEquals("Just now", agoLabel(now - 30_000, now))
        assertEquals("1 minute ago", agoLabel(now - 60_000, now))
        assertEquals("5 minutes ago", agoLabel(now - 5 * 60_000, now))
        assertEquals("2 hours ago", agoLabel(now - 2 * 3_600_000, now))
        assertEquals("1 day ago", agoLabel(now - 86_400_000, now))
        assertEquals("Just now", agoLabel(now + 5_000, now)) // clock moved back
    }

    @Test
    fun possessives() {
        assertEquals("Dad's", possessive("Dad"))
        assertEquals("Kids'", possessive("Kids"))
    }

    @Test
    fun sizeLimits() {
        assertEquals("No limit", sizeLimitLabel(0))
        assertEquals("40 GB", sizeLimitLabel(40))
        assertEquals(0, MOVIE_LIMITS.last())
        assertEquals(0, EPISODE_LIMITS.last())
    }
}
