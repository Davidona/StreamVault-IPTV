package com.streamvault.data.sports.highlightly

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.data.sports.provider.SportsProviderResponseMetadata
import com.streamvault.domain.sports.provider.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HighlightlyProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val cache: HighlightlyResponseCache,
    private val quotaTracker: HighlightlyQuotaTracker,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
            SportsCapability.TEAM_METADATA,
            SportsCapability.LEAGUE_METADATA,
        ),
        auth = SportsProviderAuth.ApiKeyHeader("x-rapidapi-key"),
        defaultBaseUrl = BASE_URL,
        displayName = "Highlightly",
        isBeta = true,
        compliance = ProviderComplianceInfo(
            termsUrl = "https://highlightly.net/terms/",
            documentationUrl = "https://highlightly.net/sport-api/documentation/",
            reviewedAt = LocalDate.of(2026, 10, 17),
            appStoreAllowed = true,
            commercialUseStatus = CommercialUseStatus.PERMITTED,
            artworkStatus = ArtworkUseStatus.DISABLED,
            releaseGate = ProviderReleaseGate.APPROVED,
        ),
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> {
        val scopes = config.eventLeagueIds.map { parseHighlightlyLeagueScope(it) ?: return emptySet() }
        val routes = scopes.map(LeagueScope::route).ifEmpty { HighlightlyRoutes.ALL }
        return buildSet {
            add(SportsCapability.EVENTS)
            add(SportsCapability.LIVE_SCORES)
            add(SportsCapability.GAME_STATUS)
            add(SportsCapability.STANDINGS)
            add(SportsCapability.TEAM_METADATA)
            if (routes.any(HighlightlyRoute::supportsLeagueMetadata)) {
                add(SportsCapability.LEAGUE_METADATA)
            }
        }
    }

    override fun create(config: SportsProviderConfig): SportsProvider = HighlightlyProvider(
        config = config,
        descriptor = descriptor,
        store = store,
        httpClient = httpClient,
        cache = cache,
        quotaTracker = quotaTracker,
    )

    companion object {
        const val TYPE_ID = "highlightly"
        const val BASE_URL = "https://sports.highlightly.net"
    }
}

/** A direct Highlightly All Sports account uses one allowance across all prefixes. */
@Singleton
class HighlightlyQuotaTracker @Inject constructor() {
    private val snapshots = LinkedHashMap<String, SportsProviderUsageSnapshot>(8, 0.75f, true)

    @Synchronized
    fun snapshot(accountFingerprint: String): SportsProviderUsageSnapshot? = snapshots[accountFingerprint]

    @Synchronized
    fun recordHeaders(
        accountFingerprint: String,
        metadata: SportsProviderResponseMetadata,
        now: Instant,
    ) {
        if (metadata.requestLimit == null && metadata.requestsRemaining == null) return
        val previous = snapshots[accountFingerprint] ?: SportsProviderUsageSnapshot()
        snapshots[accountFingerprint] = previous.copy(
            requestLimit = metadata.requestLimit ?: previous.requestLimit,
            requestsRemaining = metadata.requestsRemaining ?: previous.requestsRemaining,
            observedAt = now,
        )
        trim()
    }

    @Synchronized
    fun recordPlanTier(accountFingerprint: String, planTier: String, now: Instant) {
        val safeTier = planTier.trim().takeIf { it.matches(PLAN_TIER) } ?: return
        val previous = snapshots[accountFingerprint] ?: SportsProviderUsageSnapshot()
        snapshots[accountFingerprint] = previous.copy(planTier = safeTier, observedAt = now)
        trim()
    }

    private fun trim() {
        while (snapshots.size > MAX_ACCOUNTS) snapshots.entries.iterator().run { next(); remove() }
    }

    private companion object {
        val PLAN_TIER = Regex("[A-Za-z0-9 _.-]{1,40}")
        const val MAX_ACCOUNTS = 64
    }
}

@Singleton
class HighlightlyResponseCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Instant)
    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, now: Instant): T? {
        val entry = entries[key] ?: return null
        if (!now.isBefore(entry.expiresAt)) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    @Synchronized
    fun put(key: String, value: Any, ttl: Duration, now: Instant) {
        require(!ttl.isNegative && !ttl.isZero)
        entries[key] = Entry(value, now.plus(ttl))
        while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
    }

    private companion object { const val MAX_ENTRIES = 512 }
}

