package com.streamvault.feature.live.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveRoutePatternsTest {
    @Test
    fun liveRoutes_preserveExistingNavigationContracts() {
        assertThat(LiveRoutePatterns.LIVE_TV).isEqualTo("live_tv")
        assertThat(LiveRoutePatterns.LIVE_TV_DESTINATION)
            .isEqualTo("live_tv?categoryId={categoryId}")
        assertThat(LiveRoutePatterns.EPG).isEqualTo("epg")
        assertThat(LiveRoutePatterns.EPG_DESTINATION)
            .isEqualTo("epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}")
        assertThat(LiveRoutePatterns.epg()).isEqualTo(
            "epg?categoryId=-1&anchorTime=-1&favoritesOnly=false"
        )
        assertThat(LiveRoutePatterns.epg(42L, 1_234L, true)).isEqualTo(
            "epg?categoryId=42&anchorTime=1234&favoritesOnly=true"
        )
    }
}
