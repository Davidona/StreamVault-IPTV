package com.streamvault.data.sports.custom

import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Built-in declarative adapter. It never evaluates user code or follows a supplied URL. */
class CustomSportsProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = CUSTOM_SPORTS_PROVIDER_TYPE_ID,
        capabilities = SportsCapability.entries.toSet(),
        auth = SportsProviderAuth.None,
        displayName = null,
        configurationVersion = 1,
        supportsCustomBaseUrl = true,
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> {
        require(!config.baseUrl.isNullOrBlank())
        return CustomSportsProviderDefinition.parse(requireNotNull(config.customDefinitionJson)).capabilities
    }

    override fun displayNameFor(config: SportsProviderConfig): String =
        CustomSportsProviderDefinition.parse(requireNotNull(config.customDefinitionJson)).name

    override fun create(config: SportsProviderConfig): SportsProvider = CustomSportsProvider(
        config = config,
        descriptor = descriptor,
        definition = CustomSportsProviderDefinition.parse(requireNotNull(config.customDefinitionJson)),
        httpClient = httpClient,
        store = store,
    )
}

internal class CustomSportsProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val definition: CustomSportsProviderDefinition,
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val eventLoadTimeoutMillis: Long = EVENT_LOAD_TIMEOUT_MS,
) : EventProvider, LiveScoreProvider, GameStatusProvider, StandingsProvider, BroadcastProvider,
    TeamMetadataProvider, LeagueMetadataProvider, ArtworkProvider, LineupProvider,
    StatisticsProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> {
        val mapping = definition.events
        val now = Instant.now()
        val configuredScope = if (config.eventLeagueIds.isEmpty()) {
            definition.scopes.firstOrNull()
        } else {
            val resolved = resolveScope(null, config.eventLeagueIds.first(), mapping.endpoint)
            definition.scopes.firstOrNull { it.sport == resolved.sport && it.leagueId == resolved.leagueId }
        }
        val request = ProviderEventRequest(
            now,
            now.plusSeconds(TEST_WINDOW_SECONDS),
            sport = configuredScope?.sport,
            leagueId = config.eventLeagueIds.firstOrNull() ?: configuredScope?.leagueId,
        )
        val scope = resolveScope(request.sport, request.leagueId, mapping.endpoint)
        return when (val response = fetchJson(
            mapping.endpoint,
            eventVariables(request, mapping.dateFormat, mapping.endpoint),
            credentials,
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> when (val mapped = CustomSportsProviderJsonMapper.events(
                mapping,
                response.value,
                sportFallback = scope.sport,
                leagueIdFallback = scope.leagueId,
                leagueNameFallback = configuredScope?.leagueName,
            )) {
                is SportsProviderResult.Failure -> mapped
                is SportsProviderResult.Success -> SportsProviderResult.Success(Unit)
            }
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val credentials = storedCredentials()
        val scopes = if (request.sport != null || request.leagueId != null) {
            val resolved = resolveScope(request.sport, request.leagueId, definition.events.endpoint)
            listOf(definition.scopes.firstOrNull {
                it.sport == resolved.sport && it.leagueId == resolved.leagueId
            } ?: CustomSportsProviderScope(sport = resolved.sport, leagueId = resolved.leagueId))
        } else {
            definition.scopes.ifEmpty { listOf(CustomSportsProviderScope()) }
        }
        // Keep completed scopes when another route stalls. Indexing results preserves the
        // definition's precedence (specific leagues before aggregate routes).
        val requests = if (definition.events.dailyRequests) {
            val first = LocalDate.ofInstant(request.from, ZoneOffset.UTC)
            val last = LocalDate.ofInstant(request.until.minusNanos(1), ZoneOffset.UTC)
            val days = java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1
            if (days !in 1..MAX_EVENT_DAYS) {
                return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
            }
            (0 until days).flatMap { offset ->
                scopes.map { scope -> scope to first.plusDays(offset).atStartOfDay().toInstant(ZoneOffset.UTC) }
            }
        } else scopes.map { it to request.from }
        val results = ConcurrentHashMap<Int, SportsProviderResult<ProviderBatch<ProviderEvent>>>()
        val permits = Semaphore(MAX_CONCURRENT_EVENT_REQUESTS)
        withContext(Dispatchers.IO) { withTimeoutOrNull(eventLoadTimeoutMillis) {
            coroutineScope {
                requests.forEachIndexed { index, (scope, date) ->
                    launch {
                        permits.withPermit {
                            val scopedRequest = request.copy(from = date, sport = scope.sport, leagueId = scope.leagueId)
                            results[index] = when (val response = fetchJson(
                                definition.events.endpoint,
                                eventVariables(scopedRequest, definition.events.dateFormat, definition.events.endpoint),
                                credentials,
                            )) {
                                is SportsProviderResult.Failure -> response
                                is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.events(
                                    definition.events,
                                    response.value,
                                    sportFallback = scope.sport,
                                    leagueIdFallback = scope.leagueId,
                                    leagueNameFallback = scope.leagueName,
                                )
                            }
                        }
                    }
                }
            }
        } }
        val events = mutableListOf<ProviderEvent>()
        var rejected = 0
        var lastFailure: SportsProviderResult.Failure? = null
        var succeeded = false
        for ((_, mapped) in results.toSortedMap()) {
            when (mapped) {
                    is SportsProviderResult.Failure -> lastFailure = mapped
                    is SportsProviderResult.Success -> {
                        succeeded = true
                        rejected += mapped.value.rejectedItemCount
                        events += mapped.value.items
                        if (events.size > MAX_COMBINED_EVENT_ROWS) {
                            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                        }
                    }
            }
        }
        if (!succeeded) return lastFailure ?: SportsProviderResult.Failure(
            SportsProviderError.NETWORK,
        )
        return SportsProviderResult.Success(ProviderBatch(
            items = events.distinctBy(ProviderEvent::providerEventId),
            rejectedItemCount = rejected,
        ))
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> {
        val mapping = definition.scores
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        if (request.eventIds.isEmpty()) return SportsProviderResult.Success(ProviderBatch(emptyList()))
        if (request.eventIds.size > MAX_SCORE_EVENT_IDS) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }
        val credentials = storedCredentials()
        // Preserve the caller's live/nearest-game priority within the time budget.
        val prioritizedIds = request.eventIds.toList()
        if (mapping.endpoint.contains("{eventId}")) {
            val combined = mutableListOf<ProviderScore>()
            var rejected = 0
            val results = ConcurrentHashMap<String, SportsProviderResult<ProviderBatch<ProviderScore>>>()
            val permits = Semaphore(MAX_CONCURRENT_EVENT_REQUESTS)
            withContext(Dispatchers.IO) { withTimeoutOrNull(SCORE_LOAD_TIMEOUT_MS) {
                coroutineScope {
                    prioritizedIds.forEach { eventId -> launch {
                        permits.withPermit {
                            val scope = request.eventScopes[eventId] ?: ProviderEventScope()
                            results[eventId] = when (val response = fetchJson(
                                mapping.endpoint,
                                mapOf("eventId" to eventId) + scopedVariables(scope),
                                credentials,
                            )) {
                                is SportsProviderResult.Failure -> response
                                is SportsProviderResult.Success ->
                                    CustomSportsProviderJsonMapper.scores(mapping, response.value)
                            }
                        }
                    } }
                }
            } }
            var succeeded = false
            var lastFailure: SportsProviderResult.Failure? = null
            for ((eventId, mapped) in results.toSortedMap()) {
                when (mapped) {
                    is SportsProviderResult.Failure -> lastFailure = mapped
                    is SportsProviderResult.Success -> {
                        succeeded = true
                        combined += mapped.value.items.filter { it.providerEventId == eventId }
                        rejected += mapped.value.rejectedItemCount
                        if (combined.size > MAX_SCORE_ROWS) {
                            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                        }
                    }
                }
            }
            if (!succeeded) {
                return lastFailure ?: SportsProviderResult.Failure(SportsProviderError.NETWORK)
            }
            return SportsProviderResult.Success(ProviderBatch(combined, rejected))
        }

        val groups = if (hasScopePlaceholders(mapping.endpoint)) {
            prioritizedIds.groupBy { request.eventScopes[it] ?: ProviderEventScope() }
        } else {
            mapOf(ProviderEventScope() to prioritizedIds)
        }
        val combined = mutableListOf<ProviderScore>()
        var rejected = 0
        for ((scope, ids) in groups) {
            when (val response = fetchJson(
                mapping.endpoint,
                mapOf("eventIds" to ids.joinToString(",")) + scopedVariables(scope),
                credentials,
            )) {
                is SportsProviderResult.Failure -> return response
                is SportsProviderResult.Success -> when (
                    val mapped = CustomSportsProviderJsonMapper.scores(mapping, response.value, ids.toSet())
                ) {
                    is SportsProviderResult.Failure -> return mapped
                    is SportsProviderResult.Success -> {
                        combined += mapped.value.items
                        rejected += mapped.value.rejectedItemCount
                        if (combined.size > MAX_SCORE_ROWS) {
                            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                        }
                    }
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(combined, rejected))
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> {
        val mapping = definition.statuses
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return fetchEventBatch(mapping.endpoint, request.eventIds, request.eventScopes) { body, requestedIds ->
            CustomSportsProviderJsonMapper.statuses(mapping, body, requestedIds)
        }
    }

    override suspend fun standings(
        request: ProviderStandingsRequest,
    ): SportsProviderResult<ProviderStandings> {
        val mapping = definition.standings
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        val requestedName = request.leagueName?.let(::normalizeScopeName)
        val namedScope = requestedName?.let { name ->
            definition.scopes.firstOrNull { scope ->
                scope.leagueName?.let(::normalizeScopeName) == name &&
                    (request.sport == null || scope.sport == request.sport)
            }
        }
        val scope = namedScope?.let { ProviderEventScope(it.sport, it.leagueId) }
            ?: resolveScope(request.sport, request.leagueId, mapping.endpoint)
        val scopeName = namedScope?.leagueName
        val scopedRequest = request.copy(
            leagueId = scope.leagueId.orEmpty(),
            sport = scope.sport,
            leagueName = scopeName ?: request.leagueName,
        )
        return when (val response = fetchJson(
            mapping.endpoint,
            mapOf("leagueId" to scope.leagueId.orEmpty(), "season" to request.season.orEmpty()) +
                scopedVariables(scope),
            storedCredentials(),
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.standings(
                mapping,
                response.value,
                scopedRequest,
                leagueNameFallback = scopeName,
            )
        }
    }

    override suspend fun broadcasters(
        request: ProviderEventDetailRequest,
    ): SportsProviderResult<ProviderBatch<ProviderBroadcaster>> {
        val mapping = definition.broadcasters
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return fetchDetailBatch(mapping.endpoint, request) { body ->
            CustomSportsProviderJsonMapper.broadcasters(mapping, body)
        }
    }

    override suspend fun teamMetadata(
        request: ProviderTeamMetadataRequest,
    ): SportsProviderResult<ProviderTeamMetadata> {
        val mapping = definition.team
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return when (val response = fetchJson(
            mapping.endpoint,
            mapOf("teamId" to request.teamId) + scopedVariables(
                resolveScope(request.sport, request.leagueId, mapping.endpoint),
            ),
            storedCredentials(),
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.teamMetadata(
                mapping,
                response.value,
                request,
            )
        }
    }

    override suspend fun leagueMetadata(
        request: ProviderLeagueMetadataRequest,
    ): SportsProviderResult<ProviderLeagueMetadata> {
        val mapping = definition.league
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return when (val response = fetchJson(
            mapping.endpoint,
            mapOf("leagueId" to request.leagueId) + scopedVariables(
                resolveScope(request.sport, request.leagueId, mapping.endpoint),
            ),
            storedCredentials(),
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.leagueMetadata(
                mapping,
                response.value,
                request,
            )
        }
    }

    override suspend fun artwork(
        request: ProviderEntityRef,
    ): SportsProviderResult<ProviderBatch<ProviderArtwork>> {
        val mapping = definition.artwork
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return when (val response = fetchJson(
            mapping.endpoint,
            mapOf("entityId" to request.id, "entityType" to request.type.name.lowercase()) +
                scopedVariables(resolveScope(request.sport, request.leagueId, mapping.endpoint)),
            storedCredentials(),
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.artwork(
                mapping,
                response.value,
                request,
            )
        }
    }

    override suspend fun lineups(
        request: ProviderEventDetailRequest,
    ): SportsProviderResult<ProviderBatch<ProviderLineup>> {
        val mapping = definition.lineups
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return fetchDetailBatch(mapping.endpoint, request) { body ->
            CustomSportsProviderJsonMapper.lineups(mapping, body, request.eventId)
        }
    }

    override suspend fun statistics(
        request: ProviderEventDetailRequest,
    ): SportsProviderResult<ProviderEventStatistics> {
        val mapping = definition.statistics
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return when (val response = fetchJson(
            mapping.endpoint,
            mapOf("eventId" to request.eventId) + scopedVariables(
                resolveScope(request.sport, request.leagueId, mapping.endpoint),
            ),
            storedCredentials(),
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.statistics(
                mapping,
                response.value,
                request.eventId,
            )
        }
    }

    private suspend fun <T : Any> fetchEventBatch(
        endpoint: String,
        eventIds: Set<String>,
        eventScopes: Map<String, ProviderEventScope> = emptyMap(),
        map: (JsonElement, Set<String>) -> SportsProviderResult<ProviderBatch<T>>,
    ): SportsProviderResult<ProviderBatch<T>> {
        if (eventIds.isEmpty()) return SportsProviderResult.Success(ProviderBatch(emptyList()))
        if (eventIds.size > MAX_DETAIL_EVENT_IDS) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }
        val credentials = storedCredentials()
        if ("{eventId}" in endpoint) {
            val combined = mutableListOf<T>()
            var rejected = 0
            for (eventId in eventIds.sorted()) {
                val scope = eventScopes[eventId] ?: ProviderEventScope()
                when (val response = fetchJson(
                    endpoint,
                    mapOf("eventId" to eventId) + scopedVariables(scope),
                    credentials,
                )) {
                    is SportsProviderResult.Failure -> return response
                    is SportsProviderResult.Success -> when (val mapped = map(response.value, setOf(eventId))) {
                        is SportsProviderResult.Failure -> return mapped
                        is SportsProviderResult.Success -> {
                            combined += mapped.value.items
                            rejected += mapped.value.rejectedItemCount
                            if (combined.size > MAX_DETAIL_ROWS) {
                                return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                            }
                        }
                    }
                }
            }
            return SportsProviderResult.Success(ProviderBatch(combined, rejected))
        }
        val groups = if (hasScopePlaceholders(endpoint)) {
            eventIds.sorted().groupBy { eventScopes[it] ?: ProviderEventScope() }
        } else {
            mapOf(ProviderEventScope() to eventIds.sorted())
        }
        val combined = mutableListOf<T>()
        var rejected = 0
        for ((scope, ids) in groups) {
            when (val response = fetchJson(
                endpoint,
                mapOf("eventIds" to ids.joinToString(",")) + scopedVariables(scope),
                credentials,
            )) {
                is SportsProviderResult.Failure -> return response
                is SportsProviderResult.Success -> when (val mapped = map(response.value, ids.toSet())) {
                    is SportsProviderResult.Failure -> return mapped
                    is SportsProviderResult.Success -> {
                        combined += mapped.value.items
                        rejected += mapped.value.rejectedItemCount
                        if (combined.size > MAX_DETAIL_ROWS) {
                            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                        }
                    }
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(combined, rejected))
    }

    private suspend fun <T : Any> fetchDetailBatch(
        endpoint: String,
        request: ProviderEventDetailRequest,
        map: (JsonElement) -> SportsProviderResult<ProviderBatch<T>>,
    ): SportsProviderResult<ProviderBatch<T>> = when (val response = fetchJson(
        endpoint,
        mapOf("eventId" to request.eventId) + scopedVariables(
            resolveScope(request.sport, request.leagueId, endpoint),
        ),
        storedCredentials(),
    )) {
        is SportsProviderResult.Failure -> response
        is SportsProviderResult.Success -> map(response.value)
    }

    private suspend fun storedCredentials(): SportsProviderCredentials? =
        try {
            store.credentials(config.instanceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun fetchJson(
        endpoint: String,
        variables: Map<String, String>,
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<JsonElement> {
        val baseUrl = config.baseUrl?.toHttpUrlOrNull()
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        val url = buildCustomSportsProviderUrl(baseUrl, endpoint, variables)
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        return httpClient.get(url, definition.auth, credentials) { body ->
            Json.parseToJsonElement(body)
        }
    }

    private fun eventVariables(
        request: ProviderEventRequest,
        dateFormat: CustomEventDateFormat,
        endpoint: String,
    ): Map<String, String> = mapOf(
        "fromDate" to request.from.toString(),
        "toDate" to request.until.toString(),
        "date" to LocalDate.ofInstant(request.from, ZoneOffset.UTC).format(dateFormat.formatter),
        "fromDateFormatted" to LocalDate.ofInstant(request.from, ZoneOffset.UTC).format(dateFormat.formatter),
        "toDateFormatted" to LocalDate.ofInstant(request.until.minusNanos(1), ZoneOffset.UTC)
            .format(dateFormat.formatter),
    ) + scopedVariables(resolveScope(request.sport, request.leagueId, endpoint))

    private fun resolveScope(
        sport: String?,
        leagueId: String?,
        endpoint: String? = null,
    ): ProviderEventScope {
        val rawSport = sport?.trim()?.takeIf(String::isNotEmpty)
        val rawLeagueId = leagueId?.trim()?.takeIf(String::isNotEmpty)
        if (endpoint?.contains("{sport}") == true && rawLeagueId != null) {
            val separator = rawLeagueId.indexOf('/')
            if (separator > 0 && separator < rawLeagueId.lastIndex) {
                return ProviderEventScope(
                    sport = rawLeagueId.substring(0, separator),
                    leagueId = rawLeagueId.substring(separator + 1),
                )
            }
        }
        return ProviderEventScope(rawSport, rawLeagueId)
    }

    private fun scopedVariables(scope: ProviderEventScope): Map<String, String> = mapOf(
        "sport" to scope.sport.orEmpty(),
        "leagueId" to scope.leagueId.orEmpty(),
    )

    private fun hasScopePlaceholders(endpoint: String): Boolean =
        "{sport}" in endpoint || "{leagueId}" in endpoint

    private fun normalizeScopeName(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        const val MAX_CONCURRENT_EVENT_REQUESTS = 4
        const val EVENT_LOAD_TIMEOUT_MS = 15_000L
        const val MAX_EVENT_DAYS = 14L
        const val SCORE_LOAD_TIMEOUT_MS = 4_000L
        const val TEST_WINDOW_SECONDS = 24 * 60 * 60L
        const val MAX_COMBINED_EVENT_ROWS = 10_000
        const val MAX_SCORE_EVENT_IDS = 50
        const val MAX_SCORE_ROWS = 1_000
        const val MAX_DETAIL_EVENT_IDS = 50
        const val MAX_DETAIL_ROWS = 1_000
    }
}

internal data class CustomSportsProviderDefinition(
    val name: String,
    val auth: SportsProviderAuth,
    val events: CustomEventMapping,
    val scopes: List<CustomSportsProviderScope>,
    val scores: CustomScoreMapping?,
    val statuses: CustomStatusMapping?,
    val standings: CustomStandingsMapping?,
    val team: CustomTeamMetadataMapping?,
    val league: CustomLeagueMetadataMapping?,
    val artwork: CustomArtworkMapping?,
    val broadcasters: CustomBroadcasterMapping?,
    val lineups: CustomLineupMapping?,
    val statistics: CustomStatisticsMapping?,
) {
    val capabilities: Set<SportsCapability>
        get() = buildSet {
            add(SportsCapability.EVENTS)
            if (scores != null) add(SportsCapability.LIVE_SCORES)
            if (events.status != null || scores?.status != null || statuses != null) {
                add(SportsCapability.GAME_STATUS)
            }
            if (standings != null) add(SportsCapability.STANDINGS)
            if (team != null) add(SportsCapability.TEAM_METADATA)
            if (league != null) add(SportsCapability.LEAGUE_METADATA)
            if (artwork != null) add(SportsCapability.ARTWORK)
            if (broadcasters != null || events.broadcasters != null) add(SportsCapability.BROADCASTERS)
            if (lineups != null) add(SportsCapability.LINEUPS)
            if (statistics != null) add(SportsCapability.STATISTICS)
        }

    companion object {
        fun parse(source: String): CustomSportsProviderDefinition = try {
            require(
                source.isNotBlank() &&
                    source.length <= CUSTOM_SPORTS_PROVIDER_MAX_CONFIGURATION_CHARS,
            )
            val root = Json.parseToJsonElement(source).jsonObject
            requireKeys(root, setOf(
                "name", "auth", "events", "scores", "statuses", "standings", "team", "league",
                "artwork", "broadcasters", "lineups", "statistics", "scopes",
            ))
            val name = root.requiredString("name", maxLength = 64)
            require(name.none { Character.isISOControl(it) })
            val auth = root["auth"]?.jsonObject?.let(::parseAuth) ?: SportsProviderAuth.None
            val events = root["events"]?.jsonObject?.let(::parseEvents)
                ?: throw IllegalArgumentException()
            val scopes = root["scopes"]?.takeUnless { it is JsonNull }?.jsonArray?.let { values ->
                require(values.size <= MAX_DECLARED_SCOPES)
                values.map { element ->
                    val value = element.jsonObject
                    requireKeys(value, setOf("sport", "leagueId", "leagueName"))
                    val sport = value.optionalLiteral("sport")
                    val leagueId = value.optionalLiteral("leagueId")
                    require(sport != null || leagueId != null)
                    CustomSportsProviderScope(
                        sport = sport,
                        leagueId = leagueId,
                        leagueName = value.optionalLiteral("leagueName"),
                    )
                }.also { parsed ->
                    require(parsed.map { it.sport to it.leagueId }.distinct().size == parsed.size)
                    if ("{sport}" in events.endpoint) require(parsed.all { !it.sport.isNullOrBlank() })
                    if ("{leagueId}" in events.endpoint) require(parsed.all { !it.leagueId.isNullOrBlank() })
                }
            }.orEmpty()
            val scores = root["scores"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseScores)
            val statuses = root.optionalObject("statuses")?.let(::parseStatuses)
            val standings = root.optionalObject("standings")?.let(::parseStandings)
            val team = root.optionalObject("team")?.let(::parseTeamMetadata)
            val league = root.optionalObject("league")?.let(::parseLeagueMetadata)
            val artwork = root.optionalObject("artwork")?.let(::parseArtwork)
            val broadcasters = root.optionalObject("broadcasters")?.let(::parseBroadcasters)
            val lineups = root.optionalObject("lineups")?.let(::parseLineups)
            val statistics = root.optionalObject("statistics")?.let(::parseStatistics)
            CustomSportsProviderDefinition(
                name, auth, events, scopes, scores, statuses, standings, team, league, artwork,
                broadcasters, lineups, statistics,
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException()
        }

        private fun parseAuth(value: JsonObject): SportsProviderAuth {
            requireKeys(value, setOf("mode", "parameterName"))
            val mode = value.requiredString("mode", maxLength = 24)
            val parameterName = value["parameterName"]?.jsonPrimitive?.content
            return when (mode) {
                "none" -> SportsProviderAuth.None.also { require(parameterName == null) }
                "api_key_header" -> SportsProviderAuth.ApiKeyHeader(requireNotNull(parameterName))
                "api_key_query" -> SportsProviderAuth.ApiKeyQuery(requireNotNull(parameterName))
                "bearer" -> SportsProviderAuth.Bearer.also { require(parameterName == null) }
                "basic" -> SportsProviderAuth.Basic.also { require(parameterName == null) }
                else -> throw IllegalArgumentException()
            }
        }

        private fun parseEvents(value: JsonObject): CustomEventMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "id", "sport", "sportValue", "leagueName", "leagueNameValue",
                "leagueId", "leagueIdValue", "title", "start", "startFormat", "dateFormat",
                "homeName", "homeId", "awayName", "awayId", "competitors", "competitorKind",
                "status", "clock", "period", "broadcasters", "dailyRequests",
            ))
            val competitors = value["competitors"]?.jsonObject?.let(::parseEventCompetitors)
            val homeName = value.optionalSelector("homeName")
            val homeId = value.optionalSelector("homeId")
            val awayName = value.optionalSelector("awayName")
            val awayId = value.optionalSelector("awayId")
            require(
                if (competitors == null) homeName != null && awayName != null
                else homeName == null && homeId == null && awayName == null && awayId == null,
            )
            val sport = value.optionalSelector("sport")
            val sportValue = value.optionalLiteral("sportValue")
            val leagueName = value.optionalSelector("leagueName")
            val leagueNameValue = value.optionalLiteral("leagueNameValue")
            val leagueId = value.optionalSelector("leagueId")
            val leagueIdValue = value.optionalLiteral("leagueIdValue")
            require((sport == null || sportValue == null) &&
                (leagueName == null || leagueNameValue == null) &&
                (leagueId == null || leagueIdValue == null))
            val endpoint = value.requiredEndpoint("endpoint", EVENT_PLACEHOLDERS)
            val dailyRequests = value["dailyRequests"]?.let {
                it.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException()
            } ?: false
            require(!dailyRequests || "{date}" in endpoint)
            return CustomEventMapping(
                endpoint = endpoint,
                dailyRequests = dailyRequests,
                items = value.requiredSelector("items", allowRoot = true),
                id = value.requiredSelector("id"),
                sport = sport,
                sportValue = sportValue,
                leagueName = leagueName,
                leagueNameValue = leagueNameValue,
                leagueId = leagueId,
                leagueIdValue = leagueIdValue,
                title = value.optionalSelector("title"),
                start = value.requiredSelector("start"),
                startFormat = value.requiredTimestampFormat("startFormat"),
                dateFormat = value.optionalEventDateFormat("dateFormat") ?: CustomEventDateFormat.ISO_LOCAL_DATE,
                homeName = homeName,
                homeId = homeId,
                awayName = awayName,
                awayId = awayId,
                competitors = competitors,
                competitorKind = value.optionalCompetitorKind("competitorKind")
                    ?: ProviderCompetitorKind.TEAM,
                status = value.optionalSelector("status"),
                clock = value.optionalSelector("clock"),
                period = value.optionalSelector("period"),
                broadcasters = value.optionalObject("broadcasters")?.let(::parseBroadcasterItems),
            )
        }

        private fun parseEventCompetitors(value: JsonObject): CustomEventCompetitorMapping {
            requireKeys(value, setOf("items", "name", "id", "role", "homeValue", "awayValue", "logo"))
            return CustomEventCompetitorMapping(
                items = value.requiredSelector("items", allowRoot = true),
                name = value.requiredSelector("name"),
                logo = value.optionalSelector("logo"),
                id = value.optionalSelector("id"),
                role = value.optionalSelector("role"),
                homeValue = value.optionalLiteral("homeValue") ?: "home",
                awayValue = value.optionalLiteral("awayValue") ?: "away",
            )
        }

        private fun parseScores(value: JsonObject): CustomScoreMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "eventId", "homeName", "homeScore", "awayName", "awayScore",
                "competitors", "status", "clock", "period", "updatedAt", "updatedAtFormat",
            ))
            val endpoint = value.requiredEndpoint("endpoint", SCORE_PLACEHOLDERS)
            require(("{eventId}" in endpoint) xor ("{eventIds}" in endpoint))
            val competitors = value["competitors"]?.jsonObject?.let(::parseScoreCompetitors)
            val homeScore = value.optionalSelector("homeScore")
            val awayScore = value.optionalSelector("awayScore")
            val status = value.optionalSelector("status")
            val homeName = value.optionalSelector("homeName")
            val awayName = value.optionalSelector("awayName")
            if (competitors == null) {
                require((homeScore != null || awayScore != null || status != null) &&
                    (homeScore == null || homeName != null) && (awayScore == null || awayName != null))
            } else {
                require(homeName == null && homeScore == null && awayName == null && awayScore == null)
                require(competitors.score != null || status != null)
            }
            return CustomScoreMapping(
                endpoint = endpoint,
                items = value.requiredSelector("items", allowRoot = true),
                eventId = value.requiredSelector("eventId"),
                homeName = homeName,
                homeScore = homeScore,
                awayName = awayName,
                awayScore = awayScore,
                competitors = competitors,
                status = status,
                clock = value.optionalSelector("clock"),
                period = value.optionalSelector("period"),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseStatuses(value: JsonObject): CustomStatusMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "eventId", "status", "clock", "period", "detail",
                "updatedAt", "updatedAtFormat",
            ))
            val endpoint = value.requiredEndpoint("endpoint", SCORE_PLACEHOLDERS)
            require(("{eventId}" in endpoint) xor ("{eventIds}" in endpoint))
            return CustomStatusMapping(
                endpoint = endpoint,
                items = value.requiredSelector("items", allowRoot = true),
                eventId = value.requiredSelector("eventId"),
                status = value.requiredSelector("status"),
                clock = value.optionalSelector("clock"),
                period = value.optionalSelector("period"),
                detail = value.optionalSelector("detail"),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseStandings(value: JsonObject): CustomStandingsMapping {
            requireKeys(value, setOf(
                "endpoint", "leagueName", "leagueNameValue", "leagueId", "leagueIdValue",
                "sport", "sportValue", "season", "seasonValue", "updatedAt", "updatedAtFormat",
                "groups",
            ))
            val leagueName = value.optionalSelector("leagueName")
            val leagueNameValue = value.optionalLiteral("leagueNameValue")
            val leagueId = value.optionalSelector("leagueId")
            val leagueIdValue = value.optionalLiteral("leagueIdValue")
            val sport = value.optionalSelector("sport")
            val sportValue = value.optionalLiteral("sportValue")
            val season = value.optionalSelector("season")
            val seasonValue = value.optionalLiteral("seasonValue")
            require((leagueName == null || leagueNameValue == null) &&
                (leagueId == null || leagueIdValue == null) && (sport == null || sportValue == null) &&
                (season == null || seasonValue == null))
            return CustomStandingsMapping(
                endpoint = value.requiredEndpoint("endpoint", STANDINGS_PLACEHOLDERS),
                leagueName = leagueName,
                leagueNameValue = leagueNameValue,
                leagueId = leagueId,
                leagueIdValue = leagueIdValue,
                sport = sport,
                sportValue = sportValue,
                season = season,
                seasonValue = seasonValue,
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
                groups = value.optionalObject("groups")?.let(::parseStandingGroups)
                    ?: throw IllegalArgumentException(),
            )
        }

        private fun parseStandingGroups(value: JsonObject): CustomStandingGroupsMapping {
            requireKeys(value, setOf("items", "name", "nameValue", "rows"))
            val name = value.optionalSelector("name")
            val nameValue = value.optionalLiteral("nameValue")
            require(name == null || nameValue == null)
            return CustomStandingGroupsMapping(
                items = value.requiredSelector("items", allowRoot = true),
                name = name,
                nameValue = nameValue,
                rows = value.optionalObject("rows")?.let(::parseStandingRows)
                    ?: throw IllegalArgumentException(),
            )
        }

        private fun parseStandingRows(value: JsonObject): CustomStandingRowsMapping {
            requireKeys(value, setOf("items", "teamName", "teamId", "kind", "rank", "statistics"))
            return CustomStandingRowsMapping(
                items = value.requiredSelector("items", allowRoot = true),
                teamName = value.requiredSelector("teamName"),
                teamId = value.optionalSelector("teamId"),
                kind = value.optionalCompetitorKind("kind") ?: ProviderCompetitorKind.TEAM,
                rank = value.optionalSelector("rank"),
                statistics = value.optionalObject("statistics")?.let(::parseStandingStatistics),
            )
        }

        private fun parseStandingStatistics(value: JsonObject): CustomStandingStatisticsMapping {
            requireKeys(value, setOf("items", "key", "label", "value"))
            return CustomStandingStatisticsMapping(
                items = value.requiredSelector("items", allowRoot = true),
                key = value.requiredSelector("key"),
                label = value.optionalSelector("label"),
                value = value.requiredSelector("value"),
            )
        }

        private fun parseTeamMetadata(value: JsonObject): CustomTeamMetadataMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "id", "name", "shortName", "country", "kind", "updatedAt",
                "updatedAtFormat",
            ))
            return CustomTeamMetadataMapping(
                endpoint = value.requiredEndpoint("endpoint", TEAM_PLACEHOLDERS),
                items = value.requiredSelector("items", allowRoot = true),
                id = value.optionalSelector("id"),
                name = value.requiredSelector("name"),
                shortName = value.optionalSelector("shortName"),
                country = value.optionalSelector("country"),
                kind = value.optionalCompetitorKind("kind") ?: ProviderCompetitorKind.TEAM,
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseLeagueMetadata(value: JsonObject): CustomLeagueMetadataMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "id", "name", "sport", "country", "season", "updatedAt",
                "updatedAtFormat",
            ))
            return CustomLeagueMetadataMapping(
                endpoint = value.requiredEndpoint("endpoint", LEAGUE_PLACEHOLDERS),
                items = value.requiredSelector("items", allowRoot = true),
                id = value.optionalSelector("id"),
                name = value.optionalSelector("name"),
                sport = value.optionalSelector("sport"),
                country = value.optionalSelector("country"),
                season = value.optionalSelector("season"),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseArtwork(value: JsonObject): CustomArtworkMapping {
            requireKeys(value, setOf("endpoint", "items", "uri", "kind", "kindValue"))
            val kind = value.optionalSelector("kind")
            val kindValue = value.optionalLiteral("kindValue")?.let(::parseArtworkKind)
            require(kind == null || kindValue == null)
            return CustomArtworkMapping(
                endpoint = value.requiredEndpoint("endpoint", ARTWORK_PLACEHOLDERS),
                items = value.requiredSelector("items", allowRoot = true),
                uri = value.requiredSelector("uri"),
                kind = kind,
                kindValue = kindValue ?: ProviderArtworkKind.OTHER,
            )
        }

        private fun parseBroadcasters(value: JsonObject): CustomBroadcasterMapping {
            requireKeys(value, setOf("endpoint", "items", "name", "country", "language", "type", "typeValue"))
            return CustomBroadcasterMapping(
                endpoint = value.requiredEndpoint("endpoint", DETAIL_PLACEHOLDERS),
                fields = parseBroadcasterItems(JsonObject(value.filterKeys { it != "endpoint" })),
            )
        }

        private fun parseBroadcasterItems(value: JsonObject): CustomBroadcasterFieldsMapping {
            requireKeys(value, setOf("items", "name", "country", "language", "type", "typeValue"))
            val type = value.optionalSelector("type")
            val typeValue = value.optionalLiteral("typeValue")?.let(::parseBroadcastType)
            require(type == null || typeValue == null)
            return CustomBroadcasterFieldsMapping(
                items = value.requiredSelector("items", allowRoot = true),
                name = value.requiredSelector("name"),
                country = value.optionalSelector("country"),
                language = value.optionalSelector("language"),
                type = type,
                typeValue = typeValue ?: ProviderBroadcastType.UNKNOWN,
            )
        }

        private fun parseLineups(value: JsonObject): CustomLineupMapping {
            requireKeys(value, setOf("endpoint", "items", "teamName", "teamId", "members", "updatedAt", "updatedAtFormat"))
            return CustomLineupMapping(
                endpoint = value.requiredEndpoint("endpoint", DETAIL_PLACEHOLDERS),
                items = value.requiredSelector("items", allowRoot = true),
                teamName = value.optionalSelector("teamName"),
                teamId = value.optionalSelector("teamId"),
                members = value.optionalObject("members")?.let(::parseLineupMembers)
                    ?: throw IllegalArgumentException(),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseLineupMembers(value: JsonObject): CustomLineupMembersMapping {
            requireKeys(value, setOf("items", "name", "id", "kind", "position", "shirtNumber", "starter"))
            return CustomLineupMembersMapping(
                items = value.requiredSelector("items", allowRoot = true),
                name = value.requiredSelector("name"),
                id = value.optionalSelector("id"),
                kind = value.optionalCompetitorKind("kind") ?: ProviderCompetitorKind.PERSON,
                position = value.optionalSelector("position"),
                shirtNumber = value.optionalSelector("shirtNumber"),
                starter = value.optionalSelector("starter"),
            )
        }

        private fun parseStatistics(value: JsonObject): CustomStatisticsMapping {
            requireKeys(value, setOf("endpoint", "groups", "updatedAt", "updatedAtFormat"))
            val groups = value.optionalObject("groups") ?: throw IllegalArgumentException()
            requireKeys(groups, setOf("items", "teamName", "teamId", "statistics"))
            return CustomStatisticsMapping(
                endpoint = value.requiredEndpoint("endpoint", DETAIL_PLACEHOLDERS),
                groups = CustomStatisticGroupsMapping(
                    items = groups.requiredSelector("items", allowRoot = true),
                    teamName = groups.optionalSelector("teamName"),
                    teamId = groups.optionalSelector("teamId"),
                    statistics = groups.optionalObject("statistics")?.let(::parseStatisticsItems)
                        ?: throw IllegalArgumentException(),
                ),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
            )
        }

        private fun parseStatisticsItems(value: JsonObject): CustomStatisticsItemsMapping {
            requireKeys(value, setOf("items", "key", "label", "value", "period"))
            return CustomStatisticsItemsMapping(
                items = value.requiredSelector("items", allowRoot = true),
                key = value.requiredSelector("key"),
                label = value.optionalSelector("label"),
                value = value.requiredSelector("value"),
                period = value.optionalSelector("period"),
            )
        }

        private fun parseScoreCompetitors(value: JsonObject): CustomScoreCompetitorMapping {
            requireKeys(value, setOf("items", "name", "id", "score", "role", "homeValue", "awayValue", "kind"))
            return CustomScoreCompetitorMapping(
                items = value.requiredSelector("items", allowRoot = true),
                name = value.requiredSelector("name"),
                id = value.optionalSelector("id"),
                score = value.optionalSelector("score"),
                role = value.optionalSelector("role"),
                homeValue = value.optionalLiteral("homeValue") ?: "home",
                awayValue = value.optionalLiteral("awayValue") ?: "away",
                kind = value.optionalCompetitorKind("kind") ?: ProviderCompetitorKind.UNKNOWN,
            )
        }

        private fun JsonObject.requiredEndpoint(key: String, allowedPlaceholders: Set<String>): String {
            val endpoint = requiredString(key, maxLength = 512)
            require(endpoint.startsWith('/') && !endpoint.startsWith("//"))
            require('#' !in endpoint && '\\' !in endpoint && '\r' !in endpoint && '\n' !in endpoint)
            val placeholders = PLACEHOLDER.findAll(endpoint).map { it.groupValues[1] }.toSet()
            require(placeholders.all { it in allowedPlaceholders })
            require(endpoint.replace(PLACEHOLDER, "").none { it == '{' || it == '}' })
            return endpoint
        }

        private fun JsonObject.requiredSelector(key: String, allowRoot: Boolean = false): String =
            requiredString(key, maxLength = 256).also { requireSelector(it, allowRoot) }

        private fun JsonObject.optionalSelector(key: String): String? =
            this[key]?.let { element ->
                if (element is JsonNull) null else element.jsonPrimitive.content.also { requireSelector(it) }
            }

        private fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.let { element ->
            if (element is JsonNull) null else element.jsonObject
        }

        private fun requireSelector(value: String, allowRoot: Boolean = false) {
            parseJsonSelector(value, allowRoot)
        }

        private fun JsonObject.requiredTimestampFormat(key: String): ProviderTimestampFormat =
            ProviderTimestampFormat.entries.firstOrNull { it.name == requiredString(key, maxLength = 32) }
                ?: throw IllegalArgumentException()

        private fun JsonObject.optionalTimestampFormat(key: String): ProviderTimestampFormat? {
            val value = this[key]?.jsonPrimitive?.content ?: return null
            return ProviderTimestampFormat.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException()
        }

        private fun JsonObject.optionalEventDateFormat(key: String): CustomEventDateFormat? {
            val value = this[key]?.jsonPrimitive?.content ?: return null
            return CustomEventDateFormat.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException()
        }

        private fun JsonObject.optionalCompetitorKind(key: String): ProviderCompetitorKind? {
            val value = this[key]?.jsonPrimitive?.content ?: return null
            return ProviderCompetitorKind.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException()
        }

        private fun parseArtworkKind(value: String): ProviderArtworkKind =
            ProviderArtworkKind.entries.firstOrNull { it.name == value.uppercase() }
                ?: throw IllegalArgumentException()

        private fun parseBroadcastType(value: String): ProviderBroadcastType =
            ProviderBroadcastType.entries.firstOrNull { it.name == value.uppercase() }
                ?: throw IllegalArgumentException()

        private fun JsonObject.requiredString(key: String, maxLength: Int): String {
            val value = this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content?.trim()
                ?: throw IllegalArgumentException()
            require(value.isNotEmpty() && value.length <= maxLength)
            return value
        }

        private fun JsonObject.optionalLiteral(key: String): String? {
            val element = this[key] ?: return null
            if (element is JsonNull) return null
            val value = element.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content?.trim()
                ?: throw IllegalArgumentException()
            require(value.isNotEmpty() && value.length <= 128 && value.none(Character::isISOControl))
            return value
        }

        private fun requireKeys(value: JsonObject, allowed: Set<String>) {
            require(value.keys.all(allowed::contains))
        }

        private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}")
        private val EVENT_PLACEHOLDERS = setOf(
            "date", "fromDate", "toDate", "fromDateFormatted", "toDateFormatted", "leagueId", "sport",
        )
        private val SCORE_PLACEHOLDERS = setOf("eventId", "eventIds", "sport", "leagueId")
        private val STANDINGS_PLACEHOLDERS = setOf("leagueId", "season", "sport")
        private val TEAM_PLACEHOLDERS = setOf("teamId", "sport", "leagueId")
        private val LEAGUE_PLACEHOLDERS = setOf("leagueId", "sport")
        private val ARTWORK_PLACEHOLDERS = setOf("entityId", "entityType", "sport", "leagueId")
        private val DETAIL_PLACEHOLDERS = setOf("eventId", "sport", "leagueId")
        private const val MAX_DECLARED_SCOPES = 50
    }
}

internal data class CustomSportsProviderScope(
    val sport: String? = null,
    val leagueId: String? = null,
    val leagueName: String? = null,
)

internal data class CustomEventMapping(
    val endpoint: String,
    val dailyRequests: Boolean,
    val items: String,
    val id: String,
    val sport: String?,
    val sportValue: String?,
    val leagueName: String?,
    val leagueNameValue: String?,
    val leagueId: String?,
    val leagueIdValue: String?,
    val title: String?,
    val start: String,
    val startFormat: ProviderTimestampFormat,
    val dateFormat: CustomEventDateFormat,
    val homeName: String?,
    val homeId: String?,
    val awayName: String?,
    val awayId: String?,
    val competitors: CustomEventCompetitorMapping?,
    val competitorKind: ProviderCompetitorKind,
    val status: String?,
    val clock: String?,
    val period: String?,
    val broadcasters: CustomBroadcasterFieldsMapping?,
)

internal enum class CustomEventDateFormat(val formatter: DateTimeFormatter) {
    ISO_LOCAL_DATE(DateTimeFormatter.ISO_LOCAL_DATE),
    YYYYMMDD(DateTimeFormatter.BASIC_ISO_DATE),
}

internal data class CustomEventCompetitorMapping(
    val items: String,
    val name: String,
    val logo: String?,
    val id: String?,
    val role: String?,
    val homeValue: String,
    val awayValue: String,
)

internal data class CustomScoreMapping(
    val endpoint: String,
    val items: String,
    val eventId: String,
    val homeName: String?,
    val homeScore: String?,
    val awayName: String?,
    val awayScore: String?,
    val competitors: CustomScoreCompetitorMapping?,
    val status: String?,
    val clock: String?,
    val period: String?,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomScoreCompetitorMapping(
    val items: String,
    val name: String,
    val id: String?,
    val score: String?,
    val role: String?,
    val homeValue: String,
    val awayValue: String,
    val kind: ProviderCompetitorKind,
)

internal data class CustomStatusMapping(
    val endpoint: String,
    val items: String,
    val eventId: String,
    val status: String,
    val clock: String?,
    val period: String?,
    val detail: String?,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomStandingsMapping(
    val endpoint: String,
    val leagueName: String?,
    val leagueNameValue: String?,
    val leagueId: String?,
    val leagueIdValue: String?,
    val sport: String?,
    val sportValue: String?,
    val season: String?,
    val seasonValue: String?,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
    val groups: CustomStandingGroupsMapping,
)

internal data class CustomStandingGroupsMapping(
    val items: String,
    val name: String?,
    val nameValue: String?,
    val rows: CustomStandingRowsMapping,
)

internal data class CustomStandingRowsMapping(
    val items: String,
    val teamName: String,
    val teamId: String?,
    val kind: ProviderCompetitorKind,
    val rank: String?,
    val statistics: CustomStandingStatisticsMapping?,
)

internal data class CustomStandingStatisticsMapping(
    val items: String,
    val key: String,
    val label: String?,
    val value: String,
)

internal data class CustomTeamMetadataMapping(
    val endpoint: String,
    val items: String,
    val id: String?,
    val name: String,
    val shortName: String?,
    val country: String?,
    val kind: ProviderCompetitorKind,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomLeagueMetadataMapping(
    val endpoint: String,
    val items: String,
    val id: String?,
    val name: String?,
    val sport: String?,
    val country: String?,
    val season: String?,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomArtworkMapping(
    val endpoint: String,
    val items: String,
    val uri: String,
    val kind: String?,
    val kindValue: ProviderArtworkKind,
)

internal data class CustomBroadcasterMapping(
    val endpoint: String,
    val fields: CustomBroadcasterFieldsMapping,
)

internal data class CustomBroadcasterFieldsMapping(
    val items: String,
    val name: String,
    val country: String?,
    val language: String?,
    val type: String?,
    val typeValue: ProviderBroadcastType,
)

internal data class CustomLineupMapping(
    val endpoint: String,
    val items: String,
    val teamName: String?,
    val teamId: String?,
    val members: CustomLineupMembersMapping,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomLineupMembersMapping(
    val items: String,
    val name: String,
    val id: String?,
    val kind: ProviderCompetitorKind,
    val position: String?,
    val shirtNumber: String?,
    val starter: String?,
)

internal data class CustomStatisticsMapping(
    val endpoint: String,
    val groups: CustomStatisticGroupsMapping,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal data class CustomStatisticGroupsMapping(
    val items: String,
    val teamName: String?,
    val teamId: String?,
    val statistics: CustomStatisticsItemsMapping,
)

internal data class CustomStatisticsItemsMapping(
    val items: String,
    val key: String,
    val label: String?,
    val value: String,
    val period: String?,
)

internal object CustomSportsProviderJsonMapper {
    fun events(
        mapping: CustomEventMapping,
        body: JsonElement,
        sportFallback: String? = null,
        leagueIdFallback: String? = null,
        leagueNameFallback: String? = null,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> =
        mapItems(body, mapping.items, MAX_EVENT_ROWS) { record ->
            val eventId = record.scalar(mapping.id) ?: throw IllegalArgumentException()
            val start = record.scalar(mapping.start)?.let { parseProviderTimestamp(it, mapping.startFormat) }
                ?: throw IllegalArgumentException()
            val competitors = mapping.competitors?.let { competitorMapping ->
                eventCompetitors(record, competitorMapping, mapping.competitorKind)
            } ?: buildList {
                mapping.homeName?.let { record.scalar(it) }?.let { name ->
                    add(ProviderCompetitor(
                        name = name,
                        id = mapping.homeId?.let { record.scalar(it) },
                        kind = mapping.competitorKind,
                        role = ProviderCompetitorRole.HOME,
                    ))
                }
                mapping.awayName?.let { record.scalar(it) }?.let { name ->
                    add(ProviderCompetitor(
                        name = name,
                        id = mapping.awayId?.let { record.scalar(it) },
                        kind = mapping.competitorKind,
                        role = ProviderCompetitorRole.AWAY,
                    ))
                }
            }
            if (competitors.isEmpty()) throw IllegalArgumentException()
            val leagueName = mapping.leagueName?.let { record.scalar(it) }
                ?: mapping.leagueNameValue
                ?: leagueNameFallback
            val leagueId = mapping.leagueId?.let { record.scalar(it) } ?: mapping.leagueIdValue ?: leagueIdFallback
            val sport = mapping.sport?.let { record.scalar(it) } ?: mapping.sportValue ?: sportFallback
            ProviderEvent(
                providerEventId = eventId,
                sport = sport,
                league = if (leagueName != null || leagueId != null) ProviderLeague(leagueName, leagueId, sport) else null,
                title = mapping.title?.let { selector -> record.scalar(selector) },
                competitors = competitors,
                eventStart = start,
                status = mapping.status?.let { selector -> record.scalar(selector) }?.let { state ->
                    status(
                        state,
                        mapping.clock?.let { selector -> record.scalar(selector) },
                        mapping.period?.let { selector -> record.scalar(selector) },
                    )
                },
                broadcasters = mapping.broadcasters?.let { broadcasterFields ->
                    mapBroadcasterItems(record, broadcasterFields)
                }.orEmpty(),
            )
        }

    fun scores(
        mapping: CustomScoreMapping,
        body: JsonElement,
        requestedIds: Set<String>? = null,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> =
        mapItems(body, mapping.items, MAX_SCORE_ROWS) { record ->
            val eventId = record.scalar(mapping.eventId) ?: throw IllegalArgumentException()
            val updatedAt = mapping.updatedAt?.let { selector -> record.scalar(selector) }?.let {
                parseProviderTimestamp(it, mapping.updatedAtFormat)
            }
            val clock = mapping.clock?.let { selector -> record.scalar(selector) }
            val period = mapping.period?.let { selector -> record.scalar(selector) }
            val gameStatus = mapping.status?.let { selector -> record.scalar(selector) }
                ?.let { status(it, clock, period, updatedAt) }
            val values = buildList {
                mapping.competitors?.let { competitorMapping ->
                    addAll(scoreCompetitors(record, competitorMapping))
                }
                mapping.homeScore?.let { selector ->
                    record.scalar(selector)?.let { score ->
                        add(ProviderCompetitorScore(
                            ProviderCompetitor(
                                name = requireNotNull(mapping.homeName?.let { record.scalar(it) }),
                                role = ProviderCompetitorRole.HOME,
                            ),
                            score,
                        ))
                    }
                }
                mapping.awayScore?.let { selector ->
                    record.scalar(selector)?.let { score ->
                        add(ProviderCompetitorScore(
                            ProviderCompetitor(
                                name = requireNotNull(mapping.awayName?.let { record.scalar(it) }),
                                role = ProviderCompetitorRole.AWAY,
                            ),
                            score,
                        ))
                    }
                }
            }
            ProviderScore(eventId, values, gameStatus, updatedAt)
        }.filterIds(requestedIds, ProviderScore::providerEventId)

    fun statuses(
        mapping: CustomStatusMapping,
        body: JsonElement,
        requestedIds: Set<String>? = null,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> =
        mapItems(body, mapping.items, MAX_DETAIL_ROWS) { record ->
            val eventId = record.scalar(mapping.eventId) ?: throw IllegalArgumentException()
            val updatedAt = mapping.updatedAt?.let { record.scalar(it) }?.let {
                parseProviderTimestamp(it, mapping.updatedAtFormat)
            }
            val rawStatus = record.scalar(mapping.status) ?: throw IllegalArgumentException()
            val clock = mapping.clock?.let { record.scalar(it) }
            val period = mapping.period?.let { record.scalar(it) }
            ProviderEventStatus(
                eventId,
                status(rawStatus, clock, period, updatedAt, mapping.detail?.let { record.scalar(it) }),
            )
        }.filterIds(requestedIds, ProviderEventStatus::providerEventId)

    fun standings(
        mapping: CustomStandingsMapping,
        body: JsonElement,
        request: ProviderStandingsRequest,
        leagueNameFallback: String? = null,
    ): SportsProviderResult<ProviderStandings> {
        val groups = body.select(mapping.groups.items)?.records()
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        if (groups.size > MAX_STANDING_GROUPS) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }
        val mappedGroups = mutableListOf<ProviderStandingGroup>()
        for (group in groups) {
            val labelByKey = linkedMapOf<String, String?>()
            val rowsResult = mapItems(group, mapping.groups.rows.items, MAX_STANDING_ROWS) { row ->
                val competitorName = row.scalar(mapping.groups.rows.teamName) ?: throw IllegalArgumentException()
                val values = linkedMapOf<String, String?>()
                mapping.groups.rows.statistics?.let { statsMapping ->
                    row.select(statsMapping.items).records().take(MAX_STANDING_COLUMNS).forEach { stat ->
                        val key = stat.scalar(statsMapping.key) ?: return@forEach
                        val value = stat.scalar(statsMapping.value)
                        values.putIfAbsent(key, value)
                        labelByKey.putIfAbsent(key, statsMapping.label?.let { stat.scalar(it) })
                    }
                }
                ProviderStandingRow(
                    competitor = ProviderCompetitor(
                        name = competitorName,
                        id = mapping.groups.rows.teamId?.let { row.scalar(it) },
                        kind = mapping.groups.rows.kind,
                    ),
                    rank = mapping.groups.rows.rank?.let { row.scalar(it) }?.toIntOrNull()?.takeIf { it > 0 },
                    values = values,
                )
            }
            when (rowsResult) {
                is SportsProviderResult.Failure -> return rowsResult
                is SportsProviderResult.Success -> {
                    if (mappedGroups.sumOf { it.rows.size } + rowsResult.value.items.size > MAX_STANDING_TOTAL_ROWS) {
                        return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                    }
                    val columns = rowsResult.value.items.flatMap { it.values.keys }
                        .distinct().map { key -> ProviderStandingColumn(key, labelByKey[key]) }
                    mappedGroups += ProviderStandingGroup(
                        name = mapping.groups.name?.let { group.scalar(it) } ?: mapping.groups.nameValue,
                        columns = columns,
                        rows = rowsResult.value.items,
                    )
                }
            }
        }
        val leagueName = mapping.leagueName?.let { body.scalar(it) }
            ?: mapping.leagueNameValue
            ?: leagueNameFallback
        val leagueId = mapping.leagueId?.let { body.scalar(it) } ?: mapping.leagueIdValue ?: request.leagueId
        val sport = mapping.sport?.let { body.scalar(it) } ?: mapping.sportValue ?: request.sport
        val season = mapping.season?.let { body.scalar(it) } ?: mapping.seasonValue ?: request.season
        val updatedAt = mapping.updatedAt?.let { body.scalar(it) }?.let { parseProviderTimestamp(it, mapping.updatedAtFormat) }
        return SportsProviderResult.Success(
            ProviderStandings(
                league = ProviderLeague(leagueName, leagueId, sport),
                groups = mappedGroups,
                season = season,
                updatedAt = updatedAt,
            ),
        )
    }

    fun teamMetadata(
        mapping: CustomTeamMetadataMapping,
        body: JsonElement,
        request: ProviderTeamMetadataRequest,
    ): SportsProviderResult<ProviderTeamMetadata> {
        val item = body.singleObject(mapping.items)
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        val name = item.scalar(mapping.name)
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        val updatedAt = mapping.updatedAt?.let { item.scalar(it) }?.let { parseProviderTimestamp(it, mapping.updatedAtFormat) }
        return SportsProviderResult.Success(
            ProviderTeamMetadata(
                team = ProviderCompetitor(name, mapping.id?.let { item.scalar(it) } ?: request.teamId, mapping.kind),
                shortName = mapping.shortName?.let { item.scalar(it) },
                country = mapping.country?.let { item.scalar(it) },
                updatedAt = updatedAt,
            ),
        )
    }

    fun leagueMetadata(
        mapping: CustomLeagueMetadataMapping,
        body: JsonElement,
        request: ProviderLeagueMetadataRequest,
    ): SportsProviderResult<ProviderLeagueMetadata> {
        val item = body.singleObject(mapping.items)
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        val updatedAt = mapping.updatedAt?.let { item.scalar(it) }?.let { parseProviderTimestamp(it, mapping.updatedAtFormat) }
        return SportsProviderResult.Success(
            ProviderLeagueMetadata(
                league = ProviderLeague(
                    mapping.name?.let { item.scalar(it) },
                    mapping.id?.let { item.scalar(it) } ?: request.leagueId,
                    mapping.sport?.let { item.scalar(it) } ?: request.sport,
                ),
                country = mapping.country?.let { item.scalar(it) },
                season = mapping.season?.let { item.scalar(it) },
                updatedAt = updatedAt,
            ),
        )
    }

    fun broadcasters(
        mapping: CustomBroadcasterMapping,
        body: JsonElement,
    ): SportsProviderResult<ProviderBatch<ProviderBroadcaster>> =
        mapItems(body, mapping.fields.items, MAX_DETAIL_ROWS) { item ->
            mapBroadcaster(item, mapping.fields)
        }

    fun artwork(
        mapping: CustomArtworkMapping,
        body: JsonElement,
        entity: ProviderEntityRef,
    ): SportsProviderResult<ProviderBatch<ProviderArtwork>> =
        mapItems(body, mapping.items, MAX_DETAIL_ROWS) { item ->
            val rawUri = item.scalar(mapping.uri) ?: throw IllegalArgumentException()
            val kind = mapping.kind?.let { item.scalar(it) }?.let(::artworkKind) ?: mapping.kindValue
            ProviderArtwork(entity, java.net.URI(rawUri), kind)
        }

    fun lineups(
        mapping: CustomLineupMapping,
        body: JsonElement,
        eventId: String,
    ): SportsProviderResult<ProviderBatch<ProviderLineup>> {
        val groups = body.select(mapping.items)?.records()
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        if (groups.size > MAX_LINEUP_ROWS) return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        val result = mutableListOf<ProviderLineup>()
        var rejected = 0
        for (group in groups) {
            val membersResult = mapItems(group, mapping.members.items, MAX_LINEUP_MEMBERS) { member ->
                val name = member.scalar(mapping.members.name) ?: throw IllegalArgumentException()
                ProviderLineupMember(
                    competitor = ProviderCompetitor(
                        name = name,
                        id = mapping.members.id?.let { member.scalar(it) },
                        kind = mapping.members.kind,
                    ),
                    position = mapping.members.position?.let { member.scalar(it) },
                    shirtNumber = mapping.members.shirtNumber?.let { member.scalar(it) },
                    starter = mapping.members.starter?.let { member.scalar(it) }?.let(::parseBoolean),
                )
            }
            when (membersResult) {
                is SportsProviderResult.Failure -> return membersResult
                is SportsProviderResult.Success -> {
                    rejected += membersResult.value.rejectedItemCount
                    if (result.sumOf { it.members.size } + membersResult.value.items.size > MAX_LINEUP_TOTAL_MEMBERS) {
                        return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
                    }
                    val teamName = mapping.teamName?.let { group.scalar(it) }
                    val team = teamName?.let {
                        ProviderCompetitor(it, mapping.teamId?.let { group.scalar(it) }, ProviderCompetitorKind.TEAM)
                    }
                    result += ProviderLineup(
                        providerEventId = eventId,
                        team = team,
                        members = membersResult.value.items,
                        updatedAt = mapping.updatedAt?.let { group.scalar(it) }?.let {
                            parseProviderTimestamp(it, mapping.updatedAtFormat)
                        },
                    )
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(result, rejected))
    }

    fun statistics(
        mapping: CustomStatisticsMapping,
        body: JsonElement,
        eventId: String,
    ): SportsProviderResult<ProviderEventStatistics> {
        val groups = body.select(mapping.groups.items)?.records()
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        if (groups.size > MAX_STATISTIC_GROUPS) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }
        val statistics = mutableListOf<ProviderStatistic>()
        for (group in groups) {
            val team = mapping.groups.teamName?.let { group.scalar(it) }?.let { name ->
                ProviderCompetitor(
                    name = name,
                    id = mapping.groups.teamId?.let { group.scalar(it) },
                    kind = ProviderCompetitorKind.TEAM,
                )
            }
            val statRecords = group.select(mapping.groups.statistics.items)?.records()
                ?: continue
            if (statistics.size + statRecords.size > MAX_STATISTIC_ROWS) {
                return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
            }
            statRecords.forEach { record ->
                val key = record.scalar(mapping.groups.statistics.key) ?: return@forEach
                statistics += ProviderStatistic(
                    key = key,
                    value = mapping.groups.statistics.value.let { record.scalar(it) },
                    competitor = team,
                    label = mapping.groups.statistics.label?.let { record.scalar(it) },
                    period = mapping.groups.statistics.period?.let { record.scalar(it) },
                )
            }
        }
        val updatedAt = mapping.updatedAt?.let { body.scalar(it) }?.let { parseProviderTimestamp(it, mapping.updatedAtFormat) }
        return SportsProviderResult.Success(ProviderEventStatistics(eventId, statistics, updatedAt))
    }

    private fun mapBroadcasterItems(
        record: JsonObject,
        mapping: CustomBroadcasterFieldsMapping,
    ): List<ProviderBroadcaster> = record.select(mapping.items).records().mapNotNull { item ->
        runCatching { mapBroadcaster(item, mapping) }.getOrNull()
    }

    private fun mapBroadcaster(
        item: JsonObject,
        mapping: CustomBroadcasterFieldsMapping,
    ): ProviderBroadcaster {
        val name = item.scalar(mapping.name) ?: throw IllegalArgumentException()
        val type = mapping.type?.let { item.scalar(it) }?.let(::broadcastType) ?: mapping.typeValue
        return ProviderBroadcaster(
            name = name,
            country = mapping.country?.let { item.scalar(it) },
            language = mapping.language?.let { item.scalar(it) },
            type = type,
        )
    }

    private fun JsonElement.singleObject(selector: String): JsonObject? = when (val selected = select(selector)) {
        is JsonObject -> selected
        is JsonArray -> selected.singleOrNull() as? JsonObject
        else -> null
    }

    private fun broadcastType(value: String): ProviderBroadcastType = when (
        value.trim().lowercase().replace('-', '_').replace(' ', '_')
    ) {
        "tv", "television", "linear", "linear_tv", "broadcast" -> ProviderBroadcastType.LINEAR_TV
        "stream", "streaming", "streaming_service", "ott" -> ProviderBroadcastType.STREAMING_SERVICE
        "radio", "audio" -> ProviderBroadcastType.RADIO
        else -> ProviderBroadcastType.UNKNOWN
    }

    private fun artworkKind(value: String): ProviderArtworkKind =
        ProviderArtworkKind.entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: ProviderArtworkKind.OTHER

    private fun parseBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "starter" -> true
        "false", "0", "no", "bench" -> false
        else -> null
    }

    private fun <T : Any> mapItems(
        body: JsonElement,
        itemSelector: String,
        limit: Int,
        map: (JsonObject) -> T,
    ): SportsProviderResult<ProviderBatch<T>> {
        val selected = body.select(itemSelector) ?: return SportsProviderResult.Failure(
            SportsProviderError.INVALID_RESPONSE,
        )
        val records = when (selected) {
            is JsonArray -> selected
            is JsonObject -> JsonArray(listOf(selected))
            else -> return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        }
        if (records.size > limit) return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        return mapProviderItems(records, maxItems = limit) { value ->
            map(value.jsonObject)
        }
    }

    private fun <T : Any> SportsProviderResult<ProviderBatch<T>>.filterIds(
        requestedIds: Set<String>?,
        id: (T) -> String,
    ): SportsProviderResult<ProviderBatch<T>> = when (this) {
        is SportsProviderResult.Failure -> this
        is SportsProviderResult.Success -> if (requestedIds == null) this else SportsProviderResult.Success(
            ProviderBatch(value.items.filter { id(it) in requestedIds }, value.rejectedItemCount),
        )
    }

    private fun eventCompetitors(
        record: JsonObject,
        mapping: CustomEventCompetitorMapping,
        kind: ProviderCompetitorKind,
    ): List<ProviderCompetitor> = record.select(mapping.items).records().mapNotNull { competitor ->
        val name = competitor.scalar(mapping.name) ?: return@mapNotNull null
        ProviderCompetitor(
            name = name,
            id = mapping.id?.let { competitor.scalar(it) },
            kind = kind,
            role = role(mapping.role?.let { competitor.scalar(it) }, mapping.homeValue, mapping.awayValue),
            logoUrl = mapping.logo?.let { competitor.scalar(it) }?.takeIf { raw ->
                runCatching {
                    val uri = java.net.URI(raw)
                    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
                }.getOrDefault(false)
            },
        )
    }

    private fun scoreCompetitors(
        record: JsonObject,
        mapping: CustomScoreCompetitorMapping,
    ): List<ProviderCompetitorScore> {
        if (mapping.score == null) return emptyList()
        return record.select(mapping.items).records().mapNotNull { competitor ->
            val name = competitor.scalar(mapping.name) ?: return@mapNotNull null
            ProviderCompetitorScore(
                competitor = ProviderCompetitor(
                    name = name,
                    id = mapping.id?.let { competitor.scalar(it) },
                    kind = mapping.kind,
                    role = role(mapping.role?.let { competitor.scalar(it) }, mapping.homeValue, mapping.awayValue),
                ),
                value = competitor.scalar(mapping.score),
            )
        }
    }

    private fun role(value: String?, homeValue: String, awayValue: String): ProviderCompetitorRole = when {
        value.equals(homeValue, ignoreCase = true) -> ProviderCompetitorRole.HOME
        value.equals(awayValue, ignoreCase = true) -> ProviderCompetitorRole.AWAY
        else -> ProviderCompetitorRole.PARTICIPANT
    }

    private fun JsonElement?.records(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    private fun JsonElement.select(selector: String): JsonElement? {
        if (selector == "$") return this
        val segments = parseJsonSelector(selector, allowRoot = false)
        var current = listOf(this)
        for (segment in segments) {
            val next = mutableListOf<JsonElement>()
            current.forEach { value ->
                when (segment) {
                    is JsonSelectorSegment.Key -> (value as? JsonObject)?.get(segment.value)?.let(next::add)
                    is JsonSelectorSegment.Index -> (value as? JsonArray)?.getOrNull(segment.value)?.let(next::add)
                    JsonSelectorSegment.All -> (value as? JsonArray)?.forEach(next::add)
                }
            }
            if (next.isEmpty()) return null
            current = next
        }
        return if (segments.any { it == JsonSelectorSegment.All }) JsonArray(current) else current.singleOrNull()
    }

    private fun JsonElement.scalar(selector: String): String? =
        select(selector)?.let { value ->
            val primitive = value as? JsonPrimitive ?: return@let null
            if (primitive is JsonNull || primitive.content.isBlank() || primitive.content.length > MAX_SCALAR_CHARS) {
                null
            } else {
                primitive.content
            }
        }

    private fun status(
        value: String,
        clock: String?,
        period: String?,
        updatedAt: Instant? = null,
        detail: String? = null,
    ): ProviderGameStatus {
        val state = when (value.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "scheduled", "not_started", "pending", "pre_game", "pre" -> ProviderGameState.SCHEDULED
            "live", "in", "in_play", "in_progress", "inprogress", "halftime", "half_time" -> ProviderGameState.LIVE
            "suspended" -> ProviderGameState.SUSPENDED
            "delayed", "delay" -> ProviderGameState.DELAYED
            "postponed" -> ProviderGameState.POSTPONED
            "cancelled", "canceled" -> ProviderGameState.CANCELLED
            "final", "finished", "complete", "completed", "post" -> ProviderGameState.FINAL
            else -> ProviderGameState.UNKNOWN
        }
        return ProviderGameStatus(
            state, clock, period,
            detail = detail ?: value.takeIf { state == ProviderGameState.UNKNOWN },
            updatedAt = updatedAt,
        )
    }

    private const val MAX_EVENT_ROWS = 2_000
    private const val MAX_SCORE_ROWS = 1_000
    private const val MAX_DETAIL_ROWS = 1_000
    private const val MAX_STANDING_ROWS = 1_000
    private const val MAX_STANDING_TOTAL_ROWS = 5_000
    private const val MAX_STANDING_GROUPS = 100
    private const val MAX_STANDING_COLUMNS = 100
    private const val MAX_LINEUP_ROWS = 100
    private const val MAX_LINEUP_MEMBERS = 2_000
    private const val MAX_LINEUP_TOTAL_MEMBERS = 5_000
    private const val MAX_STATISTIC_GROUPS = 100
    private const val MAX_STATISTIC_ROWS = 5_000
    private const val MAX_SCALAR_CHARS = 512
}

private sealed interface JsonSelectorSegment {
    data class Key(val value: String) : JsonSelectorSegment
    data class Index(val value: Int) : JsonSelectorSegment
    data object All : JsonSelectorSegment
}

private fun parseJsonSelector(selector: String, allowRoot: Boolean): List<JsonSelectorSegment> {
    if (allowRoot && selector == "$") return emptyList()
    val path = selector.removePrefix("$.")
    require(path.isNotBlank())
    val pathPart = Regex("([A-Za-z0-9_-]{1,64})(?:\\[(\\*|\\d{1,4})\\])?")
    val segments = mutableListOf<JsonSelectorSegment>()
    for (part in path.split('.')) {
        val match = pathPart.matchEntire(part) ?: throw IllegalArgumentException()
        segments += JsonSelectorSegment.Key(match.groupValues[1])
        when (val index = match.groupValues[2]) {
            "*" -> segments += JsonSelectorSegment.All
            "" -> Unit
            else -> segments += JsonSelectorSegment.Index(index.toInt())
        }
        require(segments.size <= MAX_SELECTOR_DEPTH)
    }
    return segments
}

private const val MAX_SELECTOR_DEPTH = 16

/** Resolve paths beneath the configured API base path; dynamic values are percent encoded. */
internal fun buildCustomSportsProviderUrl(
    baseUrl: HttpUrl,
    endpointTemplate: String,
    variables: Map<String, String>,
): HttpUrl? = runCatching {
    require(baseUrl.isHttps && baseUrl.username.isEmpty() && baseUrl.password.isEmpty())
    require(endpointTemplate.startsWith('/') && !endpointTemplate.startsWith("//"))
    require('#' !in endpointTemplate && '\\' !in endpointTemplate && '\r' !in endpointTemplate && '\n' !in endpointTemplate)
    val pathTemplate = endpointTemplate.substringBefore('?')
    for (requiredPathVariable in listOf("sport", "leagueId")) {
        if ("{$requiredPathVariable}" in pathTemplate) {
            require(!variables[requiredPathVariable].isNullOrBlank())
        }
    }
    val expanded = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").replace(endpointTemplate) { match ->
        val value = requireNotNull(variables[match.groupValues[1]])
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
    require('{' !in expanded && '}' !in expanded)
    val basePath = baseUrl.encodedPath.trimEnd('/').ifEmpty { "" }
    val pathPrefix = "$basePath/"
    val baseDirectory = baseUrl.newBuilder().encodedPath(pathPrefix).build()
    val result = baseDirectory.resolve(expanded.removePrefix("/")) ?: throw IllegalArgumentException()
    require(result.isHttps && result.host == baseUrl.host && result.port == baseUrl.port)
    require(result.encodedPath.startsWith(pathPrefix))
    require(result.username.isEmpty() && result.password.isEmpty() && result.fragment == null)
    result
}.getOrNull()
