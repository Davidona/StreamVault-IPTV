package com.streamvault.data.sports.provider.contract

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The valid fixture must contain a text score, LIVE status, and a documented update time. */
abstract class LiveScoreProviderContract {
    enum class Scenario { VALID, MISSING_FIELDS, MIXED_INVALID, AUTH_FAILURE }
    protected abstract fun provider(scenario: Scenario): LiveScoreProvider
    protected abstract val expectedEventId: String
    protected abstract val expectedTextScore: String
    protected abstract val expectedUpdatedAt: Instant
    private val request get() = ProviderScoreRequest(setOf(expectedEventId))

    @Test fun `score contract declares and implements live scores`() {
        val adapter = provider(Scenario.VALID)
        adapter.validateCapabilities()
        assertThat(adapter.descriptor.capabilities).contains(SportsCapability.LIVE_SCORES)
    }

    @Test fun `score contract preserves external identity text score normalized status and update time`() = runTest {
        val result = provider(Scenario.VALID).liveScores(request) as SportsProviderResult.Success
        val score = result.value.items.single { it.providerEventId == expectedEventId }
        assertThat(result.value.items.all { it.providerEventId in request.eventIds }).isTrue()
        assertThat(score.scores.map { it.value }).contains(expectedTextScore)
        assertThat(score.status?.state).isEqualTo(ProviderGameState.LIVE)
        assertThat(score.updatedAt).isEqualTo(expectedUpdatedAt)
    }

    @Test fun `score contract accepts absent score status and update fields`() = runTest {
        val result = provider(Scenario.MISSING_FIELDS).liveScores(request) as SportsProviderResult.Success
        val score = result.value.items.single()
        assertThat(score.providerEventId).isEqualTo(expectedEventId)
        assertThat(score.scores.all { it.value == null }).isTrue()
        assertThat(score.status).isNull()
        assertThat(score.updatedAt).isNull()
    }

    @Test fun `score contract does not discard valid scores because another record is malformed`() = runTest {
        val result = provider(Scenario.MIXED_INVALID).liveScores(request) as SportsProviderResult.Success
        assertThat(result.value.items.map { it.providerEventId }).contains(expectedEventId)
        assertThat(result.value.rejectedItemCount).isGreaterThan(0)
        assertThat(result.toString()).doesNotContain(EventProviderContract.SECRET_MARKER)
    }

    @Test fun `score contract exposes sanitized authentication failure`() = runTest {
        val failure = provider(Scenario.AUTH_FAILURE).liveScores(request)
        assertThat(failure).isInstanceOf(SportsProviderResult.Failure::class.java)
        assertThat((failure as SportsProviderResult.Failure).category).isEqualTo(SportsProviderError.AUTHENTICATION)
        assertThat(failure.toString()).doesNotContain(EventProviderContract.SECRET_MARKER)
    }
}
