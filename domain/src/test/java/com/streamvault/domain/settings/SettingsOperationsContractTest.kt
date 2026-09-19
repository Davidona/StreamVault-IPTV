package com.streamvault.domain.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsOperationsContractTest {
    @Test
    fun `operational snapshots retain only settings-facing fields`() {
        val indexJob = SettingsXtreamIndexJob(
            providerId = 7L,
            section = "MOVIE",
            state = "PARTIAL",
            indexedRows = 42,
            lastError = "bad row"
        )
        val onboarding = SettingsXtreamLiveOnboarding(
            providerId = 7L,
            phase = "INDEXING",
            importStrategy = "STREAMING",
            acceptedRowCount = 12,
            stagedFlushCount = 3,
            syncProfileTier = "LOW",
            syncProfileBatchSize = 20,
            syncProfileStrategy = "SEQUENTIAL",
            syncProfileLowMemory = true,
            syncProfileMemoryClassMb = 256,
            syncProfileAvailableMemMb = 128L,
            lastError = "retry",
            updatedAt = 99L
        )

        assertThat(indexJob).isEqualTo(
            SettingsXtreamIndexJob(7L, "MOVIE", "PARTIAL", 42, "bad row")
        )
        assertThat(onboarding.updatedAt).isEqualTo(99L)
        assertThat(SettingsSyncSection.entries).containsExactly(
            SettingsSyncSection.LIVE,
            SettingsSyncSection.MOVIES,
            SettingsSyncSection.SERIES,
            SettingsSyncSection.EPG
        )
    }
}
