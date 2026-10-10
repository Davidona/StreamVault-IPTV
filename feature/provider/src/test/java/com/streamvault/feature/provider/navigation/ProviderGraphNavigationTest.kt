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
        val actions = fakeNavigationActions(onBack = {
            backCalls++
            true
        })

        completeProviderSetup(actions, isEditing = true) { startupRequests++ }

        assertThat(backCalls).isEqualTo(1)
        assertThat(startupRequests).isEqualTo(0)
    }

    @Test
    fun completedProviderSetup_usesStartupNavigationWhenItHasNoBackStackEntry() {
        var startupRequests = 0
        val actions = fakeNavigationActions(onBack = { false })

        completeProviderSetup(actions, isEditing = true) { startupRequests++ }

        assertThat(startupRequests).isEqualTo(1)
    }

    @Test
    fun completedNewProviderSetup_navigatesHomeAndRemovesSetupRoute() {
        var backCalls = 0
        var startupRequests = 0
        var navigation: Pair<AppDestination, NavigationOptions>? = null
        val actions = fakeNavigationActions(
            onBack = {
                backCalls++
                true
            },
            onNavigate = { destination, options -> navigation = destination to options }
        )

        completeProviderSetup(actions, isEditing = false) { startupRequests++ }

        assertThat(backCalls).isEqualTo(0)
        assertThat(startupRequests).isEqualTo(0)
        assertThat(navigation?.first).isEqualTo(AppDestination.Home)
        assertThat(navigation?.second?.popUpTo).isEqualTo(AppDestination.ProviderSetup())
        assertThat(navigation?.second?.inclusive).isTrue()
    }

    private fun fakeNavigationActions(
        onBack: () -> Boolean,
        onNavigate: (AppDestination, NavigationOptions) -> Unit = { _, _ -> }
    ): NavigationActions =
        object : NavigationActions {
            override fun navigate(destination: AppDestination, options: NavigationOptions) =
                onNavigate(destination, options)
            override fun openPlayer(request: PlayerNavigationRequest) = Unit
            override fun back(): Boolean = onBack()
            override fun returnTo(destination: AppDestination?): Boolean = false
        }
}
