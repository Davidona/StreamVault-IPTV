package com.streamvault.app.ui.remote

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.feature.live.presentation.remote.LiveBrowseRemoteShortcutHandler
import com.streamvault.feature.live.presentation.remote.dispatchLiveBrowseRemoteShortcut
import org.junit.Test

class RemoteShortcutDispatchTest {

    @Test
    fun playbackDispatchRunsExpectedHandler() {
        var guideOpened = false

        val handled = dispatchPlayerRemoteShortcut(
            action = RemoteShortcutAction.OPEN_GUIDE,
            handler = PlayerRemoteShortcutHandler(
                isLiveContent = true,
                isCatchUpPlayback = false,
                onOpenGuide = { guideOpened = true },
                onOpenPlayerControls = {},
                onOpenChannelInfo = {},
                onOpenChannelList = {},
                onOpenCategoryList = {},
                onLastChannel = {},
                onNextChannel = {},
                onPreviousChannel = {},
                onAddToSplitScreen = {}
            )
        )

        assertThat(handled).isTrue()
        assertThat(guideOpened).isTrue()
    }

    @Test
    fun playbackDispatchIgnoresUnsupportedAction() {
        var favoriteToggled = false

        val handled = dispatchPlayerRemoteShortcut(
            action = RemoteShortcutAction.TOGGLE_FAVORITE,
            handler = PlayerRemoteShortcutHandler(
                isLiveContent = true,
                isCatchUpPlayback = false,
                onOpenGuide = {},
                onOpenPlayerControls = {},
                onOpenChannelInfo = {},
                onOpenChannelList = {},
                onOpenCategoryList = {},
                onLastChannel = {},
                onNextChannel = {},
                onPreviousChannel = {},
                onAddToSplitScreen = { favoriteToggled = true }
            )
        )

        assertThat(handled).isFalse()
        assertThat(favoriteToggled).isFalse()
    }

    @Test
    fun browseChannelDispatchTogglesFavorite() {
        var favoriteToggled = false

        val handled = dispatchLiveBrowseRemoteShortcut(
            action = RemoteShortcutAction.TOGGLE_FAVORITE,
            handler = LiveBrowseRemoteShortcutHandler.Channel(
                onToggleFavorite = { favoriteToggled = true },
                onPlayChannel = {},
                onAddToSplitScreen = {}
            )
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
                onHideCategory = { hidden = true }
            )
        )

        assertThat(handled).isFalse()
        assertThat(hidden).isFalse()
    }
}
