package com.streamvault.data.sports.provider.contract

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Implement with static vendor fixtures or MockWebServer responses, never a live account. */
abstract class EventProviderContract {
    enum class Scenario { VALID, MIXED_INVALID, ALL_INVALID, EMPTY, AUTH_FAILURE }
    protected abstract fun provider(scenario: Scenario): EventProvider
    protected abstract val expectedEventId: String
    protected abstract val expectedKickoff: Instant
    protected val request = ProviderEventRequest(
        Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"),
        leagueId = "4328",
    )

    @Test fun `event contract declares and implements events`() {
        val adapter = provider(Scenario.VALID)
        adapter.validateCapabilities()
        assertThat(adapter.descriptor.capabilities).contains(SportsCapability.EVENTS)
    }

    @Test fun `event contract returns stable external ids across repeated reads`() = runTest {
        val adapter = provider(Scenario.VALID)
        val first = (adapter.events(request) as SportsProviderResult.Success).value
        val second = (adapter.events(request) as SportsProviderResult.Success).value
        assertThat(first.items.map { it.providerEventId }).contains(expectedEventId)
        assertThat(second.items.map { it.providerEventId }).containsExactlyElementsIn(first.items.map { it.providerEventId })
        assertThat(first.items.all { it.providerEventId.isNotBlank() }).isTrue()
    }

    @Test fun `event contract converts documented provider timezone to UTC instant`() = runTest {
        val result = provider(Scenario.VALID).events(request) as SportsProviderResult.Success
        assertThat(result.value.items.single { it.providerEventId == expectedEventId }.eventStart)
            .isEqualTo(expectedKickoff)
    }

    @Test fun `event contract retains valid records when another record is malformed`() = runTest {
        val result = provider(Scenario.MIXED_INVALID).events(request) as SportsProviderResult.Success
        assertThat(result.value.items.map { it.providerEventId }).contains(expectedEventId)
        assertThat(result.value.rejectedItemCount).isGreaterThan(0)
        assertThat(result.toString()).doesNotContain(SECRET_MARKER)
    }

    @Test fun `event contract distinguishes invalid feed from valid empty feed`() = runTest {
        assertThat(provider(Scenario.ALL_INVALID).events(request))
            .isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE))
        assertThat(provider(Scenario.EMPTY).events(request))
            .isEqualTo(SportsProviderResult.Success(ProviderBatch<ProviderEvent>(emptyList())))
    }

    @Test fun `event contract exposes auth failure without credential or server body`() = runTest {
        val failure = provider(Scenario.AUTH_FAILURE).events(request)
        assertThat(failure).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((failure as SportsProviderResult.Failure).category).isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(failure.toString()).doesNotContain(SECRET_MARKER)
    }

    companion object { const val SECRET_MARKER = "contract-private-key" }
}
