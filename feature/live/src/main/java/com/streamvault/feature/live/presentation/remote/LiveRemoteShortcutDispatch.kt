package com.streamvault.feature.live.presentation.remote

import android.view.KeyEvent
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction

/** Callbacks exposed by the focused Live browse item to the feature-owned dispatcher. */
sealed interface LiveBrowseRemoteShortcutHandler {
    val onOpenGuide: (() -> Unit)?

    data class Channel(
        val onToggleFavorite: () -> Unit,
        val onPlayChannel: () -> Unit,
        val onAddToSplitScreen: () -> Unit,
        override val onOpenGuide: (() -> Unit)? = null,
    ) : LiveBrowseRemoteShortcutHandler

    data class Category(
        val onPinCategory: () -> Unit,
        val onToggleCategoryLock: () -> Unit,
        val onHideCategory: () -> Unit,
        override val onOpenGuide: (() -> Unit)? = null,
    ) : LiveBrowseRemoteShortcutHandler
}

/** Maps Android TV colour-program keys to the domain-level Live shortcut buttons. */
fun remoteColorButtonForKeyCode(keyCode: Int): RemoteColorButton? = when (keyCode) {
    KeyEvent.KEYCODE_PROG_RED -> RemoteColorButton.RED
    KeyEvent.KEYCODE_PROG_GREEN -> RemoteColorButton.GREEN
    KeyEvent.KEYCODE_PROG_YELLOW -> RemoteColorButton.YELLOW
    KeyEvent.KEYCODE_PROG_BLUE -> RemoteColorButton.BLUE
    else -> null
}

/** Dispatches a configured action to the currently focused Live browse item. */
fun dispatchLiveBrowseRemoteShortcut(
    action: RemoteShortcutAction,
    handler: LiveBrowseRemoteShortcutHandler,
): Boolean {
    if (!action.isSupportedInBrowse(handler)) return false
    when (handler) {
        is LiveBrowseRemoteShortcutHandler.Channel -> when (action) {
            RemoteShortcutAction.NONE -> return true
            RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide?.invoke() ?: return false
            RemoteShortcutAction.TOGGLE_FAVORITE -> handler.onToggleFavorite()
            RemoteShortcutAction.PLAY_CHANNEL -> handler.onPlayChannel()
            RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> handler.onAddToSplitScreen()
            else -> return false
        }

        is LiveBrowseRemoteShortcutHandler.Category -> when (action) {
            RemoteShortcutAction.NONE -> return true
            RemoteShortcutAction.OPEN_GUIDE -> handler.onOpenGuide?.invoke() ?: return false
            RemoteShortcutAction.PIN_CATEGORY -> handler.onPinCategory()
            RemoteShortcutAction.TOGGLE_CATEGORY_LOCK -> handler.onToggleCategoryLock()
            RemoteShortcutAction.HIDE_CATEGORY -> handler.onHideCategory()
            else -> return false
        }
    }
    return true
}

private fun RemoteShortcutAction.isSupportedInBrowse(
    handler: LiveBrowseRemoteShortcutHandler,
): Boolean = when (handler) {
    is LiveBrowseRemoteShortcutHandler.Channel -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.TOGGLE_FAVORITE,
        RemoteShortcutAction.PLAY_CHANNEL,
        RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> true
        else -> false
    }

    is LiveBrowseRemoteShortcutHandler.Category -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.PIN_CATEGORY,
        RemoteShortcutAction.TOGGLE_CATEGORY_LOCK,
        RemoteShortcutAction.HIDE_CATEGORY -> true
        else -> false
    }
}
