package com.streamvault.feature.settings.presentation

import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection

fun availableRemoteShortcutActions(profile: RemoteShortcutProfile): List<RemoteShortcutAction> =
    when (profile) {
        RemoteShortcutProfile.GLOBAL -> listOf(
            RemoteShortcutAction.NONE,
            RemoteShortcutAction.OPEN_GUIDE,
            RemoteShortcutAction.OPEN_PLAYER_CONTROLS,
            RemoteShortcutAction.OPEN_CHANNEL_INFO,
            RemoteShortcutAction.LAST_CHANNEL,
            RemoteShortcutAction.NEXT_CHANNEL,
            RemoteShortcutAction.PREVIOUS_CHANNEL,
            RemoteShortcutAction.OPEN_CHANNEL_LIST,
            RemoteShortcutAction.OPEN_CATEGORY_LIST,
            RemoteShortcutAction.ADD_TO_SPLIT_SCREEN,
            RemoteShortcutAction.TOGGLE_FAVORITE,
            RemoteShortcutAction.PLAY_CHANNEL,
            RemoteShortcutAction.PIN_CATEGORY,
            RemoteShortcutAction.TOGGLE_CATEGORY_LOCK,
            RemoteShortcutAction.HIDE_CATEGORY
        )
        RemoteShortcutProfile.PLAYBACK -> listOf(
            RemoteShortcutAction.NONE,
            RemoteShortcutAction.OPEN_GUIDE,
            RemoteShortcutAction.OPEN_PLAYER_CONTROLS,
            RemoteShortcutAction.OPEN_CHANNEL_INFO,
            RemoteShortcutAction.LAST_CHANNEL,
            RemoteShortcutAction.NEXT_CHANNEL,
            RemoteShortcutAction.PREVIOUS_CHANNEL,
            RemoteShortcutAction.OPEN_CHANNEL_LIST,
            RemoteShortcutAction.OPEN_CATEGORY_LIST,
            RemoteShortcutAction.ADD_TO_SPLIT_SCREEN
        )
        RemoteShortcutProfile.BROWSE -> listOf(
            RemoteShortcutAction.NONE,
            RemoteShortcutAction.OPEN_GUIDE,
            RemoteShortcutAction.TOGGLE_FAVORITE,
            RemoteShortcutAction.PLAY_CHANNEL,
            RemoteShortcutAction.ADD_TO_SPLIT_SCREEN,
            RemoteShortcutAction.PIN_CATEGORY,
            RemoteShortcutAction.TOGGLE_CATEGORY_LOCK,
            RemoteShortcutAction.HIDE_CATEGORY
        )
    }

fun remoteShortcutSelectionOptions(profile: RemoteShortcutProfile): List<RemoteShortcutSelection> {
    val options = mutableListOf(RemoteShortcutSelection.profileDefault())
    if (profile != RemoteShortcutProfile.GLOBAL) {
        options += RemoteShortcutSelection.globalDefault()
    }
    options += availableRemoteShortcutActions(profile).map(RemoteShortcutSelection::explicit)
    return options
}
