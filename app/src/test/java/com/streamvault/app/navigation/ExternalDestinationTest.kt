package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import org.junit.Test

class ExternalDestinationTest {
    @Test
    fun legacyRouteCompatibilityUsesTypedCodec() {
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("home"))
            .isEqualTo(AppDestination.Home)
        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute(
                "provider_setup?providerId=7&importUri=https%3A%2F%2Fexample.com%2Fguide%3Fname%3DCaf%C3%A9%2BTV"
            )
        ).isEqualTo(
            AppDestination.ProviderSetup(
                providerId = 7L,
                importUri = "https://example.com/guide?name=Café+TV"
            )
        )
    }

    @Test
    fun legacyRouteCompatibilityRejectsUnsupportedAndMalformedRoutes() {
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("settings")).isNull()
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("series_detail/not-a-number")).isNull()
    }
}
