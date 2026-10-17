package com.streamvault.data.sports.openligadb

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private data class OpenLigaDbScope(val shortcut: String, val season: Int) {
    val id: String get() = "$shortcut:$season"
}

class OpenLigaDbProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val cache: OpenLigaDbRawResponseCache,
    private val limiter: OpenLigaDbRequestLimiter,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        ),
        auth = SportsProviderAuth.None,
        defaultBaseUrl = BASE_URL,
        displayName = "OpenLigaDB",
        compliance = ProviderComplianceInfo(
            termsUrl = "https://openligadb.de/lizenz",
            documentationUrl = "https://api.openligadb.de/index.html",
            reviewedAt = LocalDate.of(2026, 10, 17),
            attribution = ProviderAttribution(
                sourceName = "OpenLigaDB",
                sourceUrl = "https://api.openligadb.de",
                format = ProviderAttributionFormat.ODBL,
                licenseUrl = ODBL_URL,
            ),
            appStoreAllowed = true,
            commercialUseStatus = CommercialUseStatus.PERMITTED,
            artworkStatus = ArtworkUseStatus.DISABLED,
            releaseGate = ProviderReleaseGate.REQUIRES_ATTRIBUTION,
        ),
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> =
        if (config.eventLeagueIds.size in 1..MAX_LEAGUE_SCOPES &&
            config.eventLeagueIds.distinct().size == config.eventLeagueIds.size &&
            config.eventLeagueIds.all(::parseScopeValid)
        ) descriptor.capabilities else emptySet()

    override fun create(config: SportsProviderConfig): SportsProvider = OpenLigaDbProvider(
        config = config,
        descriptor = descriptor,
        httpClient = httpClient,
        cache = cache,
        limiter = limiter,
    )

    companion object {
        const val TYPE_ID = "openligadb"
        const val BASE_URL = "https://api.openligadb.de"
        const val MAX_LEAGUE_SCOPES = 5
        const val ODBL_URL = "https://opendatacommons.org/licenses/odbl/1-0/"
        internal fun parseScopeValid(value: String): Boolean = parseScope(value) != null
    }
}

/** Only OpenLigaDB JSON text is cached. There is no normalized or durable database. */
@Singleton
class OpenLigaDbRawResponseCache @Inject constructor() {
    private data class Entry(val rawJson: String, val expiresAt: Instant, val bytes: Int)
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private var byteCount = 0

    @Synchronized
    fun get(key: String, now: Instant): String? {
        val entry = entries[key] ?: return null
        if (!now.isBefore(entry.expiresAt)) {
            remove(key, entry)
            return null
        }
        return entry.rawJson
    }

    @Synchronized
    fun put(key: String, rawJson: String, ttl: Duration, now: Instant) {
        require(!ttl.isNegative && !ttl.isZero)
        val bytes = rawJson.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BYTES) return
        entries.remove(key)?.let { byteCount -= it.bytes }
        entries[key] = Entry(rawJson, now.plus(ttl), bytes)
        byteCount += bytes
        while (entries.size > MAX_ENTRIES || byteCount > MAX_BYTES) {
            val oldest = entries.entries.iterator().next()
            remove(oldest.key, oldest.value)
        }
    }

    private fun remove(key: String, entry: Entry) {
        entries.remove(key)
        byteCount -= entry.bytes
    }

    private companion object {
        const val MAX_ENTRIES = 8
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}

/** Serializes requests across all configured OpenLigaDB scopes for this app process. */
@Singleton
class OpenLigaDbRequestLimiter @Inject constructor() {
    private val mutex = Mutex()
    private var nextRequestAtNanos = 0L

