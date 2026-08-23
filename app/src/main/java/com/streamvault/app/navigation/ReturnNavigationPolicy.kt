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
