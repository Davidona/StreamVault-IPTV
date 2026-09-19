package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReturnNavigationPolicyTest {
    @Test
    fun existingTargetPopsBeforeNavigating() {
        assertThat(
            planReturnNavigation(
                hasReturnTarget = true,
                hasTargetInBackStack = true,
                hasPreviousEntry = true
            )
        ).isEqualTo(ReturnNavigationPlan.PopToTarget)
    }

    @Test
    fun missingTargetNavigatesAndClearsPlayer() {
        assertThat(
            planReturnNavigation(
                hasReturnTarget = true,
                hasTargetInBackStack = false,
                hasPreviousEntry = true
            )
        ).isEqualTo(ReturnNavigationPlan.NavigateToTarget)
    }

    @Test
    fun missingReturnAndBackStackFallsBackHome() {
        assertThat(
            planReturnNavigation(
                hasReturnTarget = false,
                hasTargetInBackStack = false,
                hasPreviousEntry = false
            )
        ).isEqualTo(ReturnNavigationPlan.NavigateHome)
    }
}
