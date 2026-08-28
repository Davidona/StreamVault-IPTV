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
    }
}
