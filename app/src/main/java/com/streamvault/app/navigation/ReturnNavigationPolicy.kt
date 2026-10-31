package com.streamvault.app.navigation

internal enum class ReturnNavigationPlan {
    PopToTarget,
    NavigateToTarget,
    PopPrevious,
    NavigateHome
}

internal fun planReturnNavigation(
    hasReturnTarget: Boolean,
    hasTargetInBackStack: Boolean,
    hasPreviousEntry: Boolean
): ReturnNavigationPlan = when {
    hasReturnTarget && hasTargetInBackStack -> ReturnNavigationPlan.PopToTarget
    hasReturnTarget -> ReturnNavigationPlan.NavigateToTarget
    hasPreviousEntry -> ReturnNavigationPlan.PopPrevious
    else -> ReturnNavigationPlan.NavigateHome
}

/**
 * Player entries must never be saved while being popped. Saving the entry keeps its
 * ViewModelStore alive, which in turn keeps the ExoPlayer and MediaSession alive after the
 * player UI is gone. Other destinations can keep the previous save-state behavior.
 */
internal fun shouldSaveCurrentStateOnReplacement(currentRoute: String?): Boolean =
    currentRoute != AppRoutePatterns.PLAYER
