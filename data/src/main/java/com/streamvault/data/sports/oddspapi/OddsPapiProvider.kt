package com.streamvault.data.sports.oddspapi

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class OddsPapiProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val cache: OddsPapiResponseCache,
    private val cooldown: OddsPapiEndpointCooldown,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
        ),
        auth = SportsProviderAuth.ApiKeyQuery("apiKey"),
        defaultBaseUrl = BASE_URL,
        displayName = "OddsPapi",
        compliance = ProviderComplianceInfo(
            termsUrl = "https://oddspapi.io/en/legal/terms",
            documentationUrl = "https://oddspapi.io/en/docs",
            reviewedAt = LocalDate.of(2026, 10, 17),
            appStoreAllowed = null,
            commercialUseStatus = CommercialUseStatus.CONDITIONAL,
            artworkStatus = ArtworkUseStatus.DISABLED,
            releaseGate = ProviderReleaseGate.REQUIRES_VENDOR_CONFIRMATION,
        ),
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> =
        if (config.eventLeagueIds.size <= MAX_TOURNAMENT_SCOPES &&
            config.eventLeagueIds.all(::isTournamentId)
        ) descriptor.capabilities else emptySet()

    override fun create(config: SportsProviderConfig): SportsProvider = OddsPapiProvider(
        config = config,
        descriptor = descriptor,
        store = store,
        httpClient = httpClient,
        cache = cache,
        cooldown = cooldown,
    )

    companion object {
        const val TYPE_ID = "oddspapi"
        const val BASE_URL = "https://api.oddspapi.io/v4"
        const val MAX_TOURNAMENT_SCOPES = 5
        internal fun isTournamentId(value: String) = value.matches(Regex("[1-9]\\d{0,11}"))
    }
}

/** Credential-fingerprinted, bounded process-local responses; URLs and raw keys are never keys. */
@Singleton
class OddsPapiResponseCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Instant, val fingerprint: String)
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
        require(!ttl.isZero && !ttl.isNegative)
        entries[key] = Entry(value, now.plus(ttl), fingerprint)
        while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
    }

    @Synchronized
    fun clearCredential(fingerprint: String) {
        entries.entries.removeAll { it.value.fingerprint == fingerprint }
    }

    private companion object { const val MAX_ENTRIES = 256 }
}

/** Applies the per-endpoint cooldowns documented by OddsPapi, including concurrent callers. */
@Singleton
class OddsPapiEndpointCooldown @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val nextAllowedNanos = ConcurrentHashMap<String, Long>()

    suspend fun <T> execute(key: String, cooldownMillis: Long, action: suspend () -> T): T {
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            val waitNanos = (nextAllowedNanos[key] ?: 0L) - System.nanoTime()
            if (waitNanos > 0L) delay((waitNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI)
            try {
                action()
            } finally {
                nextAllowedNanos[key] = System.nanoTime() + cooldownMillis * NANOS_PER_MILLI
            }
        }
    }

    private companion object { const val NANOS_PER_MILLI = 1_000_000L }
}

private data class OddsPapiSport(val id: String, val slug: String, val name: String)

private data class OddsPapiFixture(
    val event: ProviderEvent,
    val first: ProviderCompetitor,
    val second: ProviderCompetitor,
    val fixtureId: String = event.providerEventId,
)

