package com.streamvault.data.sports.balldontlie

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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class BallDontLieSubscription(val sport: String, val tier: String, val active: Boolean)

data class BallDontLieAccountProfile(val subscriptions: List<BallDontLieSubscription>) {
    fun activeSports(): Set<String> = subscriptions.asSequence()
        .filter(BallDontLieSubscription::active)
        .map(BallDontLieSubscription::sport)
        .toSet()
}

class BallDontLieProviderFactory @Inject constructor(
    private val httpClient: SportsProviderHttpClient,
    private val store: SportsProviderStore,
    private val cache: BallDontLieResponseCache,
) : SportsProviderFactory {
    override val descriptor = SportsProviderDescriptor(
        typeId = TYPE_ID,
        capabilities = setOf(
            SportsCapability.EVENTS,
            SportsCapability.LIVE_SCORES,
            SportsCapability.GAME_STATUS,
            SportsCapability.STANDINGS,
        ),
        auth = SportsProviderAuth.ApiKeyHeader("Authorization"),
        defaultBaseUrl = BASE_URL,
        displayName = "BALLDONTLIE",
        compliance = ProviderComplianceInfo(
            termsUrl = "https://www.balldontlie.io/terms.html",
            documentationUrl = "https://www.balldontlie.io/docs/",
            reviewedAt = LocalDate.of(2026, 10, 17),
            attribution = null,
            appStoreAllowed = true,
            commercialUseStatus = CommercialUseStatus.PERMITTED,
            artworkStatus = ArtworkUseStatus.DISABLED,
            releaseGate = ProviderReleaseGate.APPROVED,
        ),
    )

    override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> {
        val sports = config.eventLeagueIds.map { it.lowercase(Locale.ROOT) }
            .ifEmpty { DEFAULT_SPORTS }
        return sports.mapNotNull(SPORTS::get).flatMapTo(mutableSetOf(), SportApi::capabilities)
    }

    override fun create(config: SportsProviderConfig): SportsProvider = BallDontLieProvider(
        config = config,
        descriptor = descriptor,
        store = store,
        httpClient = httpClient,
        cache = cache,
    )

    companion object {
        const val TYPE_ID = "balldontlie"
        const val BASE_URL = "https://api.balldontlie.io"
        val DEFAULT_SPORTS = listOf("nba", "nfl", "mlb", "epl")
        internal val SPORTS = mapOf(
            "nba" to SportApi("nba", "NBA", "/v1/games", "/v1/games", "/v1/standings", "Basketball", "free", "paid_plus"),
            "nfl" to SportApi("nfl", "NFL", "/nfl/v1/games", "/nfl/v1/games", "/nfl/v1/standings", "American football", "free", "paid"),
            "mlb" to SportApi("mlb", "MLB", "/mlb/v1/games", "/mlb/v1/games", "/mlb/v1/standings", "Baseball", "free", "paid"),
            "epl" to SportApi("epl", "Premier League", "/epl/v2/matches", "/epl/v2/matches", "/epl/v2/standings", "Football", "paid", "free"),
        )
    }
}

internal data class SportApi(
    val id: String,
    val displayName: String,
    val listPath: String,
    val detailPath: String,
    val standingsPath: String?,
    val sportName: String,
    val eventMinimumTier: String,
    val standingsMinimumTier: String,
) {
    val capabilities: Set<SportsCapability> = buildSet {
        add(SportsCapability.EVENTS)
        add(SportsCapability.LIVE_SCORES)
        add(SportsCapability.GAME_STATUS)
        if (standingsPath != null) add(SportsCapability.STANDINGS)
    }
}

