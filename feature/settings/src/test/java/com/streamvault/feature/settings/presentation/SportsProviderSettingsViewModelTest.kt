package com.streamvault.feature.settings.presentation

import com.streamvault.domain.sports.provider.SportsProviderReleasePolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.SportsProviderConnectionTester
import com.streamvault.domain.sports.provider.SportsProviderCredentials
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsProviderUsageProvider
import com.streamvault.domain.sports.provider.SportsProviderUsageSnapshot
import com.streamvault.domain.sports.provider.EventProvider
import com.streamvault.domain.sports.provider.LiveScoreProvider
import com.streamvault.domain.sports.provider.ProviderBatch
import com.streamvault.domain.sports.provider.ProviderEventRequest
import com.streamvault.domain.sports.provider.ProviderScoreRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsEventSourceSettings
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import com.streamvault.domain.sports.provider.SportsScoreSourceSettings
import com.streamvault.domain.sports.provider.SportsStandingsSourceSettings
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.SportsMetadataSourceSettings
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.domain.sports.provider.StandingsProvider
import com.streamvault.domain.sports.provider.TeamMetadataProvider
import com.streamvault.domain.sports.provider.LeagueMetadataProvider
import com.streamvault.domain.sports.provider.ProviderStandings
import com.streamvault.domain.sports.provider.ProviderStandingsRequest
import com.streamvault.domain.sports.provider.ProviderTeamMetadata
import com.streamvault.domain.sports.provider.ProviderTeamMetadataRequest
import com.streamvault.domain.sports.provider.ProviderLeagueMetadata
import com.streamvault.domain.sports.provider.ProviderLeagueMetadataRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsProviderSettingsViewModelTest {
    @Test
    fun `saving custom definition preserves it and exposes mapped capabilities and display name`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val factory = TestCustomProviderFactory()
            val viewModel = SportsProviderSettingsViewModel(
                store,
                SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)),
                TestEventSourceSettings(),
                TestScoreSourceSettings(),
                TestStandingsSourceSettings(),
                TestMetadataSourceSettings(),
            )
            advanceUntilIdle()
            val definition = """{"name":"North Hockey","events":{},"scores":{}}"""

            viewModel.saveProvider(
                descriptor = factory.descriptor,
                existing = null,
                baseUrl = "https://sports.example/api",
                username = "alice",
                secret = "old-secret",
                customDefinitionJson = definition,
            )
            advanceUntilIdle()

            val initial = store.configurations().single()
            viewModel.saveProvider(
                descriptor = factory.descriptor,
                existing = initial,
                baseUrl = "https://sports.example/api",
                username = "bob",
                secret = "",
                customDefinitionJson = definition,
            )
            advanceUntilIdle()

            val saved = store.configurations().single()
            assertThat(saved.customDefinitionJson).isEqualTo(definition)
            assertThat(viewModel.displayNameFor(saved)).isEqualTo("North Hockey")
            assertThat(viewModel.capabilitiesFor(saved))
                .containsExactly(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES)
            assertThat(store.credentials(saved.instanceId)?.secret).isEqualTo("old-secret")
            assertThat(store.credentials(saved.instanceId)?.username).isEqualTo("bob")
            assertThat(viewModel.state.value.validationFailed).isFalse()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saving provider keeps credentials in the dedicated store`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val factory = TestProviderFactory()
            val scoreSourceSettings = TestScoreSourceSettings()
            val standingsSourceSettings = TestStandingsSourceSettings()
            val metadataSourceSettings = TestMetadataSourceSettings()
            val viewModel = SportsProviderSettingsViewModel(
                store, SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)), TestEventSourceSettings(), scoreSourceSettings,
                standingsSourceSettings, metadataSourceSettings,
            )
            advanceUntilIdle()

            viewModel.saveProvider(factory.descriptor, null, "", "", "premium-key", listOf("4328"))
            advanceUntilIdle()

            val saved = store.configurations().single()
            assertThat(saved.typeId).isEqualTo(factory.descriptor.typeId)
            assertThat(saved.baseUrl).isEqualTo(factory.descriptor.defaultBaseUrl)
            assertThat(saved.eventLeagueIds).containsExactly("4328")
            assertThat(store.credentials(saved.instanceId)?.secret).isEqualTo("premium-key")
            assertThat(store.credentials(saved.instanceId).toString()).doesNotContain("premium-key")
            assertThat(viewModel.state.value.validationFailed).isFalse()
            assertThat(viewModel.state.value.lastSavedInstanceId).isEqualTo(saved.instanceId)
            viewModel.setScoreSourcePreference(SportsScoreSourcePreference.provider(saved.instanceId))
            advanceUntilIdle()
            assertThat(scoreSourceSettings.scoreSourcePreference.value)
                .isEqualTo(SportsScoreSourcePreference.provider(saved.instanceId))
            viewModel.setStandingsSourcePreference(SportsStandingsSourcePreference.provider(saved.instanceId))
            viewModel.setMetadataSourcePreference(SportsMetadataSourcePreference.provider(saved.instanceId))
            advanceUntilIdle()
            assertThat(standingsSourceSettings.standingsSourcePreference.value)
                .isEqualTo(SportsStandingsSourcePreference.provider(saved.instanceId))
            assertThat(metadataSourceSettings.metadataSourcePreference.value)
                .isEqualTo(SportsMetadataSourcePreference.provider(saved.instanceId))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `refresh exposes provider usage snapshot without exposing credentials`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val factory = TestProviderFactory()
            val viewModel = SportsProviderSettingsViewModel(
                store, SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)),
                TestEventSourceSettings(), TestScoreSourceSettings(), TestStandingsSourceSettings(),
                TestMetadataSourceSettings(),
            )
            advanceUntilIdle()
            viewModel.saveProvider(factory.descriptor, null, "", "", "premium-key")
            advanceUntilIdle()
            val saved = store.configurations().single()
            viewModel.testConnection(saved)
            advanceUntilIdle()
            assertThat(viewModel.state.value.connectionStates[saved.instanceId])
                .isEqualTo(SportsProviderConnectionState.SUCCESS)
            assertThat(viewModel.state.value.usageSnapshots[saved.instanceId]?.requestsRemaining).isEqualTo(97)

            viewModel.refresh()
            advanceUntilIdle()

            assertThat(viewModel.state.value.usageSnapshots[saved.instanceId]).isEqualTo(
                SportsProviderUsageSnapshot(requestLimit = 100, requestsRemaining = 97, planTier = "BASIC"),
            )
            assertThat(viewModel.state.value.usageSnapshots.toString()).doesNotContain("premium-key")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `draft connection test uses entered key without persisting it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val factory = TestProviderFactory()
            val viewModel = SportsProviderSettingsViewModel(
                store, SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)), TestEventSourceSettings(), TestScoreSourceSettings(),
                TestStandingsSourceSettings(), TestMetadataSourceSettings(),
            )
            advanceUntilIdle()

            viewModel.testDraftConnection(factory.descriptor, null, "", "", "draft-key")
            advanceUntilIdle()

            assertThat(viewModel.state.value.draftConnectionState).isEqualTo(SportsProviderConnectionState.SUCCESS)
            assertThat(factory.lastTestedCredentials?.secret).isEqualTo("draft-key")
            assertThat(store.configurations()).isEmpty()
            assertThat(store.savedCredentials).isEmpty()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `selecting EPG persists the event source preference`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val eventSourceSettings = TestEventSourceSettings()
            val viewModel = SportsProviderSettingsViewModel(
                store,
                SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)),
                eventSourceSettings,
                TestScoreSourceSettings(),
                TestStandingsSourceSettings(),
                TestMetadataSourceSettings(),
            )
            advanceUntilIdle()

            viewModel.setEventSourcePreference(SportsEventSourcePreference.EPG)
            advanceUntilIdle()

            assertThat(eventSourceSettings.eventSourcePreference.value).isEqualTo(SportsEventSourcePreference.EPG)
            assertThat(viewModel.state.value.eventSourcePreference).isEqualTo(SportsEventSourcePreference.EPG)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `disabling selected provider resets capability sources to automatic`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = TestProviderStore()
            val factory = TestProviderFactory()
            val eventSettings = TestEventSourceSettings()
            val scoreSettings = TestScoreSourceSettings()
            val standingsSettings = TestStandingsSourceSettings()
            val metadataSettings = TestMetadataSourceSettings()
            val viewModel = SportsProviderSettingsViewModel(
                store,
                SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)),
                eventSettings,
                scoreSettings,
                standingsSettings,
                metadataSettings,
            )
            advanceUntilIdle()

            viewModel.saveProvider(factory.descriptor, null, "", "", "provider-key")
            advanceUntilIdle()
            val config = store.configurations().single()
            viewModel.setEventSourcePreference(SportsEventSourcePreference.provider(config.instanceId))
            viewModel.setScoreSourcePreference(SportsScoreSourcePreference.provider(config.instanceId))
            viewModel.setStandingsSourcePreference(SportsStandingsSourcePreference.provider(config.instanceId))
            viewModel.setMetadataSourcePreference(SportsMetadataSourcePreference.provider(config.instanceId))
            advanceUntilIdle()

            viewModel.setEnabled(config, enabled = false)
            advanceUntilIdle()

            assertThat(eventSettings.eventSourcePreference.value)
                .isEqualTo(SportsEventSourcePreference.AUTOMATIC)
            assertThat(scoreSettings.scoreSourcePreference.value)
                .isEqualTo(SportsScoreSourcePreference.AUTOMATIC)
            assertThat(standingsSettings.standingsSourcePreference.value)
                .isEqualTo(SportsStandingsSourcePreference.AUTOMATIC)
            assertThat(metadataSettings.metadataSourcePreference.value)
                .isEqualTo(SportsMetadataSourcePreference.AUTOMATIC)
            assertThat(viewModel.state.value.configurations.single().enabled).isFalse()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class TestProviderStore : SportsProviderStore {
        private val configs = linkedMapOf<String, SportsProviderConfig>()
        val savedCredentials = linkedMapOf<String, SportsProviderCredentials>()

        override suspend fun configurations(): List<SportsProviderConfig> = configs.values.toList()
        override suspend fun credentials(instanceId: String): SportsProviderCredentials? = savedCredentials[instanceId]

        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) {
            configs[config.instanceId] = config
            credentials?.let { savedCredentials[config.instanceId] = it }
        }

        override suspend fun remove(instanceId: String) {
            configs.remove(instanceId)
            savedCredentials.remove(instanceId)
        }
    }

    private class TestEventSourceSettings : SportsEventSourceSettings {
        private val preference = MutableStateFlow(SportsEventSourcePreference.AUTOMATIC)
        override val eventSourcePreference = preference
        override suspend fun setEventSourcePreference(preference: SportsEventSourcePreference) {
            this.preference.value = preference
        }
    }

    private class TestScoreSourceSettings : SportsScoreSourceSettings {
        private val preference = MutableStateFlow(SportsScoreSourcePreference.AUTOMATIC)
        override val scoreSourcePreference = preference
        override suspend fun setScoreSourcePreference(preference: SportsScoreSourcePreference) {
            this.preference.value = preference
        }
    }

    private class TestStandingsSourceSettings : SportsStandingsSourceSettings {
        private val preference = MutableStateFlow(SportsStandingsSourcePreference.AUTOMATIC)
        override val standingsSourcePreference = preference
        override suspend fun setStandingsSourcePreference(preference: SportsStandingsSourcePreference) {
            this.preference.value = preference
        }
    }

    private class TestMetadataSourceSettings : SportsMetadataSourceSettings {
        private val preference = MutableStateFlow(SportsMetadataSourcePreference.AUTOMATIC)
        override val metadataSourcePreference = preference
        override suspend fun setMetadataSourcePreference(preference: SportsMetadataSourcePreference) {
            this.preference.value = preference
        }
    }

    private class TestProviderFactory : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = "test-provider",
            capabilities = setOf(
                SportsCapability.EVENTS,
                SportsCapability.LIVE_SCORES,
                SportsCapability.STANDINGS,
                SportsCapability.TEAM_METADATA,
                SportsCapability.LEAGUE_METADATA,
            ),
            auth = SportsProviderAuth.ApiKeyHeader("X-API-KEY"),
            defaultBaseUrl = "https://sports.example/api",
        )
        var lastTestedCredentials: SportsProviderCredentials? = null

        override fun create(config: SportsProviderConfig): SportsProvider = object : SportsProvider,
            SportsProviderConnectionTester, EventProvider, LiveScoreProvider,
            StandingsProvider, TeamMetadataProvider, LeagueMetadataProvider, SportsProviderUsageProvider {
            override val descriptor = this@TestProviderFactory.descriptor
            override val instanceId = config.instanceId

            override fun usageSnapshot(credentials: SportsProviderCredentials?): SportsProviderUsageSnapshot? =
                credentials?.let {
                    SportsProviderUsageSnapshot(requestLimit = 100, requestsRemaining = 97, planTier = "BASIC")
                }

            override suspend fun testConnection(credentials: SportsProviderCredentials?): SportsProviderResult<Unit> {
                lastTestedCredentials = credentials
                return SportsProviderResult.Success(Unit)
            }

            override suspend fun events(
                request: ProviderEventRequest,
            ): SportsProviderResult<ProviderBatch<com.streamvault.domain.sports.provider.ProviderEvent>> =
                SportsProviderResult.Success(ProviderBatch(emptyList()))

            override suspend fun liveScores(
                request: ProviderScoreRequest,
            ): SportsProviderResult<ProviderBatch<com.streamvault.domain.sports.provider.ProviderScore>> =
                SportsProviderResult.Success(ProviderBatch(emptyList()))

            override suspend fun standings(request: ProviderStandingsRequest): SportsProviderResult<ProviderStandings> =
                SportsProviderResult.Success(ProviderStandings(com.streamvault.domain.sports.provider.ProviderLeague("League", request.leagueId), emptyList()))

            override suspend fun teamMetadata(request: ProviderTeamMetadataRequest): SportsProviderResult<ProviderTeamMetadata> =
                SportsProviderResult.Success(ProviderTeamMetadata(com.streamvault.domain.sports.provider.ProviderCompetitor("Team", request.teamId)))

            override suspend fun leagueMetadata(request: ProviderLeagueMetadataRequest): SportsProviderResult<ProviderLeagueMetadata> =
                SportsProviderResult.Success(ProviderLeagueMetadata(com.streamvault.domain.sports.provider.ProviderLeague("League", request.leagueId)))
        }
    }

    private class TestCustomProviderFactory : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = CUSTOM_SPORTS_PROVIDER_TYPE_ID,
            capabilities = setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES),
            auth = SportsProviderAuth.None,
            supportsCustomBaseUrl = true,
        )

        override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> {
            val definition = requireNotNull(config.customDefinitionJson)
            require("\"events\"" in definition)
            return buildSet {
                add(SportsCapability.EVENTS)
                if ("\"scores\"" in definition) add(SportsCapability.LIVE_SCORES)
            }
        }

        override fun displayNameFor(config: SportsProviderConfig): String? =
            Regex("\\\"name\\\":\\\"([^\\\"]+)\\\"").find(config.customDefinitionJson.orEmpty())
                ?.groupValues?.get(1)

        override fun create(config: SportsProviderConfig): SportsProvider = object : SportsProvider {
            override val descriptor = this@TestCustomProviderFactory.descriptor
            override val instanceId = config.instanceId
        }
    }
}
