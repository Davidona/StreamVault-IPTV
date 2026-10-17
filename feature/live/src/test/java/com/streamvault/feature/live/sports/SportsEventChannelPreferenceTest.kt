package com.streamvault.feature.live.sports

import com.streamvault.data.preferences.SportsEventChannelPick
import com.streamvault.domain.model.Channel
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsEventChannelPreferenceTest {
    @Test
    fun rememberedManualPickIsAddedAndDisplayedFirstThenRemovedOnForget() {
        val direct = channel(1, "Mapped Sports Channel")
        val selected = channel(2, "User Selected Channel")
        val event = event(broadcasts = listOf(SportsBroadcast(direct, null)))
        val index = SportsChannelIndex.build(listOf(direct, selected).map { SportsChannelIndexEntry(it, null) })

        val remembered = SportsEventChannelPreferencePolicy.apply(
            event = event,
            pick = SportsEventChannelPick(1, 2, expiresAtMillis = 2_000),
            availableChannels = index.entries,
            nowMillis = 1_000,
        )

        assertEquals("1:2", remembered.rememberedChannelKey)
        assertTrue(remembered.rememberedChannelWasAdded)
        assertEquals(listOf(2L, 1L), remembered.broadcastsForPresentation().map { it.channel.id })

        val forgotten = SportsEventChannelPreferencePolicy.forget(remembered)

        assertEquals(listOf(1L), forgotten.broadcasts.map { it.channel.id })
        assertEquals(null, forgotten.rememberedChannelKey)
        assertFalse(forgotten.rememberedChannelWasAdded)
    }

    @Test
    fun rememberedDirectPickDoesNotDuplicateOrRemoveTheDirectBroadcast() {
        val direct = channel(1, "Mapped Sports Channel")
        val event = event(broadcasts = listOf(SportsBroadcast(direct, null)))
        val index = SportsChannelIndex.build(listOf(direct).map { SportsChannelIndexEntry(it, null) })

        val remembered = SportsEventChannelPreferencePolicy.apply(
            event = event,
            pick = SportsEventChannelPick(1, 1, expiresAtMillis = 2_000),
            availableChannels = index.entries,
            nowMillis = 1_000,
        )

        assertEquals(1, remembered.broadcasts.size)
        assertFalse(remembered.rememberedChannelWasAdded)
        assertEquals(listOf(1L), SportsEventChannelPreferencePolicy.forget(remembered).broadcasts.map { it.channel.id })
    }

    @Test
    fun expiredOrUnavailableProtectedPicksAreIgnored() {
        val mapped = channel(1, "Mapped Sports Channel")
        val protected = channel(2, "Protected Sports Channel", protected = true)
        val adult = channel(3, "Adult Sports Channel", adult = true)
        val event = event(broadcasts = listOf(SportsBroadcast(mapped, null)))
        val index = SportsChannelIndex.build(listOf(mapped, protected, adult).map { SportsChannelIndexEntry(it, null) })

        val expired = SportsEventChannelPreferencePolicy.apply(
            event = event,
            pick = SportsEventChannelPick(1, 2, expiresAtMillis = 999),
            availableChannels = index.entries,
            nowMillis = 1_000,
        )
        val protectedPick = SportsEventChannelPreferencePolicy.apply(
            event = event,
            pick = SportsEventChannelPick(1, 2, expiresAtMillis = 2_000),
            availableChannels = index.entries,
            nowMillis = 1_000,
        )
        val adultPick = SportsEventChannelPreferencePolicy.apply(
            event = event,
            pick = SportsEventChannelPick(1, 3, expiresAtMillis = 2_000),
            availableChannels = index.entries,
            nowMillis = 1_000,
        )

        assertSame(event, expired)
        assertSame(event, protectedPick)
        assertSame(event, adultPick)
    }

    private fun channel(id: Long, name: String, protected: Boolean = false, adult: Boolean = false) = Channel(
        id = id,
        providerId = 1,
        name = name,
        streamUrl = "https://example.invalid/live/$id.m3u8",
        isAdult = adult,
        isUserProtected = protected,
    )

    private fun event(broadcasts: List<SportsBroadcast> = emptyList()) = SportsEvent(
        id = "event-1",
        title = "Manchester United vs Barcelona",
        league = "Premier League",
        categoryLabel = null,
        description = "",
        sport = SportType.FOOTBALL,
        startTime = LocalDateTime.of(2026, 10, 18, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        endTime = LocalDateTime.of(2026, 10, 18, 22, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        broadcasts = broadcasts,
        confidence = 0.95f,
        competitors = listOf(SportsCompetitor("Manchester United"), SportsCompetitor("Barcelona")),
        session = null,
        eventType = SportsEventType.SCHEDULED_EVENT,
        rawProgrammeRefs = emptyList(),
    )
}