internal class BallDontLieProvider(
    private val config: SportsProviderConfig,
    override val descriptor: SportsProviderDescriptor,
    private val store: SportsProviderStore,
    private val httpClient: SportsProviderHttpClient,
    private val cache: BallDontLieResponseCache,
    private val endpointBaseUrl: String = BallDontLieProviderFactory.BASE_URL,
    private val allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : EventProvider, LiveScoreProvider, GameStatusProvider, StandingsProvider, SportsProviderConnectionTester {
    override val instanceId: String = config.instanceId

    override suspend fun testConnection(
        credentials: SportsProviderCredentials?,
    ): SportsProviderResult<Unit> {
        val secret = credentials?.secret?.takeIf(::validSecret)
            ?: return invalidConfiguration()
        val profile = when (val result = accountProfile(credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        if (config.eventLeagueIds.any {
                it.lowercase(Locale.ROOT) !in BallDontLieProviderFactory.SPORTS
            }
        ) return invalidConfiguration()
        val selectedSports = selectedSports(profile)
        if (selectedSports.isEmpty()) {
            return if (config.eventLeagueIds.isNotEmpty() || profile.activeSports().any {
                    it in BallDontLieProviderFactory.SPORTS
                }
            ) authenticationFailure() else invalidConfiguration()
        }
        if (config.eventLeagueIds.isNotEmpty() && selectedSports.size != config.eventLeagueIds.size) {
            return authenticationFailure()
        }
        val first = selectedSports.first()
        val probeUrl = url(BallDontLieProviderFactory.SPORTS.getValue(first).listPath) {
            addQueryParameter("per_page", "1")
        }
        return when (val response = getObject(probeUrl, SportsProviderAuth.ApiKeyHeader("Authorization"), credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> if (response.value.arrayOrEmpty("data") != null) {
                cache.put(cacheKey("account", secret), profile, ACCOUNT_CACHE_TTL, clock.instant())
                SportsProviderResult.Success(Unit)
            } else SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        }
    }

    override suspend fun events(
        request: ProviderEventRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        val fromDate = request.from.atOffset(ZoneOffset.UTC).toLocalDate()
        val untilDate = request.until.minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate()
        if (untilDate.isBefore(fromDate) || ChronoUnit.DAYS.between(fromDate, untilDate) > MAX_EVENT_DAYS) {
            return invalidConfiguration()
        }
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val profile = when (val result = accountProfile(credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        val sports = request.leagueId?.let { listOf(it.lowercase(Locale.ROOT)) }
            ?: selectedSports(profile)
        if (sports.isEmpty() || sports.any { it !in BallDontLieProviderFactory.SPORTS }) {
            return invalidConfiguration()
        }
        validateEventAccess(profile, sports)?.let { return it }
        val all = mutableListOf<ProviderEvent>()
        var rejected = 0
        for (sportId in sports) {
            if (sportId !in profile.activeSports()) continue
            val sport = BallDontLieProviderFactory.SPORTS.getValue(sportId)
            val cacheKey = cacheKey(
                "events",
                "$sportId|${fromDate}|${untilDate}|${config.cacheRevision}",
                credentials,
            )
            val now = clock.instant()
            val cached = cache.get<ProviderBatch<ProviderEvent>>(cacheKey, now)
            if (cached != null) {
                all += cached.items
                rejected += cached.rejectedItemCount
                continue
            }
            val dates = generateSequence(fromDate) { previous ->
                previous.plusDays(1).takeIf { !it.isAfter(untilDate) }
            }.toList()
            val query = url(sport.listPath) {
                addQueryParameter("per_page", PAGE_SIZE.toString())
                dates.forEach { addQueryParameter("dates[]", it.toString()) }
            }
            when (val response = pagedEvents(sport, query, dates, request.from, request.until, credentials)) {
                is SportsProviderResult.Failure -> return response
                is SportsProviderResult.Success -> {
                    val batch = response.value
                    all += batch.items
                    rejected += batch.rejectedItemCount
                    cache.put(cacheKey, batch, EVENT_CACHE_TTL, now)
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(all.distinctBy(ProviderEvent::providerEventId), rejected))
    }

    override suspend fun liveScores(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderScore>> {
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val sportIds = groupEventIds(request.eventIds).map { it.first }
        when (val access = verifyEventAccess(credentials, sportIds)) {
            is SportsProviderResult.Failure -> return access
            is SportsProviderResult.Success -> Unit
        }
        val results = mutableListOf<ProviderScore>()
        var rejected = 0
        for ((sportId, ids) in groupEventIds(request.eventIds)) {
            val sport = BallDontLieProviderFactory.SPORTS[sportId] ?: return invalidConfiguration()
            for (externalId in ids.take(MAX_SCORE_DETAILS_PER_SPORT)) {
                val key = cacheKey("detail", "$sportId|$externalId|${config.cacheRevision}", credentials)
                val snapshot = cache.get<GameSnapshot>(key, clock.instant())
                    ?: when (val result = fetchDetail(sport, externalId, credentials)) {
                        is SportsProviderResult.Failure -> return result
                        is SportsProviderResult.Success -> result.value.also {
                            cache.put(key, it, LIVE_SCORE_CACHE_TTL, clock.instant())
                        }
                    }
                when (val mapped = mapProviderItems(listOf(snapshot), maxItems = 1) { game ->
                    game.toScore()
                }) {
                    is SportsProviderResult.Failure -> rejected++
                    is SportsProviderResult.Success -> {
                        results += mapped.value.items
                        rejected += mapped.value.rejectedItemCount
                    }
                }
            }
        }
        return SportsProviderResult.Success(ProviderBatch(results, rejected))
    }

    override suspend fun gameStatuses(
        request: ProviderScoreRequest,
    ): SportsProviderResult<ProviderBatch<ProviderEventStatus>> {
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val sportIds = groupEventIds(request.eventIds).map { it.first }
        when (val access = verifyEventAccess(credentials, sportIds)) {
            is SportsProviderResult.Failure -> return access
            is SportsProviderResult.Success -> Unit
        }
        val results = mutableListOf<ProviderEventStatus>()
        var rejected = 0
        for ((sportId, ids) in groupEventIds(request.eventIds)) {
            val sport = BallDontLieProviderFactory.SPORTS[sportId] ?: return invalidConfiguration()
            for (externalId in ids.take(MAX_SCORE_DETAILS_PER_SPORT)) {
                val key = cacheKey("detail", "$sportId|$externalId|${config.cacheRevision}", credentials)
                val snapshot = cache.get<GameSnapshot>(key, clock.instant())
                    ?: when (val result = fetchDetail(sport, externalId, credentials)) {
                        is SportsProviderResult.Failure -> return result
                        is SportsProviderResult.Success -> result.value.also {
                            cache.put(key, it, LIVE_SCORE_CACHE_TTL, clock.instant())
                        }
                    }
                val status = snapshot.event.status ?: continue
                results += ProviderEventStatus(snapshot.event.providerEventId, status)
            }
        }
        return SportsProviderResult.Success(ProviderBatch(results, rejected))
    }

    override suspend fun standings(
        request: ProviderStandingsRequest,
    ): SportsProviderResult<ProviderStandings> {
        val sport = BallDontLieProviderFactory.SPORTS[request.leagueId.lowercase(Locale.ROOT)]
            ?: return invalidConfiguration()
        val path = sport.standingsPath ?: return invalidConfiguration()
        val year = request.season?.toIntOrNull() ?: currentSeasonYear(sport.id, clock.instant())
        if (year !in 1900..9998) return invalidConfiguration()
        val credentials = storedCredentials() ?: return invalidConfiguration()
        val profile = when (val result = accountProfile(credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        val subscription = profile.subscriptions.firstOrNull { it.sport == sport.id && it.active }
            ?: return invalidConfiguration()
        if (!hasTier(sport.standingsMinimumTier, subscription.tier)) {
            return SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, httpStatus = 401)
        }
        val key = cacheKey("standings", "${sport.id}|$year|${config.cacheRevision}", credentials)
        cache.get<ProviderStandings>(key, clock.instant())?.let { return SportsProviderResult.Success(it) }
        val uri = url(path) { addQueryParameter("season", year.toString()) }
        return when (val response = getObject(uri, SportsProviderAuth.ApiKeyHeader("Authorization"), credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> mapStandings(response.value, sport, year).also { mapped ->
                if (mapped is SportsProviderResult.Success) {
                    cache.put(key, mapped.value, STANDINGS_CACHE_TTL, clock.instant())
                }
            }
        }
    }

    private suspend fun pagedEvents(
        sport: SportApi,
        firstPageUrl: HttpUrl,
        dates: List<LocalDate>,
        from: Instant,
        until: Instant,
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<ProviderBatch<ProviderEvent>> {
        var nextCursor: String? = null
        val mapped = mutableListOf<GameSnapshot>()
        var rejected = 0
        for (page in 0 until MAX_EVENT_PAGES) {
            val pageUrl = if (nextCursor == null) firstPageUrl else firstPageUrl.newBuilder()
                .setQueryParameter("cursor", nextCursor).build()
            when (val result = getObject(pageUrl, SportsProviderAuth.ApiKeyHeader("Authorization"), credentials)) {
                is SportsProviderResult.Failure -> return result
                is SportsProviderResult.Success -> {
                    val obj = result.value
                    val rows = obj.arrayOrEmpty("data") ?: return invalidResponse()
                    when (val batch = mapProviderItems(rows, maxItems = MAX_PAGE_ITEMS) { row ->
                        mapGame(row, sport, dates, from, until)
                    }) {
                        is SportsProviderResult.Failure -> return batch
                        is SportsProviderResult.Success -> {
                            mapped += batch.value.items
                            rejected += batch.value.rejectedItemCount
                        }
                    }
                    nextCursor = obj.obj("meta")?.text("next_cursor")
                    if (nextCursor.isNullOrBlank()) {
                        return SportsProviderResult.Success(ProviderBatch(mapped.map(GameSnapshot::event), rejected))
                    }
                }
            }
        }
        return if (nextCursor.isNullOrBlank()) {
            SportsProviderResult.Success(ProviderBatch(mapped.map(GameSnapshot::event), rejected))
        } else SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
    }

    private fun mapGame(
        element: JsonElement,
        sport: SportApi,
        requestedDates: List<LocalDate>,
        from: Instant,
        until: Instant,
    ): GameSnapshot {
        val obj = element.asJsonObject
        val rawId = obj.text("id")?.takeIf(NUMERIC_ID::matches) ?: throw IllegalArgumentException()
        val start = (obj.text("datetime") ?: obj.text("date"))?.let(::parseInstant)
        val rawDate = obj.text("date")?.let(::parseDate)
        if (start != null && (start.isBefore(from) || !start.isBefore(until))) throw IllegalArgumentException()
        if (start == null && rawDate != null && rawDate !in requestedDates) throw IllegalArgumentException()
        val home = competitor(obj, sport, listOf("home_team", "homeTeam"), listOf("home_team_id", "homeTeamId"), ProviderCompetitorRole.HOME)
        val away = competitor(
            obj,
            sport,
            listOf("visitor_team", "away_team", "visitorTeam", "awayTeam"),
            listOf("visitor_team_id", "away_team_id", "visitorTeamId", "awayTeamId"),
            ProviderCompetitorRole.AWAY,
        )
        val eplTeams = if (sport.id == "epl" && (home == null || away == null)) {
            parseMatchName(obj.text("name") ?: obj.text("short_name"))
        } else null
        val competitors = listOfNotNull(
            home ?: eplTeams?.second?.let { ProviderCompetitor(it, obj.text("home_team_id"), ProviderCompetitorKind.TEAM, ProviderCompetitorRole.HOME) },
            away ?: eplTeams?.first?.let { ProviderCompetitor(it, obj.text("away_team_id"), ProviderCompetitorKind.TEAM, ProviderCompetitorRole.AWAY) },
        )
        if (competitors.isEmpty()) throw IllegalArgumentException()
        val event = ProviderEvent(
            providerEventId = "${sport.id}:$rawId",
            sport = sport.sportName,
            league = ProviderLeague(sport.displayName, sport.id, sport.sportName),
            title = obj.text("name") ?: competitors.joinToString(" vs ") { it.name },
            competitors = competitors,
            eventStart = start,
            status = status(obj),
        )
        val homeScore = firstText(obj, "home_team_score", "home_score")
        val awayScore = firstText(obj, "visitor_team_score", "away_team_score", "away_score")
        val scores = listOfNotNull(
            competitors.firstOrNull { it.role == ProviderCompetitorRole.HOME }
                ?.let { ProviderCompetitorScore(it, homeScore) },
            competitors.firstOrNull { it.role == ProviderCompetitorRole.AWAY }
                ?.let { ProviderCompetitorScore(it, awayScore) },
        )
        return GameSnapshot(event, scores)
    }

    private suspend fun fetchDetail(
        sport: SportApi,
        rawId: String,
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<GameSnapshot> {
        if (!NUMERIC_ID.matches(rawId)) return invalidConfiguration()
        val uri = url("${sport.detailPath}/$rawId") { }
        return when (val response = getObject(uri, SportsProviderAuth.ApiKeyHeader("Authorization"), credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val element = response.value.get("data")
                    ?.takeUnless(JsonElement::isJsonNull)
                    ?.let { if (it.isJsonArray) it.asJsonArray.firstOrNull() else it }
                    ?: return invalidResponse()
                val mapped = runCatching {
                    mapGame(element, sport, emptyList(), Instant.MIN, Instant.MAX)
                }.getOrNull() ?: return invalidResponse()
                SportsProviderResult.Success(mapped)
            }
        }
    }

    private fun mapStandings(
        envelope: JsonObject,
        sport: SportApi,
        season: Int,
    ): SportsProviderResult<ProviderStandings> {
        val rows = envelope.arrayOrEmpty("data") ?: return invalidResponse()
        return when (val mapped = mapProviderItems(rows, maxItems = MAX_STANDINGS_ROWS) { element ->
            val row = element.asJsonObject
            val team = row.obj("team") ?: throw IllegalArgumentException()
            val teamId = team.text("id")?.takeIf(NUMERIC_ID::matches) ?: throw IllegalArgumentException()
            val name = team.text("full_name") ?: team.text("display_name")
                ?: team.text("name") ?: throw IllegalArgumentException()
            val values: Map<String, String?>
            val rank: Int
            val group: String?
            if (sport.id == "nba") {
                rank = row.int("conference_rank") ?: throw IllegalArgumentException()
                group = team.text("conference")
                values = mapOf(
                    "wins" to row.text("wins"),
                    "losses" to row.text("losses"),
                    "conference_record" to row.text("conference_record"),
                    "division_record" to row.text("division_record"),
                )
            } else if (sport.id == "nfl") {
                rank = row.int("playoff_seed") ?: row.int("division_rank")
                    ?: throw IllegalArgumentException()
                group = team.text("conference") ?: team.text("division")
                values = mapOf(
                    "wins" to row.text("wins"),
                    "losses" to row.text("losses"),
                    "ties" to row.text("ties"),
                    "overall_record" to row.text("overall_record"),
                    "points_for" to row.text("points_for"),
                    "points_against" to row.text("points_against"),
                )
            } else if (sport.id == "mlb") {
                // MLB's published standings shape has no regular-season rank field.
                // The final group ordering below derives rank from win percentage.
                rank = row.int("playoff_seed") ?: 1
                group = row.text("division_name") ?: row.text("league_name")
                values = mapOf(
                    "games_played" to row.text("games_played"),
                    "wins" to row.text("wins"),
                    "losses" to row.text("losses"),
                    "ties" to row.text("ties"),
                    "win_percent" to row.text("win_percent"),
                    "points_for" to row.text("points_for"),
                    "points_against" to row.text("points_against"),
                )
            } else {
                rank = row.int("rank") ?: throw IllegalArgumentException()
                group = row.text("group_name")
                values = mapOf(
                    "played" to row.text("games_played"),
                    "won" to row.text("wins"),
                    "drawn" to row.text("draws"),
                    "lost" to row.text("losses"),
                    "goals_for" to row.text("goals_for"),
                    "goals_against" to row.text("goals_against"),
                    "goal_difference" to row.text("goal_differential"),
                    "points" to row.text("points"),
                )
            }
            StandingRow(group, ProviderStandingRow(
                competitor = ProviderCompetitor(name, "${sport.id}:$teamId", ProviderCompetitorKind.TEAM),
                rank = rank,
                values = values.filterValues { it != null },
            ))
        }) {
            is SportsProviderResult.Failure -> mapped
            is SportsProviderResult.Success -> {
            val columns = if (sport.id == "nba") listOf(
                    ProviderStandingColumn("wins"),
                    ProviderStandingColumn("losses"),
                ) else if (sport.id == "nfl") listOf(
                    ProviderStandingColumn("wins"),
                    ProviderStandingColumn("losses"),
                    ProviderStandingColumn("ties"),
                ) else if (sport.id == "mlb") listOf(
                    ProviderStandingColumn("games_played"),
                    ProviderStandingColumn("wins"),
                    ProviderStandingColumn("losses"),
                    ProviderStandingColumn("ties"),
                ) else listOf(
                    ProviderStandingColumn("played"),
                    ProviderStandingColumn("won"),
                    ProviderStandingColumn("drawn"),
                    ProviderStandingColumn("lost"),
                    ProviderStandingColumn("goals_for"),
                    ProviderStandingColumn("goals_against"),
                    ProviderStandingColumn("goal_difference"),
                    ProviderStandingColumn("points"),
                )
                val visibleColumnKeys = columns.mapTo(mutableSetOf(), ProviderStandingColumn::key)
                val groups = mapped.value.items.groupBy(StandingRow::groupName)
                    .toSortedMap(compareBy(nullsFirst()) { it })
                    .map { (group, groupRows) ->
                        val rankedRows = if (sport.id == "mlb") {
                            groupRows.sortedWith(
                                compareByDescending<StandingRow> {
                                    it.row.values["win_percent"]?.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
                                }.thenBy { it.row.competitor.name },
                            ).mapIndexed { index, entry -> entry.row.copy(rank = index + 1) }
                        } else {
                            groupRows.map(StandingRow::row).sortedBy(ProviderStandingRow::rank)
                        }
                        ProviderStandingGroup(
                            name = group,
                            columns = columns,
                            rows = rankedRows.map { row ->
                                row.copy(values = row.values.filterKeys(visibleColumnKeys::contains))
                            },
                        )
                    }
                SportsProviderResult.Success(ProviderStandings(
                    league = ProviderLeague(sport.displayName, sport.id, sport.sportName),
                    groups = groups,
                    season = season.toString(),
                ))
            }
        }
    }

    private suspend fun accountProfile(
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<BallDontLieAccountProfile> {
        val secret = credentials.secret.takeIf(::validSecret) ?: return invalidConfiguration()
        val key = cacheKey("account", secret)
        cache.get<BallDontLieAccountProfile>(key, clock.instant())?.let {
            return SportsProviderResult.Success(it)
        }
        val uri = url("/account/v1/subscriptions") { }
        return when (val response = getObject(uri, SportsProviderAuth.Bearer, credentials)) {
            is SportsProviderResult.Failure -> response
            is SportsProviderResult.Success -> {
                val rows = response.value.arrayOrEmpty("data") ?: return invalidResponse()
                when (val mapped = mapProviderItems(rows, maxItems = MAX_SUBSCRIPTIONS) { element ->
                    val row = element.asJsonObject
                    val sport = row.text("sport")?.lowercase(Locale.ROOT)
                        ?.takeIf { it.matches(SPORT_ID) } ?: throw IllegalArgumentException()
                    val tier = row.text("tier")?.lowercase(Locale.ROOT) ?: "free"
                    val status = row.text("status")?.lowercase(Locale.ROOT)
                    BallDontLieSubscription(
                        sport = sport,
                        tier = tier,
                        active = status == null || status in setOf("active", "trialing"),
                    )
                }) {
                    is SportsProviderResult.Failure -> mapped
                    is SportsProviderResult.Success -> {
                        val profile = BallDontLieAccountProfile(mapped.value.items.distinctBy(BallDontLieSubscription::sport))
                        if (profile.activeSports().none { it in BallDontLieProviderFactory.SPORTS }) {
                            return invalidConfiguration()
                        }
                        cache.put(key, profile, ACCOUNT_CACHE_TTL, clock.instant())
                        SportsProviderResult.Success(profile)
                    }
                }
            }
        }
    }

    private suspend fun getObject(
        uri: HttpUrl,
        auth: SportsProviderAuth,
        credentials: SportsProviderCredentials,
    ): SportsProviderResult<JsonObject> = httpClient.get(
        uri,
        auth,
        credentials,
        allowInsecureHttp = allowInsecureHttp,
    ) { body -> JsonParser.parseString(body).asJsonObject }

    private fun selectedSports(profile: BallDontLieAccountProfile): List<String> {
        val requested = config.eventLeagueIds.map { it.lowercase(Locale.ROOT) }
        val candidates = requested.ifEmpty {
            profile.activeSports().filter { it in BallDontLieProviderFactory.SPORTS }.sorted()
        }
        return candidates.distinct().filter { sportId ->
            val sport = BallDontLieProviderFactory.SPORTS[sportId] ?: return@filter false
            val subscription = profile.subscriptions.firstOrNull {
                it.sport == sportId && it.active
            } ?: return@filter false
            hasTier(sport.eventMinimumTier, subscription.tier)
        }
    }

    private fun validateEventAccess(
        profile: BallDontLieAccountProfile,
        sportIds: List<String>,
    ): SportsProviderResult.Failure? {
        val configuredScopes = config.eventLeagueIds.map { it.lowercase(Locale.ROOT) }.toSet()
        if (sportIds.any { it !in BallDontLieProviderFactory.SPORTS } ||
            (configuredScopes.isNotEmpty() && sportIds.any { it !in configuredScopes })
        ) return invalidConfiguration()
        val subscriptions = profile.subscriptions.associateBy(BallDontLieSubscription::sport)
        for (sportId in sportIds) {
            val sport = BallDontLieProviderFactory.SPORTS.getValue(sportId)
            val subscription = subscriptions[sportId]?.takeIf(BallDontLieSubscription::active)
                ?: return authenticationFailure()
            if (!hasTier(sport.eventMinimumTier, subscription.tier)) return authenticationFailure()
        }
        return null
    }

    private suspend fun verifyEventAccess(
        credentials: SportsProviderCredentials,
        sportIds: List<String>,
    ): SportsProviderResult<Unit> {
        if (sportIds.isEmpty()) return SportsProviderResult.Success(Unit)
        val profile = when (val result = accountProfile(credentials)) {
            is SportsProviderResult.Failure -> return result
            is SportsProviderResult.Success -> result.value
        }
        return validateEventAccess(profile, sportIds)?.let { it } ?: SportsProviderResult.Success(Unit)
    }

    private fun groupEventIds(ids: Set<String>): List<Pair<String, List<String>>> {
        if (ids.size > MAX_SCORE_EVENT_IDS) return emptyList()
        return ids.mapNotNull { value ->
            val split = value.split(':', limit = 2)
            if (split.size != 2 || split[0] !in BallDontLieProviderFactory.SPORTS ||
                !NUMERIC_ID.matches(split[1])
            ) null
            else split[0] to split[1]
        }.groupBy({ it.first }, { it.second }).toSortedMap().map { (sport, values) -> sport to values.distinct() }
    }

    private fun status(obj: JsonObject): ProviderGameStatus? {
        val stateText = obj.text("status_state")?.lowercase(Locale.ROOT)
        val original = obj.text("status")
        val state = when (stateText) {
            "scheduled" -> ProviderGameState.SCHEDULED
            "in_progress" -> ProviderGameState.LIVE
            "final" -> ProviderGameState.FINAL
            "postponed" -> ProviderGameState.POSTPONED
            "canceled", "cancelled" -> ProviderGameState.CANCELLED
            "delayed" -> ProviderGameState.DELAYED
            "suspended" -> ProviderGameState.SUSPENDED
            else -> mapStatusText(original)
        }
        if (state == null && original == null) return null
        return ProviderGameStatus(
            state = state ?: ProviderGameState.UNKNOWN,
            clock = obj.text("time")?.takeUnless { it.equals("Final", true) },
            period = obj.text("period"),
            detail = obj.text("status_detail") ?: original,
            updatedAt = obj.text("updated_at")?.let(::parseInstant),
        )
    }

    private fun mapStatusText(value: String?): ProviderGameState? = when (
        value?.trim()?.lowercase(Locale.ROOT)
    ) {
        null, "" -> null
        "final", "finished", "complete", "completed", "ft", "full time", "game finished" -> ProviderGameState.FINAL
        "postponed" -> ProviderGameState.POSTPONED
        "canceled", "cancelled" -> ProviderGameState.CANCELLED
        "delayed" -> ProviderGameState.DELAYED
        "suspended" -> ProviderGameState.SUSPENDED
        "scheduled", "not started", "tbd" -> ProviderGameState.SCHEDULED
        else -> if (value.contains(Regex("\\d{1,2}:\\d{2}"))) ProviderGameState.LIVE else ProviderGameState.UNKNOWN
    }

    private fun competitor(
        obj: JsonObject,
        sport: SportApi,
        objectFields: List<String>,
        idFields: List<String>,
        role: ProviderCompetitorRole,
    ): ProviderCompetitor? {
        val team = objectFields.firstNotNullOfOrNull(obj::obj)
        val id = idFields.firstNotNullOfOrNull(obj::text) ?: team?.text("id")
        val name = team?.text("full_name") ?: team?.text("name") ?: team?.text("short_name")
        if (name == null || id == null) return null
        return ProviderCompetitor(name, "${sport.id}:$id", ProviderCompetitorKind.TEAM, role)
    }

    private fun parseMatchName(name: String?): Pair<String, String>? {
        val value = name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val separator = listOf(" at ", " vs ", " v ").firstOrNull { value.contains(it, ignoreCase = true) }
            ?: return null
        val index = value.indexOf(separator, ignoreCase = true)
        val first = value.substring(0, index).trim().takeIf(String::isNotEmpty) ?: return null
        val second = value.substring(index + separator.length).trim().takeIf(String::isNotEmpty) ?: return null
        // BALLDONTLIE's documented EPL title is "away at home".
        return first to second
    }

    private fun GameSnapshot.toScore(): ProviderScore =
        ProviderScore(event.providerEventId, scores, event.status, clock.instant())

    private fun currentSeasonYear(sportId: String, now: Instant): Int {
        val date = now.atOffset(ZoneOffset.UTC).toLocalDate()
        return when (sportId) {
            "nba" -> if (date.monthValue >= 10) date.year else date.year - 1
            "epl" -> if (date.monthValue >= 8) date.year else date.year - 1
            "mlb", "nfl" -> date.year
            else -> date.year
        }
    }

    private fun hasTier(minimumTier: String, tier: String): Boolean =
        when (minimumTier) {
            "free" -> tier in setOf("free", "paid", "paid_plus", "all_star", "goat", "all_access", "all_access_v3")
            "paid" -> tier in setOf("paid", "paid_plus", "all_star", "goat", "all_access", "all_access_v3")
            "paid_plus" -> tier in setOf("paid_plus", "goat", "all_access", "all_access_v3")
            else -> false
        }

    private fun cacheKey(kind: String, query: String, credentials: SportsProviderCredentials): String =
        cacheKey(kind, query, credentials.secret)

    private fun cacheKey(kind: String, secret: String): String = cacheKey(kind, "", secret)

    private fun cacheKey(kind: String, query: String, secret: String): String {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${descriptor.typeId}:${instanceId}:${config.cacheRevision}:$fingerprint:$kind:$query"
    }

    private fun url(path: String, configure: HttpUrl.Builder.() -> Unit): HttpUrl {
        val base = endpointBaseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException()
        return base.newBuilder().addPathSegments(path.trimStart('/')).apply(configure).build()
    }

    private suspend fun storedCredentials(): SportsProviderCredentials? =
        store.credentials(instanceId)?.takeIf { validSecret(it.secret) }

    private fun validSecret(value: String): Boolean =
        value.isNotBlank() && '\r' !in value && '\n' !in value

    private fun invalidConfiguration() = SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
    private fun invalidResponse() = SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    private fun authenticationFailure() =
        SportsProviderResult.Failure(SportsProviderError.AUTHENTICATION, httpStatus = 401)

    private data class GameSnapshot(
        val event: ProviderEvent,
        val scores: List<ProviderCompetitorScore>,
    )
    private data class StandingRow(val groupName: String?, val row: ProviderStandingRow)

    private companion object {
        val ACCOUNT_CACHE_TTL = Duration.ofMinutes(5)
        val EVENT_CACHE_TTL = Duration.ofMinutes(2)
        val LIVE_SCORE_CACHE_TTL = Duration.ofMinutes(1)
        val STANDINGS_CACHE_TTL = Duration.ofMinutes(30)
        val SPORT_ID = Regex("[a-z0-9_-]{1,64}")
        val NUMERIC_ID = Regex("[0-9]{1,18}")
        const val PAGE_SIZE = 100
        const val MAX_PAGE_ITEMS = 100
        const val MAX_EVENT_PAGES = 3
        const val MAX_EVENT_DAYS = 30L
        const val MAX_STANDINGS_ROWS = 500
        const val MAX_SUBSCRIPTIONS = 100
        const val MAX_SCORE_EVENT_IDS = 60
        const val MAX_SCORE_DETAILS_PER_SPORT = 4
    }
}

@Singleton
class BallDontLieResponseCache @Inject constructor() {
    private data class Entry(val value: Any, val expiresAt: Instant)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

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

    private companion object { const val MAX_ENTRIES = 192 }
}

private fun JsonObject.text(name: String): String? = get(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.let { runCatching { it.asString }.getOrNull() }
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.int(name: String): Int? = text(name)?.toIntOrNull()
private fun firstText(obj: JsonObject, vararg names: String): String? = names.firstNotNullOfOrNull(obj::text)
private fun JsonObject.obj(name: String): JsonObject? = get(name)
    ?.takeUnless(JsonElement::isJsonNull)
    ?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.arrayOrEmpty(name: String): JsonArray? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return JsonArray()
    return value.takeIf(JsonElement::isJsonArray)?.asJsonArray
}

private fun parseInstant(value: String): Instant? = runCatching {
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant()
}.getOrNull()

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
