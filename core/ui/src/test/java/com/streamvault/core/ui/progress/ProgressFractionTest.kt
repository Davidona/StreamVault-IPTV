package com.streamvault.core.ui.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressFractionTest {
    @Test
    fun parsesCurrentAndTotal() {
        assertThat(extractProgressFraction("Syncing 3 / 10")).isEqualTo(0.3f)
    }

    @Test
    fun toleratesWhitespaceAroundRatio() {
        assertThat(extractProgressFraction("Syncing  3/  10")).isEqualTo(0.3f)
    }

    @Test
    fun missingRatioReturnsNull() {
        assertThat(extractProgressFraction("Syncing provider")).isNull()
    }

    @Test
    fun nonPositiveTotalReturnsNull() {
        assertThat(extractProgressFraction("Syncing 1 / 0")).isNull()
    }

    @Test
    fun overTotalClampsToOne() {
        assertThat(extractProgressFraction("Syncing 12 / 10")).isEqualTo(1f)
    }
}

