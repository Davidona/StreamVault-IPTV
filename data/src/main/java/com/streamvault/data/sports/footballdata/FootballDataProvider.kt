package com.streamvault.data.sports.footballdata

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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class FootballDataProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val cache: FootballDataResponseCache,
    private val quota: FootballDataQuotaTracker,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        ),
        auth = SportsProviderAuth.ApiKeyHeader("X-Auth-Token"),
        defaultBaseUrl = BASE_URL,
        displayName = "football-data.org",
        compliance = ProviderComplianceInfo(
            termsUrl = "https://www.football-data.org/client/register",
            documentationUrl = "https://docs.football-data.org/general/v4/index.html",
            reviewedAt = LocalDate.of(2026, 10, 17),
            attribution = ProviderAttribution(
                sourceName = "Football-Data.org API",
                sourceUrl = "https://www.football-data.org/",
                format = ProviderAttributionFormat.FOOTBALL_DATA,
            ),
            appStoreAllowed = true,
            commercialUseStatus = CommercialUseStatus.PERMITTED,
            artworkStatus = ArtworkUseStatus.DISABLED,
            releaseGate = ProviderReleaseGate.REQUIRES_ATTRIBUTION,
        ),
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> =
        if (config.eventLeagueIds.all(::isCompetitionCode)) descriptor.capabilities else emptySet()

    override fun create(config: SportsProviderConfig): SportsProvider = FootballDataProvider(
        config = config,
        descriptor = descriptor,
        store = store,
        httpClient = httpClient,
        cache = cache,
        quota = quota,
    )

    companion object {
        const val TYPE_ID = "football-data"
        const val BASE_URL = "https://api.football-data.org/v4"
        internal const val CONSERVATIVE_CALLS_PER_MINUTE = 10

        internal fun isCompetitionCode(value: String): Boolean =
            value.matches(Regex("[A-Za-z0-9]{2,5}"))
    }
}

/** In-memory response cache, indexed by configuration and credential fingerprint. */
@Singleton
class FootballDataResponseCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Instant, val credentialFingerprint: String)
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
    fun put(key: String, fingerprint: String, value: Any, ttl: Duration, now: Instant) {
        require(!ttl.isNegative && !ttl.isZero)
        entries[key] = Entry(value, now.plus(ttl), fingerprint)
        while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
    }

    /** A 401/403 means that cached data may no longer be covered by this account. */
    @Synchronized
    fun clearCredential(fingerprint: String) {
        entries.entries.removeAll { it.value.credentialFingerprint == fingerprint }
    }

    private companion object { const val MAX_ENTRIES = 256 }
}

/**
 * The API exposes remaining calls and the counter reset, but not a stable plan identifier on
 * every response. Cap locally at the published Free tier (10/minute) so all accounts stay safe.
 */
@Singleton
class FootballDataQuotaTracker @Inject constructor() {
    private data class AccountState(
        val calls: ArrayDeque<Instant> = ArrayDeque(),
        var remaining: Int? = null,
        var resetAt: Instant? = null,
        var observedAt: Instant? = null,
    )

    private val accounts = LinkedHashMap<String, AccountState>(16, 0.75f, true)

    @Synchronized
    fun reserve(fingerprint: String, now: Instant): Instant? {
        val state = state(fingerprint)
        val resetAt = state.resetAt
        if (state.remaining == 0 && resetAt != null && resetAt.isAfter(now)) return resetAt
        val cutoff = now.minusSeconds(60)
        while (state.calls.peekFirst()?.let { !it.isAfter(cutoff) } == true) state.calls.removeFirst()
        if (state.calls.size >= FootballDataProviderFactory.CONSERVATIVE_CALLS_PER_MINUTE) {
            return state.calls.peekFirst()?.plusSeconds(60) ?: now.plusSeconds(60)
        }
        state.calls.addLast(now)
        return null
    }

