package com.streamvault.app.ui.screens.welcome

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.sync.SyncProgressBus
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.anyOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun providerObservationPublishesPresenceAndStopsWelcomeProgress() = runTest {
        val providers = MutableStateFlow<List<LegacyProvider>>(emptyList())
        val repository = mock<ProviderRepository>()
        whenever(repository.getProviders()).thenReturn(providers)
        val validateAndAddProvider = mock<ValidateAndAddProvider>()

        val viewModel = WelcomeViewModel(
            providerRepository = repository,
            validateAndAddProvider = validateAndAddProvider,
            syncProgressBus = SyncProgressBus()
        )

        advanceUntilIdle()
        assertThat(viewModel.hasProviders.value).isFalse()

        providers.value = listOf(providerFixture())
        advanceUntilIdle()

        assertThat(viewModel.hasProviders.value).isTrue()
        assertThat(viewModel.syncProgress.value).isNull()
    }

    @Test
    fun existingProviderPreventsDevelopmentSeeding() = runTest {
        val repository = mock<ProviderRepository>()
        whenever(repository.getProviders()).thenReturn(MutableStateFlow(listOf(providerFixture())))
        val validateAndAddProvider = mock<ValidateAndAddProvider>()

        WelcomeViewModel(
            providerRepository = repository,
            validateAndAddProvider = validateAndAddProvider,
            syncProgressBus = SyncProgressBus()
        )
        advanceUntilIdle()

        verifyBlocking(validateAndAddProvider, never()) { loginXtream(any(), anyOrNull()) }
        verifyBlocking(validateAndAddProvider, never()) { addM3u(any(), anyOrNull()) }
    }

    private fun providerFixture() = LegacyProvider(
        id = 7L,
        name = "Test provider",
        type = ProviderType.M3U,
        serverUrl = "https://example.test/playlist.m3u"
    )
}
