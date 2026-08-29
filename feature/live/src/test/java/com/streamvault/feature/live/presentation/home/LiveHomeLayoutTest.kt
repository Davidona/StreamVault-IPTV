package com.streamvault.feature.live.presentation.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveTvChannelMode
import androidx.compose.ui.unit.dp
import org.junit.Test

class LiveHomeLayoutTest {
    @Test
    fun `compact handset metrics respect minimum widths`() {
        val metrics = liveHomeLayoutMetrics(
            screenWidthDp = 600,
            isTelevisionDevice = false,
            channelMode = LiveTvChannelMode.COMPACT
        )

        assertThat(metrics.sidebarWidth.value).isWithin(0.01f).of(216f)
        assertThat(metrics.channelSearchWidth.value).isWithin(0.01f).of(204f)
        assertThat(metrics.channelRowHeight).isEqualTo(54.dp)
        assertThat(metrics.channelListSpacing).isEqualTo(2.dp)
    }

    @Test
    fun `non television tablet metrics use proportional widths`() {
        val metrics = liveHomeLayoutMetrics(
            screenWidthDp = 1000,
            isTelevisionDevice = false,
            channelMode = LiveTvChannelMode.COMFORTABLE
        )

        assertThat(metrics.sidebarWidth).isEqualTo(252.dp)
        assertThat(metrics.channelSearchWidth).isEqualTo(280.dp)
        assertThat(metrics.channelRowHeight).isEqualTo(92.dp)
        assertThat(metrics.channelListSpacing).isEqualTo(8.dp)
    }

    @Test
    fun `television pro metrics use the fixed desktop layout`() {
        val metrics = liveHomeLayoutMetrics(
            screenWidthDp = 1920,
            isTelevisionDevice = true,
            channelMode = LiveTvChannelMode.PRO
        )

        assertThat(metrics.sidebarWidth).isEqualTo(272.dp)
        assertThat(metrics.channelSearchWidth).isEqualTo(320.dp)
        assertThat(metrics.channelRowHeight).isEqualTo(52.dp)
        assertThat(metrics.channelListSpacing).isEqualTo(2.dp)
    }
}
