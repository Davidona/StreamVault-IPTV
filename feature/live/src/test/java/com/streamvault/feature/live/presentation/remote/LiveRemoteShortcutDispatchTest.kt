package com.streamvault.feature.live.presentation.remote

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import org.junit.Test

class LiveRemoteShortcutDispatchTest {

    @Test
    fun colorKeyMappingUsesLiveRemoteButtons() {
        assertThat(remoteColorButtonForKeyCode(KeyEvent.KEYCODE_PROG_RED))
            .isEqualTo(RemoteColorButton.RED)
        assertThat(remoteColorButtonForKeyCode(KeyEvent.KEYCODE_PROG_GREEN))
            .isEqualTo(RemoteColorButton.GREEN)
        assertThat(remoteColorButtonForKeyCode(KeyEvent.KEYCODE_PROG_YELLOW))
            .isEqualTo(RemoteColorButton.YELLOW)
        assertThat(remoteColorButtonForKeyCode(KeyEvent.KEYCODE_PROG_BLUE))
            .isEqualTo(RemoteColorButton.BLUE)
        assertThat(remoteColorButtonForKeyCode(KeyEvent.KEYCODE_ENTER)).isNull()
    }

    @Test
    fun browseChannelDispatchRunsExpectedHandler() {
        var favoriteToggled = false

        val handled = dispatchLiveBrowseRemoteShortcut(
            action = RemoteShortcutAction.TOGGLE_FAVORITE,
            handler = LiveBrowseRemoteShortcutHandler.Channel(
                onToggleFavorite = { favoriteToggled = true },
                onPlayChannel = {},
                onAddToSplitScreen = {},
            ),
        )

        assertThat(handled).isTrue()
        assertThat(favoriteToggled).isTrue()
    }

    @Test
    fun browseCategoryDispatchRejectsChannelOnlyAction() {
        var hidden = false

        val handled = dispatchLiveBrowseRemoteShortcut(
            action = RemoteShortcutAction.ADD_TO_SPLIT_SCREEN,
            handler = LiveBrowseRemoteShortcutHandler.Category(
                onPinCategory = {},
                onToggleCategoryLock = {},
                onHideCategory = { hidden = true },
            ),
        )

        assertThat(handled).isFalse()
        assertThat(hidden).isFalse()
    }
}