    suspend fun <T> execute(minimumIntervalMillis: Long = MINIMUM_INTERVAL_MILLIS, action: suspend () -> T): T =
        mutex.withLock {
            val waitNanos = nextRequestAtNanos - System.nanoTime()
            if (waitNanos > 0L) delay((waitNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
            val startedAt = System.nanoTime()
            try {
                action()
            } finally {
                nextRequestAtNanos = maxOf(nextRequestAtNanos, startedAt + minimumIntervalMillis * NANOS_PER_MILLI)
            }
        }

    private companion object {
        const val MINIMUM_INTERVAL_MILLIS = 1_200L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal class OpenLigaDbProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val httpClient: SportsProviderHttpClient,
    private val cache: OpenLigaDbRawResponseCache,
    private val limiter: OpenLigaDbRequestLimiter,
    private val endpointBaseUrl: String = OpenLigaDbProviderFactory.BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
    private val minimumRequestIntervalMillis: Long = 1_200L,
    private val allowInsecureHttpForTests: Boolean = false,
) : EventProvider, LiveScoreProvider, GameStatusProvider, StandingsProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(credentials: SportsProviderCredentials?): SportsProviderResult<Unit> {
        val scope = configuredScopes().firstOrNull() ?: return invalidConfiguration()
        val result = rawJson(path("getavailablegroups", scope.shortcut, scope.season.toString()), GROUPS_TTL)
        return when (result) {
            is SportsProviderResult.Failure -> result
            is SportsProviderResult.Success -> if (parseArray(result.value) != null) {
                SportsProviderResult.Success(Unit)
            } else invalidResponse()
        }
    }

    override suspend fun events(request: ProviderEventRequest): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val scope = resolveScope(request.leagueId) ?: return invalidConfiguration()
        val requestedSport = request.sport
        if (requestedSport != null && !requestedSport.isFootballName()) {
            return SportsProviderResult.Success(ProviderBatch(emptyList()))
        }
        if (Duration.between(request.from, request.until) > MAX_EVENT_RANGE) return responseTooLarge()
        val raw = rawJson(path("getmatchdata", scope.shortcut, scope.season.toString()), FIXTURE_TTL)
        return when (raw) {
            is SportsProviderResult.Failure -> raw
            is SportsProviderResult.Success -> {
                val array = parseArray(raw.value) ?: return invalidResponse()
                if (array.size() > MAX_MATCHES) return responseTooLarge()
                val matches = mapMatches(array, scope)
                SportsProviderResult.Success(ProviderBatch(
                    items = matches.items.map(OpenLigaDbMatch::event).filter { event ->
                        event.eventStart?.let { !it.isBefore(request.from) && it.isBefore(request.until) } == true
                    },
                    rejectedItemCount = matches.rejectedItemCount,
                ))
            }
        }
    }

    override suspend fun liveScores(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderScore>> =
        loadRequestedMatches(request.eventIds).map { matches ->
            val scores = matches.items.mapNotNull(::mapScore)
            ProviderBatch(scores, matches.rejectedItemCount + matches.items.size - scores.size)
        }

    override suspend fun gameStatuses(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderEventStatus>> =
        loadRequestedMatches(request.eventIds).map { matches ->
            val statuses = matches.items.mapNotNull { match ->
                match.event.status?.let { ProviderEventStatus(match.event.providerEventId, it) }
            }
            ProviderBatch(statuses, matches.rejectedItemCount + matches.items.size - statuses.size)
        }

    override suspend fun standings(request: ProviderStandingsRequest): SportsProviderResult<ProviderStandings> {
        val scope = resolveScope(request.leagueId, request.season) ?: return invalidConfiguration()
        val raw = rawJson(path("getbltable", scope.shortcut, scope.season.toString()), TABLE_TTL)
        return when (raw) {
            is SportsProviderResult.Failure -> raw
            is SportsProviderResult.Success -> mapStandings(raw.value, scope)
        }
    }

    private suspend fun loadRequestedMatches(ids: Set<String>): SportsProviderResult<ProviderBatch<OpenLigaDbMatch>> {
        if (ids.size > MAX_SCORE_IDS || ids.any { !it.matches(MATCH_ID) }) {
            return if (ids.size > MAX_SCORE_IDS) responseTooLarge() else invalidConfiguration()
        }
        val scopes = configuredScopes()
        if (scopes.isEmpty()) return invalidConfiguration()
        val matchesById = linkedMapOf<String, OpenLigaDbMatch>()
        for (scope in scopes) {
            val path = path("getmatchdata", scope.shortcut, scope.season.toString())
            val raw = cache.get(cacheKey(path), clock.instant()) ?: continue
            val array = parseArray(raw) ?: continue
            mapMatches(array, scope).items.forEach { matchesById[it.event.providerEventId] = it }
        }
        var rejected = 0
        for (id in ids.sorted()) {
            if (matchesById.containsKey(id)) continue
            val raw = rawJson(path("getmatchdata", id), DETAIL_TTL)
            when (raw) {
                is SportsProviderResult.Failure -> return raw
                is SportsProviderResult.Success -> {
                    val match = parseObject(raw.value)?.let(::mapMatch)
                    if (match == null || match.event.providerEventId != id) rejected++ else matchesById[id] = match
                }
            }
        }
        val requested = ids.sorted().mapNotNull(matchesById::get)
        rejected += ids.size - requested.size - rejected
        return SportsProviderResult.Success(ProviderBatch(requested, rejected.coerceAtLeast(0)))
    }

    private suspend fun rawJson(endpointPath: String, ttl: Duration): SportsProviderResult<String> {
        val key = cacheKey(endpointPath)
        cache.get(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        return limiter.execute(minimumRequestIntervalMillis) {
            cache.get(key, clock.instant())?.let { return@execute SportsProviderResult.Success(it) }
            val url = apiUrl(endpointPath) ?: return@execute invalidConfiguration()
            when (val response = httpClient.get(
                url = url,
                auth = SportsProviderAuth.None,
                credentials = null,
                allowInsecureHttp = allowInsecureHttpForTests && !url.isHttps,
            ) { it }) {
                is SportsProviderResult.Failure -> response
                is SportsProviderResult.Success -> {
                    cache.put(key, response.value, ttl, clock.instant())
                    response
                }
            }
        }
    }

    private fun mapStandings(raw: String, scope: OpenLigaDbScope): SportsProviderResult<ProviderStandings> {
        val array = parseArray(raw) ?: return invalidResponse()
        if (array.size() > MAX_TEAMS) return responseTooLarge()
        val columns = listOf("played", "won", "drawn", "lost", "goals_for", "goals_against", "goal_difference", "points")
            .map(::ProviderStandingColumn)
        val rows = array.mapIndexedNotNull { index, element ->
            val row = element.asObjectOrNull() ?: return@mapIndexedNotNull null
            val name = row.stringOrNull("teamName") ?: return@mapIndexedNotNull null
            val values = mapOf<String, Int?>(
                "played" to row.intOrNull("matches"),
                "won" to row.intOrNull("won"),
                "drawn" to row.intOrNull("draw"),
                "lost" to row.intOrNull("lost"),
                "goals_for" to row.intOrNull("goals"),
                "goals_against" to row.intOrNull("opponentGoals"),
                "goal_difference" to (row.intOrNull("goalDiff")
                    ?: row.intOrNull("goals")?.minus(row.intOrNull("opponentGoals") ?: 0)),
                "points" to row.intOrNull("points"),
            ).mapValues { (_, value) -> value?.toString() }
            ProviderStandingRow(
                competitor = ProviderCompetitor(
                    name = name,
                    id = row.intOrNull("teamInfoId")?.toString(),
                    kind = ProviderCompetitorKind.TEAM,
                ),
                rank = index + 1,
                values = values.filterValues { it != null },
            )
        }
        if (rows.isEmpty()) return invalidResponse()
        return SportsProviderResult.Success(ProviderStandings(
            league = ProviderLeague(scope.shortcut, scope.shortcut, "Football"),
            groups = listOf(ProviderStandingGroup(null, columns, rows)),
            season = scope.season.toString(),
        ))
    }

    private fun mapMatches(array: JsonArray, scope: OpenLigaDbScope): ProviderBatch<OpenLigaDbMatch> {
        val matches = mutableListOf<OpenLigaDbMatch>()
        var rejected = 0
        array.forEach { element ->
            val match = element.asObjectOrNull()?.let(::mapMatch)
            if (match == null || !match.belongsTo(scope)) rejected++ else matches += match
        }
        return ProviderBatch(matches, rejected)
    }

    private fun mapMatch(value: JsonObject): OpenLigaDbMatch? {
        val id = value.intOrNull("matchID")?.takeIf { it > 0 }?.toString() ?: return null
        val home = value.objOrNull("team1")?.toCompetitor(ProviderCompetitorRole.HOME) ?: return null
        val away = value.objOrNull("team2")?.toCompetitor(ProviderCompetitorRole.AWAY) ?: return null
        val leagueShortcut = value.stringOrNull("leagueShortcut") ?: return null
        val season = value.intOrNull("leagueSeason") ?: return null
        val leagueName = value.stringOrNull("leagueName") ?: leagueShortcut
        val start = (value.stringOrNull("matchDateTimeUTC") ?: value.stringOrNull("matchDateTime"))
            ?.toInstantOrNull() ?: return null
        val updatedAt = value.stringOrNull("lastUpdateDateTime")?.toInstantOrNull()
        val finished = value.booleanOrNull("matchIsFinished") ?: false
        val status = when {
            finished -> ProviderGameStatus(ProviderGameState.FINAL, updatedAt = updatedAt)
            start.isAfter(clock.instant()) -> ProviderGameStatus(ProviderGameState.SCHEDULED, updatedAt = updatedAt)
            !clock.instant().isAfter(start.plus(LIVE_MATCH_WINDOW)) ->
                ProviderGameStatus(ProviderGameState.LIVE, updatedAt = updatedAt)
            else -> ProviderGameStatus(ProviderGameState.UNKNOWN, updatedAt = updatedAt)
        }
        return OpenLigaDbMatch(
            raw = value,
            event = ProviderEvent(
                providerEventId = id,
                sport = "Football",
                league = ProviderLeague(leagueName, leagueShortcut, "Football"),
                competitors = listOf(home, away),
                eventStart = start,
                status = status,
            ),
        )
    }

    private fun mapScore(match: OpenLigaDbMatch): ProviderScore? {
        val first = match.event.competitors.firstOrNull { it.role == ProviderCompetitorRole.HOME } ?: return null
        val second = match.event.competitors.firstOrNull { it.role == ProviderCompetitorRole.AWAY } ?: return null
        val results = match.raw.arrayOrNull("matchResults")?.mapNotNull { it.asObjectOrNull() }.orEmpty()
            .mapNotNull { result ->
                val home = result.intOrNull("pointsTeam1") ?: return@mapNotNull null
                val away = result.intOrNull("pointsTeam2") ?: return@mapNotNull null
                val kind = result.stringOrNull("resultTypeKind").orEmpty().lowercase(Locale.ROOT)
                if (kind == "halftime") return@mapNotNull null
                ScoreResult(home, away, result.intOrNull("resultOrderID") ?: 0, when (kind) {
                    "afterpenalties" -> 4
                    "afterextratime" -> 3
                    "after90minutes" -> 2
                    else -> 1
                })
            }
        val selected = results.maxWithOrNull(compareBy<ScoreResult> { it.priority }.thenBy { it.order }) ?: return null
        return ProviderScore(
            providerEventId = match.event.providerEventId,
            scores = listOf(
                ProviderCompetitorScore(first, selected.home.toString()),
                ProviderCompetitorScore(second, selected.away.toString()),
            ),
            status = match.event.status,
            updatedAt = match.raw.stringOrNull("lastUpdateDateTime")?.toInstantOrNull(),
        )
    }

    private fun OpenLigaDbMatch.belongsTo(scope: OpenLigaDbScope): Boolean =
        raw.stringOrNull("leagueShortcut")?.equals(scope.shortcut, ignoreCase = true) == true &&
            raw.intOrNull("leagueSeason") == scope.season

    private fun resolveScope(leagueId: String?, season: String? = null): OpenLigaDbScope? {
        val configured = configuredScopes()
        if (leagueId == null) return configured.singleOrNull()
        val parsed = parseScope(leagueId)
        if (parsed != null) return parsed.takeIf { candidate ->
            configured.any { it.id.equals(candidate.id, ignoreCase = true) } &&
                (season == null || season == candidate.season.toString())
        }
        if (!leagueId.matches(LEAGUE_SHORTCUT)) return null
        val matches = configured.filter { it.shortcut.equals(leagueId, ignoreCase = true) &&
            (season == null || season == it.season.toString()) }
        return matches.maxByOrNull(OpenLigaDbScope::season)
    }

    private fun configuredScopes(): List<OpenLigaDbScope> = config.eventLeagueIds.mapNotNull(::parseScope)

    private fun apiUrl(endpointPath: String): HttpUrl? {
        return try {
            val root = endpointBaseUrl.toHttpUrlOrNull() ?: return null
            if (!root.isHttps && !allowInsecureHttpForTests) return null
            if (root.username.isNotEmpty() || root.password.isNotEmpty() || root.query != null || root.fragment != null) return null
            val builder = root.newBuilder()
            endpointPath.split('/').filter(String::isNotEmpty).forEach { builder.addPathSegment(it) }
            builder.build()
        } catch (_: Exception) { null }
    }

    private fun cacheKey(endpointPath: String) =
        "${config.instanceId}|${config.cacheRevision}|${endpointBaseUrl.lowercase(Locale.ROOT)}|" +
            endpointPath.lowercase(Locale.ROOT)

    private fun JsonObject.toCompetitor(role: ProviderCompetitorRole): ProviderCompetitor? {
        val name = stringOrNull("teamName")?.takeIf(String::isNotBlank) ?: return null
        return ProviderCompetitor(
            name = name,
            id = intOrNull("teamId")?.toString(),
            kind = ProviderCompetitorKind.TEAM,
            role = role,
        )
    }

    private fun String?.toInstantOrNull(): Instant? = this?.let { raw ->
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    }

    private fun parseArray(raw: String): JsonArray? = parseJson(raw)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun parseObject(raw: String): JsonObject? = parseJson(raw)?.asObjectOrNull()
    private fun parseJson(raw: String): JsonElement? = runCatching { JsonParser.parseString(raw) }.getOrNull()
    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.arrayOrNull(name: String): JsonArray? = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    private fun JsonObject.objOrNull(name: String): JsonObject? = get(name)?.asObjectOrNull()
    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive?.takeIf { it.isString }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeIf(JsonElement::isJsonPrimitive)
        ?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.booleanOrNull(name: String): Boolean? = get(name)?.takeIf(JsonElement::isJsonPrimitive)
        ?.let { runCatching { it.asBoolean }.getOrNull() }

    private fun String.isFootballName(): Boolean = trim().lowercase(Locale.ROOT) in setOf("football", "soccer")

    private data class OpenLigaDbMatch(val raw: JsonObject, val event: ProviderEvent)
    private data class ScoreResult(val home: Int, val away: Int, val order: Int, val priority: Int)

    private fun invalidConfiguration() = SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
    private fun invalidResponse() = SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    private fun responseTooLarge() = SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)

    private companion object {
        val FIXTURE_TTL: Duration = Duration.ofMinutes(5)
        val DETAIL_TTL: Duration = Duration.ofMinutes(2)
        val TABLE_TTL: Duration = Duration.ofMinutes(15)
        val GROUPS_TTL: Duration = Duration.ofMinutes(15)
        val MAX_EVENT_RANGE: Duration = Duration.ofDays(31)
        val LIVE_MATCH_WINDOW: Duration = Duration.ofHours(3)
        val LEAGUE_SHORTCUT = Regex("[A-Za-z0-9._-]{1,40}")
        val MATCH_ID = Regex("[1-9]\\d{0,9}")
        const val MAX_MATCHES = 500
        const val MAX_TEAMS = 100
        const val MAX_SCORE_IDS = 200
    }
}

private fun parseScope(value: String): OpenLigaDbScope? {
    val match = Regex("^([A-Za-z0-9._-]{1,40}):(\\d{4})$").matchEntire(value) ?: return null
    val year = match.groupValues[2].toIntOrNull()?.takeIf { it in 1900..2100 } ?: return null
    return OpenLigaDbScope(match.groupValues[1], year)
}

private fun path(vararg segments: String): String = segments.joinToString("/")

private fun <T, R> SportsProviderResult<T>.map(transform: (T) -> R): SportsProviderResult<R> = when (this) {
    is SportsProviderResult.Failure -> this
    is SportsProviderResult.Success -> SportsProviderResult.Success(transform(value))
}
