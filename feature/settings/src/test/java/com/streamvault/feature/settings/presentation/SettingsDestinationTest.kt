package com.streamvault.feature.settings.presentation

import org.junit.Assert.*
import org.junit.Test

class SettingsDestinationTest {
    @Test fun `pages have unique stable keys and valid owners`() {
        assertEquals(SettingsPage.entries.size, SettingsPage.entries.map { it.name }.distinct().size)
        SettingsPage.entries.forEach { page ->
            assertTrue(SettingsCategory.entries.any { it.legacyId == page.categoryId })
        }
    }

    @Test fun `backup entry point retains its category and browsing is replaced`() {
        assertEquals(5, SettingsCategory.BACKUP.legacyId)
        assertEquals(2, SettingsCategory.LIVE_TV.legacyId)
        assertTrue(SettingsCategory.entries.none { it.name == "BROWSING" })
    }

    @Test fun `about is last in the navigation list after independent feature settings`() {
        assertEquals(SettingsCategory.ABOUT, SettingsCategory.entries.last())
        assertTrue(SettingsCategory.entries.indexOf(SettingsCategory.TRAKT) < SettingsCategory.entries.indexOf(SettingsCategory.ABOUT))
        assertTrue(SettingsCategory.entries.indexOf(SettingsCategory.SPORTS) < SettingsCategory.entries.indexOf(SettingsCategory.ABOUT))
    }

    @Test fun `playback has short focused pages and remote has its own home`() {
        assertTrue(SettingsPage.entries.count { it.categoryId == 1 } in 5..8)
        assertEquals(SettingsCategory.APP.legacyId, SettingsPage.REMOTE.categoryId)
        assertEquals(SettingsCategory.MOVIES.legacyId, SettingsPage.VOD_PLAYBACK.categoryId)
        assertEquals(SettingsCategory.SOURCES.legacyId, SettingsPage.SOURCE_COMPATIBILITY.categoryId)
    }
}
