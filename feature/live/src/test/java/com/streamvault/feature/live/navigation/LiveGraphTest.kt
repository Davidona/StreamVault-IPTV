package com.streamvault.feature.live.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveGraphTest {
    @Test
    fun routeArguments_useSentinelDefaultsForOptionalValues() {
        // The NavGraph registration uses -1 for optional Long arguments, matching AppRouteCodec.
        assertThat(LiveRoutePatterns.LIVE_TV_DESTINATION).contains("{categoryId}")
        assertThat(LiveRoutePatterns.EPG_DESTINATION).contains("{anchorTime}")
        assertThat(LiveRoutePatterns.EPG_DESTINATION).contains("{favoritesOnly}")
    }
}
