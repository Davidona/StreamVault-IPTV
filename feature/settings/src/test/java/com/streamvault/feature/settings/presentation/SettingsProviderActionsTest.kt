package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.usecase.SyncProvider
import com.streamvault.feature.settings.api.SettingsSurfaceRefreshPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProviderActionsTest {

    private val providerRepository: ProviderRepository = mock()
    private val combinedM3uRepository: CombinedM3uRepository = mock()
    private val preferencesRepository: SettingsPreferences = mock()
    private val syncProvider: SyncProvider = mock()
    private val syncManager: SyncManager = mock()
    private val syncMetadataRepository: SyncMetadataRepository = mock()
    private val surfaceRefreshPort: SettingsSurfaceRefreshPort = mock()
    private val uiState = MutableStateFlow(SettingsUiState())

    private val actions = SettingsProviderActions(
        providerRepository = providerRepository,
        combinedM3uRepository = combinedM3uRepository,
        preferencesRepository = preferencesRepository,
        syncProvider = syncProvider,
        syncManager = syncManager,
        syncMetadataRepository = syncMetadataRepository,
        surfaceRefreshPort = surfaceRefreshPort,
        uiState = uiState
    )

    @Test
    fun providerWithFutureSyncTimestampIsStaleAfterBackwardClockJump() {
        assertThat(
            shouldAutoSyncProvider(
                lastSyncedAt = 10_001L,
                now = 10_000L,
                staleAfterMillis = 86_400_000L
            )
        ).isTrue()
    }

    @Test
    fun setActiveProvider_refreshesProviderScopedTvSurfaces() = runTest(StandardTestDispatcher()) {
        val provider = Provider(
            id = 7L,
            name = "Provider Seven",
            type = ProviderType.M3U,
            serverUrl = "https://example.com",
            lastSyncedAt = System.currentTimeMillis()
        )
        whenever(providerRepository.getProvider(7L)).thenReturn(provider)

        actions.setActiveProvider(this, 7L)
        advanceUntilIdle()

        verify(preferencesRepository).setLastActiveProviderId(7L)
        verify(combinedM3uRepository).setActiveLiveSource(ActiveLiveSource.ProviderSource(7L))
        verify(providerRepository).setActiveProvider(7L)
        verify(surfaceRefreshPort).refreshWatchNext()
        verify(surfaceRefreshPort).refreshRecommendations()
        verify(surfaceRefreshPort).refreshTvInputCatalog()
        verify(syncProvider, never()).invoke(any(), any())
    }

    @Test
    fun deleteProvider_refreshesProviderScopedTvSurfaces() = runTest(StandardTestDispatcher()) {
        whenever(providerRepository.deleteProvider(eq(7L), any())).thenReturn(
            Result.success(
                com.streamvault.domain.repository.ProviderDeleteOutcome(
                    providerId = 7L,
                    pendingCleanupActions = 0,
                    reconciliationRequested = true
                )
            )
        )

        actions.deleteProvider(this, 7L)
        advanceUntilIdle()

        verify(providerRepository).deleteProvider(eq(7L), any())
        verify(surfaceRefreshPort).refreshWatchNext()
        verify(surfaceRefreshPort).refreshRecommendations()
        verify(surfaceRefreshPort).refreshTvInputCatalog()
        assertThat(uiState.value.userMessage).isEqualTo("Provider deleted")
    }

    @Test
    fun deleteProvider_surfacesLibraryDeletedWhileCleanupIsPending() = runTest(StandardTestDispatcher()) {
        whenever(providerRepository.deleteProvider(eq(7L), any())).thenReturn(
            Result.success(
                com.streamvault.domain.repository.ProviderDeleteOutcome(
                    providerId = 7L,
                    pendingCleanupActions = 3,
                    reconciliationRequested = true
                )
            )
        )

        actions.deleteProvider(this, 7L)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage)
            .isEqualTo("Provider library deleted; final cleanup continues")
    }

    @Test
    fun deleteProvider_stillCompletesSuccessWhenFollowUpRefreshFails() = runTest(StandardTestDispatcher()) {
        whenever(providerRepository.deleteProvider(eq(7L), any())).thenReturn(
            Result.success(
                com.streamvault.domain.repository.ProviderDeleteOutcome(
                    providerId = 7L,
                    pendingCleanupActions = 0,
                    reconciliationRequested = true
                )
            )
        )
        doThrow(IllegalStateException("refresh boom")).whenever(surfaceRefreshPort)
            .refreshRecommendations()
        var onSuccessCalled = false

        actions.deleteProvider(this, 7L, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        verify(providerRepository).deleteProvider(eq(7L), any())
        verify(surfaceRefreshPort).refreshWatchNext()
        verify(surfaceRefreshPort).refreshRecommendations()
        verify(surfaceRefreshPort).refreshTvInputCatalog()
        assertThat(onSuccessCalled).isTrue()
        assertThat(uiState.value.isDeletingProvider).isFalse()
        assertThat(uiState.value.userMessage).isEqualTo("Provider deleted")
    }
}
