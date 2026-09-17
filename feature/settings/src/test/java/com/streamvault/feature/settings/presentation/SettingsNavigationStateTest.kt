package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsNavigationStateTest {
    @Test
    fun `compact settings opens a category as a full content pane`() {
        val initial = SettingsCompactNavigationState.categories()

        val result = initial.openCategory(SettingsCategory.LIVE_TV)

        assertThat(result.pane).isEqualTo(SettingsCompactPane.CONTENT)
        assertThat(result.category).isEqualTo(SettingsCategory.LIVE_TV)
    }

    @Test
    fun `compact back from category content returns to the category list`() {
        val content = SettingsCompactNavigationState.categories()
            .openCategory(SettingsCategory.PLAYBACK)

        val result = content.back()

        assertThat(result).isEqualTo(
            SettingsCompactBackResult.Navigate(
                SettingsCompactNavigationState.categories(SettingsCategory.PLAYBACK)
            )
        )
    }

    @Test
    fun `compact back from the category list exits settings`() {
        val result = SettingsCompactNavigationState.categories().back()

        assertThat(result).isEqualTo(SettingsCompactBackResult.ExitSettings)
    }

    @Test
    fun `opening a page targets its first enabled setting and remembers the opener`() {
        val initial = SettingsNavigationState.root(SettingsCategory.PLAYBACK)

        val result = initial.openPage(
            page = SettingsPage.GENERAL,
            openerId = "page.general",
            firstItemId = "playback.media_session",
        )

        assertThat(result.location).isEqualTo(
            SettingsLocation(
                category = SettingsCategory.PLAYBACK,
                page = SettingsPage.GENERAL,
                itemId = "playback.media_session",
            )
        )
        assertThat(result.returnPoint).isEqualTo(
            SettingsReturnPoint(
                location = SettingsLocation(SettingsCategory.PLAYBACK),
                focusedItemId = "page.general",
            )
        )
        assertThat(result.focusIntent.targetId).isEqualTo("playback.media_session")
    }

    @Test
    fun `back from general playback restores its opener in playback`() {
        val detail = SettingsNavigationState.root(SettingsCategory.PLAYBACK).openPage(
            page = SettingsPage.GENERAL,
            openerId = "page.general",
            firstItemId = "playback.media_session",
        )

        val result = detail.backToParent()

        assertThat(result.location).isEqualTo(SettingsLocation(SettingsCategory.PLAYBACK))
        assertThat(result.focusIntent.targetId).isEqualTo("page.general")
        assertThat(result.returnPoint).isNull()
    }

    @Test
    fun `selecting a category targets that category content instead of app home`() {
        val result = SettingsNavigationState.root(SettingsCategory.PLAYBACK).selectCategory(
            category = SettingsCategory.PRIVACY,
            firstTargetId = "page.parental_controls",
        )

        assertThat(result.location).isEqualTo(SettingsLocation(SettingsCategory.PRIVACY))
        assertThat(result.focusIntent.targetId).isEqualTo("page.parental_controls")
        assertThat(result.focusIntent.targetId).doesNotContain("home")
    }

    @Test
    fun `new focus transition invalidates an attached target from an older transition`() {
        val coordinator = SettingsFocusCoordinator()
        val oldIntent = coordinator.next("page.general")
        val currentIntent = coordinator.next("playback.media_session")

        assertThat(coordinator.canApply(oldIntent, attachedTargetId = "page.general")).isFalse()
        assertThat(coordinator.canApply(currentIntent, attachedTargetId = "playback.media_session")).isTrue()
        assertThat(coordinator.canApply(currentIntent, attachedTargetId = "page.general")).isFalse()
    }

    @Test
    fun `overview and detail pages own independent scroll locations`() {
        assertThat(settingsScrollKey(SettingsCategory.PLAYBACK, null))
            .isEqualTo("PLAYBACK:overview")
        assertThat(settingsScrollKey(SettingsCategory.PLAYBACK, SettingsPage.GENERAL))
            .isEqualTo("PLAYBACK:GENERAL")
        assertThat(settingsScrollKey(SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT))
            .isEqualTo("LIVE_TV:LIVE_LAYOUT")
    }
}
