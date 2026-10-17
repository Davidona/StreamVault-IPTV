package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsBroadcastStatusTest {
    @Test
    fun derivesStatusOnlyFromTheEpgProgrammeWindow() {
        val event = SportsEvent(
            id = "event",
            title = "Arsenal vs Liverpool",
            league = null,
            categoryLabel = "Football",
            description = "",
            sport = SportType.FOOTBALL,
            startTime = 100L,
            endTime = 200L,
            broadcasts = emptyList(),
            confidence = 1f,
            competitors = emptyList(),
            session = null,
            eventType = SportsEventType.SCHEDULED_EVENT,
            rawProgrammeRefs = emptyList(),
        )

        assertThat(event.broadcastStatusAt(99L)).isEqualTo(SportsBroadcastStatus.UPCOMING)
        assertThat(event.broadcastStatusAt(100L)).isEqualTo(SportsBroadcastStatus.ON_AIR)
        assertThat(event.broadcastStatusAt(199L)).isEqualTo(SportsBroadcastStatus.ON_AIR)
        assertThat(event.broadcastStatusAt(200L)).isEqualTo(SportsBroadcastStatus.ENDED)
    }
}
