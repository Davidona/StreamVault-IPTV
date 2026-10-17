package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsHubRefreshPolicyTest {
    @Test
    fun `live fixtures refresh in every selected range`() {
        assertThat(shouldRefreshSportsOnClock(SportsTimeRange.ON_NOW, hasLiveEvents = false, dateChanged = false))
            .isTrue()
        assertThat(shouldRefreshSportsOnClock(SportsTimeRange.TODAY, hasLiveEvents = true, dateChanged = false))
            .isTrue()
        assertThat(shouldRefreshSportsOnClock(SportsTimeRange.UPCOMING, hasLiveEvents = true, dateChanged = false))
            .isTrue()
    }

    @Test
    fun `inactive ranges wait for a live event or local date change`() {
        assertThat(shouldRefreshSportsOnClock(SportsTimeRange.TODAY, hasLiveEvents = false, dateChanged = false))
            .isFalse()
        assertThat(shouldRefreshSportsOnClock(SportsTimeRange.TODAY, hasLiveEvents = false, dateChanged = true))
            .isTrue()
    }
}