    @Synchronized
    fun record(fingerprint: String, metadata: SportsProviderResponseMetadata, now: Instant) {
        if (metadata.requestsRemaining == null && metadata.resetAfterSeconds == null) return
        val state = state(fingerprint)
        metadata.requestsRemaining?.let { state.remaining = it }
        metadata.resetAfterSeconds?.let { state.resetAt = now.plusSeconds(it.toLong()) }
        if (state.remaining == 0 && state.resetAt?.isAfter(now) != true) {
            state.resetAt = now.plusSeconds(60)
        }
        state.observedAt = now
    }

    @Synchronized
    fun snapshot(fingerprint: String, now: Instant): SportsProviderUsageSnapshot? {
        val state = accounts[fingerprint] ?: return null
        if (state.resetAt?.isAfter(now) == false) {
            state.remaining = null
            state.resetAt = null
        }
        if (state.remaining == null && state.observedAt == null) return null
        return SportsProviderUsageSnapshot(
            requestsRemaining = state.remaining,
            observedAt = state.observedAt,
            resetAt = state.resetAt,
        )
    }

    private fun state(fingerprint: String): AccountState = accounts.getOrPut(fingerprint, ::AccountState).also {
        while (accounts.size > MAX_ACCOUNTS) accounts.entries.iterator().run { next(); remove() }
    }

    private companion object { const val MAX_ACCOUNTS = 64 }
}

private data class FootballDataMatch(
    val event: ProviderEvent,
    val home: ProviderCompetitor,
    val away: ProviderCompetitor,
    val homeScore: Int?,
    val awayScore: Int?,
    val updatedAt: Instant?,
)

