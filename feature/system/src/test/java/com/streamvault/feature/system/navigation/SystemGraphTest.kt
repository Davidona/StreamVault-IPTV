package com.streamvault.feature.system.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemGraphTest {
    @Test
    fun systemGraphRouteSetPreservesExistingDestinations() {
        assertThat(systemGraphRoutes()).containsExactly(
            "welcome",
            "downloads",
            "plugins",
        ).inOrder()
    }
}
