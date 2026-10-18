package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventLink
import com.streamvault.domain.sports.provider.ProviderGameStatus
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.ProviderBroadcaster
import com.streamvault.domain.sports.provider.ProviderScore
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsEventField
import com.streamvault.domain.sports.provider.SportsEventMergePolicy
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.SportsEventSourceSelection
import com.streamvault.domain.sports.provider.SportsEventTiming
import com.streamvault.domain.sports.provider.SportsProviderEventLinkCache
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.Instant
import javax.inject.Inject

/** A UI-neutral event row with the chosen value and its field-level source history. */
internal data class SportsResolvedEvent(
    val id: String,
    val epgEvent: SportsEvent?,
    val providerEvent: SourcedValue<ProviderEvent>?,
    val matchConfidence: Float,
    val provenance: SportsEventProvenance,
)

/** Joins provider data to EPG events without turning provider metadata into playable channels. */
internal class SportsProviderEventResolver @Inject constructor(
    private val eventLinkCache: SportsProviderEventLinkCache,
) {
    suspend fun resolve(
        epgEvents: List<SportsEvent>,
        providerEvents: List<SourcedValue<ProviderEvent>>,
        scores: List<SourcedValue<ProviderScore>> = emptyList(),
        selection: SportsEventSourceSelection,
        now: Instant = Instant.now(),
    ): List<SportsResolvedEvent> {
        val uniqueProviderEvents = providerEvents
            .filter { sourced -> sourced.source is SportsSourceRef.Provider }
            .filter { sourced ->
                val source = sourced.source as SportsSourceRef.Provider
                source.externalEventId == sourced.value.providerEventId
            }
            .distinctBy { sourced ->
                val source = sourced.source as SportsSourceRef.Provider
                "${source.instanceId}:${source.typeId}:${source.externalEventId}"
            }

        val epgById = epgEvents.associateBy(SportsEvent::id)
        val localEventsByCandidateKey = HashMap<SportsProviderEventMatcher.CandidateKey, MutableList<SportsEvent>>()
        epgEvents.forEach { localEvent ->
            SportsProviderEventMatcher.candidateKeys(localEvent).forEach { key ->
                localEventsByCandidateKey.getOrPut(key) { mutableListOf() }.add(localEvent)
            }
        }

        // An absent guide (including a provisional API-only presentation) is not
        // evidence that an existing link has become invalid.
        val proposedMatches = if (epgEvents.isEmpty()) emptyList() else uniqueProviderEvents.mapNotNull { sourced ->
            val candidates = SportsProviderEventMatcher.candidateKeys(sourced.value)
                .flatMap { localEventsByCandidateKey[it].orEmpty() }
                .distinctBy(SportsEvent::id)
            matchProviderEvent(sourced, candidates, epgById, now)
        }
        val assignments = resolveProviderCollisions(proposedMatches)
        assignments.forEach { assignment -> persistLink(assignment, now) }

        val assignedLocalByProviderKey = assignments.associate { assignment ->
            providerKey(assignment.sourced) to assignment.localEvent
        }
        val assignmentsByLocalAndInstance = assignments.associateBy { assignment ->
            val source = assignment.sourced.source as SportsSourceRef.Provider
            assignment.localEvent.id to source.instanceId
        }
        val assignedProviderKeys = assignments.mapTo(mutableSetOf()) { providerKey(it.sourced) }
        val selectedScoreRecords = scores.filter { sourced ->
            (sourced.source as? SportsSourceRef.Provider)?.instanceId == selection.scoreInstanceId &&
                (sourced.source as? SportsSourceRef.Provider)?.externalEventId == sourced.value.providerEventId
        }
        val currentProviderEventKeys = uniqueProviderEvents.mapTo(mutableSetOf(), ::providerKey)
        val selectedScoreByProviderKey = linkedMapOf<String, SourcedValue<ProviderScore>>()
        val scoresByLocalEventId = linkedMapOf<String, SourcedValue<ProviderScore>>()
        selectedScoreRecords.forEach { sourced ->
            val key = providerKey(sourced)
            if (key !in selectedScoreByProviderKey) selectedScoreByProviderKey[key] = sourced
            val source = sourced.source as SportsSourceRef.Provider
            val linkedLocal = assignedLocalByProviderKey[key]
                ?: if (key in currentProviderEventKeys) null else cachedLocalFor(
                    source,
                    sourced.value.providerEventId,
                    epgById,
                    now,
                )
            if (linkedLocal != null && linkedLocal.id !in scoresByLocalEventId) {
                scoresByLocalEventId[linkedLocal.id] = sourced
            }
        }

        val resolvedEpg = epgEvents.map { localEvent ->
            val eventAssignment = selection.eventInstanceId?.let { instanceId ->
                assignmentsByLocalAndInstance[localEvent.id to instanceId]
            }
            val eventProvider = eventAssignment?.sourced
            val metadataProvider = selection.metadataInstanceId?.let { instanceId ->
                assignmentsByLocalAndInstance[localEvent.id to instanceId]?.sourced
            }
            val scoreEventProvider = selection.scoreInstanceId?.let { instanceId ->
                assignmentsByLocalAndInstance[localEvent.id to instanceId]?.sourced
            }
            val matchConfidence = eventAssignment?.match?.confidence ?: 0f
            val score = scoresByLocalEventId[localEvent.id]
            val statusFromScore = score?.value?.status?.let { status ->
                SourcedValue(status, score.source, score.value.updatedAt ?: status.updatedAt, isStale = score.isStale)
            }
            val statusFromEvent = listOfNotNull(scoreEventProvider, eventProvider)
                .distinctBy(::providerKey)
                .firstNotNullOfOrNull { sourced -> sourced.value.status?.let { status ->
                    SourcedValue(status, sourced.source, status.updatedAt)
                } }
            val status = statusFromScore ?: statusFromEvent
            val providerEventValue = eventProvider?.value
            val metadataEventValue = metadataProvider?.value
            val scoreValue = score?.let {
                SourcedValue(it.value, it.source, it.value.updatedAt ?: it.updatedAt, isStale = it.isStale)
            }
            val providerTitle = providerEventValue?.title?.takeIf(String::isNotBlank)?.let { title ->
                SourcedValue(title, requireProviderSource(eventProvider), eventProvider?.updatedAt)
            }
            val providerLeague = providerEventValue?.league?.let { league ->
                SourcedValue(league, requireProviderSource(eventProvider), eventProvider?.updatedAt)
            }
            val providerStart = providerEventValue?.eventStart?.let { start ->
                SourcedValue(start, requireProviderSource(eventProvider), eventProvider?.updatedAt)
            }
            val providerEnd = providerEventValue?.eventEnd?.let { end ->
                SourcedValue(end, requireProviderSource(eventProvider), eventProvider?.updatedAt)
            }
            val broadcasters = metadataEventValue?.broadcasters?.takeIf { it.isNotEmpty() }
                ?.let { values ->
                    SourcedValue(values, requireProviderSource(metadataProvider), metadataProvider?.updatedAt)
                }

            SportsResolvedEvent(
                id = localEvent.id,
                epgEvent = localEvent,
                providerEvent = eventProvider,
                matchConfidence = matchConfidence,
                provenance = mergeProvenance(
                    localEvent = localEvent,
                    matchConfidence = matchConfidence,
                    selection = selection,
                    providerTitle = providerTitle,
                    providerLeague = providerLeague,
                    providerStart = providerStart,
                    providerEnd = providerEnd,
                    score = scoreValue,
                    status = status,
                    broadcasters = broadcasters,
                    providerEventSources = listOfNotNull(eventProvider, metadataProvider, scoreEventProvider)
                        .map(SourcedValue<ProviderEvent>::source),
                ),
            )
        }

        val resolvedProviderOnly = uniqueProviderEvents
            .filter { (it.source as SportsSourceRef.Provider).instanceId == selection.eventInstanceId }
            .filter { sourced -> providerKey(sourced) !in assignedProviderKeys }
            .map { sourced ->
                val source = sourced.source as SportsSourceRef.Provider
                val event = sourced.value
                val score = selectedScoreByProviderKey[providerKey(sourced)]
                val status = score?.value?.status
                    ?: event.status?.takeIf {
                        selection.scoreInstanceId == null || selection.scoreInstanceId == source.instanceId
                    }
                val title = event.title?.takeIf(String::isNotBlank)?.let {
                    SourcedValue(it, source, sourced.updatedAt)
                }
                val league = event.league?.let { SourcedValue(it, source, sourced.updatedAt) }
                SportsResolvedEvent(
                    id = "provider:${source.instanceId}:${source.externalEventId}",
                    epgEvent = null,
                    providerEvent = sourced,
                    matchConfidence = 0f,
                    provenance = SportsEventProvenance(
                        sources = setOfNotNull(source, score?.source),
                        timing = SportsEventTiming(eventStart = event.eventStart, eventEnd = event.eventEnd),
                        title = title,
                        league = league,
                        status = status?.let { statusValue ->
                            val statusSource = score?.source ?: source
                            SourcedValue(
                                statusValue,
                                statusSource,
                                statusValue.updatedAt ?: score?.updatedAt ?: sourced.updatedAt,
                                isStale = score?.isStale == true,
                            )
                        },
                        broadcasters = event.broadcasters.takeIf { it.isNotEmpty() }
                            ?.let { SourcedValue(it, source, sourced.updatedAt) },
                        score = score?.let {
                            SourcedValue(
                                it.value,
                                it.source,
                                it.value.updatedAt ?: it.updatedAt,
                                isStale = it.isStale,
                            )
                        },
                    ),
                )
            }

        return (resolvedEpg + resolvedProviderOnly).sortedWith(
            compareBy<SportsResolvedEvent>(
                { it.provenance.timing.eventStart ?: it.provenance.timing.broadcastStart ?: Instant.MAX },
                SportsResolvedEvent::id,
            ),
        )
    }

    private suspend fun matchProviderEvent(
        sourced: SourcedValue<ProviderEvent>,
        candidateEvents: List<SportsEvent>,
        epgById: Map<String, SportsEvent>,
        now: Instant,
    ): ProposedMatch? {
        val source = sourced.source as SportsSourceRef.Provider
        val cached = eventLinkCache.get(
            providerInstanceId = source.instanceId,
            providerEventId = sourced.value.providerEventId,
            now = now,
            providerEventStart = sourced.value.eventStart,
        )
        val matchCandidates = candidateEvents.mapNotNull { local ->
            SportsProviderEventMatcher.match(sourced.value, local)
                ?.takeIf { it.confidence >= SportsEventMergePolicy.MIN_EVENT_IDENTITY_CONFIDENCE }
                ?.let { local to it }
        }.sortedWith(compareByDescending<Pair<SportsEvent, SportsProviderEventMatch>> { it.second.confidence }
            .thenBy { it.first.id })

        val cachedCandidate = cached?.let { link ->
            epgById[link.localEventId]?.let { local ->
                val fingerprint = local.fingerprint()
                SportsProviderEventMatcher.match(sourced.value, local)
                    ?.takeIf { fingerprint == link.localEventFingerprint &&
                        it.confidence >= SportsEventMergePolicy.MIN_EVENT_IDENTITY_CONFIDENCE }
                    ?.let { local to it }
            }
        }
        if (cached != null && cachedCandidate == null) {
            eventLinkCache.recordFailure(source.instanceId, sourced.value.providerEventId)
        }

        val top = matchCandidates.firstOrNull() ?: return null
        val next = matchCandidates.getOrNull(1)
        if (next != null && top.second.confidence - next.second.confidence < LOCAL_MATCH_MARGIN) return null
        // A cached association is evidence of stability, but the current identity and uniqueness checks still apply.
        val selected = cachedCandidate?.takeIf { it.first.id == top.first.id } ?: top
        return ProposedMatch(sourced, selected.first, selected.second)
    }

    private fun resolveProviderCollisions(proposals: List<ProposedMatch>): List<ProposedMatch> {
        return proposals.groupBy { proposal ->
            val source = proposal.sourced.source as SportsSourceRef.Provider
            source.instanceId to proposal.localEvent.id
        }.values.mapNotNull { contenders ->
            val ranked = contenders.sortedWith(compareByDescending<ProposedMatch> { it.match.confidence }
                .thenBy { providerKey(it.sourced) })
            val top = ranked.first()
            val runnerUp = ranked.getOrNull(1)
            if (runnerUp != null && top.match.confidence - runnerUp.match.confidence < LOCAL_MATCH_MARGIN) null else top
        }
    }

    private suspend fun persistLink(match: ProposedMatch, now: Instant) {
        val source = match.sourced.source as SportsSourceRef.Provider
        eventLinkCache.put(
            ProviderEventLink(
                providerInstanceId = source.instanceId,
                providerTypeId = source.typeId,
                providerEventId = source.externalEventId,
                localEventId = match.localEvent.id,
                localEventFingerprint = match.localEvent.fingerprint(),
                confidence = match.match.confidence,
                linkedAt = now,
                eventStart = match.sourced.value.eventStart,
            ),
        )
    }

    private suspend fun cachedLocalFor(
        source: SportsSourceRef.Provider,
        providerEventId: String,
        epgById: Map<String, SportsEvent>,
        now: Instant,
    ): SportsEvent? {
        val link = eventLinkCache.get(source.instanceId, providerEventId, now) ?: return null
        if (link.confidence < SportsEventMergePolicy.MIN_EVENT_IDENTITY_CONFIDENCE) return null
        val local = epgById[link.localEventId]
        if (local == null || local.fingerprint() != link.localEventFingerprint) {
            eventLinkCache.recordFailure(source.instanceId, providerEventId)
            return null
        }
        return local
    }

    private fun mergeProvenance(
        localEvent: SportsEvent,
        matchConfidence: Float,
        selection: SportsEventSourceSelection,
        providerTitle: SourcedValue<String>?,
        providerLeague: SourcedValue<ProviderLeague>?,
        providerStart: SourcedValue<Instant>?,
        providerEnd: SourcedValue<Instant>?,
        score: SourcedValue<ProviderScore>?,
        status: SourcedValue<ProviderGameStatus>?,
        broadcasters: SourcedValue<List<ProviderBroadcaster>>?,
        providerEventSources: List<SportsSourceRef>,
    ): SportsEventProvenance {
        val current = localEvent.provenance
        val epgSource = current.sources.firstOrNull { it is SportsSourceRef.Epg }
        val titleFallback = current.title ?: epgSource?.let { SourcedValue(localEvent.title, it) }
        val descriptionFallback = current.description ?: epgSource?.let { SourcedValue(localEvent.description, it) }
        val leagueFallback = current.league ?: localEvent.league?.let { league ->
            epgSource?.let { source -> SourcedValue(ProviderLeague(league), source) }
        }
        val currentEventSource = current.sources.firstOrNull { source ->
            (source as? SportsSourceRef.Provider)?.instanceId == selection.eventInstanceId
        } ?: epgSource
        val timing = current.timing.copy(
            eventStart = SportsEventMergePolicy.select(
                SportsEventField.EVENT_START,
                current.timing.eventStart?.let { value -> currentEventSource?.let { SourcedValue(value, it) } },
                providerStart, selection, matchConfidence,
            )?.value,
            eventEnd = SportsEventMergePolicy.select(
                SportsEventField.EVENT_END,
                current.timing.eventEnd?.let { value -> currentEventSource?.let { SourcedValue(value, it) } },
                providerEnd, selection, matchConfidence,
            )?.value,
        )
        val mergedTitle = SportsEventMergePolicy.select(
            SportsEventField.EVENT_TITLE, titleFallback, providerTitle, selection, matchConfidence,
        )
        val mergedLeague = SportsEventMergePolicy.select(
            SportsEventField.LEAGUE, leagueFallback, providerLeague, selection, matchConfidence,
        )
        val mergedScore = SportsEventMergePolicy.select(
            SportsEventField.SCORE, current.score, score, selection, matchConfidence,
        )
        val mergedStatus = SportsEventMergePolicy.select(
            SportsEventField.GAME_STATUS, current.status, status, selection, matchConfidence,
        )
        val mergedBroadcasters = SportsEventMergePolicy.select(
            SportsEventField.BROADCASTERS, current.broadcasters, broadcasters, selection, matchConfidence,
        )
        return SportsEventProvenance(
            sources = current.sources + providerEventSources,
            timing = timing,
            title = mergedTitle,
            description = descriptionFallback,
            league = mergedLeague,
            score = mergedScore,
            status = mergedStatus,
            teamMetadata = current.teamMetadata,
            artwork = current.artwork,
            broadcasters = mergedBroadcasters,
        )
    }

    private fun SportsEvent.fingerprint(): String = buildString {
        append(sport.name.lowercase())
        append('|')
        append(competitors.map(SportsCompetitor::canonicalName).filter(String::isNotBlank).sorted().joinToString("|"))
        append('|')
        append(leagueInfo?.id ?: league?.let(SportsTextNormalizer::normalize).orEmpty())
        append('|')
        append(session?.let(SportsTextNormalizer::normalize).orEmpty())
        if (competitors.isEmpty()) {
            append('|')
            append(SportsProgrammeClassifier.canonicalEventTitle(title))
        }
    }

    private fun providerKey(sourced: SourcedValue<*>): String {
        val source = sourced.source as SportsSourceRef.Provider
        return "${source.instanceId}:${source.externalEventId}"
    }

    private fun requireProviderSource(sourced: SourcedValue<*>?): SportsSourceRef.Provider =
        sourced?.source as? SportsSourceRef.Provider
            ?: error("Provider event values must retain a provider source")

    private data class ProposedMatch(
        val sourced: SourcedValue<ProviderEvent>,
        val localEvent: SportsEvent,
        val match: SportsProviderEventMatch,
    )

    private companion object {
        const val LOCAL_MATCH_MARGIN = 0.12f
    }
}
