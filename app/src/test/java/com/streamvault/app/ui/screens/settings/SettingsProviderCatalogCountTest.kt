package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.*

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsProviderCatalogCountTest {

    @Test
    fun `movie catalog count stays syncing while job is running even with live rows present`() {
        val diagnostics = ProviderDiagnosticsUiModel(
            movieCount = 140_484,
            lastMovieSuccess = 0L
        )

        val result = diagnostics.movieCatalogCount(ProviderCatalogCountStatus.SYNCING)

        assertThat(result.count).isEqualTo(140_484)
        assertThat(result.status).isEqualTo(ProviderCatalogCountStatus.SYNCING)
    }

    @Test
    fun `catalog tile shows queued tag while section has not indexed rows yet`() {
        val value = ProviderCatalogCountUiModel(
            count = 0,
            status = ProviderCatalogCountStatus.QUEUED
        )

        assertThat(value.shouldShowCatalogStatusTag()).isTrue()
    }

    @Test
    fun `catalog tile shows indexing tag while keeping real count visible`() {
        val value = ProviderCatalogCountUiModel(
            count = 81_500,
            status = ProviderCatalogCountStatus.SYNCING
        )

        assertThat(value.shouldShowCatalogStatusTag()).isTrue()
    }

    @Test
    fun `catalog tile hides status tag when ready`() {
        val value = ProviderCatalogCountUiModel(
            count = 81_500,
            status = ProviderCatalogCountStatus.READY
        )

        assertThat(value.shouldShowCatalogStatusTag()).isFalse()
    }

    @Test
    fun `live catalog count stays syncing during xtream onboarding even with accepted rows present`() {
        val diagnostics = ProviderDiagnosticsUiModel(liveCount = 12_500)

        val result = diagnostics.liveCatalogCount(
            liveOnboardingIncomplete = true,
            xtreamLiveOnboardingPhase = "FETCHING"
        )

        assertThat(result.count).isEqualTo(12_500)
        assertThat(result.status).isEqualTo(ProviderCatalogCountStatus.SYNCING)
    }
}
