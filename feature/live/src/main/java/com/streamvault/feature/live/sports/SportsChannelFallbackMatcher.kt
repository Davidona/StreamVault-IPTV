package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import com.streamvault.domain.sports.provider.ProviderBroadcaster
import com.streamvault.domain.util.ChannelNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.util.Locale

private val CHANNEL_NAME_DECORATION = setOf(
    "4k", "alt", "backup", "feed", "fhd", "h265", "hd", "hevc", "raw", "uhd", "vip",
)

internal data class SportsChannelIndexEntry(
    val channel: Channel,
    val combinedProfileId: Long?,
)

internal data class SportsChannelMatch(
    val entry: SportsChannelIndexEntry,
    val score: Int,
    val reasons: Set<SportsChannelMatchReason>,
)

internal enum class SportsChannelMatchReason {
    TEAM_PAIR,
    FIGHTER_PAIR,
    EVENT_NAME,
    BROADCASTER,
    SERIES,
    MATCHING_DATE,
    DIRECT_MAPPING,
    MANUAL_SEARCH,
}

/** Normalized once for an effective playable-channel set and reused across events. */
internal class SportsChannelIndex private constructor(
    val entries: List<SportsChannelIndexEntry>,
    private val dateSources: List<String>,
    private val normalizedNames: List<String>,
    private val tokensByIndex: List<Set<String>>,
    private val tokenToIndices: Map<String, Set<Int>>,
) {
    val byCanonicalName: Map<String, List<Int>> by lazy {
        entries.indices.groupBy { index ->
            val channel = entries[index].channel
            SportsTextNormalizer.normalize(channel.canonicalName.ifBlank { channel.name })
                .split(' ').filterNot { it in CHANNEL_NAME_DECORATION }.joinToString(" ")
        }.filterKeys(String::isNotBlank)
    }

    val byEpgId: Map<String, List<Int>> by lazy {
        entries.indices.mapNotNull { index ->
            entries[index].channel.epgChannelId?.trim()?.takeIf(String::isNotBlank)
                ?.lowercase(Locale.ROOT)?.let { it to index }
        }.groupBy({ it.first }, { it.second })
    }

    fun candidateIndices(tokens: Set<String>): Set<Int> = buildSet {
        tokens.forEach { token -> addAll(tokenToIndices[token].orEmpty()) }
    }

    fun normalizedName(index: Int): String = normalizedNames[index]

    fun tokens(index: Int): Set<String> = tokensByIndex[index]

    fun dateSource(index: Int): String = dateSources[index]

    companion object {
        fun build(entries: List<SportsChannelIndexEntry>): SportsChannelIndex {
            val playableEntries = entries.asSequence()
                .filter { entry ->
                    entry.channel.streamUrl.isNotBlank() &&
                        !entry.channel.isAdult &&
                        !entry.channel.isUserProtected
                }
                .distinctBy { entry -> "${entry.channel.providerId}:${entry.channel.id}" }
                .toList()
            val dateSources = playableEntries.map { entry ->
                listOfNotNull(
                    entry.channel.name,
                    entry.channel.canonicalName,
                    entry.channel.groupTitle,
                ).joinToString(" ")
            }
            val normalizedNames = dateSources.map { source ->
                SportsTextNormalizer.normalize(source)
            }
            // Repeated country, sport, league and group tokens dominate large catalogs.
            // Share their strings across channels rather than retaining a copy per row.
            val tokenPool = hashMapOf<String, String>()
            val tokensByIndex = normalizedNames.map { name ->
                name.split(' ').asSequence().filter(String::isNotBlank)
                    .map { token -> tokenPool.getOrPut(token) { token } }.toSet()
            }
            val tokenToIndices: Map<String, Set<Int>> = buildMap<String, MutableSet<Int>> {
                tokensByIndex.forEachIndexed { index, tokens ->
                    tokens.forEach { token -> getOrPut(token) { linkedSetOf() } += index }
                }
            }
            return SportsChannelIndex(
                entries = playableEntries,
                dateSources = dateSources,
                normalizedNames = normalizedNames,
                tokensByIndex = tokensByIndex,
                tokenToIndices = tokenToIndices,
            )
        }
    }
}

