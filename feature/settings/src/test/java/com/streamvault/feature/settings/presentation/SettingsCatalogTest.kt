package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile

class SettingsCatalogTest {
    @Test
    fun `remote shortcut targets address every profile and color independently`() {
        val ids = RemoteShortcutProfile.entries.flatMap { profile ->
            RemoteColorButton.entries.map { button -> remoteShortcutSettingId(profile, button) }
        }

        assertThat(ids).containsExactly(
            "remote.shortcuts.global.red",
            "remote.shortcuts.global.green",
            "remote.shortcuts.global.yellow",
            "remote.shortcuts.global.blue",
            "remote.shortcuts.playback.red",
            "remote.shortcuts.playback.green",
            "remote.shortcuts.playback.yellow",
            "remote.shortcuts.playback.blue",
            "remote.shortcuts.browse.red",
            "remote.shortcuts.browse.green",
            "remote.shortcuts.browse.yellow",
            "remote.shortcuts.browse.blue",
        )
        ids.forEach { id -> assertThat(remoteShortcutTargetFromId(id)).isNotNull() }
    }

    private val entries = listOf(
        ResolvedSettingsCatalogEntry(
            id = "playback.media_session",
            category = SettingsCategory.PLAYBACK,
            page = SettingsPage.GENERAL,
            label = "Media session",
            aliases = setOf("notification controls"),
        ),
        ResolvedSettingsCatalogEntry(
            id = "privacy.pin",
            category = SettingsCategory.PRIVACY,
            page = null,
            label = "Change PIN",
            aliases = setOf("parental password"),
        ),
        ResolvedSettingsCatalogEntry(
            id = "recording.usb_storage",
            category = SettingsCategory.RECORDING,
            page = SettingsPage.RECORDING_STORAGE,
            label = "Use USB storage",
            available = false,
        ),
    )

    @Test
    fun `search matches labels aliases categories and pages without case sensitivity`() {
        assertThat(SettingsCatalogSearch.search(entries, "MEDIA").map { it.id })
            .containsExactly("playback.media_session")
        assertThat(SettingsCatalogSearch.search(entries, "notification").map { it.id })
            .containsExactly("playback.media_session")
        assertThat(SettingsCatalogSearch.search(entries, "parental").map { it.id })
            .containsExactly("privacy.pin")
        assertThat(SettingsCatalogSearch.search(entries, "general").map { it.id })
            .containsExactly("playback.media_session")
    }

    @Test
    fun `search excludes actions that are unavailable on this device`() {
        assertThat(SettingsCatalogSearch.search(entries, "usb")).isEmpty()
    }

    @Test
    fun `catalog stores no current values so secrets cannot enter the index`() {
        val pin = entries.single { it.id == "privacy.pin" }

        assertThat(pin.searchableText()).doesNotContain("1234")
        assertThat(pin::class.java.declaredFields.map { it.name }).doesNotContain("value")
    }

    @Test
    fun `a result keeps the exact setting destination`() {
        val result = SettingsCatalogSearch.search(entries, "media").single()

        assertThat(result.category).isEqualTo(SettingsCategory.PLAYBACK)
        assertThat(result.page).isEqualTo(SettingsPage.GENERAL)
        assertThat(result.id).isEqualTo("playback.media_session")
    }
}
