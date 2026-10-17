package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.SportsEventTiming
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.Instant
import kotlin.math.abs

internal object SportsEventAssembler {
    const val DEFAULT_START_TOLERANCE_MS = 15 * 60 * 1_000L
    private const val NON_TEAM_START_TOLERANCE_MS = 30 * 60 * 1_000L
    private const val MIN_CONFIDENCE_FOR_CROSS_CHANNEL_MERGE = 0.6f

    fun assemble(
        candidates: List<SportsEventCandidate>,
        startToleranceMs: Long = DEFAULT_START_TOLERANCE_MS,
    ): List<SportsEvent> {
        if (candidates.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<SportsEventCandidate>>()
        val groupsByIdentity = mutableMapOf<String, MutableList<MutableList<SportsEventCandidate>>>()
        var mergedCandidateCount = 0
        candidates.sortedBy { it.program.startTime }.forEach { candidate ->
            val identity = candidate.eventIdentity
            val sameIdentityGroups = groupsByIdentity.getOrPut(identity) { mutableListOf() }
            val tolerance = effectiveStartTolerance(candidate.classification.sport, startToleranceMs)
            val matchingGroup = sameIdentityGroups.asReversed().firstOrNull { group ->
                canMerge(group, candidate, tolerance)
            }
            if (matchingGroup == null) {
                val newGroup = mutableListOf(candidate)
                sameIdentityGroups += newGroup
                groups += newGroup
                SportsDiagnostics.dedupe(
                    programId = candidate.program.id,
                    eventFingerprint = identity,
                    decision = "new-group",
                    groupSize = 1,
                )
            } else {
                matchingGroup += candidate
                mergedCandidateCount++
                SportsDiagnostics.dedupe(
                    programId = candidate.program.id,
                    eventFingerprint = identity,
                    decision = "merged",
                    groupSize = matchingGroup.size,
                )
            }
        }

        SportsDiagnostics.dedupeSummary(candidates.size, groups.size, mergedCandidateCount)
        return groups.map { group -> group.toSportsEvent() }
            .sortedWith(compareBy<SportsEvent>({ it.startTime }, { it.title.lowercase() }))
    }

    private fun List<SportsEventCandidate>.toSportsEvent(): SportsEvent {
        val representative = maxBy { candidate ->
            candidate.classification.confidence +
                (candidate.classification.competitors.size * 0.05f) +
                (candidate.classification.displayTitle.length.coerceAtMost(80) / 1_000f)
        }
        val start = minOf { it.program.startTime }
        val end = maxOf { it.program.endTime }
        val identity = representative.eventIdentity
        val league = firstNotBlank {
            representative.classification.league
                ?: firstOrNull { it.classification.league?.isNotBlank() == true }
                    ?.classification?.league
        }
        val leagueInfo = representative.classification.leagueInfo
            ?: firstNotNullOfOrNull { it.classification.leagueInfo }
        val leagueIdentity = leagueInfo?.id
            ?: league?.let(SportsTextNormalizer::normalize)?.takeIf(String::isNotBlank)
            ?.replace(' ', '-')
        val leagueIdentitySuffix = leagueIdentity?.let { "|league:$it" }
            .orEmpty()
        val session = representative.classification.session
            ?: firstNotBlank { firstOrNull { it.classification.session?.isNotBlank() == true }?.classification?.session }
        val sessionIdentity = session?.let(SportsTextNormalizer::normalize)
            ?.takeIf(String::isNotBlank)
            ?.let { "|session:$it" }
            .orEmpty()
        val broadcasts = map { candidate ->
            SportsBroadcast(candidate.channel, candidate.combinedProfileId)
        }.distinctBy { broadcast ->
            "${broadcast.channel.providerId}:${broadcast.channel.id}"
        }.sortedBy { it.channel.name.lowercase() }
        val competitors = flatMap { candidate ->
            candidate.classification.competitors.map { displayName -> SportsCompetitor(displayName) }
        }.distinctBy(SportsCompetitor::canonicalName)
        val rawProgrammeRefs = map(SportsEventCandidate::program).distinctBy { program ->
            "${program.id}:${program.channelId}:${program.startTime}:${program.endTime}"
        }
        val sourceRefs = flatMap { candidate ->
            candidate.program.let { program ->
                listOf(SportsSourceRef.Epg(
                    tvProviderId = program.providerId,
                    channelId = program.channelId,
                    programmeId = program.id,
                ))
            }
        }.toSet()
        val representativeProgramme = representative.program
        val representativeSource = SportsSourceRef.Epg(
            tvProviderId = representativeProgramme.providerId,
            channelId = representativeProgramme.channelId,
            programmeId = representativeProgramme.id,
        )
        val providerLeague = leagueInfo?.let { info ->
            com.streamvault.domain.sports.provider.ProviderLeague(
                name = info.displayName,
                id = info.id,
                sport = info.sport.name.lowercase(),
            )
        } ?: league?.let { com.streamvault.domain.sports.provider.ProviderLeague(name = it) }

        return SportsEvent(
            id = "$identity$leagueIdentitySuffix$sessionIdentity:$start",
            title = representative.classification.displayTitle,
            league = league,
            categoryLabel = firstNotBlank {
                representative.classification.categoryLabel
                    ?: firstOrNull { it.classification.categoryLabel?.isNotBlank() == true }
                        ?.classification?.categoryLabel
            },
            description = maxByOrNull { it.program.description.length }?.program?.description.orEmpty(),
            sport = representative.classification.sport,
            startTime = start,
            endTime = end,
            broadcasts = broadcasts,
            confidence = representative.classification.confidence,
            competitors = competitors,
            session = session,
            eventType = if (any { it.classification.eventType == SportsEventType.LIVE_EVENT }) {
                SportsEventType.LIVE_EVENT
            } else {
                representative.classification.eventType
            },
            rawProgrammeRefs = rawProgrammeRefs,
            leagueInfo = leagueInfo,
            provenance = SportsEventProvenance(
                sources = sourceRefs,
                timing = SportsEventTiming(
                    broadcastStart = Instant.ofEpochMilli(start),
                    broadcastEnd = Instant.ofEpochMilli(end),
                ),
                title = SourcedValue(representative.classification.displayTitle, representativeSource),
                description = SourcedValue(
                    representativeProgramme.description,
                    representativeSource,
                ),
                league = providerLeague?.let { SourcedValue(it, representativeSource) },
            ),
        )
    }

    private fun canMerge(
        group: List<SportsEventCandidate>,
        candidate: SportsEventCandidate,
        startToleranceMs: Long,
    ): Boolean {
        val representative = group.first()
        val current = candidate.classification
        if (abs(representative.program.startTime - candidate.program.startTime) > startToleranceMs) return false
        val currentCompetitors = current.competitors.map(SportsEventParser::canonicalCompetitor).sorted()
        return group.all { existingCandidate ->
            val existing = existingCandidate.classification
            val existingCompetitors = existing.competitors.map(SportsEventParser::canonicalCompetitor).sorted()
            current.sport == existing.sport &&
                minOf(current.confidence, existing.confidence) >= MIN_CONFIDENCE_FOR_CROSS_CHANNEL_MERGE &&
                compatibleEventTypes(current.eventType, existing.eventType) &&
                compatibleLeagues(current, existing) &&
                compatibleLabels(current.session, existing.session) &&
                (currentCompetitors.isEmpty() || existingCompetitors.isEmpty() || currentCompetitors == existingCompetitors)
        }
    }

    private fun compatibleLeagues(left: SportsClassification, right: SportsClassification): Boolean {
        val leftId = left.leagueInfo?.id ?: left.league?.let {
            SportsTextNormalizer.normalize(it).replace(' ', '-')
        }
        val rightId = right.leagueInfo?.id ?: right.league?.let {
            SportsTextNormalizer.normalize(it).replace(' ', '-')
        }
        return leftId == null || rightId == null || leftId == rightId
    }

    private fun compatibleEventTypes(left: SportsEventType, right: SportsEventType): Boolean {
        val competitionTypes = setOf(SportsEventType.LIVE_EVENT, SportsEventType.SCHEDULED_EVENT)
        return if (left in competitionTypes && right in competitionTypes) true else left == right
    }

    private fun compatibleLabels(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return true
        return SportsTextNormalizer.normalize(left) == SportsTextNormalizer.normalize(right)
    }

    private fun effectiveStartTolerance(sport: SportType, requestedToleranceMs: Long): Long {
        if (requestedToleranceMs != DEFAULT_START_TOLERANCE_MS) return requestedToleranceMs
        return when (sport) {
            SportType.GOLF,
            SportType.MOTORSPORT,
            SportType.CYCLING,
            SportType.OTHER,
            SportType.SNOOKER,
            SportType.DARTS,
            -> NON_TEAM_START_TOLERANCE_MS
            else -> DEFAULT_START_TOLERANCE_MS
        }
    }

    private inline fun <T> List<T>.firstNotBlank(value: () -> String?): String? =
        value()?.takeIf(String::isNotBlank)

}