internal object SportsChannelFallbackMatcher {
    private const val MIN_MATCH_SCORE = 70
    private const val TEAM_PAIR_SCORE = 100
    private const val EVENT_NAME_SCORE = 90
    private const val BROADCASTER_SCORE = 110
    private const val SERIES_SCORE = 15
    private const val MATCHING_DATE_SCORE = 20
    private const val MIN_DISTINCTIVE_SURNAME_LENGTH = 5
    private const val MAX_BOUNDARY_DATE_DIFFERENCE_DAYS = 1L
    private const val EVENT_DAY_BOUNDARY_TOLERANCE_HOURS = 2L
    private const val MAX_AUTOMATIC_EVENT_MATCHES = 8

    private val matchOrder = Comparator<SportsChannelMatch> { first, second ->
        second.score.compareTo(first.score)
            .takeIf { it != 0 }
            ?: first.entry.channel.name.compareTo(second.entry.channel.name, ignoreCase = true)
                .takeIf { it != 0 }
            ?: first.entry.channel.providerId.compareTo(second.entry.channel.providerId)
                .takeIf { it != 0 }
            ?: first.entry.channel.id.compareTo(second.entry.channel.id)
    }

    private val eventNoise = setOf(
        "and", "at", "basketball", "card", "day", "direct", "direto", "event", "fight",
        "final", "football", "game", "golf", "highlights", "live", "main", "match", "mma",
        "motorsport", "night", "practice", "prelims", "preview", "qualifying", "race", "racing",
        "replay", "round", "session", "soccer", "sport", "sports", "tennis", "the", "undercard",
        "vs", "vivo",
    )

    private val qualityNoise = CHANNEL_NAME_DECORATION

    private val commonFightSurnames = setOf(
        "ali", "brown", "davis", "garcia", "jones", "johnson", "kim", "lee", "li", "martin",
        "miller", "moore", "rodriguez", "santos", "silva", "smith", "wang", "yu",
    )

