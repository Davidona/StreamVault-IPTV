package com.streamvault.core.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigationRequestTest {
    @Test
    fun externalAndInternalRequestsRemainTyped() {
        assertThat(ExternalNavigationRequest.Search("sports"))
            .isNotEqualTo(ExternalNavigationRequest.Destination(AppDestination.Home))
        assertThat(NavigationCommand.OpenPlayer(PlayerNavigationRequest("https://x", "X")))
            .isInstanceOf(NavigationCommand.OpenPlayer::class.java)
    }

    @Test
    fun navigationActionsFakeRecordsTypedCommandsWithoutController() {
        val actions = RecordingNavigationActions()
        val destination = AppDestination.Search("news")
        val request = PlayerNavigationRequest("https://example.com/live.m3u8", "News")

        actions.navigate(destination, NavigationOptions(launchSingleTop = true))
        actions.openPlayer(request)
        actions.returnTo(AppDestination.Home)

        assertThat(actions.navigations).containsExactly(
            NavigationCommand.Navigate(
                destination,
                NavigationOptions(launchSingleTop = true)
            )
        )
        assertThat(actions.players).containsExactly(request)
        assertThat(actions.returns).containsExactly(AppDestination.Home)
    }

    private class RecordingNavigationActions : NavigationActions {
        val navigations = mutableListOf<NavigationCommand.Navigate>()
        val players = mutableListOf<PlayerNavigationRequest>()
        val returns = mutableListOf<AppDestination?>()

        override fun navigate(destination: AppDestination, options: NavigationOptions) {
            navigations += NavigationCommand.Navigate(destination, options)
        }

        override fun openPlayer(request: PlayerNavigationRequest) {
            players += request
        }

        override fun back(): Boolean = true

        override fun returnTo(destination: AppDestination?): Boolean {
            returns += destination
            return true
        }
    }
}
