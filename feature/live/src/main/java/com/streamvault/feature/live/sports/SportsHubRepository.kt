package com.streamvault.feature.live.sports

import android.os.SystemClock
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsEventSourceSettings
import com.streamvault.domain.sports.provider.SportsEventSourceSelection
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import com.streamvault.domain.sports.provider.SportsScoreSourceSettings
import com.streamvault.domain.sports.provider.SportsStandingsSourceSettings
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.SportsMetadataSourceSettings
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsSourceRef
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SportsHubUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val events: List<SportsEvent> = emptyList(),
    val selectedTimeRange: SportsTimeRange = SportsTimeRange.ON_NOW,
    val selectedSportFilter: SportsSportFilter = SportsSportFilter.ALL,
    val selectedLeagueFilter: String? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val searchQuery: String = "",
    val excludedSports: Set<String> = emptySet(),
    val excludedLeagues: Set<String> = emptySet(),
    val knownLeagues: Map<String, String> = emptyMap(),
)

internal fun shouldRefreshSportsOnClock(
    range: SportsTimeRange,
    hasLiveEvents: Boolean,
    dateChanged: Boolean,
): Boolean = range == SportsTimeRange.ON_NOW || hasLiveEvents || dateChanged

/** Keep background work on the same scheduler as Main in repository tests. */
internal class SportsHubDispatchers(
    val default: CoroutineDispatcher,
    val io: CoroutineDispatcher,
) {
    @Inject constructor() : this(Dispatchers.Default, Dispatchers.IO)
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
internal class SportsHubRepository @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
    private val sportsProviderStore: SportsProviderStore,
    private val sportsProviderEventSourceRouter: SportsProviderEventSourceRouter,
    private val sportsProviderScoreSourceRouter: SportsProviderScoreSourceRouter,
    private val sportsProviderStandingsRouter: SportsProviderStandingsRouter,
    private val sportsProviderMetadataRouter: SportsProviderMetadataRouter,
    private val sportsProviderExtraDetailsRouter: SportsProviderExtraDetailsRouter,
    private val sportsEventSourceSettings: SportsEventSourceSettings,
    private val sportsScoreSourceSettings: SportsScoreSourceSettings,
    private val sportsStandingsSourceSettings: SportsStandingsSourceSettings,
    private val sportsMetadataSourceSettings: SportsMetadataSourceSettings,
    private val sportsProviderEventResolver: SportsProviderEventResolver,
    applicationScope: CoroutineScope,
    private val dispatchers: SportsHubDispatchers,
) {
    private val scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(SportsHubUiState())
    val uiState = _uiState.asStateFlow()
    private val activeObservers = _uiState.subscriptionCount.map { it > 0 }.distinctUntilChanged()
    private val classificationCache = SportsClassificationCache()

    private val refreshRequest = MutableStateFlow(0)
    private val retryRequest = MutableStateFlow(0)
    private val selectedTimeRange = MutableStateFlow(SportsTimeRange.ON_NOW)
    private var lastRequestedLocalDate: LocalDate? = null
    private val eventIndexCache = linkedMapOf<SportsIndexKey, CachedSportsEvents>()
    @Volatile private var fallbackChannelIndexCache: CachedSportsChannelIndex? = null
    @Volatile private var availableChannelEntries: List<SportsChannelIndexEntry> = emptyList()
    private val initialLoadStartedAt = SystemClock.elapsedRealtime()
    private var initialContentLogged = false
    private var lastPublishedAtMillis = 0L
    private var loadInProgress = false
    private var progressiveLoad = true

    init {
        scope.launch {
            combine(preferencesRepository.getExcludedSports(), preferencesRepository.getExcludedSportsLeagues()) { sports, leagues ->
                sports to leagues
            }.collect { (sports, leagues) ->
                _uiState.update { it.copy(excludedSports = sports, excludedLeagues = leagues,
                    selectedLeagueFilter = it.selectedLeagueFilter?.takeUnless { league -> league in leagues }) }
            }
        }
        val activeSource = combine(
            combinedM3uRepository.getActiveLiveSource(),
            providerRepository.getActiveProvider(),
        ) { selectedSource, activeProvider ->
            selectedSource ?: activeProvider?.id?.let { ActiveLiveSource.ProviderSource(it) }
        }.distinctUntilChanged()

        val clock = flow {
            while (true) {
                delay(60_000L)
                emit(System.currentTimeMillis())
            }
        }

        val guideRevision = combine(
            observeProviderRevisions(),
            epgSourceRepository.getAllSources().map { sources ->
                sources.map { source ->
                    ExternalGuideRevision(source.id, source.lastSuccessAt, source.updatedAt)
                }.sortedBy(ExternalGuideRevision::sourceId)
            },
            combinedM3uRepository.getProfiles().map { profiles ->
                profiles.map { profile ->
                    CombinedProfileRevision(
                        profileId = profile.id,
                        enabled = profile.enabled,
                        members = profile.members.map { member ->
                            CombinedProfileMemberRevision(
                                providerId = member.providerId,
                                enabled = member.enabled,
                                priority = member.priority,
                            )
                        }.sortedWith(
                            compareBy(CombinedProfileMemberRevision::priority)
                                .thenBy(CombinedProfileMemberRevision::providerId),
                        ),
                    )
                }.sortedBy(CombinedProfileRevision::profileId)
            },
        ) { providers, sources, profiles -> SportsGuideRevision(providers, sources, profiles) }
            .distinctUntilChanged()
            .shareIn(scope, SharingStarted.Eagerly, replay = 1)

        // Keep the channel flow subscribed while this ViewModel is alive. A clock tick or
        // time-range change must not re-query and re-present the entire provider catalog.
        val channelSnapshots = combine(
            activeSource,
            guideRevision.map { it.combinedProfiles }.distinctUntilChanged(),
        ) { source, profiles -> source to profiles }.flatMapLatest { (source, _) ->
            val sources = resolveProviderSources(source)
            if (sources.isEmpty()) {
                flowOf(emptyList<ProviderChannels>())
            } else {
                combine(sources.map { (providerId, combinedProfileId) ->
                    val startedAt = SystemClock.elapsedRealtime()
                    var initialSnapshot = true
                    combine(
                        channelRepository.getChannels(providerId),
                        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
                    ) { channels, hiddenCategories ->
                        ProviderChannels(
                            providerId = providerId,
                            combinedProfileId = combinedProfileId,
                            channels = channels.filter { channel ->
                                channel.categoryId?.let { it !in hiddenCategories } != false &&
                                    !channel.isAdult && !channel.isUserProtected
                            },
                        ).also { snapshot ->
                            if (initialSnapshot) {
                                SportsDiagnostics.performance(
                                    stage = "visible-channels",
                                    elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                                    itemCount = snapshot.channels.size,
                                )
                                initialSnapshot = false
                            }
                        }
                    }.distinctUntilChanged().flowOn(dispatchers.default)
                }) { it.toList() }
            }
        }.distinctUntilChanged().retryWhen { _, _ ->
            val failedRequest = retryRequest.value
            _uiState.update { it.copy(isLoading = false, hasError = true) }
            retryRequest.first { it != failedRequest }
            _uiState.update { it.copy(isLoading = true, hasError = false) }
            true
        }

        var previousInputs: SportsLoadInputs? = null
        var previouslyActive = false
        val sourcePreferences = combine(
            sportsEventSourceSettings.eventSourcePreference,
            sportsScoreSourceSettings.scoreSourcePreference,
            sportsProviderStore.configurationChanges,
        ) { eventSourcePreference, scoreSourcePreference, revision ->
            Triple(eventSourcePreference, scoreSourcePreference, revision)
        }
        val loadSettings = combine(
            selectedTimeRange,
            refreshRequest,
            retryRequest,
            sourcePreferences,
            sportsMetadataSourceSettings.metadataSourcePreference,
        ) { range, _, retry, sourceSelection, metadataSourcePreference ->
            SportsLoadSettings(
                range,
                retry,
                sourceSelection.first,
                sourceSelection.second,
                metadataSourcePreference,
                sourceSelection.third,
            )
        }
        val loadRequests = combine(channelSnapshots, guideRevision, loadSettings) { channels, revision, settings ->
            SportsLoadInputs(
                channels,
                revision,
                settings.retry,
                settings.eventSourcePreference,
                settings.scoreSourcePreference,
                settings.metadataSourcePreference,
                settings.providerRevision,
            ) to settings.range
        }
        scope.launch {
            combine(loadRequests, activeObservers) { request, active -> request to active }.collectLatest { (request, active) ->
                val (inputs, range) = request
                val resumed = active && !previouslyActive
                previouslyActive = active
                if (previousInputs != inputs) {
                    eventIndexCache.clear()
                    fallbackChannelIndexCache = null
                    availableChannelEntries = emptyList()
                    if (previousInputs?.retry != inputs.retry) classificationCache.clear()
                    _uiState.update { it.copy(events = emptyList(), isLoading = true, hasError = false) }
                    previousInputs = inputs
                }
                if (active) {
                    if (resumed) {
                        refreshPresentationNow()
                        if (!_uiState.value.isLoading && !_uiState.value.hasError) {
                            SportsDiagnostics.performance("retained-presentation", 0L, _uiState.value.events.size)
                        }
                    }
                    loadEvents(inputs, range, System.currentTimeMillis())
                }
            }
        }
        scope.launch {
            clock.collect { now ->
                prunePresentation(now)
                val today = localDate(now)
                val range = selectedTimeRange.value
                val hasLiveEvents = _uiState.value.events.any { event ->
                    event.provenance.status?.value?.state ==
                        com.streamvault.domain.sports.provider.ProviderGameState.LIVE ||
                        event.broadcastStatusAt(now) == SportsBroadcastStatus.ON_AIR
                }
                if (_uiState.subscriptionCount.value > 0 && !loadInProgress &&
                    shouldRefreshSportsOnClock(range, hasLiveEvents, lastRequestedLocalDate != today)
                ) {
                    refreshRequest.update { it + 1 }
                }
            }
        }
    }

    /** Called before a new screen observes the retained state; never replay expired fixtures. */
    fun refreshPresentationNow() {
        val now = System.currentTimeMillis()
        if (lastPublishedAtMillis > 0L &&
            (now - lastPublishedAtMillis > EVENT_INDEX_CACHE_TTL_MS || lastRequestedLocalDate != localDate(now))
        ) {
            _uiState.update { it.copy(events = emptyList(), isLoading = true, nowMillis = now) }
        } else {
            prunePresentation(now)
        }
    }

    private fun prunePresentation(now: Long) {
        val window = windowFor(selectedTimeRange.value, now)
        _uiState.update { state ->
            state.copy(
                events = state.events.filter { event ->
                    programMatchesRange(event.startTime, event.endTime, state.selectedTimeRange, now, window)
                },
                nowMillis = now,
            )
        }
    }

    fun retry() {
        retryRequest.update { it + 1 }
    }

    fun selectTimeRange(range: SportsTimeRange) {
        if (selectedTimeRange.value == range) return
        selectedTimeRange.value = range
        _uiState.update { it.copy(selectedTimeRange = range, isLoading = true, hasError = false) }
    }

    fun selectSportFilter(filter: SportsSportFilter) {
        _uiState.update { state ->
            val selectedLeague = state.selectedLeagueFilter?.takeIf { league ->
                state.events.any { event -> event.leagueFilterKey == league && event.matches(filter) }
            }
            state.copy(selectedSportFilter = filter, selectedLeagueFilter = selectedLeague)
        }
    }

    fun selectLeagueFilter(league: String?) {
        _uiState.update { it.copy(selectedLeagueFilter = league) }
    }

    fun setSearchQuery(query: String) { _uiState.update { it.copy(searchQuery = query) } }

    fun setSportIncluded(sport: SportType, included: Boolean) {
        scope.launch { preferencesRepository.setSportsSportIncluded(sport.discoveryKey, included) }
    }

    fun setLeagueIncluded(league: String, included: Boolean) {
        scope.launch { preferencesRepository.setSportsLeagueIncluded(league, included) }
    }

    fun resetInclusions() { scope.launch { preferencesRepository.resetSportsInclusions() } }

    private suspend fun loadEvents(inputs: SportsLoadInputs, range: SportsTimeRange, now: Long) {
        val loadStartedAt = SystemClock.elapsedRealtime()
        loadInProgress = true
        progressiveLoad = _uiState.value.events.isEmpty() || _uiState.value.isLoading
        val providerChannels = inputs.providers
        val window = windowFor(range, now)
        lastRequestedLocalDate = localDate(now)
        _uiState.update { it.copy(isLoading = it.isLoading || it.hasError, hasError = false) }
        try {
            val configuredProviders = runCatching { sportsProviderStore.configurations() }.getOrDefault(emptyList())
            val eventProviders = sportsProviderEventSourceRouter.eligibleProviders(
                configuredProviders,
                inputs.eventSourcePreference,
            )
            val cacheKey = if (range == SportsTimeRange.ON_NOW) null else {
                SportsIndexKey(
                    sources = providerChannels.map { provider ->
                        SportsSourceKey(provider.providerId, provider.combinedProfileId)
                    },
                    range = range,
                    localDate = localDate(now),
                    eventSourcePreference = inputs.eventSourcePreference,
                    scoreSourcePreference = inputs.scoreSourcePreference,
                    metadataSourcePreference = inputs.metadataSourcePreference,
                    eventProviderScopes = eventProviders,
                )
            }
            val cachedEvents = cacheKey?.let(::cachedEventsFor)
            val (baseEvents, reusableProviderEvents) = cachedEvents?.let { cached ->
                cached.events to cached.providerEvents
            } ?: withContext(dispatchers.default) {
                val (requestFromMillis, requestUntilMillis) = providerEventRequestWindow(range, now, window)
                availableChannelEntries = providerChannels.flatMap { provider ->
                    provider.channels.map { SportsChannelIndexEntry(it, provider.combinedProfileId) }
                }
                val providerTask = async {
                    withTimeoutOrNull(PROVIDER_LOAD_TIMEOUT_MS) {
                        sportsProviderEventSourceRouter.load(
                            eventProviders, Instant.ofEpochMilli(requestFromMillis), Instant.ofEpochMilli(requestUntilMillis),
                        )
                    } ?: LoadedSportsProviderEvents()
                }
                // A week of guide data can be large. Show API events while that local scan
                // continues; the final resolver will join them to actual guide broadcasts.
                val provisionalPublisher = launch {
                    val early = providerTask.await()
                    if (early.events.isNotEmpty()) {
                        val resolved = sportsProviderEventResolver.resolve(
                            epgEvents = emptyList(), providerEvents = sourcedEvents(early),
                            selection = SportsEventSourceSelection(eventInstanceId = early.instanceId),
                            now = Instant.ofEpochMilli(now),
                        )
                        withContext(Dispatchers.Main.immediate) {
                            publishEvents(resolved.mapNotNull { it.toSportsHubEvent() }, range, now, window)
                        }
                    }
                }
                val providerData = buildList {
                    providerChannels.forEach { provider ->
                        add(loadProviderCandidates(provider, range, now, window))
                    }
                }
                val candidates = providerData.flatMap { it.candidates }
                val assemblyStartedAt = SystemClock.elapsedRealtime()
                val assembled = SportsEventAssembler.assemble(candidates)
                SportsDiagnostics.performance(
                    stage = "event-assembly",
                    elapsedMillis = SystemClock.elapsedRealtime() - assemblyStartedAt,
                    itemCount = assembled.size,
                    groupCount = candidates.size,
                )
                val entries = providerData.flatMap { it.channels }
                availableChannelEntries = entries
                // The guide is usable immediately, even when a remote API is slow.
                if (!providerTask.isCompleted && (assembled.isNotEmpty() || eventProviders.isEmpty())) {
                    withContext(Dispatchers.Main.immediate) { publishEvents(assembled, range, now, window) }
                }
                val providerEventLoad = providerTask.await()
                provisionalPublisher.join()
                // Most guide-derived events already have playable broadcasts. Build the
                // full text index only when fallback matching or manual search needs it.
                val channelIndex by lazy { getFallbackChannelIndex(entries) }
                val sourcedProviderEvents = sourcedEvents(providerEventLoad)
                val selectedProviderId = providerEventLoad.instanceId
                val providerResolutionStartedAt = SystemClock.elapsedRealtime()
                val catalogResolved = sportsProviderEventResolver.resolve(
                        epgEvents = assembled,
                        providerEvents = sourcedProviderEvents,
                        selection = SportsEventSourceSelection(
                            eventInstanceId = selectedProviderId,
                            metadataInstanceId = selectedProviderId,
                        ),
                        now = Instant.ofEpochMilli(now),
                )
                withContext(Dispatchers.Main.immediate) {
                    publishEvents(catalogResolved.mapNotNull { it.toSportsHubEvent() }, range, now, window)
                }
                val providerResolved = withTimeoutOrNull(ENRICHMENT_TIMEOUT_MS) {
                    sportsProviderEventSourceRouter.enrichBroadcasters(
                        catalogResolved, configuredProviders, Instant.ofEpochMilli(now),
                    )
                } ?: catalogResolved
                SportsDiagnostics.performance(
                    stage = "provider-event-resolution",
                    elapsedMillis = SystemClock.elapsedRealtime() - providerResolutionStartedAt,
                    itemCount = assembled.size,
                    groupCount = sourcedProviderEvents.size,
                )
                val resolved = providerResolved.mapNotNull { resolved ->
                    currentCoroutineContext().ensureActive()
                    resolved.toSportsHubEvent()?.takeIf { event ->
                        programMatchesRange(event.startTime, event.endTime, range, now, window)
                    }?.let { event ->
                        val resolvedEvent = if (event.broadcasts.none { it.channel.streamUrl.isNotBlank() }) {
                            SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event, channelIndex)
                        } else {
                            event
                        }
                        resolvedEvent.broadcasts.mapNotNull(SportsBroadcast::fallbackMatch)
                            .forEach { match -> SportsDiagnostics.fallbackMatch(event.id, match) }
                        resolvedEvent
                    }
                }
                if (cacheKey != null) cacheEvents(cacheKey, resolved, providerEventLoad)
                resolved to providerEventLoad
            }
            publishEvents(baseEvents, range, now, window)
            val selectedEventProviderInstanceId = reusableProviderEvents.instanceId
            val scoreProviders = sportsProviderScoreSourceRouter.eligibleProviders(
                configuredProviders,
                inputs.scoreSourcePreference,
                selectedEventProviderInstanceId,
            )
            val (requestFromMillis, requestUntilMillis) = providerEventRequestWindow(range, now, window)
            val scoreLoad = withContext(dispatchers.default) {
                withTimeoutOrNull(ENRICHMENT_TIMEOUT_MS) { sportsProviderScoreSourceRouter.load(
                    providers = scoreProviders,
                    reusableEventLoad = reusableProviderEvents,
                    from = Instant.ofEpochMilli(requestFromMillis),
                    until = Instant.ofEpochMilli(requestUntilMillis),
                    now = Instant.ofEpochMilli(now),
                ) } ?: LoadedSportsProviderScores()
            }
            val sourcedScoreEvents = if (scoreLoad.instanceId == null || scoreLoad.typeId == null) {
                emptyList()
            } else {
                scoreLoad.events.map { event ->
                    SourcedValue(
                        value = event,
                        source = SportsSourceRef.Provider(
                            instanceId = scoreLoad.instanceId,
                            typeId = scoreLoad.typeId,
                            externalEventId = event.providerEventId,
                            sport = event.sport.takeIf { scoreLoad.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                            leagueId = event.league?.id.takeIf { scoreLoad.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                        ),
                    )
                }
            }
            val resolvedEvents = withContext(dispatchers.default) {
                val scoreResolutionStartedAt = SystemClock.elapsedRealtime()
                val resolved = sportsProviderEventResolver.resolve(
                    epgEvents = baseEvents,
                    providerEvents = sourcedScoreEvents,
                    scores = scoreLoad.scores,
                    selection = SportsEventSourceSelection(scoreInstanceId = scoreLoad.instanceId),
                    now = Instant.ofEpochMilli(now),
                )
                SportsDiagnostics.performance(
                    stage = "score-event-resolution",
                    elapsedMillis = SystemClock.elapsedRealtime() - scoreResolutionStartedAt,
                    itemCount = baseEvents.size,
                    groupCount = sourcedScoreEvents.size,
                )
                resolved.mapNotNull { it.toSportsHubEvent() }
            }
            val eventCatalogs = listOf(
                reusableProviderEvents,
                LoadedSportsProviderEvents(
                    instanceId = scoreLoad.instanceId,
                    typeId = scoreLoad.typeId,
                    events = scoreLoad.events,
                ),
            )
            publishEvents(resolvedEvents, range, now, window)
            val metadata = withContext(dispatchers.default) {
                withTimeoutOrNull(ENRICHMENT_TIMEOUT_MS) { sportsProviderMetadataRouter.load(
                    configs = configuredProviders,
                    preference = inputs.metadataSourcePreference,
                    eventCatalogs = eventCatalogs,
                    eventProviderInstanceId = reusableProviderEvents.instanceId,
                    now = Instant.ofEpochMilli(now),
                ) } ?: LoadedSportsProviderMetadata()
            }
            val events = sportsProviderMetadataRouter.enrichEvents(resolvedEvents, metadata).map { event ->
                event.copy(providerAttributions = sportsProviderEventSourceRouter.attributions(event.provenance))
            }
            SportsDiagnostics.performance(
                stage = if (cachedEvents == null) "window-cold" else "window-cache-hit",
                elapsedMillis = SystemClock.elapsedRealtime() - loadStartedAt,
                itemCount = events.size,
            )
            publishEvents(events, range, now, window, complete = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _uiState.update { it.copy(isLoading = false, hasError = true) }
        } finally {
            loadInProgress = false
        }
    }

    private fun sourcedEvents(loaded: LoadedSportsProviderEvents): List<SourcedValue<ProviderEvent>> {
        val instanceId = loaded.instanceId ?: return emptyList()
        val typeId = loaded.typeId ?: return emptyList()
        return loaded.events.map { event ->
            SourcedValue(event, SportsSourceRef.Provider(
                instanceId, typeId, event.providerEventId,
                sport = event.sport.takeIf { typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                leagueId = event.league?.id.takeIf { typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
            ))
        }
    }

    private suspend fun publishEvents(
        events: List<SportsEvent>,
        range: SportsTimeRange,
        now: Long,
        window: SportsTimeWindow,
        complete: Boolean = false,
    ) {
        if (!complete && !progressiveLoad) return
        val previous = _uiState.value.events.associateBy(SportsEvent::id)
        val currentEvents = events.filter { event ->
            programMatchesRange(event.startTime, event.endTime, range, now, window)
        }
        val rememberedPicks = preferencesRepository.getSportsEventChannelPicks(
            eventIds = currentEvents.mapTo(mutableSetOf(), SportsEvent::id),
            nowMillis = now,
        )
        val availableChannels = availableChannelEntries
        val presentedEvents = currentEvents.map { event ->
            val retained = retainSportsPresentation(event, previous[event.id], Instant.ofEpochMilli(now))
            SportsEventChannelPreferencePolicy.apply(
                event = retained,
                pick = rememberedPicks[event.id],
                availableChannels = availableChannels,
                nowMillis = now,
            ).copy(providerAttributions = sportsProviderEventSourceRouter.attributions(retained.provenance))
        }
        val currentState = _uiState.value
        val selectedLeague = currentState.selectedLeagueFilter?.takeIf { league ->
            presentedEvents.any { event -> event.leagueFilterKey == league && event.matches(currentState.selectedSportFilter) }
        }
        _uiState.value = currentState.copy(
            isLoading = false,
            events = presentedEvents,
            selectedTimeRange = range,
            selectedSportFilter = currentState.selectedSportFilter,
            selectedLeagueFilter = selectedLeague,
            nowMillis = now,
            knownLeagues = currentState.knownLeagues + presentedEvents.mapNotNull { event ->
                event.leagueFilterKey?.let { it to (event.league ?: event.leagueInfo?.displayName ?: it) }
            }.toMap(),
        )
        lastPublishedAtMillis = System.currentTimeMillis()
        if (!initialContentLogged) {
            SportsDiagnostics.performance(
                stage = "initial-content",
                elapsedMillis = SystemClock.elapsedRealtime() - initialLoadStartedAt,
                itemCount = presentedEvents.size,
            )
            initialContentLogged = true
        }
    }

    private fun providerEventRequestWindow(
        range: SportsTimeRange,
        now: Long,
        window: SportsTimeWindow,
    ): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val startOfToday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val from = when (range) {
            SportsTimeRange.ON_NOW, SportsTimeRange.TODAY -> minOf(startOfToday, now - PROVIDER_EVENT_LOOKBACK_MS)
            SportsTimeRange.UPCOMING -> window.startTime
        }
        val until = when (range) {
            SportsTimeRange.ON_NOW -> now + 1L
            SportsTimeRange.TODAY, SportsTimeRange.UPCOMING -> window.endTime
        }
        return from to until.coerceAtLeast(from + 1L)
    }

    suspend fun loadStandings(event: SportsEvent): LoadedSportsStandings? {
        val preference = sportsStandingsSourceSettings.standingsSourcePreference.first()
        val configs = runCatching { sportsProviderStore.configurations() }.getOrDefault(emptyList())
        val sourcedLeague = event.provenance.league
        val providerSource = sourcedLeague?.source as? SportsSourceRef.Provider
        val league = sourcedLeague?.value ?: event.league?.let(::ProviderLeague)
        val providers = sportsProviderStandingsRouter.eligibleProviders(configs, preference, providerSource)
        return withContext(dispatchers.default) {
            sportsProviderStandingsRouter.load(
                providers = providers,
                eventLeague = league,
                eventLeagueSource = providerSource,
            )
        }
    }

    suspend fun loadExtraDetails(event: SportsEvent): LoadedSportsEventExtraDetails? = withContext(dispatchers.io) {
        sportsProviderExtraDetailsRouter.load(event)
    }

    private fun SportsResolvedEvent.toSportsHubEvent(): SportsEvent? {
        val local = epgEvent
        if (local != null) {
            val resolvedLeague = provenance.league?.value?.name?.takeIf(String::isNotBlank) ?: local.league
            return local.copy(
                title = provenance.title?.value?.takeIf(String::isNotBlank) ?: local.title,
                league = resolvedLeague,
                leagueInfo = local.leagueInfo ?: resolvedLeague?.let { league ->
                    SportsLeagueRegistry.detectNormalized(SportsTextNormalizer.normalize(league))
                },
                provenance = provenance,
                competitors = local.competitors.map { competitor ->
                    val providerCompetitor = providerEvent?.value?.competitors?.firstOrNull {
                        SportsAliasRegistry.normalizeTeamName(it.name) == competitor.canonicalName
                    }
                    competitor.copy(logoUrl = providerCompetitor?.logoUrl ?: competitor.logoUrl)
                },
            )
        }

        val providerEvent = providerEvent?.value ?: return null
        val eventStart = providerEvent.eventStart ?: return null
        val title = providerEvent.title?.takeIf(String::isNotBlank)
            ?: providerEvent.competitors.takeIf { it.isNotEmpty() }
                ?.joinToString(" vs ") { it.name }
            ?: return null
        val startTime = eventStart.toEpochMilli()
        val endTime = providerEvent.eventEnd?.toEpochMilli() ?: when (providerEvent.status?.state) {
            com.streamvault.domain.sports.provider.ProviderGameState.FINAL,
            com.streamvault.domain.sports.provider.ProviderGameState.CANCELLED,
            com.streamvault.domain.sports.provider.ProviderGameState.POSTPONED -> startTime + 1L
            else -> eventStart.plus(PROVIDER_EVENT_DEFAULT_DURATION).toEpochMilli()
        }
        val safeEndTime = endTime.coerceAtLeast(startTime + MIN_PROVIDER_EVENT_DURATION_MS)
        val league = providerEvent.league?.name?.takeIf(String::isNotBlank)
        return SportsEvent(
            id = id,
            title = title,
            league = league,
            categoryLabel = null,
            description = "",
            sport = SportsProgrammeClassifier.sportForProvider(
                providerEvent.sport ?: providerEvent.league?.sport, league,
            ).takeUnless { it == SportType.UNKNOWN } ?: SportType.OTHER,
            startTime = startTime,
            endTime = safeEndTime,
            broadcasts = emptyList(),
            confidence = 0.85f,
            competitors = providerEvent.competitors.map { competitor ->
                SportsCompetitor(competitor.name, logoUrl = competitor.logoUrl)
            },
            session = providerEvent.session,
            eventType = providerEvent.status?.state.toSportsEventType(),
            rawProgrammeRefs = emptyList(),
            leagueInfo = league?.let { SportsLeagueRegistry.detectNormalized(SportsTextNormalizer.normalize(it)) },
            provenance = provenance,
        )
    }

    private fun com.streamvault.domain.sports.provider.ProviderGameState?.toSportsEventType(): SportsEventType = when (this) {
        com.streamvault.domain.sports.provider.ProviderGameState.LIVE -> SportsEventType.LIVE_EVENT
        com.streamvault.domain.sports.provider.ProviderGameState.SCHEDULED,
        com.streamvault.domain.sports.provider.ProviderGameState.DELAYED,
        com.streamvault.domain.sports.provider.ProviderGameState.SUSPENDED -> SportsEventType.SCHEDULED_EVENT
        com.streamvault.domain.sports.provider.ProviderGameState.FINAL,
        com.streamvault.domain.sports.provider.ProviderGameState.CANCELLED,
        com.streamvault.domain.sports.provider.ProviderGameState.POSTPONED -> SportsEventType.POST_GAME
        null, com.streamvault.domain.sports.provider.ProviderGameState.UNKNOWN -> SportsEventType.UNKNOWN
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeProviderRevisions() = providerRepository.getProviders()
        .flatMapLatest { providers ->
            val orderedProviders = providers.sortedBy { it.id }
            if (orderedProviders.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    orderedProviders.map { provider ->
                        combine(
                            syncMetadataRepository.observeMetadata(provider.id),
                            epgSourceRepository.getAssignmentsForProvider(provider.id),
                            preferencesRepository.getHiddenChannelIds(provider.id),
                            preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE),
                        ) { metadata, assignments, hiddenChannelIds, hiddenCategoryIds ->
                            ProviderGuideRevision(
                                providerId = provider.id,
                                lastSyncedAt = provider.lastSyncedAt,
                                lastEpgSuccess = metadata?.lastEpgSuccess ?: 0L,
                                guideSourcePolicy = provider.guideSourcePolicy,
                                assignments = assignments.map { assignment ->
                                    ExternalAssignmentRevision(
                                        assignment.epgSourceId,
                                        assignment.enabled,
                                        assignment.priority,
                                    )
                                }.sortedBy(ExternalAssignmentRevision::sourceId),
                                hiddenChannelIds = hiddenChannelIds,
                                hiddenCategoryIds = hiddenCategoryIds,
                            )
                        }
                    },
                ) { revisions -> revisions.toList() }
            }
        }

    private fun cachedEventsFor(key: SportsIndexKey): CachedSportsEvents? {
        val cached = eventIndexCache[key] ?: return null
        if (System.currentTimeMillis() - cached.cachedAtMillis > EVENT_INDEX_CACHE_TTL_MS) {
            eventIndexCache.remove(key)
            return null
        }
        return cached
    }

    private fun cacheEvents(
        key: SportsIndexKey,
        events: List<SportsEvent>,
        providerEvents: LoadedSportsProviderEvents,
    ) {
        eventIndexCache[key] = CachedSportsEvents(events, providerEvents, System.currentTimeMillis())
        while (eventIndexCache.size > MAX_EVENT_INDEX_ENTRIES) {
            eventIndexCache.remove(eventIndexCache.keys.first())
        }
    }

    suspend fun searchChannels(event: SportsEvent, query: String): List<SportsBroadcast> {
        val entries = availableChannelEntries
        if (entries.isEmpty()) return emptyList()
        return withContext(dispatchers.default) {
            val index = getFallbackChannelIndex(entries)
            if (availableChannelEntries != entries) return@withContext emptyList()
            SportsChannelFallbackMatcher.searchChannels(event, index, query).map { match ->
                SportsBroadcast(
                    channel = match.entry.channel,
                    combinedProfileId = match.entry.combinedProfileId,
                    fallbackMatch = match,
                )
            }
        }
    }

    suspend fun rememberChannelForEvent(event: SportsEvent, broadcast: SportsBroadcast): Boolean {
        val entry = availableChannelEntries.firstOrNull { candidate ->
            candidate.channel.providerId == broadcast.channel.providerId &&
                candidate.channel.id == broadcast.channel.id
        } ?: return false
        val channel = entry.channel
        if (channel.streamUrl.isBlank() || channel.isAdult || channel.isUserProtected) return false
        preferencesRepository.saveSportsEventChannelPick(
            eventId = event.id,
            providerId = channel.providerId,
            channelId = channel.id,
            expiresAtMillis = System.currentTimeMillis() + SPORTS_EVENT_CHANNEL_PICK_TTL_MILLIS,
        )
        refreshRequest.update { it + 1 }
        return true
    }

    suspend fun forgetChannelForEvent(event: SportsEvent) {
        preferencesRepository.forgetSportsEventChannelPick(event.id)
        refreshRequest.update { it + 1 }
    }

    private fun getFallbackChannelIndex(entries: List<SportsChannelIndexEntry>): SportsChannelIndex {
        fallbackChannelIndexCache?.takeIf { it.entries == entries }?.let { return it.index }
        val startedAt = SystemClock.elapsedRealtime()
        return SportsChannelIndex.build(entries).also { index ->
            if (availableChannelEntries == entries) {
                fallbackChannelIndexCache = CachedSportsChannelIndex(entries, index)
            }
            SportsDiagnostics.performance(
                stage = "fallback-index",
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                itemCount = index.entries.size,
            )
        }
    }

    private suspend fun resolveProviderSources(source: ActiveLiveSource?): List<Pair<Long, Long?>> = when (source) {
        is ActiveLiveSource.ProviderSource -> listOf(source.providerId to null)
        is ActiveLiveSource.CombinedM3uSource -> combinedM3uRepository.getProfile(source.profileId)
            ?.members
            .orEmpty()
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.priority }
            .map { member -> member.providerId to source.profileId }
            .distinctBy { it.first }
            .toList()
        null -> emptyList()
    }

    private suspend fun loadProviderCandidates(
        provider: ProviderChannels,
        range: SportsTimeRange,
        now: Long,
        window: SportsTimeWindow,
    ): LoadedSportsProvider {
        val providerId = provider.providerId
        val combinedProfileId = provider.combinedProfileId
        val channels = provider.channels
        if (channels.isEmpty()) return LoadedSportsProvider(emptyList(), emptyList())

        val diagnosticsEnabled = SportsDiagnostics.isEnabled()
        val channelMatchConfidences = if (diagnosticsEnabled) {
            epgSourceRepository.getChannelMappingConfidences(
                providerId = providerId,
                channelIds = channels.map { it.id },
            )
        } else {
            emptyMap()
        }

        val programsStartedAt = SystemClock.elapsedRealtime()
        val programsByChannelKey = epgRepository.getResolvedProgramsForChannels(
            providerId = providerId,
            channelIds = channels.map { it.id }.distinct(),
            startTime = window.startTime,
            endTime = window.endTime,
        )
        SportsDiagnostics.performance(
            stage = "resolved-programs",
            elapsedMillis = SystemClock.elapsedRealtime() - programsStartedAt,
            itemCount = programsByChannelKey.values.sumOf { programs -> programs.size },
            groupCount = programsByChannelKey.size,
        )

        val classificationStartedAt = SystemClock.elapsedRealtime()
        val cacheStatsBefore = classificationCache.stats()
        val candidates = withContext(dispatchers.default) {
            val coroutineContext = currentCoroutineContext()
            buildList {
                channels.forEach { channel ->
                    coroutineContext.ensureActive()
                    val lookupKey = channel.streamId.takeIf { it > 0L }?.toString()
                        ?: channel.epgChannelId?.trim()?.takeIf(String::isNotEmpty)
                        ?: return@forEach
                    val channelPrograms = programsByChannelKey[lookupKey].orEmpty()
                    if (channelPrograms.isEmpty()) return@forEach
                    val channelContext = provider.contexts.getOrPut(channel.id) {
                        SportsProgrammeClassifier.prepareChannel(channel)
                    }
                    channelPrograms.forEachIndexed programLoop@{ index, program ->
                        if (index % 128 == 0) coroutineContext.ensureActive()
                        if (!programMatchesRange(program.startTime, program.endTime, range, now, window)) {
                            return@programLoop
                        }
                        val classification = classificationCache.classify(program, channelContext)
                        val fingerprint = if (classification.isCompetitiveEvent || diagnosticsEnabled) {
                            SportsProgrammeClassifier.eventIdentity(
                                classification = classification,
                                sourceKey = channel.providerId.toString() + ":" + channel.id,
                            )
                        } else {
                            null
                        }
                        if (diagnosticsEnabled) {
                            SportsDiagnostics.classification(
                                program = program,
                                classification = classification,
                                eventFingerprint = fingerprint.orEmpty(),
                                channelId = channel.id,
                                channelName = channel.name,
                                channelMatchConfidence = channelMatchConfidences[channel.id],
                            )
                        }
                        if (classification.isCompetitiveEvent) {
                            add(
                                SportsEventCandidate(
                                    program = program,
                                    channel = channel,
                                    combinedProfileId = combinedProfileId,
                                    classification = classification,
                                    eventIdentity = checkNotNull(fingerprint),
                                ),
                            )
                        }
                    }
                }
            }
        }
        SportsDiagnostics.performance(
            stage = "classification",
            elapsedMillis = SystemClock.elapsedRealtime() - classificationStartedAt,
            itemCount = candidates.size,
            groupCount = channels.size,
        )
        val cacheStats = classificationCache.stats()
        SportsDiagnostics.classificationCache(
            hits = cacheStats.hits - cacheStatsBefore.hits,
            misses = cacheStats.misses - cacheStatsBefore.misses,
            entries = cacheStats.entries,
            estimatedBytes = cacheStats.estimatedBytes,
        )
        return LoadedSportsProvider(
            candidates = candidates,
            channels = channels.map { channel -> SportsChannelIndexEntry(channel, combinedProfileId) },
        )
    }

    private fun windowFor(range: SportsTimeRange, now: Long): SportsTimeWindow {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        return when (range) {
            SportsTimeRange.ON_NOW -> SportsTimeWindow(now, now + 1L)
            SportsTimeRange.TODAY -> SportsTimeWindow(
                startTime = now,
                endTime = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli(),
            )
            SportsTimeRange.UPCOMING -> SportsTimeWindow(
                startTime = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli(),
                endTime = tomorrow.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
            )
        }
    }

    private fun programMatchesRange(
        startTime: Long,
        endTime: Long,
        range: SportsTimeRange,
        now: Long,
        window: SportsTimeWindow,
    ): Boolean = when (range) {
        SportsTimeRange.ON_NOW -> startTime <= now && endTime > now
        SportsTimeRange.TODAY -> endTime > now && startTime < window.endTime
        SportsTimeRange.UPCOMING -> startTime >= window.startTime && startTime < window.endTime
    }

    private fun localDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    private data class SportsTimeWindow(val startTime: Long, val endTime: Long)

    private data class SportsSourceKey(val providerId: Long, val combinedProfileId: Long?)

    private data class ProviderChannels(
        val providerId: Long,
        val combinedProfileId: Long?,
        val channels: List<Channel>,
    ) {
        val contexts = java.util.concurrent.ConcurrentHashMap<Long, SportsProgrammeClassifier.ChannelContext>()
    }

    private data class SportsLoadInputs(
        val providers: List<ProviderChannels>,
        val guideRevision: SportsGuideRevision,
        val retry: Int,
        val eventSourcePreference: SportsEventSourcePreference,
        val scoreSourcePreference: SportsScoreSourcePreference,
        val metadataSourcePreference: SportsMetadataSourcePreference,
        val providerRevision: Long,
    )

    private data class SportsLoadSettings(
        val range: SportsTimeRange,
        val retry: Int,
        val eventSourcePreference: SportsEventSourcePreference,
        val scoreSourcePreference: SportsScoreSourcePreference,
        val metadataSourcePreference: SportsMetadataSourcePreference,
        val providerRevision: Long,
    )

    private data class LoadedSportsProvider(
        val candidates: List<SportsEventCandidate>,
        val channels: List<SportsChannelIndexEntry>,
    )

    private data class CachedSportsChannelIndex(
        val entries: List<SportsChannelIndexEntry>,
        val index: SportsChannelIndex,
    )

    private data class SportsIndexKey(
        val sources: List<SportsSourceKey>,
        val range: SportsTimeRange,
        val localDate: LocalDate,
        val eventSourcePreference: SportsEventSourcePreference,
        val scoreSourcePreference: SportsScoreSourcePreference,
        val metadataSourcePreference: SportsMetadataSourcePreference,
        val eventProviderScopes: List<SportsProviderConfig>,
    )

    private data class CachedSportsEvents(
        val events: List<SportsEvent>,
        val providerEvents: LoadedSportsProviderEvents,
        val cachedAtMillis: Long,
    )

    private data class ProviderGuideRevision(
        val providerId: Long,
        val lastSyncedAt: Long,
        val lastEpgSuccess: Long,
        val guideSourcePolicy: GuideSourcePolicy,
        val assignments: List<ExternalAssignmentRevision>,
        val hiddenChannelIds: Set<Long>,
        val hiddenCategoryIds: Set<Long>,
    )

    private data class ExternalAssignmentRevision(
        val sourceId: Long,
        val enabled: Boolean,
        val priority: Int,
    )

    private data class ExternalGuideRevision(
        val sourceId: Long,
        val lastSuccessAt: Long,
        val updatedAt: Long,
    )

    private data class CombinedProfileRevision(
        val profileId: Long,
        val enabled: Boolean,
        val members: List<CombinedProfileMemberRevision>,
    )

    private data class CombinedProfileMemberRevision(
        val providerId: Long,
        val enabled: Boolean,
        val priority: Int,
    )

    private data class SportsGuideRevision(
        val providers: List<ProviderGuideRevision>,
        val sources: List<ExternalGuideRevision>,
        val combinedProfiles: List<CombinedProfileRevision>,
    )

    private companion object {
        const val PROVIDER_LOAD_TIMEOUT_MS = 25_000L
        const val ENRICHMENT_TIMEOUT_MS = 5_000L
        const val EVENT_INDEX_CACHE_TTL_MS = 5 * 60_000L
        const val MAX_EVENT_INDEX_ENTRIES = 8
        const val PROVIDER_EVENT_LOOKBACK_MS = 18 * 60 * 60_000L
        const val MIN_PROVIDER_EVENT_DURATION_MS = 30 * 60_000L
        val PROVIDER_EVENT_DEFAULT_DURATION: Duration = Duration.ofHours(6)
    }
}