    private val isoDatePattern = Regex("\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
    private val numericDatePattern = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})(?:[-/.](\\d{2,4}))?\\b")
    private val monthFirstPattern = Regex("\\b([a-z]{3,9})\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?\\b")
    private val dayFirstPattern = Regex("\\b(\\d{1,2})\\s+([a-z]{3,9})(?:\\s+(\\d{4}))?\\b")
    private val monthNames = mapOf(
        "jan" to Month.JANUARY, "january" to Month.JANUARY,
        "feb" to Month.FEBRUARY, "february" to Month.FEBRUARY,
        "mar" to Month.MARCH, "march" to Month.MARCH,
        "apr" to Month.APRIL, "april" to Month.APRIL,
        "may" to Month.MAY,
        "jun" to Month.JUNE, "june" to Month.JUNE,
        "jul" to Month.JULY, "july" to Month.JULY,
        "aug" to Month.AUGUST, "august" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "september" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER, "october" to Month.OCTOBER,
        "nov" to Month.NOVEMBER, "november" to Month.NOVEMBER,
        "dec" to Month.DECEMBER, "december" to Month.DECEMBER,
    )

    fun findMatches(
        event: SportsEvent,
        index: SportsChannelIndex,
        excludedChannelKeys: Set<String> = event.broadcasts.mapTo(mutableSetOf()) { broadcast ->
            channelKey(broadcast.channel)
        },
        zoneId: ZoneId = ZoneId.systemDefault(),
        limit: Int = 8,
    ): List<SportsChannelMatch> {
        if (limit <= 0 || index.entries.isEmpty()) return emptyList()
        val isFight = event.sport == SportType.MMA || event.sport == SportType.BOXING
        val competitorAliases = event.competitors.map { competitor ->
            buildSet {
                addAll(SportsAliasRegistry.aliasesForCanonical(competitor.canonicalName))
                if (isFight) {
                    val surname = SportsTextNormalizer.normalize(competitor.displayName)
                        .split(' ')
                        .lastOrNull()
                        .orEmpty()
                    if (surname.length >= MIN_DISTINCTIVE_SURNAME_LENGTH && surname !in commonFightSurnames) {
                        add(surname)
                    }
                }
            }
        }
        val leagueAliases = SportsLeagueRegistry.aliasesFor(event.league)
        val eventTokens = eventSpecificTokens(event, leagueAliases)
        val broadcasterNames = event.provenance.broadcasters?.value.orEmpty()
            .map { broadcaster -> broadcaster.normalizedChannelName() }
            .filter(String::isNotBlank)
            .toSet()
        val queryTokens = buildSet {
            competitorAliases.flatten().forEach { alias ->
                addAll(SportsTextNormalizer.normalize(alias).split(' ').filter(String::isNotBlank))
            }
            addAll(eventTokens)
            leagueAliases.forEach { alias ->
                addAll(SportsTextNormalizer.normalize(alias).split(' ').filter(String::isNotBlank))
            }
            broadcasterNames.forEach { broadcaster ->
                addAll(broadcaster.split(' ').filter(String::isNotBlank))
            }
        }
        if (queryTokens.isEmpty()) return emptyList()

        val matches = ArrayList<SportsChannelMatch>(limit)
        for (candidateIndex in index.candidateIndices(queryTokens)) {
            val entry = index.entries[candidateIndex]
            if (channelKey(entry.channel) in excludedChannelKeys) continue
            val match = scoreCandidate(
                event = event,
                entry = entry,
                channelName = index.normalizedName(candidateIndex),
                channelTokens = index.tokens(candidateIndex),
                competitorAliases = competitorAliases,
                leagueAliases = leagueAliases,
                eventTokens = eventTokens,
                broadcasterNames = broadcasterNames,
                dateSource = index.dateSource(candidateIndex),
                zoneId = zoneId,
            ) ?: continue
            if (match.score >= MIN_MATCH_SCORE) insertTopMatch(matches, match, limit)
        }
        return matches
    }

    fun searchChannels(
        event: SportsEvent,
        index: SportsChannelIndex,
        query: String,
        limit: Int = MAX_MANUAL_SEARCH_RESULTS,
    ): List<SportsChannelMatch> {
        if (limit <= 0 || index.entries.isEmpty()) return emptyList()
        val normalizedQuery = SportsTextNormalizer.normalize(query)
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank).toSet()
        val hasQuery = queryTokens.isNotEmpty()
        val directKeys = event.broadcasts.mapTo(mutableSetOf()) { channelKey(it.channel) }
        val eventMatches = if (hasQuery) {
            emptyMap()
        } else {
            findMatches(
                event = event,
                index = index,
                excludedChannelKeys = emptySet(),
                limit = MAX_AUTOMATIC_EVENT_MATCHES,
            ).associateBy { match -> channelKey(match.entry.channel) }
        }
        val indexedCandidates = if (hasQuery) index.candidateIndices(queryTokens) else emptySet()
        val candidateIndices: Iterable<Int> = when {
            !hasQuery -> index.entries.indices
            indexedCandidates.isEmpty() -> index.entries.indices
            else -> indexedCandidates
        }
        val results = ArrayList<SportsChannelMatch>(limit)
        for (candidateIndex in candidateIndices) {
            val entry = index.entries[candidateIndex]
            val key = channelKey(entry.channel)
            val direct = key in directKeys
            val eventMatch = eventMatches[key]
            val queryScore = if (hasQuery) {
                manualQueryScore(
                    normalizedName = index.normalizedName(candidateIndex),
                    channelTokens = index.tokens(candidateIndex),
                    normalizedQuery = normalizedQuery,
                    queryTokens = queryTokens,
                )
            } else {
                0
            }
            if (hasQuery && queryScore == 0) continue
            if (!hasQuery && !direct && eventMatch == null) continue

            val reasons = linkedSetOf<SportsChannelMatchReason>()
            reasons += eventMatch?.reasons.orEmpty()
            if (direct) reasons += SportsChannelMatchReason.DIRECT_MAPPING
            if (hasQuery) reasons += SportsChannelMatchReason.MANUAL_SEARCH
            val score = if (hasQuery) {
                queryScore * 100 + (eventMatch?.score ?: 0) + if (direct) DIRECT_MAPPING_BONUS else 0
            } else {
                (eventMatch?.score ?: 0) * 10 + if (direct) DIRECT_MAPPING_BONUS else 0
            }
            insertTopMatch(results, SportsChannelMatch(entry, score, reasons), limit)
        }
        return results
    }

    fun channelKey(channel: Channel): String = "${channel.providerId}:${channel.id}"

    private fun scoreCandidate(
        event: SportsEvent,
        entry: SportsChannelIndexEntry,
        channelName: String,
        channelTokens: Set<String>,
        competitorAliases: List<Set<String>>,
        leagueAliases: Set<String>,
        eventTokens: Set<String>,
        broadcasterNames: Set<String>,
        dateSource: String,
        zoneId: ZoneId,
    ): SportsChannelMatch? {
        val isFight = event.sport == SportType.MMA || event.sport == SportType.BOXING
        val hasCompetitorPair = competitorAliases.size >= 2 && competitorAliases
            .take(2)
            .all { aliases -> aliases.any { alias -> containsPhrase(channelName, alias) } }
        val hasBroadcasterMatch = broadcasterNames.any { broadcaster ->
            broadcaster == normalizedBroadcasterName(entry.channel.name, entry.channel.providerId) ||
                broadcaster == normalizedBroadcasterName(entry.channel.canonicalName, entry.channel.providerId)
        }
        val reasons = linkedSetOf<SportsChannelMatchReason>()
        var score = 0
        var hasEventIdentityMatch = false

        if (competitorAliases.size >= 2) {
            if (hasCompetitorPair) {
                score += TEAM_PAIR_SCORE
                reasons += if (isFight) {
                    SportsChannelMatchReason.FIGHTER_PAIR
                } else {
                    SportsChannelMatchReason.TEAM_PAIR
                }
                hasEventIdentityMatch = true
            }
        } else {
            val fullTitleTokens = SportsTextNormalizer.normalize(event.title)
                .split(' ')
                .filter { token ->
                    token.isNotBlank() && token !in eventNoise && token !in qualityNoise && token.toIntOrNull() == null
                }
                .toSet()
            val hasEventPhrase = (eventTokens.size >= 2 && eventTokens.all(channelTokens::contains)) ||
                (eventTokens.isNotEmpty() && fullTitleTokens.size >= 2 && fullTitleTokens.all(channelTokens::contains))
            if (hasEventPhrase) {
                score += EVENT_NAME_SCORE
                reasons += SportsChannelMatchReason.EVENT_NAME
                hasEventIdentityMatch = true
            }
        }
        if (hasBroadcasterMatch) {
            score += BROADCASTER_SCORE
            reasons += SportsChannelMatchReason.BROADCASTER
        }
        if (!hasEventIdentityMatch && !hasBroadcasterMatch) return null

        if (leagueAliases.any { alias -> containsPhrase(channelName, alias) }) {
            score += SERIES_SCORE
            reasons += SportsChannelMatchReason.SERIES
        }

        val channelDate = parseChannelDate(dateSource) ?: return SportsChannelMatch(
            entry = entry,
            score = score,
            reasons = reasons,
        )
        if (!isDateCompatible(channelDate, event.startTime, zoneId)) return null
        score += MATCHING_DATE_SCORE
        reasons += SportsChannelMatchReason.MATCHING_DATE
        return SportsChannelMatch(entry, score, reasons)
    }

    private fun eventSpecificTokens(event: SportsEvent, leagueAliases: Set<String>): Set<String> {
        val leagueTokens = leagueAliases.flatMapTo(mutableSetOf()) { alias ->
            SportsTextNormalizer.normalize(alias).split(' ').filter(String::isNotBlank)
        }
        return SportsTextNormalizer.normalize(event.title)
            .split(' ')
            .filter { token ->
                token.isNotBlank() &&
                    token !in eventNoise &&
                    token !in qualityNoise &&
                    token !in leagueTokens &&
                    token.toIntOrNull() == null &&
                    (token.length >= 3 || token in SHORT_EVENT_TOKENS)
            }
            .toSet()
    }

    private fun ProviderBroadcaster.normalizedChannelName(): String =
        normalizedBroadcasterName(name, 0L)

    private fun normalizedBroadcasterName(name: String, providerId: Long): String =
        SportsTextNormalizer.normalize(ChannelNormalizer.classify(name, providerId).canonicalName)

    private fun containsPhrase(normalizedText: String, phrase: String): Boolean {
        val normalizedPhrase = SportsTextNormalizer.normalize(phrase)
        if (normalizedPhrase.isBlank()) return false
        return " $normalizedText ".contains(" $normalizedPhrase ")
    }

    private fun manualQueryScore(
        normalizedName: String,
        channelTokens: Set<String>,
        normalizedQuery: String,
        queryTokens: Set<String>,
    ): Int {
        if (normalizedName == normalizedQuery) return 300
        if (normalizedName.startsWith("$normalizedQuery ")) return 260
        if (" $normalizedQuery " in " $normalizedName ") return 220
        if (queryTokens.all(channelTokens::contains)) return 180 + queryTokens.size
        if (queryTokens.all { queryToken -> channelTokens.any { it.startsWith(queryToken) } }) return 120 + queryTokens.size
        return 0
    }

    private fun insertTopMatch(
        matches: MutableList<SportsChannelMatch>,
        match: SportsChannelMatch,
        limit: Int,
    ) {
        val insertionPoint = matches.binarySearch(match, comparator = matchOrder)
            .let { index -> if (index < 0) -index - 1 else index }
        if (insertionPoint >= limit) return
        matches.add(insertionPoint, match)
        if (matches.size > limit) matches.removeAt(matches.lastIndex)
    }

    private fun parseChannelDate(dateSource: String): ParsedChannelDate? {
        val searchableDate = dateSource.lowercase(Locale.ROOT)
        isoDatePattern.find(searchableDate)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val day = match.groupValues[3].toIntOrNull() ?: return@let
            return ParsedChannelDate(month, day, year)
        }
        monthFirstPattern.find(searchableDate)?.let { match ->
            val month = monthNames[match.groupValues[1]]?.value ?: return@let
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            val year = match.groupValues[3].toIntOrNull()
            return ParsedChannelDate(month, day, year)
        }
        dayFirstPattern.find(searchableDate)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = monthNames[match.groupValues[2]]?.value ?: return@let
            val year = match.groupValues[3].toIntOrNull()
            return ParsedChannelDate(month, day, year)
        }
        numericDatePattern.find(searchableDate)?.let { match ->
            val first = match.groupValues[1].toIntOrNull() ?: return@let
            val second = match.groupValues[2].toIntOrNull() ?: return@let
            val rawYear = match.groupValues[3].toIntOrNull()
            val (month, day) = when {
                rawYear != null && first > 12 && second <= 12 -> second to first
                rawYear != null && second > 12 && first <= 12 -> first to second
                rawYear != null -> first to second
                first > 12 && second <= 12 -> second to first
                second > 12 && first <= 12 -> first to second
                else -> return@let
            }
            val year = when {
                rawYear == null -> null
                rawYear < 100 -> if (rawYear >= 70) 1900 + rawYear else 2000 + rawYear
                else -> rawYear
            }
            return ParsedChannelDate(month, day, year)
        }
        return null
    }

    private fun isDateCompatible(
        channelDate: ParsedChannelDate,
        eventStartTime: Long,
        zoneId: ZoneId,
    ): Boolean {
        val eventInstant = Instant.ofEpochMilli(eventStartTime)
        val eventDateTime = eventInstant.atZone(zoneId)
        val eventDate = eventDateTime.toLocalDate()
        val expectedYear = channelDate.year ?: eventDate.year
        val candidateDate = runCatching {
            LocalDate.of(expectedYear, channelDate.month, channelDate.day)
        }.getOrNull() ?: return false
        if (candidateDate == eventDate) return true

        val dayDifference = kotlin.math.abs(candidateDate.toEpochDay() - eventDate.toEpochDay())
        if (dayDifference != MAX_BOUNDARY_DATE_DIFFERENCE_DAYS) return false
        val localTime = eventDateTime.toLocalTime()
        return localTime <= LocalTime.of(EVENT_DAY_BOUNDARY_TOLERANCE_HOURS.toInt(), 0) ||
            localTime >= LocalTime.of(24 - EVENT_DAY_BOUNDARY_TOLERANCE_HOURS.toInt(), 0)
    }

    private data class ParsedChannelDate(
        val month: Int,
        val day: Int,
        val year: Int?,
    )

    private val SHORT_EVENT_TOKENS = setOf("f1", "gp", "wsl", "ufc", "nba", "nfl", "mlb", "nhl")
    private const val MAX_MANUAL_SEARCH_RESULTS = 40
    private const val DIRECT_MAPPING_BONUS = 20
}

internal object SportsEventFallbackResolver {
    fun addFallbacksWhenDirectChoicesAreUnavailable(
        event: SportsEvent,
        index: SportsChannelIndex,
    ): SportsEvent {
        val playableDirectChoices = event.broadcasts.filter { broadcast ->
            broadcast.channel.streamUrl.isNotBlank()
        }
        if (playableDirectChoices.isNotEmpty()) return event
        val matches = SportsChannelFallbackMatcher.findMatches(event, index)
        if (matches.isEmpty()) return event
        return event.copy(
            broadcasts = playableDirectChoices + matches.map { match ->
                SportsBroadcast(
                    channel = match.entry.channel,
                    combinedProfileId = match.entry.combinedProfileId,
                    fallbackMatch = match,
                )
            },
        )
    }
}
