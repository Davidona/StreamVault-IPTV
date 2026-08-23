package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import org.junit.Test

class AppNavigationCoordinatorTest {
    @Test
    fun commandIsClearedOnlyAfterMatchingAcknowledgement() {
        val ids = sequenceOf(10L, 11L).iterator()
        val coordinator = AppNavigationCoordinator(NavigationCommandIdSource { ids.next() })
        coordinator.submitExternalRequest(ExternalNavigationRequest.Search("news"))

        val pending = coordinator.pendingCommand.value
        assertThat(pending?.id).isEqualTo(10L)
        coordinator.acknowledge(11L)
        assertThat(coordinator.pendingCommand.value).isEqualTo(pending)
        coordinator.acknowledge(10L)
        assertThat(coordinator.pendingCommand.value).isNull()
    }

    @Test
    fun queuedCommandsArePublishedInSubmissionOrder() {
        val ids = sequenceOf(10L, 11L).iterator()
        val coordinator = AppNavigationCoordinator(NavigationCommandIdSource { ids.next() })
        coordinator.submitExternalRequest(ExternalNavigationRequest.Search("news"))
        coordinator.submitExternalRequest(ExternalNavigationRequest.Destination(AppDestination.Home))

        val first = coordinator.pendingCommand.value
        assertThat(first?.id).isEqualTo(10L)
        coordinator.acknowledge(10L)
        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
        assertThat(coordinator.pendingCommand.value?.command)
            .isEqualTo(
                ExternalNavigationRequest.Destination(AppDestination.Home).toNavigationCommand()
            )
    }
}
