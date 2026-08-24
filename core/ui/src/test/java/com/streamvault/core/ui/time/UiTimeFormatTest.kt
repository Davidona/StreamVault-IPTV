package com.streamvault.core.ui.time

import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import java.util.Locale
import org.junit.Test

class UiTimeFormatTest {

    @Test
    fun `twenty four hour formatter preserves padded 24 hour output`() {
        val formatted = UiTimeFormat.TWENTY_FOUR_HOUR
            .createTimeFormatter(Locale.US)
            .format(LocalTime.of(13, 5))

        assertThat(formatted).isEqualTo("13:05")
    }
}
