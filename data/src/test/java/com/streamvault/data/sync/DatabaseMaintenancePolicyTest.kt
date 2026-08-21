package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DatabaseMaintenancePolicyTest {

    @Test
    fun `program retention covers maximum EPG correction without catch-up`() {
        assertThat(calculateProgramRetentionMillis(catchUpDays = 0))
            .isEqualTo(12L * 60 * 60 * 1000L)
    }

    @Test
    fun `program retention ignores invalid negative catch-up days`() {
        assertThat(calculateProgramRetentionMillis(catchUpDays = -1))
            .isEqualTo(12L * 60 * 60 * 1000L)
    }

    @Test
    fun `program retention preserves provider catch-up window`() {
        assertThat(calculateProgramRetentionMillis(catchUpDays = 7))
            .isEqualTo(7L * 24 * 60 * 60 * 1000L)
    }
}
