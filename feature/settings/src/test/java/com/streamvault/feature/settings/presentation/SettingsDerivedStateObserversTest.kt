package com.streamvault.feature.settings.presentation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.settings.R
import com.streamvault.domain.settings.SettingsOperations
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsDerivedStateObserversTest {
    private val providerRepository: ProviderRepository = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val movieRepository: MovieRepository = mock()
    private val seriesRepository: SeriesRepository = mock()
    private val settingsOperations: SettingsOperations = mock()
    private val application: Context = mock()

    @Test
    fun `observeProviderDiagnostics uses live movie and series library counts over metadata counts`() = runTest {
        val provider = Provider(
            id = 7L,
            name = "Premium",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com"
        )
        whenever(providerRepository.getProviders()).thenReturn(flowOf(listOf(provider)))
        whenever(syncMetadataRepository.observeMetadata(7L)).thenReturn(
            flowOf(
                SyncMetadata(
                    providerId = 7L,
                    movieCount = 109_899,
                    seriesCount = 26_147,
                    movieCatalogStale = true
                )
            )
        )
        whenever(movieRepository.getLibraryCount(7L)).thenReturn(flowOf(140_484))
        whenever(seriesRepository.getLibraryCount(7L)).thenReturn(flowOf(32_037))
        whenever(settingsOperations.observeProgramCount(7L)).thenReturn(flowOf(18_422))
        whenever(application.getString(R.string.settings_capability_xtream_without_epg))
            .thenReturn("Xtream without EPG")

        val diagnostics = observeProviderDiagnostics(
            providerRepository = providerRepository,
            syncMetadataRepository = syncMetadataRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            settingsOperations = settingsOperations,
            application = application
        ).first()

        assertThat(diagnostics.getValue(7L).movieCount).isEqualTo(140_484)
        assertThat(diagnostics.getValue(7L).seriesCount).isEqualTo(32_037)
        assertThat(diagnostics.getValue(7L).movieCatalogStale).isTrue()
    }
}
