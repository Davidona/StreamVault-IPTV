package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsXtreamLiveOnboarding
import org.junit.Test

class SettingsOperationalModelsTest {
    @Test
    fun `onboarding snapshot maps every settings-facing field to ui model`() {
        val snapshot = SettingsXtreamLiveOnboarding(
            providerId = 12L,
            phase = "INDEXING",
            importStrategy = "STREAMING",
            acceptedRowCount = 72,
            stagedFlushCount = 4,
            syncProfileTier = "LOW",
            syncProfileBatchSize = 16,
            syncProfileStrategy = "SEQUENTIAL",
            syncProfileLowMemory = true,
            syncProfileMemoryClassMb = 384,
            syncProfileAvailableMemMb = 192L,
            lastError = "malformed row",
            updatedAt = 456L
        )

        assertThat(snapshot.toUiModel()).isEqualTo(
            XtreamLiveOnboardingUiModel(
                phase = "INDEXING",
                importStrategy = "STREAMING",
                acceptedRowCount = 72,
                stagedFlushCount = 4,
                profileTier = "LOW",
                profileBatchSize = 16,
                profileStrategy = "SEQUENTIAL",
                lowMemory = true,
                memoryClassMb = 384,
                availableMemMb = 192L,
                lastError = "malformed row",
                updatedAt = 456L
            )
        )
    }
}
