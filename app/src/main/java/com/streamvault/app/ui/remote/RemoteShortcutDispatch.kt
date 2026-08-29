package com.streamvault.app.ui.remote

import com.streamvault.domain.model.RemoteShortcutAction

data class PlayerRemoteShortcutHandler(
    val isLiveContent: Boolean,
    val isCatchUpPlayback: Boolean,
    val onOpenGuide: () -> Unit,
    val onOpenPlayerControls: () -> Unit,
    val onOpenChannelInfo: () -> Unit,
    val onOpenChannelList: () -> Unit,
    val onOpenCategoryList: () -> Unit,
    val onLastChannel: () -> Unit,
    val onNextChannel: () -> Unit,
    val onPreviousChannel: () -> Unit,
    val onAddToSplitScreen: () -> Unit
)

fun dispatchPlayerRemoteShortcut(
    action: RemoteShortcutAction,
    handler: PlayerRemoteShortcutHandler
): Boolean {
    if (!action.isSupportedInPlayback(handler.isLiveContent, handler.isCatchUpPlayback)) return false
    when (action) {
        RemoteShortcutAction.NONE -> return true
        RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide()
        RemoteShortcutAction.OPEN_PLAYER_CONTROLS -> handler.onOpenPlayerControls()
        RemoteShortcutAction.OPEN_CHANNEL_INFO -> handler.onOpenChannelInfo()
        RemoteShortcutAction.LAST_CHANNEL -> handler.onLastChannel()
        RemoteShortcutAction.NEXT_CHANNEL -> handler.onNextChannel()
        RemoteShortcutAction.PREVIOUS_CHANNEL -> handler.onPreviousChannel()
        RemoteShortcutAction.OPEN_CHANNEL_LIST -> handler.onOpenChannelList()
        RemoteShortcutAction.OPEN_CATEGORY_LIST -> handler.onOpenCategoryList()
        RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> handler.onAddToSplitScreen()
        else -> return false
    }
    return true
}

fun RemoteShortcutAction.isSupportedInPlayback(
    isLiveContent: Boolean,
    isCatchUpPlayback: Boolean
): Boolean = when (this) {
    RemoteShortcutAction.NONE,
    RemoteShortcutAction.OPEN_PLAYER_CONTROLS -> true
    RemoteShortcutAction.OPEN_GUIDE,
    RemoteShortcutAction.OPEN_CHANNEL_INFO,
    RemoteShortcutAction.LAST_CHANNEL,
    RemoteShortcutAction.NEXT_CHANNEL,
    RemoteShortcutAction.PREVIOUS_CHANNEL,
    RemoteShortcutAction.OPEN_CHANNEL_LIST,
    RemoteShortcutAction.OPEN_CATEGORY_LIST,
    RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> isLiveContent && !isCatchUpPlayback
    else -> false
}
