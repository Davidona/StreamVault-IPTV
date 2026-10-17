package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.ProviderCompetitor
import com.streamvault.domain.sports.provider.ProviderCompetitorKind
import com.streamvault.domain.sports.provider.ProviderCompetitorRole
import com.streamvault.domain.sports.provider.ProviderCompetitorScore
import com.streamvault.domain.sports.provider.ProviderBroadcaster
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventLink
import com.streamvault.domain.sports.provider.ProviderGameState
import com.streamvault.domain.sports.provider.ProviderGameStatus
import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.ProviderScore
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsEventSourceSelection
import com.streamvault.domain.sports.provider.SportsEventTiming
import com.streamvault.domain.sports.provider.SportsProviderEventLinkCache
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsProviderEventResolverTest {
    @Test
    fun `selected provider enriches event while EPG keeps broadcast window and description`() = runTest {
        val cache = TestEventLinkCache()
        val resolver = SportsProviderEventResolver(cache)
        val epg = localEvent()
        val eventSource = providerSource("event-account", "event-1")
        val providerEvent = SourcedValue(
            value = providerEvent("event-1"),
            source = eventSource,
        )
        val score = SourcedValue(
            value = ProviderScore(
                providerEventId = "event-1",
                scores = listOf(
                    ProviderCompetitorScore(team("Wolves", ProviderCompetitorRole.HOME), "1"),
                    ProviderCompetitorScore(team("Brentford", ProviderCompetitorRole.AWAY), "0"),
                ),
                status = ProviderGameStatus(ProviderGameState.LIVE, clock = "65", period = "2H"),
                updatedAt = KICKOFF.plusSeconds(60),
            ),
            source = eventSource,
            updatedAt = KICKOFF.plusSeconds(60),
        )

        val resolved = resolver.resolve(
            epgEvents = listOf(epg),
            providerEvents = listOf(providerEvent),
            scores = listOf(score),
            selection = SportsEventSourceSelection(
                eventInstanceId = "event-account",
                scoreInstanceId = "event-account",
            ),
            now = KICKOFF,
        ).single()

        assertThat(resolved.epgEvent).isEqualTo(epg)
        assertThat(resolved.id).isEqualTo(epg.id)
        assertThat(resolved.matchConfidence).isAtLeast(0.8f)
        assertThat(resolved.provenance.title?.value).isEqualTo("Wolves vs Brentford")
        assertThat(resolved.provenance.description?.value).isEqualTo("EPG coverage information")
        assertThat(resolved.provenance.timing.eventStart).isEqualTo(KICKOFF)
        assertThat(resolved.provenance.timing.broadcastStart).isEqualTo(BROADCAST_START)
        assertThat(resolved.provenance.timing.broadcastEnd).isEqualTo(BROADCAST_END)
        assertThat(resolved.provenance.score?.value?.scores?.map { it.value })
            .containsExactly("1", "0")
        assertThat(resolved.provenance.status?.value?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(resolved.provenance.sources).contains(eventSource)
        assertThat(cache.links).hasSize(1)
    }

    @Test
    fun `selected event provider broadcaster is retained for local channel matching`() = runTest {
        val resolver = SportsProviderEventResolver(TestEventLinkCache())
        val source = providerSource("event-account", "event-1")
        val broadcaster = ProviderBroadcaster(name = "ESPN", country = "United States")
        val event = providerEvent("event-1").copy(broadcasters = listOf(broadcaster))

        val resolved = resolver.resolve(
            epgEvents = listOf(localEvent()),
            providerEvents = listOf(SourcedValue(event, source)),
            selection = SportsEventSourceSelection(
                eventInstanceId = "event-account",
                metadataInstanceId = "event-account",
            ),
            now = KICKOFF,
        ).single()

        assertThat(resolved.provenance.broadcasters?.value).containsExactly(broadcaster)
        assertThat(resolved.provenance.broadcasters?.source).isEqualTo(source)
    }

    @Test
    fun `independent score provider reuses its event link and preserves stale marker`() = runTest {
        val cache = TestEventLinkCache()
        val resolver = SportsProviderEventResolver(cache)
        val epg = localEvent()
        val scoreSource = providerSource("scores-account", "score-event")
        val scoreEvent = providerEvent("score-event")
        val originalScore = ProviderScore(
            providerEventId = "score-event",
            scores = listOf(
                ProviderCompetitorScore(team("Wolves", ProviderCompetitorRole.HOME), "1"),
                ProviderCompetitorScore(team("Brentford", ProviderCompetitorRole.AWAY), "0"),
            ),
            updatedAt = KICKOFF,
        )

        val first = resolver.resolve(
            epgEvents = listOf(epg),
            providerEvents = listOf(SourcedValue(scoreEvent, scoreSource)),
            scores = listOf(SourcedValue(originalScore, scoreSource, KICKOFF)),
            selection = SportsEventSourceSelection(scoreInstanceId = "scores-account"),
            now = KICKOFF,
        ).single()
        val refreshedScore = originalScore.copy(updatedAt = KICKOFF.plusSeconds(90))
        val refreshed = resolver.resolve(
            epgEvents = listOf(epg),
            providerEvents = emptyList(),
            scores = listOf(
                SourcedValue(refreshedScore, scoreSource, refreshedScore.updatedAt, isStale = true),
            ),
            selection = SportsEventSourceSelection(scoreInstanceId = "scores-account"),
            now = KICKOFF.plusSeconds(120),
        ).single()

        assertThat(first.provenance.score?.source).isEqualTo(scoreSource)
        assertThat(cache.links).containsKey("scores-account" to "score-event")
        assertThat(refreshed.provenance.score?.value).isEqualTo(refreshedScore)
        assertThat(refreshed.provenance.score?.isStale).isTrue()
    }

    @Test
    fun `selected provider event without EPG match remains a data-only event`() = runTest {
        val source = providerSource("event-account", "unmatched-1")
        val event = providerEvent("unmatched-1")
        val resolved = SportsProviderEventResolver(TestEventLinkCache()).resolve(
            epgEvents = emptyList(),
            providerEvents = listOf(SourcedValue(event, source)),
            selection = SportsEventSourceSelection(eventInstanceId = "event-account"),
            now = KICKOFF,
        ).single()

        assertThat(resolved.id).isEqualTo("provider:event-account:unmatched-1")
        assertThat(resolved.epgEvent).isNull()
        assertThat(resolved.provenance.title?.value).isEqualTo(event.title)
        assertThat(resolved.provenance.sources).containsExactly(source)
        assertThat(resolved.provenance.timing.eventStart).isEqualTo(KICKOFF)
    }

    @Test
    fun `large event sets resolve matching provider records without pairing unrelated fixtures`() = runTest {
        val matchCount = 400
        val decoyCount = 1_000
        val epgEvents = buildList {
            repeat(decoyCount) { index ->
                add(
                    localEvent().copy(
                        id = "decoy-$index",
                        title = "Other fixture $index",
                        competitors = listOf(
                            SportsCompetitor("Other home $index"),
                            SportsCompetitor("Other away $index"),
                        ),
                    ),
                )
            }
            repeat(matchCount) { index ->
                val home = "Home club $index"
                val away = "Away club $index"
                add(
                    localEvent().copy(
                        id = "epg-match-$index",
                        title = "$home vs $away",
                        competitors = listOf(SportsCompetitor(home), SportsCompetitor(away)),
                    ),
                )
            }
        }
        val providerEvents = (0 until matchCount).map { index ->
            val home = "Home club $index"
            val away = "Away club $index"
            SourcedValue(
                providerEvent("provider-event-$index").copy(
                    title = "$home vs $away",
                    competitors = listOf(
                        team(home, ProviderCompetitorRole.HOME),
                        team(away, ProviderCompetitorRole.AWAY),
                    ),
                ),
                providerSource("event-account", "provider-event-$index"),
            )
        }

        val resolved = SportsProviderEventResolver(TestEventLinkCache()).resolve(
            epgEvents = epgEvents,
            providerEvents = providerEvents,
            selection = SportsEventSourceSelection(eventInstanceId = "event-account"),
            now = KICKOFF,
        )

        assertThat(resolved).hasSize(decoyCount + matchCount)
        assertThat(resolved.count { it.providerEvent != null }).isEqualTo(matchCount)
        assertThat(resolved.mapNotNull { it.providerEvent?.value?.providerEventId })
            .containsExactlyElementsIn((0 until matchCount).map { "provider-event-$it" })
    }

    private fun localEvent() = SportsEvent(
        id = "epg-event-1",
        title = "Live Football",
        league = "English Premier League",
        categoryLabel = "Football",
        description = "EPG coverage information",
        sport = SportType.FOOTBALL,
        startTime = BROADCAST_START.toEpochMilli(),
        endTime = BROADCAST_END.toEpochMilli(),
        broadcasts = emptyList(),
        confidence = 0.9f,
        competitors = listOf(SportsCompetitor("Wolves"), SportsCompetitor("Brentford")),
        session = null,
        eventType = SportsEventType.SCHEDULED_EVENT,
        rawProgrammeRefs = emptyList(),
        provenance = com.streamvault.domain.sports.provider.SportsEventProvenance(
            sources = setOf(SportsSourceRef.Epg(1L, "epg-channel-1", 100L)),
            timing = SportsEventTiming(
                broadcastStart = BROADCAST_START,
                broadcastEnd = BROADCAST_END,
            ),
            description = SourcedValue(
                "EPG coverage information",
                SportsSourceRef.Epg(1L, "epg-channel-1", 100L),
            ),
        ),
    )

    private fun providerEvent(id: String) = ProviderEvent(
        providerEventId = id,
        sport = "Soccer",
        league = ProviderLeague("English Premier League", "4328", "Soccer"),
        title = "Wolves vs Brentford",
        competitors = listOf(
            team("Wolves", ProviderCompetitorRole.HOME),
            team("Brentford", ProviderCompetitorRole.AWAY),
        ),
        eventStart = KICKOFF,
        status = ProviderGameStatus(ProviderGameState.SCHEDULED),
    )

    private fun team(name: String, role: ProviderCompetitorRole) = ProviderCompetitor(
        name = name,
        kind = ProviderCompetitorKind.TEAM,
        role = role,
    )

    private fun providerSource(instanceId: String, eventId: String) = SportsSourceRef.Provider(
        instanceId = instanceId,
        typeId = "test-provider",
        externalEventId = eventId,
    )

    private class TestEventLinkCache : SportsProviderEventLinkCache {
        val links = mutableMapOf<Pair<String, String>, ProviderEventLink>()

        override suspend fun get(
            providerInstanceId: String,
            providerEventId: String,
            now: Instant,
            expectedLocalFingerprint: String?,
            providerEventStart: Instant?,
        ): ProviderEventLink? = links[providerInstanceId to providerEventId]

        override suspend fun put(link: ProviderEventLink) {
            links[link.providerInstanceId to link.providerEventId] = link
        }

        override suspend fun recordFailure(providerInstanceId: String, providerEventId: String): Boolean = false

        override suspend fun invalidate(providerInstanceId: String, providerEventId: String) {
            links.remove(providerInstanceId to providerEventId)
        }
    }

    private companion object {
        val BROADCAST_START: Instant = Instant.parse("2026-10-17T17:00:00Z")
        val KICKOFF: Instant = Instant.parse("2026-10-17T18:00:00Z")
        val BROADCAST_END: Instant = Instant.parse("2026-10-17T20:00:00Z")
    }
}
