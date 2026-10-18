package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.data.sports.provider.InMemorySportsProviderEventLinkCache
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SportsHubViewModelTest {
    private val providers: ProviderRepository = mock()
    private val combined: CombinedM3uRepository = mock()
    private val channels: ChannelRepository = mock()
    private val epg: EpgRepository = mock()
    private val sources: EpgSourceRepository = mock()
    private val sync: SyncMetadataRepository = mock()
    private val preferences: PreferencesRepository = mock()
    private var sportsProviderConfigs: List<SportsProviderConfig> = emptyList()
    private val sportsProviderRevision = MutableStateFlow(0L)
    private val sportsProviderStore = object : SportsProviderStore {
        override val configurationChanges = sportsProviderRevision
        override suspend fun configurations(): List<SportsProviderConfig> = sportsProviderConfigs
        override suspend fun credentials(instanceId: String): SportsProviderCredentials? = null
        override suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials?) = Unit
        override suspend fun remove(instanceId: String) = Unit
    }
    private val eventSourceSettings = object : SportsEventSourceSettings {
        override val eventSourcePreference = flowOf(SportsEventSourcePreference.AUTOMATIC)
        override suspend fun setEventSourcePreference(preference: SportsEventSourcePreference) = Unit
    }
    private val providerEventRouter = SportsProviderEventSourceRouter(SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)))
    private val providerScoreRouter = SportsProviderScoreSourceRouter(
        SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)),
        providerEventRouter,
    )
    private val providerStandingsRouter = SportsProviderStandingsRouter(SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)))
    private val providerMetadataRouter = SportsProviderMetadataRouter(SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)))
    private val scoreSourceSettings = object : SportsScoreSourceSettings {
        override val scoreSourcePreference = flowOf(SportsScoreSourcePreference.AUTOMATIC)
        override suspend fun setScoreSourcePreference(preference: SportsScoreSourcePreference) = Unit
    }
    private val standingsSourceSettings = object : SportsStandingsSourceSettings {
        override val standingsSourcePreference = flowOf(SportsStandingsSourcePreference.AUTOMATIC)
        override suspend fun setStandingsSourcePreference(preference: SportsStandingsSourcePreference) = Unit
    }
    private val metadataSourceSettings = object : SportsMetadataSourceSettings {
        override val metadataSourcePreference = flowOf(SportsMetadataSourcePreference.AUTOMATIC)
        override suspend fun setMetadataSourcePreference(preference: SportsMetadataSourcePreference) = Unit
    }
    private val providerEventResolver = SportsProviderEventResolver(InMemorySportsProviderEventLinkCache())
    private val store = ViewModelStore()
    private val scheduler = TestCoroutineScheduler()
    private lateinit var repositoryScope: CoroutineScope
    private var modelCount = 0
    private val subscriptions = AtomicInteger()
    private val programmeReads = AtomicInteger()
    private val lastQueryStart = AtomicLong()
    private val channel = Channel(
        id = 1L, providerId = 7L, streamId = 1L, name = "Sports One",
        categoryId = 10L, streamUrl = "https://example.test/live",
    )
    private val channelRows = MutableStateFlow(listOf(channel))
    private val activeSource = MutableStateFlow<ActiveLiveSource?>(ActiveLiveSource.ProviderSource(7L))
    private val hiddenCategories = MutableStateFlow(emptySet<Long>())

    @Before
    fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val provider = LegacyProvider(7L, "Test", ProviderType.XTREAM_CODES, "https://example.test")
        whenever(providers.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(providers.getProviders()).thenReturn(flowOf(listOf(provider)))
        whenever(combined.getActiveLiveSource()).thenReturn(activeSource)
        whenever(combined.getProfiles()).thenReturn(flowOf(emptyList()))
        whenever(channels.getChannels(7L)).thenReturn(flow {
            subscriptions.incrementAndGet()
            emitAll(channelRows)
        })
        whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(hiddenCategories)
        whenever(preferences.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        whenever(preferences.getSportsEventChannelPicks(any(), any())).thenReturn(emptyMap())
        whenever(preferences.getExcludedSports()).thenReturn(flowOf(emptySet()))
        whenever(preferences.getExcludedSportsLeagues()).thenReturn(flowOf(emptySet()))
        whenever(sync.observeMetadata(7L)).thenReturn(flowOf(null))
        whenever(sources.getAllSources()).thenReturn(flowOf(emptyList()))
        whenever(sources.getAssignmentsForProvider(7L)).thenReturn(flowOf(emptyList()))
        whenever(epg.getResolvedProgramsForChannels(any(), any(), any(), any())).thenAnswer {
            programmeReads.incrementAndGet()
            lastQueryStart.set(it.getArgument(2))
            val now = System.currentTimeMillis()
            mapOf("1" to listOf(Program(
                id = 1L, channelId = "1", title = "Arsenal vs Chelsea",
                category = "Football", startTime = now - 60_000L, endTime = now + 3_600_000L,
            )))
        }
    }

    @After
    fun tearDown() {
        store.clear()
        repositoryScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `new ViewModel immediately retains results while repository refresh is blocked`() = runBlocking<Unit> {
        val repository = createRepository()
        val first = createModel(repository)
        await { !first.uiState.value.isLoading }
        val events = first.uiState.value.events
        store.clear()

        val gate = CompletableDeferred<Map<String, List<Program>>>()
        val refreshStarted = CompletableDeferred<Unit>()
        whenever(epg.getResolvedProgramsForChannels(any(), any(), any(), any())).doSuspendableAnswer {
            refreshStarted.complete(Unit)
            gate.await()
        }
        val second = createModel(repository)
        withTimeout(10_000L) { refreshStarted.await() }

        assertThat(second.uiState.value.isLoading).isFalse()
        assertThat(second.uiState.value.events).isEqualTo(events)
        assertThat(subscriptions.get()).isEqualTo(1)
        gate.complete(emptyMap())
    }

    @Test
    fun `expired retained event is removed before another screen observes`() = runBlocking {
        val now = System.currentTimeMillis()
        whenever(epg.getResolvedProgramsForChannels(any(), any(), any(), any())).thenReturn(
            mapOf("1" to listOf(Program(
                id = 1L, channelId = "1", title = "Arsenal vs Chelsea", category = "Football",
                startTime = now - 60_000L, endTime = now + 1_000L,
            ))),
        )
        val repository = createRepository()
        val first = createModel(repository)
        await { !first.uiState.value.isLoading }
        assertThat(first.uiState.value.events).hasSize(1)
        store.clear()
        delay(1_100L)

        val second = SportsHubViewModel(repository)

        assertThat(second.uiState.value.events).isEmpty()
    }

    @Test
    fun `no EPG reloads while the repository has no screen observer`() = runBlocking {
        val repository = createRepository()
        val model = createModel(repository)
        await { !model.uiState.value.isLoading }
        store.clear()
        val reads = programmeReads.get()

        scheduler.advanceTimeBy(120_001L)

        assertThat(programmeReads.get()).isEqualTo(reads)
    }

    @Test
    fun `hidden category invalidates retained results before a new ViewModel observes`() = runBlocking {
        val repository = createRepository()
        val first = createModel(repository)
        await { !first.uiState.value.isLoading }
        store.clear()

        hiddenCategories.value = setOf(10L)
        await { repository.uiState.value.events.isEmpty() }
        val second = createModel(repository)
        await { !second.uiState.value.isLoading }

        assertThat(second.uiState.value.events).isEmpty()
    }

    @Test
    fun `Today queries from current time while retaining an ongoing programme`() = runBlocking {
        val model = createModel()
        await { !model.uiState.value.isLoading }
        val before = System.currentTimeMillis()

        model.selectTimeRange(SportsTimeRange.TODAY)
        await { !model.uiState.value.isLoading }

        assertThat(lastQueryStart.get()).isAtLeast(before)
        assertThat(lastQueryStart.get()).isAtMost(System.currentTimeMillis())
        assertThat(model.uiState.value.events).hasSize(1)
    }

    @Test
    fun `minute refresh reads current programmes without reloading channel catalog`() = runBlocking {
        val model = createModel()
        await { !model.uiState.value.isLoading }
        val firstReads = programmeReads.get()

        scheduler.advanceTimeBy(60_001L)
        await { programmeReads.get() > firstReads && !model.uiState.value.isLoading }

        assertThat(subscriptions.get()).isEqualTo(1)
        assertThat(model.uiState.value.events).hasSize(1)
    }

    @Test
    fun `range changes reuse channels and hidden categories invalidate cached events`() = runBlocking {
        val model = createModel()
        await { !model.uiState.value.isLoading }
        model.selectTimeRange(SportsTimeRange.TODAY)
        await { !model.uiState.value.isLoading }
        assertThat(model.uiState.value.events).hasSize(1)

        hiddenCategories.value = setOf(10L)
        await { model.uiState.value.events.isEmpty() && !model.uiState.value.isLoading }
        hiddenCategories.value = emptySet()
        await { model.uiState.value.events.size == 1 && !model.uiState.value.isLoading }

        assertThat(subscriptions.get()).isEqualTo(1)
    }

    @Test
    fun `manual search and remembered choices work with deferred fallback indexing`() = runBlocking {
        val model = createModel()
        await { !model.uiState.value.isLoading }
        val event = model.uiState.value.events.single()

        val matches = model.searchChannels(event, "Sports One")

        assertThat(matches.map { it.channel.id }).containsExactly(1L)
        assertThat(model.rememberChannelForEvent(event, matches.single())).isTrue()
    }

    @Test
    fun `changing provider replaces channel subscription and playback choices`() = runBlocking {
        whenever(channels.getChannels(8L)).thenReturn(flowOf(listOf(channel.copy(providerId = 8L))))
        whenever(preferences.getHiddenCategoryIds(8L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        val model = createModel()
        await { !model.uiState.value.isLoading }

        activeSource.value = ActiveLiveSource.ProviderSource(8L)

        await {
            model.uiState.value.events.firstOrNull()?.broadcasts?.firstOrNull()?.channel?.providerId == 8L
        }
    }

    @Test
    fun `retry recovers a failed initial channel subscription`() = runBlocking {
        val attempts = AtomicInteger()
        whenever(channels.getChannels(7L)).thenReturn(flow {
            if (attempts.incrementAndGet() == 1) error("Catalog temporarily unavailable")
            emitAll(channelRows)
        })
        val model = createModel()
        await { model.uiState.value.hasError }

        model.retry()

        await { !model.uiState.value.isLoading && !model.uiState.value.hasError }
        assertThat(model.uiState.value.events).hasSize(1)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `catalog changes replace cached playback choices`() = runBlocking {
        val model = createModel()
        await { !model.uiState.value.isLoading }
        channelRows.value = listOf(channel.copy(name = "Renamed Sports", streamUrl = "https://example.test/new"))

        await {
            model.uiState.value.events.firstOrNull()?.broadcasts?.firstOrNull()?.channel?.streamUrl ==
                "https://example.test/new"
        }
        assertThat(subscriptions.get()).isEqualTo(1)
    }

    @Test
    fun `guide is published while provider discovery is pending`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val factory = enrichmentFactory(events = {
            started.complete(Unit)
            gate.await()
            emptyList()
        })
        sportsProviderConfigs = listOf(SportsProviderConfig("test-api", factory.descriptor.typeId))
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val model = createModel(createRepository(registry))
        withTimeout(10_000L) { started.await() }

        assertThat(model.uiState.value.isLoading).isFalse()
        assertThat(model.uiState.value.events).hasSize(1)
        assertThat(model.uiState.value.events.single().broadcasts.single().channel.id).isEqualTo(1L)
        gate.complete(Unit)
    }

    @Test
    fun `API events appear while a long guide scan remains pending across clock ticks`() = runBlocking<Unit> {
        val guideGate = CompletableDeferred<Map<String, List<Program>>>()
        val guideReads = AtomicInteger()
        whenever(epg.getResolvedProgramsForChannels(any(), any(), any(), any())).doSuspendableAnswer {
            guideReads.incrementAndGet()
            guideGate.await()
        }
        val factory = enrichmentFactory(events = { listOf(ProviderEvent(
            providerEventId = "api-match", title = "North vs South", sport = "soccer",
            competitors = listOf(ProviderCompetitor("North"), ProviderCompetitor("South")),
            eventStart = java.time.Instant.now().minusSeconds(60),
        )) })
        sportsProviderConfigs = listOf(SportsProviderConfig("test-api", factory.descriptor.typeId))
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val model = createModel(createRepository(registry))
        await { !model.uiState.value.isLoading && model.uiState.value.events.isNotEmpty() && guideReads.get() > 0 }
        assertThat(model.uiState.value.events.single().title).isEqualTo("North vs South")

        scheduler.advanceTimeBy(120_000L)
        scheduler.runCurrent()
        assertThat(guideReads.get()).isEqualTo(1)
        assertThat(model.uiState.value.isLoading).isFalse()
        guideGate.complete(emptyMap())
    }

    @Test
    fun `saving provider configuration reloads the retained sports tab`() = runBlocking {
        val calls = AtomicInteger()
        val factory = enrichmentFactory(events = { calls.incrementAndGet(); emptyList() })
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val model = createModel(createRepository(registry))
        await { !model.uiState.value.isLoading }
        assertThat(calls.get()).isEqualTo(0)

        sportsProviderConfigs = listOf(SportsProviderConfig("test-api", factory.descriptor.typeId))
        sportsProviderRevision.value++

        await { calls.get() > 0 && !model.uiState.value.isLoading }
        assertThat(model.uiState.value.events.single().broadcasts.single().channel.id).isEqualTo(1L)
    }

    @Test
    fun `events remain usable while scores and metadata are pending`() = runBlocking<Unit> {
        val scoresStarted = CompletableDeferred<Unit>()
        val scoresGate = CompletableDeferred<Unit>()
        val metadataStarted = CompletableDeferred<Unit>()
        val metadataGate = CompletableDeferred<Unit>()
        val factory = enrichmentFactory(
            events = { listOf(ProviderEvent(
                providerEventId = "match", sport = "soccer", title = "Arsenal vs Chelsea",
                eventStart = java.time.Instant.now().minusSeconds(60),
                competitors = listOf(ProviderCompetitor("Arsenal", "arsenal"), ProviderCompetitor("Chelsea", "chelsea")),
            )) },
            scores = { scoresStarted.complete(Unit); scoresGate.await() },
            metadata = { if (!metadataStarted.isCompleted) metadataStarted.complete(Unit); metadataGate.await() },
        )
        sportsProviderConfigs = listOf(SportsProviderConfig("test-api", factory.descriptor.typeId))
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val model = createModel(createRepository(registry))
        withTimeout(10_000L) { scoresStarted.await() }
        assertThat(model.uiState.value.isLoading).isFalse()
        assertThat(model.uiState.value.events).hasSize(1)
        scoresGate.complete(Unit)
        withTimeout(10_000L) { metadataStarted.await() }
        assertThat(model.uiState.value.isLoading).isFalse()
        assertThat(model.uiState.value.events.single().broadcasts.single().channel.id).isEqualTo(1L)
        metadataGate.complete(Unit)
    }

    @Test
    fun `clock refresh never publishes unscored replacements for scored fixtures`() = runBlocking<Unit> {
        val eventStart = java.time.Instant.now().minusSeconds(60)
        val score = ProviderScore("match", listOf(
            ProviderCompetitorScore(ProviderCompetitor("Arsenal", "arsenal"), "2"),
            ProviderCompetitorScore(ProviderCompetitor("Chelsea", "chelsea"), "1"),
        ))
        val calls = AtomicInteger()
        val factory = enrichmentFactory(
            events = {
                calls.incrementAndGet()
                listOf(ProviderEvent(
                    providerEventId = "match", sport = "soccer", title = "Arsenal vs Chelsea",
                    eventStart = eventStart,
                    competitors = listOf(ProviderCompetitor("Arsenal", "arsenal"), ProviderCompetitor("Chelsea", "chelsea")),
                ))
            },
            scoreItems = { listOf(score) },
        )
        sportsProviderConfigs = listOf(SportsProviderConfig("test-api", factory.descriptor.typeId))
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val model = createModel(createRepository(registry))
        await { model.uiState.value.events.singleOrNull()?.provenance?.score != null }
        // Let the first load finish, then collect every state during a scheduled refresh.
        delay(100L)
        val observed = mutableListOf<SportsHubUiState>()
        val collector = repositoryScope.launch { model.uiState.collect { observed.add(it) } }
        val initialCalls = calls.get()
        scheduler.advanceTimeBy(60_000L)
        scheduler.runCurrent()
        await { calls.get() > initialCalls }
        delay(100L)
        collector.cancel()
        assertThat(observed).isNotEmpty()
        assertThat(observed.all { state -> state.events.isNotEmpty() && state.events.all { it.provenance.score != null } }).isTrue()
    }

    private fun enrichmentFactory(
        events: suspend () -> List<ProviderEvent>,
        scores: suspend () -> Unit = {},
        metadata: suspend () -> Unit = {},
        scoreItems: () -> List<ProviderScore> = { emptyList() },
    ) = object : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = "test-api", auth = SportsProviderAuth.None,
            capabilities = setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES, SportsCapability.TEAM_METADATA),
        )
        override fun create(config: SportsProviderConfig): SportsProvider {
            val factoryDescriptor = descriptor
            return object : EventProvider, LiveScoreProvider, TeamMetadataProvider {
                override val descriptor = factoryDescriptor
                override val instanceId = config.instanceId
                override suspend fun events(request: ProviderEventRequest) = SportsProviderResult.Success(ProviderBatch(events()))
                override suspend fun liveScores(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderScore>> {
                    scores()
                    return SportsProviderResult.Success(ProviderBatch(scoreItems()))
                }
                override suspend fun teamMetadata(request: ProviderTeamMetadataRequest): SportsProviderResult<ProviderTeamMetadata> {
                    metadata()
                    return SportsProviderResult.Failure(SportsProviderError.NETWORK)
                }
            }
        }
    }

    private fun createRepository(registry: SportsProviderRegistry? = null) = SportsHubRepository(
        providers,
        combined,
        channels,
        epg,
        sources,
        sync,
        preferences,
        sportsProviderStore,
        registry?.let(::SportsProviderEventSourceRouter) ?: providerEventRouter,
        registry?.let { SportsProviderScoreSourceRouter(it, SportsProviderEventSourceRouter(it)) } ?: providerScoreRouter,
        providerStandingsRouter,
        registry?.let(::SportsProviderMetadataRouter) ?: providerMetadataRouter,
        SportsProviderExtraDetailsRouter(SportsProviderRegistry(emptyList(), SportsProviderReleasePolicy(development = true)), sportsProviderStore),
        eventSourceSettings,
        scoreSourceSettings,
        standingsSourceSettings,
        metadataSourceSettings,
        providerEventResolver,
        repositoryScope,
    )

    private fun createModel(repository: SportsHubRepository = createRepository()) = SportsHubViewModel(repository)
        .also { model ->
            store.put("sports-${modelCount++}", model)
            model.viewModelScope.launch { model.uiState.collect {} }
        }

    private suspend fun await(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000L) {
            while (!condition()) delay(10L)
        }
    }
}
