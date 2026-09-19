package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AppTimeFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Test

class LiveClockFormattingTest {

    private val timestamp = Instant.parse("2026-01-02T17:05:00Z").toEpochMilli()
    private val utc = TimeZone.getTimeZone(ZoneId.of("UTC"))

    @Test
    fun `twelve hour format includes meridiem`() {
        assertThat(formatLiveClock(timestamp, AppTimeFormat.TWELVE_HOUR, Locale.US, utc))
            .isEqualTo("5:05 PM")
    }

    @Test
    fun `twenty four hour format uses leading hour`() {
        assertThat(formatLiveClock(timestamp, AppTimeFormat.TWENTY_FOUR_HOUR, Locale.US, utc))
            .isEqualTo("17:05")
    }

    @Test
    fun `clock is only visible for enabled non catch up live playback`() {
        assertThat(shouldShowLiveClock("LIVE", isCatchUpPlayback = false, isInPictureInPictureMode = false, enabled = true))
            .isTrue()
        assertThat(shouldShowLiveClock("LIVE", isCatchUpPlayback = true, isInPictureInPictureMode = false, enabled = true))
            .isFalse()
        assertThat(shouldShowLiveClock("MOVIE", isCatchUpPlayback = false, isInPictureInPictureMode = false, enabled = true))
            .isFalse()
        assertThat(shouldShowLiveClock("LIVE", isCatchUpPlayback = false, isInPictureInPictureMode = true, enabled = true))
            .isFalse()
    }
}
