package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import com.streamvault.domain.sports.provider.ProviderBroadcaster
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsChannelFallbackMatcherTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun teamMatchRequiresBothCompetitorsAndSupportsAliases() {
        val event = teamEvent()
        val matching = channel(1, "EPL | Man Utd @ Barça FHD")
        val oneTeamOnly = channel(2, "EPL | Man Utd FHD")
        val index = SportsChannelIndex.build(listOf(matching, oneTeamOnly).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(matching.channel.id), matches.map { it.entry.channel.id })
        assertTrue(SportsChannelMatchReason.TEAM_PAIR in matches.single().reasons)
        assertEquals(115, matches.single().score)
    }

    @Test
    fun explicitWrongDatesAreRejectedAndUnambiguousDatesScore() {
        val event = teamEvent(start = LocalDateTime.of(2026, 10, 18, 20, 0))
        val matching = channel(1, "EPL | 18/10 | Man Utd vs Barcelona")
        val stale = channel(2, "EPL | 17 Oct | Man Utd vs Barcelona")
        val wrongYear = channel(3, "EPL | 2025-10-18 | Man Utd vs Barcelona")
        val index = SportsChannelIndex.build(listOf(matching, stale, wrongYear).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(matching.channel.id), matches.map { it.entry.channel.id })
        assertEquals(135, matches.single().score)
        assertTrue(SportsChannelMatchReason.MATCHING_DATE in matches.single().reasons)
    }

    @Test
    fun dateOnlyOneDayAwayIsAllowedNearLocalMidnight() {
        val event = teamEvent(start = LocalDateTime.of(2026, 10, 18, 1, 0))
        val previousLocalDate = channel(1, "EPL | 17 Oct | Man Utd vs Barcelona")
        val unrelatedDay = channel(2, "EPL | 16 Oct | Man Utd vs Barcelona")
        val index = SportsChannelIndex.build(listOf(previousLocalDate, unrelatedDay).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(previousLocalDate.channel.id), matches.map { it.entry.channel.id })
        assertTrue(SportsChannelMatchReason.MATCHING_DATE in matches.single().reasons)
    }

    @Test
    fun ambiguousNumericDateIsIgnoredRatherThanAssumed() {
        val event = teamEvent(start = LocalDateTime.of(2026, 10, 18, 20, 0))
        val ambiguous = channel(1, "EPL | 09/10 | Man Utd vs Barcelona")
        val index = SportsChannelIndex.build(listOf(entry(ambiguous)))

        val match = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc).single()

        assertEquals(115, match.score)
        assertFalse(SportsChannelMatchReason.MATCHING_DATE in match.reasons)
    }

    @Test
    fun nonTeamEventPhraseOutranksGenericSeriesOnlyChannel() {
        val event = event(
            title = "Buick LPGA Shanghai Day 2",
            league = "LPGA Tour",
            sport = SportType.GOLF,
        )
        val specific = channel(1, "LPGA Tour | Buick | Shanghai | 18 Oct")
        val generic = channel(2, "LPGA Tour HD")
        val index = SportsChannelIndex.build(listOf(specific, generic).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(specific.channel.id), matches.map { it.entry.channel.id })
        assertEquals(125, matches.single().score)
        assertTrue(SportsChannelMatchReason.EVENT_NAME in matches.single().reasons)
        assertTrue(SportsChannelMatchReason.SERIES in matches.single().reasons)
        assertTrue(SportsChannelMatchReason.MATCHING_DATE in matches.single().reasons)
    }

    @Test
    fun eventSpecificMotorsportTitleMatchesSeriesAlias() {
        val event = event(
            title = "Japanese Grand Prix",
            league = "Formula 1",
            sport = SportType.MOTORSPORT,
        )
        val channel = channel(1, "F1 Japanese Grand Prix | Oct 18")
        val index = SportsChannelIndex.build(listOf(entry(channel)))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(channel.channel.id), matches.map { it.entry.channel.id })
        assertEquals(125, matches.single().score)
    }

    @Test
    fun fightMatcherAcceptsFullNamesOrDistinctiveSurnamesButNotCommonSurnames() {
        val event = event(
            title = "UFC Main Event",
            league = "UFC",
            sport = SportType.MMA,
            competitors = listOf("Dustin Poirier", "Justin Gaethje"),
        )
        val fullNames = channel(1, "UFC | Dustin Poirier vs Justin Gaethje")
        val distinctiveSurnames = channel(2, "UFC | Poirier vs Gaethje")
        val commonSurnames = channel(3, "UFC | Smith vs Jones")
        val index = SportsChannelIndex.build(listOf(fullNames, distinctiveSurnames, commonSurnames).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(fullNames.channel.id, distinctiveSurnames.channel.id), matches.map { it.entry.channel.id })
        assertTrue(matches.all { SportsChannelMatchReason.FIGHTER_PAIR in it.reasons })

        val commonNameEvent = event(
            title = "UFC Main Event",
            league = "UFC",
            sport = SportType.MMA,
            competitors = listOf("Muhammad Ali", "Zhang Li"),
        )
        val commonNameIndex = SportsChannelIndex.build(listOf(entry(channel(4, "UFC | Ali vs Li"))))
        assertTrue(SportsChannelFallbackMatcher.findMatches(commonNameEvent, commonNameIndex, zoneId = utc).isEmpty())
    }

    @Test
    fun configuredPlaybackChoicesAreExcludedFromFallbackResults() {
        val direct = channel(1, "EPL | Manchester United vs Barcelona")
        val fallback = channel(2, "EPL | Man Utd vs Barça FHD")
        val event = teamEvent(broadcasts = listOf(SportsBroadcast(direct.channel, null)))
        val index = SportsChannelIndex.build(listOf(direct, fallback).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)

        assertEquals(listOf(fallback.channel.id), matches.map { it.entry.channel.id })
    }

    @Test
    fun resolverKeepsExistingPlayableDirectMappingAheadOfFallback() {
        val direct = channel(1, "Sports Direct Channel")
        val fallback = channel(2, "EPL | Manchester United vs Barcelona")
        val event = teamEvent(broadcasts = listOf(SportsBroadcast(direct.channel, null)))
        val index = SportsChannelIndex.build(listOf(direct, fallback).map(::entry))

        val resolved = SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event, index)

        assertSame(event, resolved)
    }

    @Test
    fun resolverAddsOnlyConfiguredPlayableFallbackWhenDirectUrlIsMissing() {
        val unavailable = channel(1, "Unavailable Guide Channel", streamUrl = "")
        val fallback = channel(2, "EPL | Man Utd vs Barcelona")
        val event = teamEvent(broadcasts = listOf(SportsBroadcast(unavailable.channel, null)))
        val index = SportsChannelIndex.build(listOf(unavailable, fallback).map(::entry))

        val resolved = SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event, index)

        assertEquals(listOf(fallback.channel.id), resolved.broadcasts.map { it.channel.id })
        assertEquals(fallback.channel.streamUrl, resolved.broadcasts.single().channel.streamUrl)
        assertTrue(SportsChannelMatchReason.TEAM_PAIR in checkNotNull(resolved.broadcasts.single().fallbackMatch).reasons)
    }

    @Test
    fun providerBroadcasterMapsProviderOnlyEventToExactPlayableLocalChannel() {
        val source = SportsSourceRef.Provider("event-account", "test-provider", "provider-event-1")
        val event = teamEvent().copy(
            id = "provider:event-account:provider-event-1",
            provenance = SportsEventProvenance(
                sources = setOf(source),
                broadcasters = SourcedValue(listOf(ProviderBroadcaster("ESPN")), source),
            ),
        )
        val espn = channel(1, "US: ESPN HD", canonicalName = "ESPN")
        val espnTwo = channel(2, "ESPN 2", canonicalName = "ESPN 2")
        val index = SportsChannelIndex.build(listOf(espn, espnTwo).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(event, index, zoneId = utc)
        val resolved = SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event, index)
        val manualSuggestions = SportsChannelFallbackMatcher.searchChannels(event, index, "")

        assertEquals(listOf(espn.channel.id), matches.map { it.entry.channel.id })
        assertEquals(110, matches.single().score)
        assertTrue(SportsChannelMatchReason.BROADCASTER in matches.single().reasons)
        assertEquals(listOf(espn.channel.id), resolved.broadcasts.map { it.channel.id })
        assertEquals(listOf(espn.channel.id), manualSuggestions.map { it.entry.channel.id })
    }

    @Test
    fun providerBroadcasterDoesNotOverridePlayableEpgMapping() {
        val source = SportsSourceRef.Provider("event-account", "test-provider", "provider-event-1")
        val direct = channel(1, "Local Sports Guide Channel")
        val broadcaster = channel(2, "ESPN HD", canonicalName = "ESPN")
        val event = teamEvent(broadcasts = listOf(SportsBroadcast(direct.channel, null))).copy(
            provenance = SportsEventProvenance(
                sources = setOf(source),
                broadcasters = SourcedValue(listOf(ProviderBroadcaster("ESPN")), source),
            ),
        )
        val index = SportsChannelIndex.build(listOf(direct, broadcaster).map(::entry))

        val resolved = SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event, index)

        assertSame(event, resolved)
        assertEquals(listOf(direct.channel.id), resolved.broadcasts.map { it.channel.id })
    }

    @Test
    fun indexKeepsVariantsButExcludesProtectedAdultAndUnplayableChannels() {
        val fhd = channel(1, "beIN Sports 1 FHD", canonicalName = "beIN Sports 1")
        val uhd = channel(2, "beIN Sports 1 UHD", canonicalName = "beIN Sports 1")
        val protected = channel(3, "EPL Arsenal vs Liverpool", protected = true)
        val adult = channel(4, "EPL Arsenal vs Liverpool", adult = true)
        val unplayable = channel(5, "EPL Arsenal vs Liverpool", streamUrl = "")

        val index = SportsChannelIndex.build(listOf(fhd, uhd, protected, adult, unplayable).map(::entry))

        assertEquals(2, index.entries.size)
        assertEquals(setOf(1L, 2L), index.entries.map { it.channel.id }.toSet())
        assertEquals(setOf(0, 1), index.byCanonicalName.getValue("bein sports 1").toSet())
    }

    @Test
    fun manualSearchFindsConfiguredChannelsOutsideAutomaticEventMatches() {
        val unrelated = channel(1, "Local Sports Network 2")
        val protected = channel(2, "Local Sports Network 2 VIP", protected = true)
        val unplayable = channel(3, "Local Sports Network 2 Backup", streamUrl = "")
        val index = SportsChannelIndex.build(listOf(unrelated, protected, unplayable).map(::entry))

        val results = SportsChannelFallbackMatcher.searchChannels(teamEvent(), index, "local sports network")

        assertEquals(listOf(unrelated.channel.id), results.map { it.entry.channel.id })
        assertTrue(SportsChannelMatchReason.MANUAL_SEARCH in results.single().reasons)
    }

    @Test
    fun manualSearchBoundsBroadAndPrefixQueriesToTheTopResults() {
        val channels = (1L..80L).map { id -> channel(id, "Local Network Channel $id") }
        val index = SportsChannelIndex.build(channels.map(::entry))

        val results = SportsChannelFallbackMatcher.searchChannels(teamEvent(), index, "netw")

        assertEquals(40, results.size)
        assertTrue(results.all { SportsChannelMatchReason.MANUAL_SEARCH in it.reasons })
        assertTrue(results.zipWithNext().all { (first, second) -> first.score >= second.score })
    }

    @Test
    fun manualSuggestionsRankEventNamedChannelsAheadOfUnrelatedDirectMappings() {
        val direct = channel(1, "Sports Network")
        val eventNamed = channel(2, "EPL | Man Utd vs Barcelona FHD")
        val event = teamEvent(broadcasts = listOf(SportsBroadcast(direct.channel, null)))
        val index = SportsChannelIndex.build(listOf(direct, eventNamed).map(::entry))

        val results = SportsChannelFallbackMatcher.searchChannels(event, index, "")

        assertEquals(listOf(eventNamed.channel.id, direct.channel.id), results.map { it.entry.channel.id })
        assertTrue(SportsChannelMatchReason.DIRECT_MAPPING in results.last().reasons)
        assertTrue(SportsChannelMatchReason.TEAM_PAIR in results.first().reasons)
    }

    private fun teamEvent(
        start: LocalDateTime = LocalDateTime.of(2026, 10, 18, 20, 0),
        broadcasts: List<SportsBroadcast> = emptyList(),
    ): SportsEvent = event(
        title = "Manchester United vs Barcelona",
        league = "Premier League",
        sport = SportType.FOOTBALL,
        competitors = listOf("Manchester United", "Barcelona"),
        start = start,
        broadcasts = broadcasts,
    )

    private fun event(
        title: String,
        league: String?,
        sport: SportType,
        competitors: List<String> = emptyList(),
        start: LocalDateTime = LocalDateTime.of(2026, 10, 18, 20, 0),
        broadcasts: List<SportsBroadcast> = emptyList(),
    ) = SportsEvent(
        id = "event-1",
        title = title,
        league = league,
        categoryLabel = null,
        description = "",
        sport = sport,
        startTime = start.toInstant(ZoneOffset.UTC).toEpochMilli(),
        endTime = start.plusHours(2).toInstant(ZoneOffset.UTC).toEpochMilli(),
        broadcasts = broadcasts,
        confidence = 0.95f,
        competitors = competitors.map(::SportsCompetitor),
        session = null,
        eventType = SportsEventType.SCHEDULED_EVENT,
        rawProgrammeRefs = emptyList(),
    )

    private fun entry(candidate: TestChannel): SportsChannelIndexEntry =
        SportsChannelIndexEntry(candidate.channel, candidate.combinedProfileId)

    private fun channel(
        id: Long,
        name: String,
        canonicalName: String = name,
        streamUrl: String = "https://example.invalid/live/$id.m3u8",
        protected: Boolean = false,
        adult: Boolean = false,
    ) = TestChannel(
        channel = Channel(
            id = id,
            name = name,
            canonicalName = canonicalName,
            streamUrl = streamUrl,
            epgChannelId = "epg-$id",
            providerId = 1,
            isAdult = adult,
            isUserProtected = protected,
        ),
        combinedProfileId = null,
    )

    private data class TestChannel(
        val channel: Channel,
        val combinedProfileId: Long?,
    )
}
