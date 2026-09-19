package com.streamvault.feature.live.presentation.remote

import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutPreferences

/** The focus target used when dispatching shortcuts from the Live browse surface. */
enum class LiveBrowseTarget {
    CHANNEL,
    CATEGORY
}

/**
 * Resolves a configured color button using the same profile semantics as the app-wide
 * remote preferences, while keeping Live presentation independent from the app dispatcher.
 */
fun resolveLiveRemoteShortcut(
    preferences: RemoteShortcutPreferences,
    profile: RemoteShortcutProfile,
    button: RemoteColorButton
): RemoteShortcutAction = preferences.resolvedAction(profile, button)

/** Returns whether an action is meaningful for the currently focused Live browse target. */
fun RemoteShortcutAction.isSupportedInLiveBrowse(target: LiveBrowseTarget): Boolean = when (target) {
    LiveBrowseTarget.CHANNEL -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.TOGGLE_FAVORITE,
        RemoteShortcutAction.PLAY_CHANNEL,
        RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> true
        else -> false
    }

    LiveBrowseTarget.CATEGORY -> when (this) {
        RemoteShortcutAction.NONE,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.PIN_CATEGORY,
        RemoteShortcutAction.TOGGLE_CATEGORY_LOCK,
        RemoteShortcutAction.HIDE_CATEGORY -> true
        else -> false
    }
}