internal class FootballDataProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val store: SportsProviderStore,
    private val httpClient: SportsProviderHttpClient,
    private val cache: FootballDataResponseCache,
    private val quota: FootballDataQuotaTracker,
    private val endpointBaseUrl: String = FootballDataProviderFactory.BASE_URL,
    private val allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : EventProvider, LiveScoreProvider, GameStatusProvider, StandingsProvider,
    SportsProviderConnectionTester, SportsProviderUsageProvider {
    override val instanceId: String = config.instanceId

    override fun usageSnapshot(credentials: SportsProviderCredentials?): SportsProviderUsageSnapshot? =
        credentials?.secret?.takeIf(::validSecret)?.let(::fingerprint)?.let { quota.snapshot(it, clock.instant()) }

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> {
        if (credentials?.secret?.takeIf(::validSecret) == null || configuredCodes() == null) {
            return invalidConfiguration()
        }
        val today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val url = apiUrl("/matches") {
            addQueryParameter("dateFrom", today.toString())
            addQueryParameter("dateTo", today.toString())
            addQueryParameter("limit", "1")
        } ?: return invalidConfiguration()
        return when (val result = getObject(url, credentials)) {
            is SportsProviderResult.Failure -> result
            is SportsProviderResult.Success -> if (result.value.arrayOrNull("matches") != null) {
                SportsProviderResult.Success(Unit)
            } else invalidResponse()
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        if (request.sport != null && !request.sport.equals("football", ignoreCase = true)) {
            return invalidConfiguration()
        }
        val from = request.from.atOffset(ZoneOffset.UTC).toLocalDate()
        val until = request.until.minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate()
        val days = ChronoUnit.DAYS.between(from, until) + 1
        if (days !in 1..MAX_EVENT_DAYS) return responseTooLarge()
        val codes = eventCodes(request) ?: return invalidConfiguration()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        // The provider query uses calendar dates, but the public request has exact instants.
        // Keep the local filter's bounds in the key so same-day windows cannot share a partial result.
        val cacheKey = cacheKey(
            "events",
            "${request.from}|${request.until}|${codes.joinToString(",")}",
            fingerprint,
        )
        val now = clock.instant()
        cache.get<ProviderBatch<ProviderEvent>>(cacheKey, now)?.let { return SportsProviderResult.Success(it) }

        val url = apiUrl("/matches") {
            addQueryParameter("dateFrom", from.toString())
            addQueryParameter("dateTo", until.toString())
            addQueryParameter("limit", MAX_MATCHES.toString())
            if (codes.isNotEmpty()) addQueryParameter("competitions", codes.joinToString(","))
        } ?: return invalidConfiguration()
        return when (val response = getObject(url, credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> when (val mapped = mapMatches(
                response.value, request.from, request.until,
            )) {
                is SportsProviderResult.Failure -> mapped
                is SportsProviderResult.Success -> mapped.also {
                    cache.put(cacheKey, fingerprint, it.value, EVENT_CACHE_TTL, now)
                }
            }
        }
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> = when (val matches = fetchByIds(request.eventIds)) {
        is SportsProviderResult.Failure -> matches
        is SportsProviderResult.Success -> SportsProviderResult.Success(ProviderBatch(
            items = matches.value.items.map { match ->
                ProviderScore(
                    providerEventId = match.event.providerEventId,
                    scores = listOf(
                        ProviderCompetitorScore(match.home, match.homeScore?.toString()),
                        ProviderCompetitorScore(match.away, match.awayScore?.toString()),
                    ),
                    status = match.event.status,
                    updatedAt = match.updatedAt,
                )
            },
            rejectedItemCount = matches.value.rejectedItemCount,
        ))
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> = when (val matches = fetchByIds(request.eventIds)) {
        is SportsProviderResult.Failure -> matches
        is SportsProviderResult.Success -> SportsProviderResult.Success(ProviderBatch(
            items = matches.value.items.mapNotNull { match ->
                match.event.status?.let { ProviderEventStatus(match.event.providerEventId, it) }
            },
            rejectedItemCount = matches.value.rejectedItemCount,
        ))
    }

    override suspend fun standings(
        request: ProviderStandingsRequest,
    ): SportsProviderResult<ProviderStandings> {
        val code = request.leagueId.uppercase(Locale.ROOT)
            .takeIf(FootballDataProviderFactory::isCompetitionCode) ?: return invalidConfiguration()
        val configured = configuredCodes() ?: return invalidConfiguration()
        if (configured.isNotEmpty() && code !in configured) return invalidConfiguration()
        val season = request.season?.takeIf { it.matches(Regex("\\d{4}")) }
        if (request.season != null && season == null) return invalidConfiguration()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        val cacheKey = cacheKey("standings", "$code|${season.orEmpty()}", fingerprint)
        val now = clock.instant()
        cache.get<ProviderStandings>(cacheKey, now)?.let { return SportsProviderResult.Success(it) }

        val url = apiUrl("/competitions/$code/standings") {
            season?.let { addQueryParameter("season", it) }
        } ?: return invalidConfiguration()
        return when (val response = getObject(url, credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> when (val mapped = mapStandings(response.value, code, season)) {
                is SportsProviderResult.Failure -> mapped
                is SportsProviderResult.Success -> mapped.also {
                    cache.put(cacheKey, fingerprint, it.value, STANDINGS_CACHE_TTL, now)
                }
            }
        }
    }

    private suspend fun fetchByIds(ids: Set<String>): SportsProviderResult<ProviderBatch<FootballDataMatch>> {
        if (ids.isEmpty() || ids.size > MAX_SCORE_IDS || ids.any { !it.matches(ID_PATTERN) }) {
            return if (ids.size > MAX_SCORE_IDS) responseTooLarge() else invalidConfiguration()
        }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        val orderedIds = ids.sorted()
        val cacheKey = cacheKey("score-details", orderedIds.joinToString(","), fingerprint)
        val now = clock.instant()
        cache.get<ProviderBatch<FootballDataMatch>>(cacheKey, now)?.let { return SportsProviderResult.Success(it) }
        val url = apiUrl("/matches") {
            addQueryParameter("ids", orderedIds.joinToString(","))
            addQueryParameter("limit", MAX_SCORE_IDS.toString())
        } ?: return invalidConfiguration()
        return when (val response = getObject(url, credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> when (val mapped = mapMatchSnapshots(response.value)) {
                is SportsProviderResult.Failure -> mapped
                is SportsProviderResult.Success -> {
                    val requested = orderedIds.toSet()
                    val results = mapped.value.items.filter { it.event.providerEventId in requested }
                    val rejected = mapped.value.rejectedItemCount + (orderedIds.size - results.size)
                    val batch = ProviderBatch(results, rejected.coerceAtLeast(0))
                    cache.put(cacheKey, fingerprint, batch, SCORE_CACHE_TTL, now)
                    SportsProviderResult.Success(batch)
                }
            }
        }
    }

    private suspend fun getObject(
        url: HttpUrl,
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<JsonObject> {
        val fingerprint = fingerprint(credentials.secret)
        val now = clock.instant()
        quota.reserve(fingerprint, now)?.let {
            return SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, retryAt = it)
        }
        val result = httpClient.get(
            url = url,
            auth = SportsProviderAuth.ApiKeyHeader("X-Auth-Token"),
            credentials = credentials,
            allowInsecureHttp = allowInsecureHttp,
            onResponseMetadata = { metadata -> quota.record(fingerprint, metadata, clock.instant()) },
        ) { text -> JsonParser.parseString(text).asJsonObject }
        if (result is SportsProviderResult.Failure &&
            (result.httpStatus == 401 || result.httpStatus == 403)
        ) {
            cache.clearCredential(fingerprint)
        }
        return result
    }

    private fun mapMatches(
        envelope: JsonObject,
        from: Instant,
        until: Instant,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> = when (val mapped = mapMatchSnapshots(envelope)) {
        is SportsProviderResult.Failure -> mapped
        is SportsProviderResult.Success -> {
            val filtered = mapped.value.items.filter { match ->
                val start = match.event.eventStart
                start == null || !start.isBefore(from) && start.isBefore(until)
            }
            SportsProviderResult.Success(ProviderBatch(
                items = filtered.map(FootballDataMatch::event),
                rejectedItemCount = mapped.value.rejectedItemCount,
            ))
        }
    }

    private fun mapMatchSnapshots(envelope: JsonObject): SportsProviderResult<ProviderBatch<FootballDataMatch>> {
        val matches = envelope.arrayOrNull("matches") ?: return invalidResponse()
        if (matches.size() > MAX_MATCHES) return responseTooLarge()
        val reportedCount = envelope.objOrNull("resultSet")?.intOrNull("count")
        if (reportedCount != null && reportedCount > matches.size()) return responseTooLarge()
        val output = mutableListOf<FootballDataMatch>()
        var rejected = 0
        matches.forEach { element ->
            val match = element.asObjectOrNull()?.let(::mapMatch)
            if (match == null) rejected++ else output += match
        }
        return SportsProviderResult.Success(ProviderBatch(output, rejected))
    }

    private fun mapMatch(value: JsonObject): FootballDataMatch? {
        val id = value.longOrNull("id")?.takeIf { it > 0 }?.toString() ?: return null
        val competition = value.objOrNull("competition")
        val competitionName = competition?.stringOrNull("name")
            ?: competition?.stringOrNull("code")
        val competitionId = competition?.stringOrNull("code")
            ?: competition?.longOrNull("id")?.toString()
        val home = value.objOrNull("homeTeam")?.toCompetitor(ProviderCompetitorRole.HOME) ?: return null
        val away = value.objOrNull("awayTeam")?.toCompetitor(ProviderCompetitorRole.AWAY) ?: return null
        val score = value.objOrNull("score")
        val fullTime = score?.objOrNull("fullTime")
        val statusText = value.stringOrNull("status")
        val updatedAt = value.stringOrNull("lastUpdated")?.toInstantOrNull()
        val start = value.stringOrNull("utcDate")?.toInstantOrNull()
        val status = statusText?.let {
            mapStatus(
                status = it,
                minute = value.intOrNull("minute"),
                injuryTime = value.intOrNull("injuryTime"),
                duration = score?.stringOrNull("duration"),
                updatedAt = updatedAt,
            )
        }
        val event = ProviderEvent(
            providerEventId = id,
            sport = "Football",
            league = competitionName?.let { ProviderLeague(it, competitionId, "Football") },
            title = null,
            competitors = listOf(home, away),
            eventStart = start,
            status = status,
        )
        return FootballDataMatch(
            event = event,
            home = home,
            away = away,
            homeScore = fullTime?.intOrNull("home"),
            awayScore = fullTime?.intOrNull("away"),
            updatedAt = updatedAt,
        )
    }

    private fun mapStandings(
        envelope: JsonObject,
        requestedCode: String,
        requestedSeason: String?,
    ): SportsProviderResult<ProviderStandings> {
        val competition = envelope.objOrNull("competition") ?: return invalidResponse()
        val code = competition.stringOrNull("code") ?: requestedCode
        if (!code.equals(requestedCode, ignoreCase = true)) return invalidResponse()
        val name = competition.stringOrNull("name") ?: code
        val rawStandings = envelope.arrayOrNull("standings") ?: return invalidResponse()
        val entries = rawStandings.mapNotNull { it.asObjectOrNull() }
        val totals = entries.filter { it.stringOrNull("type") == "TOTAL" }
        val grouped = entries.filter { !it.stringOrNull("group").isNullOrBlank() }
        val selected = when {
            totals.isNotEmpty() -> totals
            grouped.isNotEmpty() -> grouped
            else -> entries.take(1)
        }
        if (selected.isEmpty()) return invalidResponse()
        val columns = listOf(
            "intPlayed", "intWin", "intDraw", "intLoss", "intGoalsFor", "intGoalsAgainst",
            "intGoalDifference", "intPoints",
        ).map { ProviderStandingColumn(it) }
        val groups = selected.mapNotNull { standing ->
            val table = standing.arrayOrNull("table") ?: return@mapNotNull null
            val rows = table.mapNotNull { element ->
                val row = element.asObjectOrNull() ?: return@mapNotNull null
                val team = row.objOrNull("team")?.toCompetitor(ProviderCompetitorRole.PARTICIPANT)
                    ?: return@mapNotNull null
                val values = mapOf(
                    "intPlayed" to row.intOrNull("playedGames"),
                    "intWin" to row.intOrNull("won"),
                    "intDraw" to row.intOrNull("draw"),
                    "intLoss" to row.intOrNull("lost"),
                    "intGoalsFor" to row.intOrNull("goalsFor"),
                    "intGoalsAgainst" to row.intOrNull("goalsAgainst"),
                    "intGoalDifference" to row.intOrNull("goalDifference"),
                    "intPoints" to row.intOrNull("points"),
                ).mapValues { (_, raw) -> raw?.toString() }
                ProviderStandingRow(team, row.intOrNull("position"), values)
            }
            ProviderStandingGroup(
                name = standing.stringOrNull("group")?.takeIf(String::isNotBlank),
                columns = columns,
                rows = rows,
            )
        }
        if (groups.isEmpty()) return invalidResponse()
        val seasonStart = envelope.objOrNull("season")?.stringOrNull("startDate")
        val season = requestedSeason ?: seasonStart?.take(4)
        return SportsProviderResult.Success(ProviderStandings(
            league = ProviderLeague(name, code, "Football"),
            groups = groups,
            season = season,
            updatedAt = envelope.stringOrNull("lastUpdated")?.toInstantOrNull(),
        ))
    }

    private fun mapStatus(
        status: String,
        minute: Int?,
        injuryTime: Int?,
        duration: String?,
        updatedAt: Instant?,
    ): ProviderGameStatus {
        val normalized = status.uppercase(Locale.ROOT)
        val state = when (normalized) {
            "SCHEDULED", "TIMED" -> ProviderGameState.SCHEDULED
            "IN_PLAY", "PAUSED" -> ProviderGameState.LIVE
            "SUSPENDED" -> ProviderGameState.SUSPENDED
            "POSTPONED" -> ProviderGameState.POSTPONED
            "CANCELLED" -> ProviderGameState.CANCELLED
            "FINISHED", "AWARDED" -> ProviderGameState.FINAL
            else -> ProviderGameState.UNKNOWN
        }
        val clock = minute?.let { if (injuryTime != null && injuryTime > 0) "$it+$injuryTime'" else "$it'" }
        return ProviderGameStatus(state, clock = clock, period = duration, detail = normalized, updatedAt = updatedAt)
    }

    private fun JsonObject.toCompetitor(role: ProviderCompetitorRole): ProviderCompetitor? {
        val name = stringOrNull("name")?.takeIf(String::isNotBlank) ?: return null
        return ProviderCompetitor(
            name = name,
            id = longOrNull("id")?.toString(),
            kind = ProviderCompetitorKind.TEAM,
            role = role,
        )
    }

    private fun eventCodes(request: ProviderEventRequest): List<String>? {
        val configured = configuredCodes() ?: return null
        val selected = request.leagueId?.let { listOf(it) } ?: configured
        val parsed = selected.map { it.uppercase(Locale.ROOT).takeIf(FootballDataProviderFactory::isCompetitionCode) ?: return null }
        if (request.leagueId != null && configured.isNotEmpty() && parsed.first() !in configured) return null
        return parsed.distinct().sorted()
    }

    private fun configuredCodes(): List<String>? = config.eventLeagueIds.map { value ->
        value.uppercase(Locale.ROOT).takeIf(FootballDataProviderFactory::isCompetitionCode) ?: return null
    }

    private fun cacheKey(resource: String, query: String, credentialFingerprint: String) =
        "${config.instanceId}|${config.cacheRevision}|$credentialFingerprint|$resource|$query"

    private suspend fun storedCredentials(): SportsProviderCredentials? =
        store.credentials(config.instanceId)?.takeIf { validSecret(it.secret) }

    private fun apiUrl(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl? {
        return try {
            val root = endpointBaseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
            if (root.username.isNotEmpty() || root.password.isNotEmpty() || root.query != null || root.fragment != null) {
                return null
            }
            val prefix = root.encodedPath.trimEnd('/')
            root.newBuilder().encodedPath("$prefix$path").apply(query).build()
        } catch (_: Exception) {
            null
        }
    }

    private fun validSecret(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_SECRET_LENGTH && '\r' !in value && '\n' !in value

    private fun fingerprint(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun String.toInstantOrNull(): Instant? = runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

    private fun JsonObject.arrayOrNull(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    private fun JsonObject.objOrNull(name: String) = get(name)?.asObjectOrNull()
    private fun JsonObject.stringOrNull(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.intOrNull(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.longOrNull(name: String) = get(name)?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asLong }.getOrNull() }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun invalidConfiguration() = SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
    private fun invalidResponse() = SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    private fun responseTooLarge() = SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)

    private companion object {
        val ID_PATTERN = Regex("[1-9]\\d{0,11}")
        const val MAX_SECRET_LENGTH = 512
        const val MAX_EVENT_DAYS = 14L
        const val MAX_MATCHES = 500
        const val MAX_SCORE_IDS = 50
        val EVENT_CACHE_TTL = Duration.ofMinutes(2)
        val SCORE_CACHE_TTL = Duration.ofSeconds(30)
        val STANDINGS_CACHE_TTL = Duration.ofMinutes(30)
    }
}
