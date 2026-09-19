package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveClockPreferencesTest {

    @Test
    fun `clock position storage values are stable and restore safely`() {
        assertThat(LiveClockPosition.entries.map(LiveClockPosition::storageValue)).containsExactly(
            "top_start",
            "top_end",
            "bottom_start",
            "bottom_end"
        ).inOrder()
        assertThat(LiveClockPosition.fromStorage(" bottom_start ")).isEqualTo(LiveClockPosition.BOTTOM_START)
        assertThat(LiveClockPosition.fromStorage("unknown")).isEqualTo(LiveClockPosition.TOP_END)
    }

    @Test
    fun `clock size and font storage values are stable`() {
        assertThat(LiveClockSize.entries.map(LiveClockSize::storageValue)).containsExactly(
            "small",
            "medium",
            "large"
        ).inOrder()
        assertThat(LiveClockFont.entries.map(LiveClockFont::storageValue)).containsExactly(
            "clean",
            "digital_mono",
            "classic_serif"
        ).inOrder()
        assertThat(LiveClockSize.fromStorage(null)).isEqualTo(LiveClockSize.MEDIUM)
        assertThat(LiveClockFont.fromStorage("digital_mono")).isEqualTo(LiveClockFont.DIGITAL_MONO)
    }
}
