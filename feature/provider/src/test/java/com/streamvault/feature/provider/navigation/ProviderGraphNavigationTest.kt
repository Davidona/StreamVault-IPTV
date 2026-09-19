package com.streamvault.feature.provider.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test

class ProviderGraphNavigationTest {
    @Test
    fun completedProviderSetup_popsBackToSettingsWhenLaunchedFromSettings() {
        var backCalls = 0
        var startupRequests = 0
        val actions = fakeNavigationActions {
            backCalls++
            true
        }

        completeProviderSetup(actions) { startupRequests++ }

        assertThat(backCalls).isEqualTo(1)
        assertThat(startupRequests).isEqualTo(0)
    }

    @Test
    fun completedProviderSetup_usesStartupNavigationWhenItHasNoBackStackEntry() {
        var startupRequests = 0
        val actions = fakeNavigationActions { false }

        completeProviderSetup(actions) { startupRequests++ }

        assertThat(startupRequests).isEqualTo(1)
    }

    private fun fakeNavigationActions(onBack: () -> Boolean): NavigationActions =
        object : NavigationActions {
            override fun navigate(destination: AppDestination, options: NavigationOptions) = Unit
            override fun openPlayer(request: PlayerNavigationRequest) = Unit
            override fun back(): Boolean = onBack()
            override fun returnTo(destination: AppDestination?): Boolean = false
        }
}
