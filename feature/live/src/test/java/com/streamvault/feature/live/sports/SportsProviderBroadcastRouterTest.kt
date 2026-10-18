package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class SportsProviderBroadcastRouterTest {
    private val now = Instant.parse("2026-10-17T18:00:00Z")
    private val calls = mutableListOf<String>()
    private val config = SportsProviderConfig("premium", "thesportsdb")
    private var response: SportsProviderResult<ProviderBatch<ProviderBroadcaster>> =
        SportsProviderResult.Success(ProviderBatch(listOf(ProviderBroadcaster("ESPN"))))
    private var cancel = false
    private val descriptor = SportsProviderDescriptor("thesportsdb", setOf(SportsCapability.BROADCASTERS),
        SportsProviderAuth.None, compliance = ProviderComplianceInfo(
            "https://provider.test/terms", "https://provider.test/docs", LocalDate.of(2026, 10, 17),
            attribution = ProviderAttribution("TheSportsDB", "https://www.thesportsdb.com"),
            appStoreAllowed = true, commercialUseStatus = CommercialUseStatus.PERMITTED,
            releaseGate = ProviderReleaseGate.REQUIRES_ATTRIBUTION,
        ))
    private val registry = SportsProviderRegistry(listOf(object : SportsProviderFactory {
        override val descriptor = this@SportsProviderBroadcastRouterTest.descriptor
        override fun create(config: SportsProviderConfig) = object : BroadcastProvider {
            override val descriptor = this@SportsProviderBroadcastRouterTest.descriptor
            override val instanceId = config.instanceId
            override suspend fun broadcasters(request: ProviderEventDetailRequest): SportsProviderResult<ProviderBatch<ProviderBroadcaster>> {
                if (cancel) throw CancellationException()
                calls += request.eventId
                return response
            }
        }
    }), SportsProviderReleasePolicy(attributionSupportedTypes = setOf("thesportsdb")))
    private val router = SportsProviderEventSourceRouter(registry)

    @Test fun `API event TV lookup resolves only a users playable canonical channel with provider credit`() = runTest {
        val resolved = router.enrichBroadcasters(listOf(resolved()), listOf(config), now).single()
        val event = localEvent().copy(provenance = resolved.provenance,
            providerAttributions = router.attributions(resolved.provenance))
        val channel = channel(1, "US: ESPN HD", "ESPN")
        val unrelated = channel(2, "ESPN 2", "ESPN 2")
        val matched = SportsEventFallbackResolver.addFallbacksWhenDirectChoicesAreUnavailable(event,
            SportsChannelIndex.build(listOf(channel, unrelated).map { SportsChannelIndexEntry(it, null) }))
        assertThat(calls).containsExactly("2070217")
        assertThat(matched.broadcasts.map { it.channel.id }).containsExactly(1L)
        assertThat(matched.broadcasts.single().channel.streamUrl).isEqualTo(channel.streamUrl)
        assertThat(matched.providerAttributions.map { it.sourceName }).containsExactly("TheSportsDB")
        assertThat(matched.provenance.broadcasters?.source).isEqualTo(source())
    }

    @Test fun `playable EPG mapping existing TV data disabled and unrelated accounts cause no TV calls`() = runTest {
        val direct = resolved().copy(epgEvent = localEvent().copy(
            broadcasts = listOf(SportsBroadcast(channel(1, "Local Guide Channel"), null))))
        assertThat(router.enrichBroadcasters(listOf(direct), listOf(config), now).single()).isEqualTo(direct)
        val already = resolved().copy(provenance = SportsEventProvenance(
            broadcasters = SourcedValue(listOf(ProviderBroadcaster("Known TV")), source())))
        router.enrichBroadcasters(listOf(already), listOf(config), now)
        router.enrichBroadcasters(listOf(resolved()), listOf(config.copy(enabled = false)), now)
        router.enrichBroadcasters(listOf(resolved()), listOf(config.copy(instanceId = "other-account")), now)
        assertThat(calls).isEmpty()
    }

    @Test fun `TV fanout is bounded to twelve nearest events and preserves output ordering`() = runTest {
        val input = (0..19).map { index -> resolved(index.toString(), now.plusSeconds((20 - index) * 60L)) }
        val output = router.enrichBroadcasters(input, listOf(config), now)
        assertThat(calls).hasSize(12)
        assertThat(calls.first()).isEqualTo("19")
        assertThat(output.map { it.id }).containsExactlyElementsIn(input.map { it.id }).inOrder()
    }

    @Test fun `quota failure stops fanout until retry time without spending another accounts quota`() = runTest {
        response = SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, 429, now.plusSeconds(600))
        val input = listOf(resolved(), resolved("2070218"))
        router.enrichBroadcasters(input, listOf(config), now)
        router.enrichBroadcasters(input, listOf(config), now.plusSeconds(599))
        assertThat(calls).hasSize(1)
        router.enrichBroadcasters(input, listOf(config), now.plusSeconds(600))
        assertThat(calls).hasSize(2)
    }

    @Test fun `coroutine cancellation is never converted into a missing broadcaster`() {
        cancel = true
        assertThrows(CancellationException::class.java) {
            runTest { router.enrichBroadcasters(listOf(resolved()), listOf(config), now) }
        }
    }

    private fun source(id: String = "2070217") = SportsSourceRef.Provider("premium", "thesportsdb", id)
    private fun resolved(id: String = "2070217", start: Instant = now) = SportsResolvedEvent(
        "provider:premium:$id", null, SourcedValue(ProviderEvent(id, title = "Event", eventStart = start), source(id)),
        0f, SportsEventProvenance(sources = setOf(source(id))),
    )
    private fun channel(id: Long, name: String, canonical: String = name) = Channel(
        id = id, name = name, canonicalName = canonical, providerId = 1,
        streamUrl = "https://local.invalid/$id.m3u8",
    )
    private fun localEvent() = SportsEvent("event", "Event", null, null, "", SportType.FOOTBALL,
        now.toEpochMilli(), now.plusSeconds(7200).toEpochMilli(), emptyList(), 1f, emptyList(), null,
        SportsEventType.LIVE_EVENT, emptyList())
}
