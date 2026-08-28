package com.streamvault.feature.live.presentation.remote

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutPreferences
import org.junit.Test

class LiveRemoteShortcutPolicyTest {
    @Test
    fun `browse profile keeps red green yellow blue defaults`() {
        val preferences = RemoteShortcutPreferences()

        assertThat(resolveLiveRemoteShortcut(preferences, RemoteShortcutProfile.BROWSE, RemoteColorButton.RED))
            .isEqualTo(RemoteShortcutAction.TOGGLE_FAVORITE)
        assertThat(resolveLiveRemoteShortcut(preferences, RemoteShortcutProfile.BROWSE, RemoteColorButton.GREEN))
            .isEqualTo(RemoteShortcutAction.PIN_CATEGORY)
        assertThat(resolveLiveRemoteShortcut(preferences, RemoteShortcutProfile.BROWSE, RemoteColorButton.YELLOW))
            .isEqualTo(RemoteShortcutAction.HIDE_CATEGORY)
        assertThat(resolveLiveRemoteShortcut(preferences, RemoteShortcutProfile.BROWSE, RemoteColorButton.BLUE))
            .isEqualTo(RemoteShortcutAction.ADD_TO_SPLIT_SCREEN)
    }

    @Test
    fun `channel and category support remain distinct`() {
        assertThat(RemoteShortcutAction.TOGGLE_FAVORITE.isSupportedInLiveBrowse(LiveBrowseTarget.CHANNEL)).isTrue()
        assertThat(RemoteShortcutAction.PIN_CATEGORY.isSupportedInLiveBrowse(LiveBrowseTarget.CATEGORY)).isTrue()
        assertThat(RemoteShortcutAction.PIN_CATEGORY.isSupportedInLiveBrowse(LiveBrowseTarget.CHANNEL)).isFalse()
        assertThat(RemoteShortcutAction.PLAY_CHANNEL.isSupportedInLiveBrowse(LiveBrowseTarget.CATEGORY)).isFalse()
    }

    @Test
    fun `unmapped actions are rejected`() {
        assertThat(RemoteShortcutAction.OPEN_PLAYER_CONTROLS.isSupportedInLiveBrowse(LiveBrowseTarget.CHANNEL))
            .isFalse()
        assertThat(RemoteShortcutAction.NONE.isSupportedInLiveBrowse(LiveBrowseTarget.CATEGORY)).isTrue()
    }
}
