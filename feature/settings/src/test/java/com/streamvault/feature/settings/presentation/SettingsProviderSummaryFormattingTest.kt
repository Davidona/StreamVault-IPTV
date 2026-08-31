package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsProviderSummaryFormattingTest {
    @Test
    fun providerSummary_usesReadableBulletSeparators() {
        assertThat(
            formatProviderSummary(
                listOf("Xtream Codes", "1 connection(s)", "Active until 25 Apr 2027")
            )
        ).isEqualTo("Xtream Codes • 1 connection(s) • Active until 25 Apr 2027")
    }
}