internal class HighlightlyProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val store: SportsProviderStore,
    private val httpClient: SportsProviderHttpClient,
    private val cache: HighlightlyResponseCache,
    private val quotaTracker: HighlightlyQuotaTracker,
    private val endpointBaseUrl: String = HighlightlyProviderFactory.BASE_URL,
    private val allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : EventProvider, LiveScoreProvider, GameStatusProvider, StandingsProvider,
    TeamMetadataProvider, LeagueMetadataProvider, SportsProviderConnectionTester,
    SportsProviderUsageProvider {
    override val instanceId: String = config.instanceId

    override fun usageSnapshot(credentials: SportsProviderCredentials?): SportsProviderUsageSnapshot? =
        credentials?.secret?.takeIf(::validSecret)?.let(::fingerprint)?.let(quotaTracker::snapshot)

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> {
        val secret = credentials?.secret?.takeIf(::validSecret) ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(secret)
        val scopes = configuredScopes() ?: return invalidConfiguration()
        val (route, scope) = scopes.firstOrNull()
            ?: (HighlightlyRoutes.BY_PREFIX.getValue("football") to null)
        val uri = matchesUrl(route, scope, clock.instant().atOffset(ZoneOffset.UTC).toLocalDate(), 0)
        return when (val response = getJson(uri, credentials, accountFingerprint)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val envelope = response.value.asObjectOrNull() ?: return invalidResponse()
                recordPlan(envelope, accountFingerprint)
                if (envelope.array("data") == null || envelope.obj("pagination") == null) {
                    invalidResponse()
                } else SportsProviderResult.Success(Unit)
            }
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val fromDate = request.from.atOffset(ZoneOffset.UTC).toLocalDate()
        val untilDate = request.until.minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate()
        val days = Duration.between(fromDate.atStartOfDay().toInstant(ZoneOffset.UTC),
            untilDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)).toDays().toInt()
        if (days !in 1..MAX_EVENT_DAYS) return responseTooLarge()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(credentials.secret)
        val scopes = scopesForRequest(request) ?: return invalidConfiguration()
        if (scopes.isEmpty() || scopes.size * days > MAX_EVENT_HTTP_REQUESTS) return responseTooLarge()

        val snapshots = mutableListOf<GameSnapshot>()
        var rejected = 0
        val budget = RequestBudget(MAX_EVENT_HTTP_REQUESTS)
        for ((route, scope) in scopes) {
            var date = fromDate
            while (!date.isAfter(untilDate)) {
                val key = cacheKey("matches", "${route.prefix}|${scope?.leagueId.orEmpty()}|$date", credentials)
                val cached = cache.get<ProviderBatch<GameSnapshot>>(key, clock.instant())
                val batch = cached ?: when (val response = pagedMatches(
                    route = route,
                    scope = scope,
                    date = date,
                    from = request.from,
                    until = request.until,
                    credentials = credentials,
                    accountFingerprint = accountFingerprint,
                    budget = budget,
                )) {
                    is SportsProviderResult.Failure -> return response
                    is SportsProviderResult.Success -> response.value.also {
                        cache.put(key, it, EVENT_CACHE_TTL, clock.instant())
                    }
                }
                snapshots += batch.items
                rejected += batch.rejectedItemCount
                batch.items.forEach { snapshot ->
                    cache.put(cacheKey("match", snapshot.event.providerEventId, credentials),
                        snapshot, MATCH_CACHE_TTL, clock.instant())
                }
                if (snapshots.size + rejected > MAX_MAPPED_EVENTS) return responseTooLarge()
                date = date.plusDays(1)
            }
        }
        return SportsProviderResult.Success(ProviderBatch(
            items = snapshots.distinctBy { it.event.providerEventId }.map(GameSnapshot::event),
            rejectedItemCount = rejected,
        ))
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> {
        val snapshots = when (val result = loadMatchesForIds(request.eventIds)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        val now = clock.instant()
        return SportsProviderResult.Success(ProviderBatch(snapshots.map { snapshot ->
            ProviderScore(snapshot.event.providerEventId, snapshot.scores, snapshot.event.status, now)
        }))
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> {
        val snapshots = when (val result = loadMatchesForIds(request.eventIds)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        return SportsProviderResult.Success(ProviderBatch(snapshots.mapNotNull { snapshot ->
            snapshot.event.status?.let { ProviderEventStatus(snapshot.event.providerEventId, it) }
        }))
    }

    override suspend fun standings(
        request: ProviderStandingsRequest,
    ): SportsProviderResult<ProviderStandings> {
        val scope = parseLeagueScope(request.leagueId) ?: return invalidConfiguration()
        if (!scope.route.supportsStandings) return invalidConfiguration()
        val season = request.season?.takeIf { YEAR.matches(it) }
            ?: currentSeasonYear(clock.instant()).toString()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(credentials.secret)
        val key = cacheKey("standings", "${scope.route.prefix}|${scope.leagueId}|$season", credentials)
        cache.get<ProviderStandings>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        val uri = standingsUrl(scope, season) ?: return invalidConfiguration()
        return when (val response = getJson(uri, credentials, accountFingerprint)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                response.value.asObjectOrNull()?.let { recordPlan(it, accountFingerprint) }
                when (val mapped = mapStandings(response.value, scope, season)) {
                    is SportsProviderResult.Failure -> mapped
                    is SportsProviderResult.Success -> mapped.also {
                        cache.put(key, it.value, STANDINGS_CACHE_TTL, clock.instant())
                    }
                }
            }
        }
    }

    override suspend fun teamMetadata(
        request: ProviderTeamMetadataRequest,
    ): SportsProviderResult<ProviderTeamMetadata> {
        val (route, rawId) = parseEntityId(request.teamId) ?: return invalidConfiguration()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(credentials.secret)
        val key = cacheKey("team", request.teamId, credentials)
        cache.get<ProviderTeamMetadata>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        return when (val response = getJson(entityUrl(route, "teams", rawId), credentials, accountFingerprint)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                response.value.asObjectOrArrayEntities()?.singleOrNull()?.let { row ->
                    val id = row.text("id") ?: rawId
                    val name = row.firstText("name", "displayName", "fullName", "shortName")
                    if (id != rawId || name.isNullOrBlank()) return invalidResponse()
                    SportsProviderResult.Success(ProviderTeamMetadata(
                        team = ProviderCompetitor(name, "${route.prefix}:$id", ProviderCompetitorKind.TEAM),
                        shortName = row.firstText("abbreviation", "shortName"),
                        country = row.obj("country")?.text("name") ?: row.text("country"),
                    )).also { cache.put(key, it.value, METADATA_CACHE_TTL, clock.instant()) }
                } ?: invalidResponse()
            }
        }
    }

    override suspend fun leagueMetadata(
        request: ProviderLeagueMetadataRequest,
    ): SportsProviderResult<ProviderLeagueMetadata> {
        val scope = parseLeagueScope(request.leagueId) ?: return invalidConfiguration()
        val leagueId = scope.leagueId ?: return invalidConfiguration()
        if (!scope.route.supportsLeagueMetadata || !NUMERIC_ID.matches(leagueId)) {
            return invalidConfiguration()
        }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(credentials.secret)
        val key = cacheKey("league", request.leagueId, credentials)
        cache.get<ProviderLeagueMetadata>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        return when (val response = getJson(
            entityUrl(scope.route, "leagues", leagueId), credentials, accountFingerprint,
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                response.value.asObjectOrArrayEntities()?.singleOrNull()?.let { row ->
                    val id = row.text("id") ?: leagueId
                    val name = row.text("name")
                    if (id != leagueId || name.isNullOrBlank()) return invalidResponse()
                    val seasons = row.array("seasons")?.mapNotNull { it.asObjectOrNull()?.let { season ->
                        season.firstText("season", "name", "year")
                    } }.orEmpty()
                    val country = row.obj("country")?.text("name") ?: row.text("country")
                    SportsProviderResult.Success(ProviderLeagueMetadata(
                        league = ProviderLeague(name, "${scope.route.prefix}:$id", scope.route.sportName),
                        country = country,
                        season = seasons.lastOrNull(),
                    )).also { cache.put(key, it.value, METADATA_CACHE_TTL, clock.instant()) }
                } ?: invalidResponse()
            }
        }
    }

    private suspend fun pagedMatches(
        route: HighlightlyRoute,
        scope: LeagueScope?,
        date: LocalDate,
        from: Instant,
        until: Instant,
        credentials: SportsProviderCredentials,
        accountFingerprint: String,
        budget: RequestBudget,
    ): SportsProviderResult<ProviderBatch<GameSnapshot>> {
        var offset = 0
        var expectedTotal: Int? = null
        var requestedPages = 0
        val items = mutableListOf<GameSnapshot>()
        var rejected = 0
        while (requestedPages < MAX_EVENT_PAGES) {
            if (!budget.consume()) return responseTooLarge()
            val uri = matchesUrl(route, scope, date, offset)
            val envelope = when (val response = getJson(uri, credentials, accountFingerprint)) {
                is SportsProviderResult.Failure -> return response
                is SportsProviderResult.Success -> response.value.asObjectOrNull() ?: return invalidResponse()
            }
            recordPlan(envelope, accountFingerprint)
            val rows = envelope.array("data") ?: return invalidResponse()
            val pagination = envelope.obj("pagination") ?: return invalidResponse()
            val total = pagination.int("totalCount") ?: return invalidResponse()
            val pageOffset = pagination.int("offset") ?: offset
            val pageLimit = pagination.int("limit")?.takeIf { it > 0 } ?: PAGE_SIZE
            if (total < 0 || pageOffset != offset || pageLimit > PAGE_SIZE ||
                (expectedTotal != null && total != expectedTotal)
            ) return invalidResponse()
            expectedTotal = total
            val mapped = mapProviderItems(rows, maxItems = PAGE_SIZE) { element ->
                mapMatch(element, route, scope, date, from, until)
            }
            when (mapped) {
                is SportsProviderResult.Failure -> return mapped
                is SportsProviderResult.Success -> {
                    items += mapped.value.items
                    rejected += mapped.value.rejectedItemCount
                }
            }
            requestedPages++
            if (offset + pageLimit >= total) {
                return SportsProviderResult.Success(ProviderBatch(items, rejected))
            }
            if (rows.size() == 0) return invalidResponse()
            val nextOffset = offset + pageLimit
            if (nextOffset <= offset) return invalidResponse()
            offset = nextOffset
        }
        return if (expectedTotal == null || offset >= expectedTotal) {
            SportsProviderResult.Success(ProviderBatch(items, rejected))
        } else responseTooLarge()
    }

    private suspend fun loadMatchesForIds(ids: Set<String>): SportsProviderResult<List<GameSnapshot>> {
        if (ids.size > MAX_SCORE_EVENTS) return responseTooLarge()
        val parsed = ids.map { parseEventId(it) ?: return invalidConfiguration() }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val accountFingerprint = fingerprint(credentials.secret)
        val results = mutableListOf<GameSnapshot>()
        for ((route, rawId) in parsed) {
            val eventId = "${route.prefix}:$rawId"
            val key = cacheKey("match", eventId, credentials)
            val snapshot = cache.get<GameSnapshot>(key, clock.instant()) ?: when (
                val response = getJson(entityUrl(route, "matches", rawId), credentials, accountFingerprint)
            ) {
                is SportsProviderResult.Failure -> return response
                is SportsProviderResult.Success -> {
                    response.value.asObjectOrArrayEntities()?.singleOrNull()?.let { row ->
                        runCatching { mapMatch(row, route, null, null, Instant.MIN, Instant.MAX) }
                            .getOrNull()
                    }?.takeIf { it.event.providerEventId == eventId }?.also {
                        cache.put(key, it, MATCH_CACHE_TTL, clock.instant())
                    } ?: return invalidResponse()
                }
            }
            results += snapshot
        }
        return SportsProviderResult.Success(results)
    }

    private fun mapMatch(
        element: JsonElement,
        route: HighlightlyRoute,
        scope: LeagueScope?,
        requestedDate: LocalDate?,
        from: Instant,
        until: Instant,
    ): GameSnapshot {
        val row = element.asJsonObject
        val rawId = row.text("id")?.takeIf(NUMERIC_ID::matches) ?: throw IllegalArgumentException()
        val parsedStart = parseMatchStart(row)
        val actualDate = parsedStart?.date ?: throw IllegalArgumentException()
        if (requestedDate != null && actualDate != requestedDate) throw IllegalArgumentException()
        if (requestedDate != null && parsedStart.instant != null &&
            (parsedStart.instant.isBefore(from) || !parsedStart.instant.isBefore(until))
        ) throw IllegalArgumentException()

        val home = row.firstObject("homeTeam", "home_team", "home")
            ?.let { mapCompetitor(it, route, ProviderCompetitorRole.HOME) }
        val away = row.firstObject("awayTeam", "away_team", "visitorTeam", "away", "visitor")
            ?.let { mapCompetitor(it, route, ProviderCompetitorRole.AWAY) }
        val competitors = listOfNotNull(home, away)
        if (competitors.isEmpty()) throw IllegalArgumentException()

        val leagueObject = row.firstObject("league", "competition")
        val rawLeagueValue = row.get("league")?.takeIf { !it.isJsonNull && !it.isJsonObject }
        val leagueKey = scope?.leagueId ?: leagueObject?.firstText("id", "leagueId")
            ?: rawLeagueValue?.let(JsonElement::safeText)
            ?: leagueObject?.text("name")?.let(::stableLeagueKey)
        val leagueName = leagueObject?.firstText("name", "leagueName")
            ?: rawLeagueValue?.safeText()
            ?: row.text("leagueName")
        val event = ProviderEvent(
            providerEventId = "${route.prefix}:$rawId",
            sport = route.sportName,
            league = if (leagueName != null || leagueKey != null) {
                ProviderLeague(leagueName, leagueKey?.let { "${route.prefix}:$it" }, route.sportName)
            } else null,
            title = row.firstText("name", "shortName", "round", "stage")
                ?: competitors.joinToString(" vs ") { it.name },
            competitors = competitors,
            eventStart = parsedStart.instant,
            status = row.obj("state")?.let(::mapStatus),
            session = row.firstText("round", "week", "stage", "dayType"),
        )
        return GameSnapshot(event, mapScores(row, event, home, away))
    }

    private fun mapCompetitor(
        row: JsonObject,
        route: HighlightlyRoute,
        role: ProviderCompetitorRole,
    ): ProviderCompetitor? {
        val name = row.firstText("name", "displayName", "fullName", "shortName", "abbreviation")
            ?: return null
        val id = row.firstText("id", "teamId")?.takeIf(NUMERIC_ID::matches)
        return ProviderCompetitor(name, id?.let { "${route.prefix}:$it" }, ProviderCompetitorKind.TEAM, role)
    }

    private fun mapScores(
        match: JsonObject,
        event: ProviderEvent,
        home: ProviderCompetitor?,
        away: ProviderCompetitor?,
    ): List<ProviderCompetitorScore> {
        val state = match.obj("state") ?: return emptyList()
        val score = state.obj("score")
        val direct = score?.firstText("current", "score", "displayValue")
        val pair = direct?.let(::splitScore)
        if (pair != null) return listOfNotNull(
            home?.let { ProviderCompetitorScore(it, pair.first) },
            away?.let { ProviderCompetitorScore(it, pair.second) },
        )

        val homeValue = score?.firstElementText("homeTeam", "home", "homeScore")
            ?: state.obj("teams")?.obj("home")?.firstElementText("score", "info")
        val awayValue = score?.firstElementText("awayTeam", "away", "awayScore")
            ?: state.obj("teams")?.obj("away")?.firstElementText("score", "info")
        return listOfNotNull(
            home?.let { homeValue?.let { value -> ProviderCompetitorScore(it, value) } },
            away?.let { awayValue?.let { value -> ProviderCompetitorScore(it, value) } },
        )
    }

    private fun splitScore(value: String): Pair<String, String>? {
        val match = SCORE_PAIR.matchEntire(value.trim()) ?: return null
        return match.groupValues[1].trim().takeIf(String::isNotEmpty)
            ?.let { home -> match.groupValues[2].trim().takeIf(String::isNotEmpty)?.let { away -> home to away } }
    }

    private fun mapStatus(state: JsonObject): ProviderGameStatus? {
        val detail = state.text("description") ?: state.text("status") ?: return null
        val normalized = detail.trim().lowercase(Locale.ROOT).replace('_', ' ')
        val gameState = when {
            normalized in SCHEDULED_STATES -> ProviderGameState.SCHEDULED
            normalized in FINAL_STATES -> ProviderGameState.FINAL
            normalized in POSTPONED_STATES -> ProviderGameState.POSTPONED
            normalized in CANCELLED_STATES -> ProviderGameState.CANCELLED
            normalized in SUSPENDED_STATES -> ProviderGameState.SUSPENDED
            normalized.contains("delay") -> ProviderGameState.DELAYED
            normalized in LIVE_STATES || normalized.contains("in progress") || normalized.contains("in play") ->
                ProviderGameState.LIVE
            else -> ProviderGameState.UNKNOWN
        }
        return ProviderGameStatus(
            state = gameState,
            clock = state.text("clock"),
            period = state.text("period"),
            detail = detail,
        )
    }

    private fun parseMatchStart(row: JsonObject): ParsedMatchStart? {
        val direct = row.firstText("date", "datetime", "startDate", "start_date")
        if (direct != null) {
            parseOffsetInstant(direct)?.let { instant ->
                return ParsedMatchStart(instant.atOffset(ZoneOffset.UTC).toLocalDate(), instant)
            }
            parseLocalDateTime(direct)?.let { instant ->
                return ParsedMatchStart(instant.atOffset(ZoneOffset.UTC).toLocalDate(), instant)
            }
            parseLocalDate(direct)?.let { date ->
                val timeText = row.firstText("startTime", "start_time")
                val instant = timeText?.let { value ->
                    runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant() }
                        .getOrElse {
                            runCatching { LocalTime.parse(value).atDate(date).toInstant(ZoneOffset.UTC) }.getOrNull()
                        }
                }
                return ParsedMatchStart(instant?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: date, instant)
            }
        }
        val date = row.firstText("startDate", "start_date", "date")?.let(::parseLocalDate)
            ?: return null
        val timeText = row.firstText("startTime", "start_time") ?: return ParsedMatchStart(date, null)
        val instant = runCatching {
            OffsetDateTime.parse(timeText, DateTimeFormatter.ISO_DATE_TIME).toInstant()
        }.getOrElse {
            runCatching { LocalTime.parse(timeText).atDate(date).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        return ParsedMatchStart(instant?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: date, instant)
    }

    private fun matchesUrl(route: HighlightlyRoute, scope: LeagueScope?, date: LocalDate, offset: Int): HttpUrl =
        routeUrl(route, "matches").newBuilder()
            .addQueryParameter("date", date.toString())
            .addQueryParameter("timezone", "Etc/UTC")
            .apply {
                scope?.let { addQueryParameter(route.leagueFilterParameter, it.leagueId) }
                addQueryParameter("limit", PAGE_SIZE.toString())
                addQueryParameter("offset", offset.toString())
            }
            .build()

    private fun standingsUrl(scope: LeagueScope, season: String): HttpUrl? = runCatching {
        val leagueId = requireNotNull(scope.leagueId)
        routeUrl(scope.route, "standings").newBuilder().apply {
            if (scope.route.numericLeagueId) {
                addQueryParameter("leagueId", leagueId)
                addQueryParameter("season", season)
            } else {
                addQueryParameter("leagueName", leagueId)
                addQueryParameter("year", season)
                addQueryParameter("limit", PAGE_SIZE.toString())
                addQueryParameter("offset", "0")
            }
        }.build()
    }.getOrNull()

    private fun mapStandings(
        root: JsonElement,
        scope: LeagueScope,
        season: String,
    ): SportsProviderResult<ProviderStandings> {
        val topObjects = root.asObjectOrArrayEntities() ?: return invalidResponse()
        val records = mutableListOf<StandingRecord>()
        for (top in topObjects) collectStandingRecords(top, null, records, 0)
        if (records.size > MAX_STANDING_ROWS) return responseTooLarge()
        val mappedRows = mapProviderItems(records, maxItems = MAX_STANDING_ROWS) { record ->
            MappedStandingRecord(
                groupName = record.groupName ?: groupName(record.row),
                row = mapStandingRow(record.row, scope.route.prefix),
            )
        }
        if (mappedRows is SportsProviderResult.Failure) return mappedRows
        val rows = (mappedRows as SportsProviderResult.Success).value.items
        val mappedWithGroups = rows.groupBy(MappedStandingRecord::groupName)
        val groups = mappedWithGroups.toList()
            .sortedWith(compareBy(nullsFirst()) { it.first })
            .map { (name, entries) ->
                val groupRows = entries.map(MappedStandingRecord::row)
                val columns = groupRows.flatMap { it.values.keys }.distinct().map(::ProviderStandingColumn)
                ProviderStandingGroup(
                    name = name,
                    columns = columns,
                    rows = groupRows.sortedWith(
                        compareBy<ProviderStandingRow> { it.rank ?: Int.MAX_VALUE }
                            .thenBy { it.competitor.name.lowercase(Locale.ROOT) },
                    ),
                )
            }
        val scopeLeagueId = requireNotNull(scope.leagueId)
        val name = topObjects.asSequence().mapNotNull(::standingsLeagueName).firstOrNull()
            ?: scopeLeagueId.replace('_', ' ').replace('-', ' ')
        val sourceSeason = topObjects.asSequence().mapNotNull(::standingsSeason).firstOrNull() ?: season
        val updatedAt = topObjects.asSequence().mapNotNull { it.firstText("updatedAt", "updated_at") }
            .mapNotNull(::parseOffsetInstant).firstOrNull()
        return SportsProviderResult.Success(ProviderStandings(
            league = ProviderLeague(name, "${scope.route.prefix}:$scopeLeagueId", scope.route.sportName),
            groups = groups,
            season = sourceSeason,
            updatedAt = updatedAt,
        ))
    }

    private fun collectStandingRecords(
        obj: JsonObject,
        inheritedGroup: String?,
        result: MutableList<StandingRecord>,
        depth: Int,
    ) {
        if (depth > MAX_STANDING_DEPTH || result.size >= MAX_STANDING_ROWS) return
        val groups = obj.array("groups")
        if (groups != null) {
            for (group in groups) {
                val groupObject = group.asObjectOrNull() ?: continue
                val name = groupObject.firstText("name", "groupName", "conference", "division") ?: inheritedGroup
                val rows = groupObject.array("standings")
                if (rows != null) {
                    rows.forEach { item -> item.asObjectOrNull()?.let { result += StandingRecord(name, it) } }
                } else collectStandingRecords(groupObject, name, result, depth + 1)
            }
            return
        }
        val data = obj.array("data")
        if (data != null) {
            val group = inheritedGroup ?: groupName(obj)
            for (item in data) {
                val child = item.asObjectOrNull() ?: continue
                if (isStandingRow(child)) result += StandingRecord(group, child)
                else collectStandingRecords(child, group, result, depth + 1)
            }
            return
        }
        if (isStandingRow(obj)) result += StandingRecord(inheritedGroup ?: groupName(obj), obj)
    }

    private fun isStandingRow(obj: JsonObject): Boolean {
        val team = obj.firstObject("team", "competitor")
        val hasTeam = team?.firstText("name", "displayName", "fullName", "shortName") != null ||
            (obj.text("id") != null && obj.firstText("name", "displayName", "fullName") != null)
        return hasTeam && (obj.has("statistics") || obj.has("stats") || obj.has("total") ||
            obj.has("position") || obj.has("rank") || obj.has("points") || obj.has("wins"))
    }

    private fun mapStandingRow(obj: JsonObject, prefix: String): ProviderStandingRow {
        val team = obj.firstObject("team", "competitor") ?: obj
        val name = team.firstText("name", "displayName", "fullName", "shortName", "abbreviation")
            ?: throw IllegalArgumentException()
        val rawId = team.firstText("id", "teamId")?.takeIf(NUMERIC_ID::matches)
        return ProviderStandingRow(
            competitor = ProviderCompetitor(
                name,
                rawId?.let { "$prefix:$it" },
                ProviderCompetitorKind.TEAM,
            ),
            rank = obj.firstText("position", "rank", "standing")?.toIntOrNull()?.takeIf { it > 0 },
            values = standingValues(obj),
        )
    }

    private fun standingValues(obj: JsonObject): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        val ignored = setOf("team", "competitor", "id", "position", "rank", "standing", "league", "season")
        for ((key, value) in obj.entrySet()) {
            if (key in ignored || value.isJsonNull) continue
            when {
                value.isJsonPrimitive -> value.safeText()?.let { result[key] = it }
                value.isJsonObject && key !in setOf("country") -> {
                    value.asJsonObject.entrySet().forEach { (nestedKey, nestedValue) ->
                        nestedValue.safeText()?.let { result["$key.$nestedKey"] = it }
                    }
                }
                value.isJsonArray && key in setOf("statistics", "stats") -> {
                    value.asJsonArray.forEach { statistic ->
                        val stat = statistic.asObjectOrNull() ?: return@forEach
                        val field = stat.firstText("abbreviation", "key", "name", "description")
                            ?: return@forEach
                        val fieldValue = stat.firstText("displayValue", "value", "total")
                            ?: return@forEach
                        result[field] = fieldValue
                    }
                }
            }
        }
        return result
    }

    private fun groupName(obj: JsonObject): String? = obj.firstText(
        "groupName", "conferenceName", "divisionName", "conference", "division", "leagueType", "seasonType",
    )

    private fun standingsLeagueName(obj: JsonObject): String? =
        obj.obj("league")?.firstText("name", "leagueName") ?: obj.firstText("leagueName", "name")

    private fun standingsSeason(obj: JsonObject): String? =
        obj.obj("league")?.firstText("season", "year") ?: obj.firstText("season", "year")

    private suspend fun storedCredentials(): SportsProviderCredentials? = try {
        store.credentials(instanceId)?.takeIf { validSecret(it.secret) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun getJson(
        uri: HttpUrl,
        credentials: SportsProviderCredentials,
        accountFingerprint: String,
    ): SportsProviderResult<JsonElement> = httpClient.get(
        url = uri,
        auth = descriptor.auth,
        credentials = credentials,
        allowInsecureHttp = allowInsecureHttp,
        onResponseMetadata = { metadata ->
            quotaTracker.recordHeaders(accountFingerprint, metadata, clock.instant())
        },
    ) { body -> JsonParser.parseString(body) }

    private fun recordPlan(obj: JsonObject, accountFingerprint: String) {
        obj.obj("plan")?.text("tier")?.let {
            quotaTracker.recordPlanTier(accountFingerprint, it, clock.instant())
        }
    }

    private fun cacheKey(kind: String, query: String, credentials: SportsProviderCredentials): String =
        "${descriptor.typeId}:$instanceId:${config.cacheRevision}:${fingerprint(credentials.secret)}:$kind:$query"

    private fun configuredScopes(): List<Pair<HighlightlyRoute, LeagueScope?>>? {
        val parsed = parseScopes(config.eventLeagueIds) ?: return null
        return parsed.map { it.route to it }.ifEmpty { emptyList() }
    }

    private fun scopesForRequest(request: ProviderEventRequest): List<Pair<HighlightlyRoute, LeagueScope?>>? {
        val configured = parseScopes(config.eventLeagueIds) ?: return null
        val requested = request.leagueId?.let(::parseLeagueScope)
        if (request.leagueId != null && requested == null) return null
        if (requested != null && configured.isNotEmpty() && configured.none { it.sameAs(requested) }) return null
        val allScopes = when {
            requested != null -> listOf(requested)
            configured.isNotEmpty() -> configured
            else -> HighlightlyRoutes.ALL.map { route -> LeagueScope(route, null) }
        }
        val requestedSport = request.sport
        val filteredScopes = allScopes.filter { scope ->
            requestedSport == null || scope.route.matchesSport(requestedSport)
        }
        return filteredScopes.map { it.route to it.takeIf { scope -> scope.leagueId != null } }
    }

    private fun parseScopes(values: List<String>): List<LeagueScope>? = values.map { parseLeagueScope(it) ?: return null }

    private fun parseLeagueScope(value: String): LeagueScope? {
        return parseHighlightlyLeagueScope(value)
    }

    private fun parseEntityId(value: String): Pair<HighlightlyRoute, String>? = parseEventId(value)

    private fun parseEventId(value: String): Pair<HighlightlyRoute, String>? {
        val parts = value.split(':', limit = 2)
        if (parts.size != 2 || !NUMERIC_ID.matches(parts[1])) return null
        val route = HighlightlyRoutes.BY_PREFIX[parts[0].lowercase(Locale.ROOT)] ?: return null
        return route to parts[1]
    }

    private fun routeUrl(route: HighlightlyRoute, vararg segments: String): HttpUrl {
        val base = endpointBaseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException()
        return base.newBuilder().addPathSegment(route.prefix).apply {
            segments.forEach { addPathSegment(it) }
        }.build()
    }

    private fun entityUrl(route: HighlightlyRoute, endpoint: String, id: String): HttpUrl =
        routeUrl(route, endpoint, id)

    private fun currentSeasonYear(now: Instant): Int {
        val date = now.atOffset(ZoneOffset.UTC).toLocalDate()
        return if (date.monthValue >= 8) date.year else date.year - 1
    }

    private fun fingerprint(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun validSecret(value: String): Boolean = value.isNotBlank() && '\r' !in value && '\n' !in value

    private fun invalidConfiguration() = SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
    private fun invalidResponse() = SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    private fun responseTooLarge() = SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)

    private data class ParsedMatchStart(val date: LocalDate, val instant: Instant?)
    private data class GameSnapshot(val event: ProviderEvent, val scores: List<ProviderCompetitorScore>)
    private data class StandingRecord(val groupName: String?, val row: JsonObject)
    private data class MappedStandingRecord(val groupName: String?, val row: ProviderStandingRow)
    private class RequestBudget(private val max: Int) {
        private var used = 0
        fun consume(): Boolean = (++used) <= max
    }

    private companion object {
        val EVENT_CACHE_TTL = Duration.ofMinutes(2)
        val MATCH_CACHE_TTL = Duration.ofMinutes(1)
        val STANDINGS_CACHE_TTL = Duration.ofMinutes(30)
        val METADATA_CACHE_TTL = Duration.ofHours(24)
        val SCHEDULED_STATES = setOf("not started", "scheduled", "to be announced", "tbd", "upcoming")
        val FINAL_STATES = setOf(
            "finished", "finished after penalties", "finished after extra time", "final", "complete", "completed",
            "awarded", "abandoned", "game finished", "match finished",
        )
        val POSTPONED_STATES = setOf("postponed")
        val CANCELLED_STATES = setOf("cancelled", "canceled")
        val SUSPENDED_STATES = setOf("suspended", "interrupted")
        val LIVE_STATES = setOf(
            "first half", "second half", "half time", "extra time", "break time", "penalties", "in progress",
            "live", "first quarter", "second quarter", "third quarter", "fourth quarter", "in play", "innings",
        )
        val SCORE_PAIR = Regex("^\\s*(.+?)\\s*[-–:]\\s*(.+?)\\s*$")
        val NUMERIC_ID = Regex("[0-9]{1,18}")
        val SCOPE_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val YEAR = Regex("(?:19|20|21)[0-9]{2}")
        val MAX_EVENT_DAYS = 14
        const val MAX_EVENT_HTTP_REQUESTS = 120
        const val MAX_EVENT_PAGES = 3
        const val PAGE_SIZE = 100
        const val MAX_MAPPED_EVENTS = 2_000
        const val MAX_SCORE_EVENTS = 30
        const val MAX_STANDING_ROWS = 1_000
        const val MAX_STANDING_DEPTH = 5
    }
}

private data class HighlightlyRoute(
    val prefix: String,
    val sportName: String,
    val leagueFilterParameter: String,
    val numericLeagueId: Boolean,
    val supportsLeagueMetadata: Boolean,
    val supportsStandings: Boolean = true,
) {
    fun matchesSport(value: String): Boolean =
        value.equals(prefix, ignoreCase = true) || value.equals(sportName, ignoreCase = true)
}

private object HighlightlyRoutes {
    val ALL = listOf(
        HighlightlyRoute("football", "Football", "leagueId", true, true),
        HighlightlyRoute("american-football", "American football", "league", false, false),
        HighlightlyRoute("nba", "Basketball", "league", false, false),
        HighlightlyRoute("baseball", "Baseball", "league", false, false),
        HighlightlyRoute("nhl", "Ice hockey", "league", false, false),
        HighlightlyRoute("cricket", "Cricket", "leagueId", true, true),
        HighlightlyRoute("basketball", "Basketball", "leagueId", true, true),
        HighlightlyRoute("hockey", "Ice hockey", "leagueId", true, true),
        HighlightlyRoute("rugby", "Rugby", "leagueId", true, true),
        HighlightlyRoute("volleyball", "Volleyball", "leagueId", true, true),
        HighlightlyRoute("handball", "Handball", "leagueId", true, true),
    )
    val BY_PREFIX = ALL.associateBy(HighlightlyRoute::prefix)
}

private data class LeagueScope(val route: HighlightlyRoute, val leagueId: String?) {
    fun sameAs(other: LeagueScope): Boolean = route.prefix == other.route.prefix &&
        leagueId.equals(other.leagueId, ignoreCase = true)
}

private fun parseHighlightlyLeagueScope(value: String): LeagueScope? {
    val parts = value.split(':', limit = 2)
    if (parts.size != 2 || !parts[1].matches(Regex("[A-Za-z0-9._-]{1,64}"))) return null
    val route = HighlightlyRoutes.BY_PREFIX[parts[0].lowercase(Locale.ROOT)] ?: return null
    if (route.numericLeagueId && !parts[1].matches(Regex("[0-9]{1,18}"))) return null
    return LeagueScope(route, parts[1])
}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonElement.asObjectOrArrayEntities(): List<JsonObject>? = when {
    isJsonArray -> asJsonArray.mapNotNull(JsonElement::asObjectOrNull)
    isJsonObject -> {
        val obj = asJsonObject
        val data = obj.get("data")
        when {
            data == null || data.isJsonNull -> listOf(obj)
            data.isJsonArray -> data.asJsonArray.mapNotNull(JsonElement::asObjectOrNull)
            data.isJsonObject -> listOf(data.asJsonObject)
            else -> listOf(obj)
        }
    }
    else -> null
}

private fun JsonObject.obj(name: String): JsonObject? = get(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.asObjectOrNull()

private fun JsonObject.firstObject(vararg names: String): JsonObject? = names.firstNotNullOfOrNull(::obj)

private fun JsonObject.array(name: String): JsonArray? = get(name)
    ?.takeIf(JsonElement::isJsonArray)?.asJsonArray

private fun JsonObject.text(name: String): String? = get(name)?.safeText()

private fun JsonObject.firstText(vararg names: String): String? = names.firstNotNullOfOrNull(::text)

private fun JsonObject.int(name: String): Int? = text(name)?.toIntOrNull()

private fun JsonObject.firstElementText(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    get(name)?.let(JsonElement::safeText)
}

private fun JsonElement.safeText(): String? = when {
    isJsonNull -> null
    isJsonPrimitive && asJsonPrimitive.isString -> asString.trim().takeIf(String::isNotEmpty)
    isJsonPrimitive && (asJsonPrimitive.isNumber || asJsonPrimitive.isBoolean) -> asString
    isJsonArray -> asJsonArray.mapNotNull(JsonElement::safeText).takeIf(List<String>::isNotEmpty)?.joinToString(", ")
    isJsonObject -> asJsonObject.firstText("current", "score", "total", "displayValue", "value", "points", "runs")
    else -> null
}

private fun parseOffsetInstant(value: String): Instant? = runCatching {
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant()
}.getOrNull()

private fun parseLocalDateTime(value: String): Instant? = runCatching {
    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
}.getOrNull()

private fun parseLocalDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private fun stableLeagueKey(value: String): String? = value.trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-')
    .takeIf { it.matches(Regex("[a-z0-9._-]{1,64}")) }
