package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.feature.system.navigation.SystemRoutePatterns
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppRouteCodecTest {
    @Test
    fun typedDestinationsPreserveExistingRoutes() {
        val cases = mapOf(
            AppDestination.Home to "home",
            AppDestination.LiveTv(42L) to "live_tv?categoryId=42",
            AppDestination.Guide(21L, 1_700_000_000_000L, true) to
                "epg?categoryId=21&anchorTime=1700000000000&favoritesOnly=true",
            AppDestination.Search("night shift") to "search?query=night%20shift",
            AppDestination.Welcome to SystemRoutePatterns.WELCOME,
            AppDestination.Downloads to SystemRoutePatterns.DOWNLOADS,
            AppDestination.Plugins to SystemRoutePatterns.PLUGINS,
            AppDestination.ProviderSetup(7L, "content://playlist/1") to
                "provider_setup?providerId=7&importUri=content%3A%2F%2Fplaylist%2F1"
        )

        cases.forEach { (destination, route) ->
            assertThat(AppRouteCodec.encode(destination)).isEqualTo(route)
            assertThat(AppRouteCodec.decode(route)).isEqualTo(destination)
        }
    }

    @Test
    fun nestedReturnDestinationRoundTrips() {
        val destination = AppDestination.SeriesDetail(
            seriesId = 42L,
            returnDestination = AppDestination.Search("night")
        )

        assertThat(AppRouteCodec.decode(AppRouteCodec.encode(destination)))
            .isEqualTo(destination)
    }

    @Test
    fun sentinelArgumentsDecodeAsNullValues() {
        assertThat(AppRouteCodec.decode("live_tv?categoryId=-1"))
            .isEqualTo(AppDestination.LiveTv())
        assertThat(
            AppRouteCodec.decode("epg?categoryId=-1&anchorTime=-1&favoritesOnly=false")
        ).isEqualTo(AppDestination.Guide())
        assertThat(AppRouteCodec.decode("provider_setup?providerId=-1&importUri="))
            .isEqualTo(AppDestination.ProviderSetup())
    }

    @Test
    fun malformedArgumentsReturnNullWithoutThrowing() {
        assertThat(AppRouteCodec.decode("live_tv?categoryId=not-a-number")).isNull()
        assertThat(AppRouteCodec.decode("movie_detail/not-a-number?returnRoute=home")).isNull()
        assertThat(AppRouteCodec.decode("unknown?query=value")).isNull()
    }

    @Test
    fun legacyExternalRoutesDecodeToTypedDestinations() {
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("home"))
            .isEqualTo(AppDestination.Home)
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("plugins"))
            .isEqualTo(AppDestination.Plugins)
        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute(
                "provider_setup?providerId=-1&importUri="
            )
        ).isEqualTo(AppDestination.ProviderSetup())
        assertThat(
            AppRouteCodec.decodeLegacyExternalRoute("series_detail/42?returnRoute=home")
        ).isEqualTo(AppDestination.SeriesDetail(42L, AppDestination.Home))
        assertThat(AppRouteCodec.decodeLegacyExternalRoute("settings")).isNull()
    }
}