internal class OddsPapiProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val store: SportsProviderStore,
    private val httpClient: SportsProviderHttpClient,
    private val cache: OddsPapiResponseCache,
    private val cooldown: OddsPapiEndpointCooldown,
    private val endpointBaseUrl: String = OddsPapiProviderFactory.BASE_URL,
    private val allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : EventProvider, LiveScoreProvider, GameStatusProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(credentials: SportsProviderCredentials?): SportsProviderResult<Unit> {
        val supplied = credentials?.takeIf(::validCredentials) ?: return invalidConfiguration()
        if (configuredTournamentIds() == null) return invalidConfiguration()
        val url = apiUrl("/account") { } ?: return invalidConfiguration()
        return when (val account = getJson(url, supplied, LOOKUP_COOLDOWN_MILLIS)) {
            is SportsProviderResult.Failure -> account
            is SportsProviderResult.Success -> if (
                account.value.asObjectOrNull()?.arrayOrNull("subscriptions") != null
            ) SportsProviderResult.Success(Unit) else invalidResponse()
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val tournamentId = tournamentId(request.leagueId)
        if (tournamentId == INVALID_ID) return invalidConfiguration()
        val span = Duration.between(request.from, request.until)
        val maxDays = if (tournamentId != null) MAX_TOURNAMENT_EVENT_DAYS else MAX_SPORT_EVENT_DAYS
        if (span.isZero || span.isNegative || span > Duration.ofDays(maxDays)) return responseTooLarge()
        // Without a tournament filter, the API only permits a strict <48-hour date window.
        if (tournamentId == null && request.sport == null && span >= UNSCOPED_WINDOW) return responseTooLarge()

        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        val key = cacheKey("events", "${request.from}|${request.until}|${request.sport}|$tournamentId", fingerprint)
        val now = clock.instant()
        cache.get<ProviderBatch<ProviderEvent>>(key, now)?.let { return SportsProviderResult.Success(it) }

        val sportId = request.sport?.let { name ->
            when (val result = sports(credentials)) {
                is SportsProviderResult.Failure -> return result
                is SportsProviderResult.Success -> result.value.firstOrNull { matchesSport(it, name) }?.id
                    ?: return invalidConfiguration()
            }
        }
        if (sportId != null && tournamentId == null && span >= SPORT_WINDOW) return responseTooLarge()

        val url = apiUrl("/fixtures") {
            tournamentId?.let { addQueryParameter("tournamentId", it) }
            sportId?.let { addQueryParameter("sportId", it) }
            addQueryParameter("from", request.from.toString())
            addQueryParameter("to", request.until.toString())
        } ?: return invalidConfiguration()
        return when (val response = getJson(url, credentials, FIXTURE_COOLDOWN_MILLIS)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val array = response.value.takeIf { it.isJsonArray }?.asJsonArray ?: return invalidResponse()
                if (array.size() > MAX_FIXTURES) return responseTooLarge()
                val output = mutableListOf<ProviderEvent>()
                var rejected = 0
                array.forEach { item ->
                    val fixture = item.asObjectOrNull()?.let(::mapFixture)
                    if (fixture == null) {
                        rejected++
                    } else {
                        val start = fixture.event.eventStart
                        if (start != null && !start.isBefore(request.from) && start.isBefore(request.until)) {
                            output += fixture.event
                            cache.put(fixtureCacheKey(fixture.fixtureId, fingerprint), fingerprint, fixture,
                                FIXTURE_CACHE_TTL, now)
                        }
                    }
                }
                val batch = ProviderBatch(output, rejected)
                cache.put(key, fingerprint, batch, EVENT_CACHE_TTL, now)
                SportsProviderResult.Success(batch)
            }
        }
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> {
        if (request.eventIds.size > MAX_SCORE_FIXTURES || request.eventIds.any { !it.matches(FIXTURE_ID) }) {
            return if (request.eventIds.size > MAX_SCORE_FIXTURES) responseTooLarge() else invalidConfiguration()
        }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        val output = mutableListOf<ProviderScore>()
        var rejected = 0
        for (id in request.eventIds.sorted()) {
            when (val fixture = fixtureById(id, credentials, fingerprint)) {
                is SportsProviderResult.Failure -> return fixture
                is SportsProviderResult.Success -> when (val scores = scores(id, credentials, fingerprint)) {
                    is SportsProviderResult.Failure -> return scores
                    is SportsProviderResult.Success -> {
                        val parsed = mapScore(scores.value, fixture.value)
                        if (parsed == null) rejected++ else output += parsed
                    }
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(output, rejected))
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> {
        if (request.eventIds.size > MAX_SCORE_FIXTURES || request.eventIds.any { !it.matches(FIXTURE_ID) }) {
            return if (request.eventIds.size > MAX_SCORE_FIXTURES) responseTooLarge() else invalidConfiguration()
        }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val fingerprint = fingerprint(credentials.secret)
        val output = mutableListOf<ProviderEventStatus>()
        var rejected = 0
        for (id in request.eventIds.sorted()) {
            when (val fixture = fixtureById(id, credentials, fingerprint)) {
                is SportsProviderResult.Failure -> return fixture
                is SportsProviderResult.Success -> {
                    val status = fixture.value.event.status
                    if (status == null) rejected++ else output += ProviderEventStatus(id, status)
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(output, rejected))
    }

    private suspend fun sports(credentials: SportsProviderCredentials): SportsProviderResult<List<OddsPapiSport>> {
        val fingerprint = fingerprint(credentials.secret)
        val key = cacheKey("sports", "all", fingerprint)
        val now = clock.instant()
        cache.get<List<OddsPapiSport>>(key, now)?.let { return SportsProviderResult.Success(it) }
        val url = apiUrl("/sports") { addQueryParameter("language", "en") } ?: return invalidConfiguration()
        return when (val response = getJson(url, credentials, LOOKUP_COOLDOWN_MILLIS)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val array = response.value.takeIf { it.isJsonArray }?.asJsonArray ?: return invalidResponse()
                val values = array.mapNotNull { element ->
                    val item = element.asObjectOrNull() ?: return@mapNotNull null
                    val id = item.intOrNull("sportId")?.takeIf { it > 0 }?.toString() ?: return@mapNotNull null
                    val slug = item.stringOrNull("slug") ?: return@mapNotNull null
                    val name = item.stringOrNull("sportName") ?: return@mapNotNull null
                    OddsPapiSport(id, slug, name)
                }
                cache.put(key, fingerprint, values, SPORTS_CACHE_TTL, now)
                SportsProviderResult.Success(values)
            }
        }
    }

    private suspend fun fixtureById(
        id: String,
        credentials: SportsProviderCredentials,
        fingerprint: String,
    ): SportsProviderResult<OddsPapiFixture> {
        val key = fixtureCacheKey(id, fingerprint)
        cache.get<OddsPapiFixture>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        val url = apiUrl("/fixture") { addQueryParameter("fixtureId", id) } ?: return invalidConfiguration()
        return when (val response = getJson(url, credentials, FIXTURE_COOLDOWN_MILLIS)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val fixture = response.value.asObjectOrNull()?.let(::mapFixture) ?: return invalidResponse()
                if (fixture.fixtureId != id) return invalidResponse()
                cache.put(key, fingerprint, fixture, FIXTURE_CACHE_TTL, clock.instant())
                SportsProviderResult.Success(fixture)
            }
        }
    }

    private suspend fun scores(
        id: String,
        credentials: SportsProviderCredentials,
        fingerprint: String,
    ): SportsProviderResult<JsonObject> {
        val key = cacheKey("scores", id, fingerprint)
        cache.get<JsonObject>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        val url = apiUrl("/scores") { addQueryParameter("fixtureId", id) } ?: return invalidConfiguration()
        return when (val response = getJson(url, credentials, SCORE_COOLDOWN_MILLIS)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val value = response.value.asObjectOrNull() ?: return invalidResponse()
                if (value.stringOrNull("fixtureId") != id || value.objOrNull("scores") == null) return invalidResponse()
                cache.put(key, fingerprint, value, SCORE_CACHE_TTL, clock.instant())
                SportsProviderResult.Success(value)
            }
        }
    }

    private suspend fun getJson(
        url: HttpUrl,
        credentials: SportsProviderCredentials,
        cooldownMillis: Long,
    ): SportsProviderResult<JsonElement> {
        val fingerprint = fingerprint(credentials.secret)
        val endpoint = url.encodedPath
        val result = cooldown.execute("$fingerprint|$endpoint", cooldownMillis) {
            httpClient.get(
                url = url,
                auth = SportsProviderAuth.ApiKeyQuery("apiKey"),
                credentials = credentials,
                allowInsecureHttp = allowInsecureHttp,
            ) { text -> JsonParser.parseString(text) }
        }
        if (result is SportsProviderResult.Failure &&
            (result.httpStatus == 401 || result.httpStatus == 403)
        ) cache.clearCredential(fingerprint)
        return result
    }

    private fun mapFixture(value: JsonObject): OddsPapiFixture? {
        val id = value.stringOrNumber("fixtureId")?.takeIf { it.matches(FIXTURE_ID) } ?: return null
        val first = value.toCompetitor("participant1", ProviderCompetitorRole.HOME) ?: return null
        val second = value.toCompetitor("participant2", ProviderCompetitorRole.AWAY) ?: return null
        val start = value.stringOrNull("trueStartTime")?.toInstantOrNull()
            ?: value.stringOrNull("startTime")?.toInstantOrNull() ?: return null
        val sportName = value.stringOrNull("sportName") ?: value.stringOrNull("sportSlug") ?: return null
        val tournamentName = value.stringOrNull("tournamentName")
        val tournamentId = value.stringOrNumber("tournamentId")
        val updatedAt = value.stringOrNull("updatedAt")?.toInstantOrNull()
        val state = mapState(value.intOrNull("statusId"), value.stringOrNull("statusName"))
        val end = value.stringOrNull("trueEndTime")?.toInstantOrNull()
        if (end?.isBefore(start) == true) return null
        val event = ProviderEvent(
            providerEventId = id,
            sport = sportName,
            league = tournamentName?.let { ProviderLeague(it, tournamentId, sportName) },
            title = null,
            competitors = listOf(first, second),
            eventStart = start,
            eventEnd = end,
            status = ProviderGameStatus(state, detail = value.stringOrNull("statusName"), updatedAt = updatedAt),
        )
        return OddsPapiFixture(event, first, second, id)
    }

    private fun mapScore(value: JsonObject, fixture: OddsPapiFixture): ProviderScore? {
        val periods = value.objOrNull("scores") ?: return null
        val entries = periods.entrySet().mapNotNull { (_, period) ->
            period.asObjectOrNull()?.let { score ->
                Triple(score.intOrNull("participant1Score"), score.intOrNull("participant2Score"),
                    score.stringOrNull("updatedAt")?.toInstantOrNull())
            }
        }
        fun total(selector: (Triple<Int?, Int?, Instant?>) -> Int?): String? {
            val values = entries.mapNotNull(selector)
            return values.takeIf { it.isNotEmpty() }?.sum()?.toString()
        }
        val updatedAt = entries.mapNotNull { it.third }.maxOrNull()
        return ProviderScore(
            providerEventId = fixture.fixtureId,
            scores = listOf(
                ProviderCompetitorScore(fixture.first, total { it.first }),
                ProviderCompetitorScore(fixture.second, total { it.second }),
            ),
            status = fixture.event.status,
            updatedAt = updatedAt,
        )
    }

    private fun mapState(statusId: Int?, statusName: String?): ProviderGameState {
        val label = statusName.orEmpty().uppercase(Locale.ROOT)
        return when {
            "POSTPON" in label -> ProviderGameState.POSTPONED
            "SUSPEND" in label -> ProviderGameState.SUSPENDED
            "DELAY" in label -> ProviderGameState.DELAYED
            statusId == 0 -> ProviderGameState.SCHEDULED
            statusId == 1 -> ProviderGameState.LIVE
            statusId == 2 -> ProviderGameState.FINAL
            statusId == 3 -> ProviderGameState.CANCELLED
            else -> ProviderGameState.UNKNOWN
        }
    }

    private fun matchesSport(sport: OddsPapiSport, requested: String): Boolean {
        val normalized = requested.trim().lowercase(Locale.ROOT)
        val alias = if (normalized == "football") "soccer" else normalized
        return sport.slug.lowercase(Locale.ROOT) == alias || sport.name.lowercase(Locale.ROOT) == requested.lowercase(Locale.ROOT)
    }

    private fun JsonObject.toCompetitor(prefix: String, role: ProviderCompetitorRole): ProviderCompetitor? {
        val name = stringOrNull("${prefix}Name") ?: return null
        return ProviderCompetitor(
            name = name,
            id = stringOrNumber("${prefix}Id"),
            kind = ProviderCompetitorKind.TEAM,
            role = role,
        )
    }

    private fun tournamentId(requested: String?): String? {
        val configured = configuredTournamentIds() ?: return INVALID_ID
        val selected = requested ?: configured.singleOrNull() ?: return if (configured.isEmpty()) null else INVALID_ID
        if (!OddsPapiProviderFactory.isTournamentId(selected)) return INVALID_ID
        if (configured.isNotEmpty() && selected !in configured) return INVALID_ID
        return selected
    }

    private fun configuredTournamentIds(): List<String>? {
        if (config.eventLeagueIds.size > OddsPapiProviderFactory.MAX_TOURNAMENT_SCOPES) return null
        return config.eventLeagueIds.map { value ->
            value.takeIf(OddsPapiProviderFactory::isTournamentId) ?: return null
        }
    }

    private suspend fun storedCredentials() = store.credentials(config.instanceId)?.takeIf(::validCredentials)

    private fun apiUrl(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl? {
        return try {
            val root = endpointBaseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
            if (root.username.isNotEmpty() || root.password.isNotEmpty() || root.query != null || root.fragment != null) {
                return null
            }
            root.newBuilder().encodedPath("${root.encodedPath.trimEnd('/')}$path").apply(query).build()
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(resource: String, query: String, fingerprint: String) =
        "${config.instanceId}|${config.cacheRevision}|$fingerprint|$resource|$query"

    private fun fixtureCacheKey(id: String, fingerprint: String) = cacheKey("fixture", id, fingerprint)

    private fun validCredentials(credentials: SportsProviderCredentials?): Boolean =
        credentials?.secret?.let { it.isNotBlank() && it.length <= MAX_SECRET_LENGTH && '\r' !in it && '\n' !in it } == true

    private fun fingerprint(secret: String) = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun String.toInstantOrNull(): Instant? = runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

    private fun JsonObject.arrayOrNull(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.objOrNull(name: String) = get(name)?.asObjectOrNull()
    private fun JsonObject.stringOrNull(name: String) = get(name)?.takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive?.takeIf { it.isString }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.stringOrNumber(name: String) = get(name)?.takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive?.takeIf { it.isString || it.isNumber }?.asString?.takeIf(String::isNotBlank)
    private fun JsonObject.intOrNull(name: String) = get(name)?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun invalidConfiguration() = SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
    private fun invalidResponse() = SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    private fun responseTooLarge() = SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)

    private companion object {
        const val MAX_SECRET_LENGTH = 512
        const val MAX_FIXTURES = 500
        const val MAX_SCORE_FIXTURES = 10
        const val MAX_TOURNAMENT_EVENT_DAYS = 14L
        const val MAX_SPORT_EVENT_DAYS = 9L
        val FIXTURE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        val UNSCOPED_WINDOW = Duration.ofHours(48)
        val SPORT_WINDOW = Duration.ofDays(10)
        val EVENT_CACHE_TTL = Duration.ofMinutes(2)
        val FIXTURE_CACHE_TTL = Duration.ofMinutes(5)
        val SCORE_CACHE_TTL = Duration.ofSeconds(15)
        val SPORTS_CACHE_TTL = Duration.ofHours(24)
        const val FIXTURE_COOLDOWN_MILLIS = 2_000L
        const val SCORE_COOLDOWN_MILLIS = 1_000L
        const val LOOKUP_COOLDOWN_MILLIS = 1_000L
        const val INVALID_ID = "!invalid!"
    }
}
