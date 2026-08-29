package com.streamvault.feature.live.epg

import com.streamvault.feature.live.presentation.epg.guidePrimeTimeAnchor
import com.streamvault.feature.live.presentation.epg.jumpGuideAnchorToDay
import com.streamvault.feature.live.presentation.epg.shiftGuideDayStart

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class GuideDateTimeTest {

    @Test
    fun `guide prime time anchor uses selected local day`() {
        val zoneId = ZoneId.of("America/New_York")
        val anchor = LocalDateTime.of(2026, 5, 2, 10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val primeTime = guidePrimeTimeAnchor(anchor, EpgViewModel.PRIME_TIME_HOUR, zoneId)
        val localPrimeTime = Instant.ofEpochMilli(primeTime).atZone(zoneId).toLocalDateTime()

        assertThat(localPrimeTime).isEqualTo(LocalDateTime.of(2026, 5, 2, 20, 0))
    }

    @Test
    fun `jump guide anchor to day preserves local time across dst transition`() {
        val zoneId = ZoneId.of("America/New_York")
        val anchor = LocalDateTime.of(2026, 3, 7, 23, 30)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val targetDayStart = LocalDate.of(2026, 3, 8)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val jumpedAnchor = jumpGuideAnchorToDay(anchor, targetDayStart, zoneId)
        val localJumpedAnchor = Instant.ofEpochMilli(jumpedAnchor).atZone(zoneId).toLocalDateTime()

        assertThat(localJumpedAnchor).isEqualTo(LocalDateTime.of(2026, 3, 8, 23, 30))
    }

    @Test
    fun `shift guide day start advances by local calendar day across dst`() {
        val zoneId = ZoneId.of("America/New_York")
        val dayStart = LocalDate.of(2026, 3, 8)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val shiftedDayStart = shiftGuideDayStart(dayStart, 1, zoneId)
        val shiftedLocalDate = Instant.ofEpochMilli(shiftedDayStart).atZone(zoneId).toLocalDate()

        assertThat(shiftedLocalDate).isEqualTo(LocalDate.of(2026, 3, 9))
    }
}
