package com.streamvault.data.sports.custom

import com.streamvault.data.sports.provider.SportsProviderHttpClient
import com.streamvault.domain.sports.provider.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
        capabilities = setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES),
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
) : EventProvider, LiveScoreProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> {
        val mapping = definition.events
        val now = Instant.now()
        return when (val response = fetchJson(
            mapping.endpoint,
            eventVariables(
                ProviderEventRequest(now, now.plusSeconds(TEST_WINDOW_SECONDS), config.eventLeagueIds.firstOrNull()),
            ),
            credentials,
        )) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> when (val mapped = CustomSportsProviderJsonMapper.events(
                mapping,
                response.value,
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
        val response = fetchJson(definition.events.endpoint, eventVariables(request), credentials)
        return when (response) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.events(
                definition.events,
                response.value,
            )
        }
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
        val sortedIds = request.eventIds.sorted()
        if (mapping.endpoint.contains("{eventId}")) {
            val combined = mutableListOf<ProviderScore>()
            var rejected = 0
            for (eventId in sortedIds) {
                val response = fetchJson(mapping.endpoint, mapOf("eventId" to eventId), credentials)
                when (response) {
                    is SportsProviderResult.Failure -> return response
                    is SportsProviderResult.Success -> when (
                        val mapped = CustomSportsProviderJsonMapper.scores(mapping, response.value)
                    ) {
                        is SportsProviderResult.Failure -> return mapped
                        is SportsProviderResult.Success -> {
                            combined += mapped.value.items.filter { it.providerEventId == eventId }
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

        val response = fetchJson(
            mapping.endpoint,
            mapOf("eventIds" to sortedIds.joinToString(",")),
            credentials,
        )
        return when (response) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> CustomSportsProviderJsonMapper.scores(
                mapping,
                response.value,
                request.eventIds,
            )
        }
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

    private fun eventVariables(request: ProviderEventRequest): Map<String, String> = mapOf(
        "fromDate" to request.from.toString(),
        "toDate" to request.until.toString(),
        "date" to LocalDate.ofInstant(request.from, ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE),
        "leagueId" to request.leagueId.orEmpty(),
        "sport" to request.sport.orEmpty(),
    )

    private companion object {
        const val TEST_WINDOW_SECONDS = 24 * 60 * 60L
        const val MAX_SCORE_EVENT_IDS = 50
        const val MAX_SCORE_ROWS = 1_000
    }
}

internal data class CustomSportsProviderDefinition(
    val name: String,
    val auth: SportsProviderAuth,
    val events: CustomEventMapping,
    val scores: CustomScoreMapping?,
) {
    val capabilities: Set<SportsCapability>
        get() = buildSet {
            add(SportsCapability.EVENTS)
            if (scores != null) add(SportsCapability.LIVE_SCORES)
        }

    companion object {
        fun parse(source: String): CustomSportsProviderDefinition = try {
            require(
                source.isNotBlank() &&
                    source.length <= CUSTOM_SPORTS_PROVIDER_MAX_CONFIGURATION_CHARS,
            )
            val root = Json.parseToJsonElement(source).jsonObject
            requireKeys(root, setOf("name", "auth", "events", "scores"))
            val name = root.requiredString("name", maxLength = 64)
            require(name.none { Character.isISOControl(it) })
            val auth = root["auth"]?.jsonObject?.let(::parseAuth) ?: SportsProviderAuth.None
            val events = root["events"]?.jsonObject?.let(::parseEvents)
                ?: throw IllegalArgumentException()
            val scores = root["scores"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseScores)
            CustomSportsProviderDefinition(name, auth, events, scores)
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
                "endpoint", "items", "id", "sport", "leagueName", "leagueId", "title", "start",
                "startFormat", "homeName", "homeId", "awayName", "awayId", "competitorKind",
                "status", "clock", "period",
            ))
            return CustomEventMapping(
                endpoint = value.requiredEndpoint("endpoint", EVENT_PLACEHOLDERS),
                items = value.requiredSelector("items", allowRoot = true),
                id = value.requiredSelector("id"),
                sport = value.optionalSelector("sport"),
                leagueName = value.optionalSelector("leagueName"),
                leagueId = value.optionalSelector("leagueId"),
                title = value.optionalSelector("title"),
                start = value.requiredSelector("start"),
                startFormat = value.requiredTimestampFormat("startFormat"),
                homeName = value.requiredSelector("homeName"),
                homeId = value.optionalSelector("homeId"),
                awayName = value.requiredSelector("awayName"),
                awayId = value.optionalSelector("awayId"),
                competitorKind = value.optionalCompetitorKind("competitorKind")
                    ?: ProviderCompetitorKind.TEAM,
                status = value.optionalSelector("status"),
                clock = value.optionalSelector("clock"),
                period = value.optionalSelector("period"),
            )
        }

        private fun parseScores(value: JsonObject): CustomScoreMapping {
            requireKeys(value, setOf(
                "endpoint", "items", "eventId", "homeName", "homeScore", "awayName", "awayScore",
                "status", "clock", "period", "updatedAt", "updatedAtFormat",
            ))
            val endpoint = value.requiredEndpoint("endpoint", SCORE_PLACEHOLDERS)
            require(("{eventId}" in endpoint) xor ("{eventIds}" in endpoint))
            val homeScore = value.optionalSelector("homeScore")
            val awayScore = value.optionalSelector("awayScore")
            val status = value.optionalSelector("status")
            require(homeScore != null || awayScore != null || status != null)
            val homeName = value.optionalSelector("homeName")
            val awayName = value.optionalSelector("awayName")
            require((homeScore == null || homeName != null) && (awayScore == null || awayName != null))
            return CustomScoreMapping(
                endpoint = endpoint,
                items = value.requiredSelector("items", allowRoot = true),
                eventId = value.requiredSelector("eventId"),
                homeName = homeName,
                homeScore = homeScore,
                awayName = awayName,
                awayScore = awayScore,
                status = status,
                clock = value.optionalSelector("clock"),
                period = value.optionalSelector("period"),
                updatedAt = value.optionalSelector("updatedAt"),
                updatedAtFormat = value.optionalTimestampFormat("updatedAtFormat")
                    ?: ProviderTimestampFormat.ISO_OFFSET,
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

        private fun requireSelector(value: String, allowRoot: Boolean = false) {
            if (allowRoot && value == "$") return
            val path = value.removePrefix("$.")
            require(path.isNotBlank() && path.split('.').size <= MAX_SELECTOR_DEPTH)
            require(path.split('.').all { SELECTOR_SEGMENT.matches(it) })
        }

        private fun JsonObject.requiredTimestampFormat(key: String): ProviderTimestampFormat =
            ProviderTimestampFormat.entries.firstOrNull { it.name == requiredString(key, maxLength = 32) }
                ?: throw IllegalArgumentException()

        private fun JsonObject.optionalTimestampFormat(key: String): ProviderTimestampFormat? {
            val value = this[key]?.jsonPrimitive?.content ?: return null
            return ProviderTimestampFormat.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException()
        }

        private fun JsonObject.optionalCompetitorKind(key: String): ProviderCompetitorKind? {
            val value = this[key]?.jsonPrimitive?.content ?: return null
            return ProviderCompetitorKind.entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException()
        }

        private fun JsonObject.requiredString(key: String, maxLength: Int): String {
            val value = this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content?.trim()
                ?: throw IllegalArgumentException()
            require(value.isNotEmpty() && value.length <= maxLength)
            return value
        }

        private fun requireKeys(value: JsonObject, allowed: Set<String>) {
            require(value.keys.all(allowed::contains))
        }

        private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
        private val SELECTOR_SEGMENT = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_SELECTOR_DEPTH = 16
        private val EVENT_PLACEHOLDERS = setOf("date", "fromDate", "toDate", "leagueId", "sport")
        private val SCORE_PLACEHOLDERS = setOf("eventId", "eventIds")
    }
}

internal data class CustomEventMapping(
    val endpoint: String,
    val items: String,
    val id: String,
    val sport: String?,
    val leagueName: String?,
    val leagueId: String?,
    val title: String?,
    val start: String,
    val startFormat: ProviderTimestampFormat,
    val homeName: String,
    val homeId: String?,
    val awayName: String,
    val awayId: String?,
    val competitorKind: ProviderCompetitorKind,
    val status: String?,
    val clock: String?,
    val period: String?,
)

internal data class CustomScoreMapping(
    val endpoint: String,
    val items: String,
    val eventId: String,
    val homeName: String?,
    val homeScore: String?,
    val awayName: String?,
    val awayScore: String?,
    val status: String?,
    val clock: String?,
    val period: String?,
    val updatedAt: String?,
    val updatedAtFormat: ProviderTimestampFormat,
)

internal object CustomSportsProviderJsonMapper {
    fun events(
        mapping: CustomEventMapping,
        body: JsonElement,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> =
        mapItems(body, mapping.items, MAX_EVENT_ROWS) { record ->
            val eventId = record.scalar(mapping.id) ?: throw IllegalArgumentException()
            val start = record.scalar(mapping.start)?.let { parseProviderTimestamp(it, mapping.startFormat) }
                ?: throw IllegalArgumentException()
            val competitors = buildList {
                record.scalar(mapping.homeName)?.let { name ->
                    add(ProviderCompetitor(
                        name = name,
                        id = mapping.homeId?.let { selector -> record.scalar(selector) },
                        kind = mapping.competitorKind,
                        role = ProviderCompetitorRole.HOME,
                    ))
                }
                record.scalar(mapping.awayName)?.let { name ->
                    add(ProviderCompetitor(
                        name = name,
                        id = mapping.awayId?.let { selector -> record.scalar(selector) },
                        kind = mapping.competitorKind,
                        role = ProviderCompetitorRole.AWAY,
                    ))
                }
            }
            if (competitors.isEmpty()) throw IllegalArgumentException()
            ProviderEvent(
                providerEventId = eventId,
                sport = mapping.sport?.let { selector -> record.scalar(selector) },
                league = mapping.leagueName?.let { selector -> record.scalar(selector) }?.let { name ->
                    ProviderLeague(name, mapping.leagueId?.let { selector -> record.scalar(selector) })
                },
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
                mapping.homeScore?.let { selector ->
                    record.scalar(selector)?.let { score ->
                        add(ProviderCompetitorScore(
                            ProviderCompetitor(
                                name = requireNotNull(mapping.homeName?.let { nameSelector ->
                                    record.scalar(nameSelector)
                                }),
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
                                name = requireNotNull(mapping.awayName?.let { nameSelector ->
                                    record.scalar(nameSelector)
                                }),
                                role = ProviderCompetitorRole.AWAY,
                            ),
                            score,
                        ))
                    }
                }
            }
            ProviderScore(eventId, values, gameStatus, updatedAt)
        }.filterIds(requestedIds)

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

    private fun SportsProviderResult<ProviderBatch<ProviderScore>>.filterIds(
        requestedIds: Set<String>?,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> = when (this) {
        is SportsProviderResult.Failure -> this
        is SportsProviderResult.Success -> if (requestedIds == null) this else SportsProviderResult.Success(
            ProviderBatch(value.items.filter { it.providerEventId in requestedIds }, value.rejectedItemCount),
        )
    }

    private fun JsonElement.select(selector: String): JsonElement? {
        if (selector == "$") return this
        var current: JsonElement = this
        for (part in selector.removePrefix("$.").split('.')) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }

    private fun JsonObject.scalar(selector: String): String? =
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
    ): ProviderGameStatus {
        val state = when (value.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "scheduled", "not_started", "pending", "pre_game" -> ProviderGameState.SCHEDULED
            "live", "in_play", "in_progress", "inprogress", "halftime", "half_time" -> ProviderGameState.LIVE
            "suspended" -> ProviderGameState.SUSPENDED
            "delayed", "delay" -> ProviderGameState.DELAYED
            "postponed" -> ProviderGameState.POSTPONED
            "cancelled", "canceled" -> ProviderGameState.CANCELLED
            "final", "finished", "complete", "completed" -> ProviderGameState.FINAL
            else -> ProviderGameState.UNKNOWN
        }
        return ProviderGameStatus(state, clock, period, detail = value.takeIf { state == ProviderGameState.UNKNOWN }, updatedAt)
    }

    private const val MAX_EVENT_ROWS = 2_000
    private const val MAX_SCORE_ROWS = 1_000
    private const val MAX_SCALAR_CHARS = 512
}

/** Resolve paths beneath the configured API base path; dynamic values are percent encoded. */
internal fun buildCustomSportsProviderUrl(
    baseUrl: HttpUrl,
    endpointTemplate: String,
    variables: Map<String, String>,
): HttpUrl? = runCatching {
    require(baseUrl.isHttps && baseUrl.username.isEmpty() && baseUrl.password.isEmpty())
    require(endpointTemplate.startsWith('/') && !endpointTemplate.startsWith("//"))
    require('#' !in endpointTemplate && '\\' !in endpointTemplate && '\r' !in endpointTemplate && '\n' !in endpointTemplate)
    val expanded = Regex("\\{([A-Za-z][A-Za-z0-9]*)}").replace(endpointTemplate) { match ->
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
