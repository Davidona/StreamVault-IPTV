package com.streamvault.data.sports.thesportsdb

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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class TheSportsDbProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val cache: TheSportsDbResponseCache,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
        ),
        auth = SportsProviderAuth.ApiKeyHeader("X-API-KEY"),
        defaultBaseUrl = BASE_URL,
        supportsCustomBaseUrl = false,
    )

    override fun create(config: SportsProviderConfig): SportsProvider = TheSportsDbProvider(
        config = config,
        descriptor = descriptor,
        store = store,
        httpClient = httpClient,
        cache = cache,
    )

    companion object {
        const val TYPE_ID = "thesportsdb"
        const val BASE_URL = "https://www.thesportsdb.com/api/v2/json"
    }
}

/** Premium V2 adapter for league schedules and account-scoped live-score enrichment. */
internal class TheSportsDbProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val store: SportsProviderStore,
    private val httpClient: SportsProviderHttpClient,
    private val cache: TheSportsDbResponseCache,
    private val endpointBaseUrl: String = config.baseUrl ?: TheSportsDbProviderFactory.BASE_URL,
    private val allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : EventProvider, LiveScoreProvider, GameStatusProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> = when (val response = getObject(listOf("all", "sports"), credentials)) {
        is SportsProviderResult.Failure -> response
        is SportsProviderResult.Success -> {
            val sports = response.value.get("sports")
            if (sports == null || (!sports.isJsonNull && !sports.isJsonArray)) {
                SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
            } else {
                SportsProviderResult.Success(Unit)
            }
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val leagueId = request.leagueId?.takeIf(LEAGUE_ID::matches)
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        val credentials = storedCredentials() ?: return SportsProviderResult.Failure(
            SportsProviderError.INVALID_CONFIGURATION,
        )
        val seasons = when (val result = seasons(leagueId, credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        val selectedSeasons = seasons.mapNotNull(::parseSeasonRange)
            .filter { it.overlaps(request.from, request.until) }
        if (seasons.isNotEmpty() && selectedSeasons.isEmpty() && seasons.any { parseSeasonRange(it) == null }) {
            return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        }
        if (selectedSeasons.size > MAX_SEASONS_PER_REQUEST) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }

        val mappedSchedule = mutableListOf<MappedScheduleEvent>()
        var rejectedItemCount = 0
        for (season in selectedSeasons) {
            val scheduleKey = cacheKey("schedule", "$leagueId|${season.label}", credentials)
            val seasonBatch = cache.get<ProviderBatch<MappedScheduleEvent>>(scheduleKey, clock.instant()) ?: run {
                val envelope = when (val result = getObject(
                    listOf("schedule", "league", leagueId, season.label), credentials,
                )) {
                    is SportsProviderResult.Failure -> return result
                    is SportsProviderResult.Success -> result.value
                }
                val schedule = envelope.arrayOrEmpty("schedule")
                    ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
                val mapped = mapProviderItems(
                    schedule,
                    maxItems = MAX_MAPPED_ITEMS,
                    map = ::mapScheduleEvent,
                )
                if (mapped is SportsProviderResult.Failure) return mapped
                (mapped as SportsProviderResult.Success).value.also {
                    cache.put(scheduleKey, it, SCHEDULE_CACHE_TTL, clock.instant())
                }
            }
            if (mappedSchedule.size + rejectedItemCount + seasonBatch.items.size +
                seasonBatch.rejectedItemCount > MAX_MAPPED_ITEMS
            ) {
                return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
            }
            mappedSchedule.addAll(seasonBatch.items)
            rejectedItemCount += seasonBatch.rejectedItemCount
        }

        val filtered = mappedSchedule.asSequence()
            .filter { item -> item.event.league?.id?.let { it == leagueId } ?: true }
            .filter { item -> request.sport == null || item.event.sport.equals(request.sport, ignoreCase = true) }
            .filter { it.overlaps(request.from, request.until) }
            .map(MappedScheduleEvent::event)
            .toList()
        return SportsProviderResult.Success(ProviderBatch(filtered, rejectedItemCount = rejectedItemCount))
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> {
        val credentials = storedCredentials() ?: return SportsProviderResult.Failure(
            SportsProviderError.INVALID_CONFIGURATION,
        )
        val key = cacheKey("live-scores", "all", credentials)
        val allScores = cache.get<ProviderBatch<ProviderScore>>(key, clock.instant()) ?: run {
            val envelope = when (val result = getObject(listOf("livescore", "all"), credentials)) {
                is SportsProviderResult.Failure -> return result
                is SportsProviderResult.Success -> result.value
            }
            val records = envelope.arrayOrEmpty("livescore")
                ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
            val mapped = mapProviderItems(records, maxItems = MAX_MAPPED_ITEMS, map = ::mapLiveScore)
            if (mapped is SportsProviderResult.Failure) return mapped
            (mapped as SportsProviderResult.Success).value.also {
                cache.put(key, it, LIVE_SCORE_CACHE_TTL, clock.instant())
            }
        }
        val filtered = allScores.copy(items = allScores.items.filter { it.providerEventId in request.eventIds })
        return SportsProviderResult.Success(filtered)
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> = when (val result = liveScores(request)) {
        is SportsProviderResult.Failure -> result
        is SportsProviderResult.Success -> SportsProviderResult.Success(
            ProviderBatch(
                items = result.value.items.mapNotNull { score ->
                    score.status?.let { ProviderEventStatus(score.providerEventId, it) }
                },
                rejectedItemCount = result.value.rejectedItemCount,
            ),
        )
    }

    private suspend fun seasons(
        leagueId: String,
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<List<String>> {
        val key = cacheKey("seasons", leagueId, credentials)
        cache.get<List<String>>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        val envelope = when (val result = getObject(listOf("list", "seasons", leagueId), credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        val array = envelope.arrayOrEmpty("list")
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        val values = array.mapNotNull { item ->
            runCatching { item.asJsonObject.text("strSeason") }.getOrNull()
        }
        if (array.size() > 0 && values.isEmpty()) {
            return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        }
        return SportsProviderResult.Success(values.distinct()).also {
            cache.put(key, values.distinct(), SEASON_CACHE_TTL, clock.instant())
        }
    }

    private suspend fun storedCredentials(): SportsProviderCredentials? = try {
        store.credentials(instanceId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun getObject(
        path: List<String>,
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<JsonObject> {
        val base = endpointBaseUrl.toHttpUrlOrNull()
            ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        val url = try {
            path.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()
        } catch (_: IllegalArgumentException) {
            return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        }
        return httpClient.get(
            url = url,
            auth = descriptor.auth,
            credentials = credentials,
            allowInsecureHttp = allowInsecureHttp,
            decode = { JsonParser.parseString(it).asJsonObject },
        )
    }

    private fun cacheKey(
        kind: String,
        query: String,
        credentials: SportsProviderCredentials,
    ): String {
        val endpoint = endpointBaseUrl.toHttpUrlOrNull()?.toString() ?: endpointBaseUrl
        val identity = "$endpoint\u0000${credentials.username.orEmpty()}\u0000${credentials.secret}"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "${descriptor.typeId}:$instanceId:$fingerprint:$kind:$query"
    }

    private fun mapScheduleEvent(element: JsonElement): MappedScheduleEvent {
        val obj = element.asJsonObject
        val id = obj.text("idEvent") ?: throw IllegalArgumentException()
        val date = obj.text("dateEvent")?.let(::parseUtcDate)
            ?: obj.text("strTimestamp")?.let(::parseUtcDateTime)?.atZone(ZoneOffset.UTC)?.toLocalDate()
        val start = obj.text("strTimestamp")?.let(::parseUtcDateTime)
            ?: if (date != null) parseUtcDateTime(date, obj.text("strTime")) else null
        val sport = obj.text("strSport")
        val competitors = listOfNotNull(
            competitor(obj, "strHomeTeam", "idHomeTeam", ProviderCompetitorRole.HOME),
            competitor(obj, "strAwayTeam", "idAwayTeam", ProviderCompetitorRole.AWAY),
        )
        return MappedScheduleEvent(
            event = ProviderEvent(
                providerEventId = id,
                sport = sport,
                league = obj.text("strLeague")?.let { ProviderLeague(it, obj.text("idLeague"), sport) },
                title = obj.text("strEvent"),
                competitors = competitors,
                eventStart = start,
                status = mapStatus(obj.text("strStatus"), obj.text("strProgress"), null),
                session = obj.text("strGroup"),
            ),
            utcEventDate = date,
        )
    }

    private fun mapLiveScore(element: JsonElement): ProviderScore {
        val obj = element.asJsonObject
        val id = obj.text("idEvent") ?: throw IllegalArgumentException()
        val scores = listOfNotNull(
            score(obj, "strHomeTeam", "idHomeTeam", "intHomeScore", ProviderCompetitorRole.HOME),
            score(obj, "strAwayTeam", "idAwayTeam", "intAwayScore", ProviderCompetitorRole.AWAY),
        )
        val status = mapStatus(obj.text("strStatus"), obj.text("strProgress"), obj.text("updated"))
        return ProviderScore(
            providerEventId = id,
            scores = scores,
            status = status,
            updatedAt = obj.text("updated")?.let(::parseUtcDateTime),
        )
    }

    private fun competitor(
        obj: JsonObject,
        nameField: String,
        idField: String,
        role: ProviderCompetitorRole,
    ): ProviderCompetitor? = obj.text(nameField)?.let {
        ProviderCompetitor(name = it, id = obj.text(idField), kind = ProviderCompetitorKind.TEAM, role = role)
    }

    private fun score(
        obj: JsonObject,
        nameField: String,
        idField: String,
        valueField: String,
        role: ProviderCompetitorRole,
    ): ProviderCompetitorScore? = competitor(obj, nameField, idField, role)?.let {
        ProviderCompetitorScore(it, obj.text(valueField))
    }

    private fun mapStatus(rawStatus: String?, progress: String?, updated: String?): ProviderGameStatus? {
        val normalized = rawStatus?.trim()?.uppercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        val clock = progress?.trim()?.takeIf(String::isNotEmpty)
        if (normalized == null && clock == null) return null
        val state = when (normalized) {
            "NS", "TBD", "NOT STARTED", "SCHEDULED" -> ProviderGameState.SCHEDULED
            "FT", "AOT", "AP", "FINAL", "FINISHED", "FULL TIME", "GAME FINISHED", "MATCH FINISHED" ->
                ProviderGameState.FINAL
            "POST", "POSTPONED" -> ProviderGameState.POSTPONED
            "CANC", "CANCELLED", "CANCELED" -> ProviderGameState.CANCELLED
            "SUSP", "SUSPENDED" -> ProviderGameState.SUSPENDED
            "DELAYED", "RAIN DELAY", "WEATHER DELAY" -> ProviderGameState.DELAYED
            "LIVE", "IN PLAY", "IN PROGRESS", "1H", "2H", "HT", "ET", "BT",
            "Q1", "Q2", "Q3", "Q4", "OT", "P1", "P2", "P3", "SO", "1ST", "2ND", "3RD" ->
                ProviderGameState.LIVE
            else -> ProviderGameState.UNKNOWN
        }
        return ProviderGameStatus(
            state = state,
            clock = clock,
            period = normalized?.takeIf { state == ProviderGameState.LIVE },
            detail = rawStatus,
            updatedAt = updated?.let(::parseUtcDateTime),
        )
    }

    private fun parseUtcDateTime(value: String): Instant? =
        parseOffsetDateTime(value) ?: runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(value, SQL_DATE_TIME).toInstant(ZoneOffset.UTC)
            }.getOrNull()
        }

    private fun parseUtcDateTime(date: LocalDate, time: String?): Instant? = time?.let {
        runCatching { LocalDateTime.of(date, LocalTime.parse(it)).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun parseUtcDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun parseOffsetDateTime(value: String): Instant? =
        runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant() }.getOrNull()

    private data class MappedScheduleEvent(val event: ProviderEvent, val utcEventDate: LocalDate?) {
        fun overlaps(from: Instant, until: Instant): Boolean {
            event.eventStart?.let { return !it.isBefore(from) && it.isBefore(until) }
            val date = utcEventDate ?: return false
            val dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC)
            val nextDay = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            return dayStart.isBefore(until) && nextDay.isAfter(from)
        }
    }

    private data class SeasonRange(val label: String, private val firstYear: Int, private val lastYear: Int) {
        fun overlaps(from: Instant, until: Instant): Boolean {
            val start = LocalDate.of(firstYear, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
            val end = LocalDate.of(lastYear + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
            return start.isBefore(until) && end.isAfter(from)
        }
    }

    private fun parseSeasonRange(label: String): SeasonRange? {
        val match = SEASON_RANGE.matchEntire(label) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val suffix = match.groupValues[2]
        val last = if (suffix.isEmpty()) first else when (suffix.length) {
            2 -> ((first / 100) * 100 + suffix.toInt()).let { if (it < first) it + 100 else it }
            4 -> suffix.toIntOrNull() ?: return null
            else -> return null
        }
        if (first !in 1800..9998 || last !in first..9998) return null
        return SeasonRange(label, first, last)
    }

    private fun JsonObject.text(name: String): String? = get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.let { runCatching { it.asString }.getOrNull() }
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun JsonObject.arrayOrEmpty(name: String): JsonArray? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return JsonArray()
        return value.takeIf(JsonElement::isJsonArray)?.asJsonArray
    }

    companion object {
        private val SCHEDULE_CACHE_TTL = Duration.ofMinutes(15)
        private val LIVE_SCORE_CACHE_TTL = Duration.ofMinutes(2)
        private val SEASON_CACHE_TTL = Duration.ofHours(24)
        private val SQL_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        private val SEASON_RANGE = Regex("^(\\d{4})(?:-(\\d{2}|\\d{4}))?$")
        private val LEAGUE_ID = Regex("\\d{1,12}")
        private const val MAX_SEASONS_PER_REQUEST = 4
        private const val MAX_MAPPED_ITEMS = 10_000
    }
}
