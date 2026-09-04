package com.streamvault.feature.system.presentation.welcome

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.sync.Section
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.feature.system.api.SystemWelcomePort
import com.streamvault.feature.system.api.WelcomeDevProviderConfig
import com.streamvault.feature.system.api.WelcomeSyncProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

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

        val viewModel = createViewModel(repository, validateAndAddProvider)

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
        whenever(repository.getProviders()).thenReturn(
            MutableStateFlow(listOf(providerFixture()))
        )
        val validateAndAddProvider = mock<ValidateAndAddProvider>()

        createViewModel(repository, validateAndAddProvider)
        advanceUntilIdle()

        verifyBlocking(validateAndAddProvider, never()) { loginXtream(any(), anyOrNull()) }
        verifyBlocking(validateAndAddProvider, never()) { addM3u(any(), anyOrNull()) }
    }

    @Test
    fun xtreamDevConfigTakesPrecedenceOverM3uConfig() = runTest {
        val repository = emptyProviderRepository()
        val validateAndAddProvider = mock<ValidateAndAddProvider>()
        val config = WelcomeDevProviderConfig(
            xtreamServer = "https://xtream.example",
            xtreamUsername = "user",
            xtreamPassword = "pass",
            xtreamName = "Seeded Xtream",
            m3uUrl = "https://m3u.example/list.m3u",
            m3uName = "Seeded M3U",
        )

        createViewModel(repository, validateAndAddProvider, config)
        advanceUntilIdle()

        val command = argumentCaptor<XtreamProviderSetupCommand>()
        verifyBlocking(validateAndAddProvider) { loginXtream(command.capture(), anyOrNull()) }
        assertThat(command.firstValue).isEqualTo(
            XtreamProviderSetupCommand(
                serverUrl = "https://xtream.example",
                username = "user",
                password = "pass",
                name = "Seeded Xtream",
                xtreamFastSyncEnabled = true,
            )
        )
        verifyBlocking(validateAndAddProvider, never()) { addM3u(any(), anyOrNull()) }
    }

    @Test
    fun m3uDevConfigIsUsedWhenXtreamConfigIsIncomplete() = runTest {
        val repository = emptyProviderRepository()
        val validateAndAddProvider = mock<ValidateAndAddProvider>()
        val config = WelcomeDevProviderConfig(
            xtreamServer = "https://xtream.example",
            xtreamUsername = "user",
            xtreamPassword = "",
            m3uUrl = "https://m3u.example/list.m3u",
            m3uName = "Seeded M3U",
        )

        createViewModel(repository, validateAndAddProvider, config)
        advanceUntilIdle()

        val command = argumentCaptor<M3uProviderSetupCommand>()
        verifyBlocking(validateAndAddProvider) { addM3u(command.capture(), anyOrNull()) }
        assertThat(command.firstValue).isEqualTo(
            M3uProviderSetupCommand(
                url = "https://m3u.example/list.m3u",
                name = "Seeded M3U",
            )
        )
        verifyBlocking(validateAndAddProvider, never()) { loginXtream(any(), anyOrNull()) }
    }

    private fun createViewModel(
        repository: ProviderRepository,
        validateAndAddProvider: ValidateAndAddProvider,
        devProviderConfig: WelcomeDevProviderConfig = WelcomeDevProviderConfig(),
    ) = WelcomeViewModel(
        providerRepository = repository,
        validateAndAddProvider = validateAndAddProvider,
        welcomePort = TestSystemWelcomePort(devProviderConfig),
    )

    private fun emptyProviderRepository(): ProviderRepository {
        val repository = mock<ProviderRepository>()
        whenever(repository.getProviders()).thenReturn(MutableStateFlow(emptyList()))
        return repository
    }

    private fun providerFixture() = LegacyProvider(
        id = 7L,
        name = "Test provider",
        type = ProviderType.M3U,
        serverUrl = "https://example.test/playlist.m3u",
    )

    private class TestSystemWelcomePort(
        override val devProviderConfig: WelcomeDevProviderConfig,
    ) : SystemWelcomePort {
        override val syncProgress: Flow<WelcomeSyncProgress?> =
            MutableStateFlow<WelcomeSyncProgress?>(null)
    }
}
