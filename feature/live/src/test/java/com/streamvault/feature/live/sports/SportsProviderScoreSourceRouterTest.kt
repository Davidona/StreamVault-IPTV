package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.SportsProviderReleasePolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.EventProvider
import com.streamvault.domain.sports.provider.LiveScoreProvider
import com.streamvault.domain.sports.provider.ProviderBatch
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventRequest
import com.streamvault.domain.sports.provider.ProviderGameState
import com.streamvault.domain.sports.provider.ProviderGameStatus
import com.streamvault.domain.sports.provider.ProviderScore
import com.streamvault.domain.sports.provider.ProviderScoreRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderError
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsProviderScoreSourceRouterTest {
    @Test
    fun `busy catalog caps score requests and prioritizes live games`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        val events = (1..75).map { index -> ProviderEvent(
            providerEventId = "event-$index", eventStart = now.plusSeconds(index.toLong()),
            status = ProviderGameStatus(if (index == 75) ProviderGameState.LIVE else ProviderGameState.SCHEDULED),
        ) }
        var requested = emptySet<String>()
        val factory = TestFactory("scores", scoreResponse = { _, request ->
            requested = request.eventIds
            SportsProviderResult.Success(ProviderBatch(request.eventIds.map { ProviderScore(it) }))
        })
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val router = SportsProviderScoreSourceRouter(registry, SportsProviderEventSourceRouter(registry))
        val loaded = router.load(
            listOf(config("account", "scores")),
            LoadedSportsProviderEvents("account", "scores", events),
            now.minusSeconds(86_400), now.plusSeconds(86_400), now,
        )

        assertThat(requested).hasSize(50)
        assertThat(requested).contains("event-75")
        assertThat(requested.first()).isEqualTo("event-75")
        assertThat(loaded.scores).hasSize(50)
    }

    @Test
    fun `automatic selection prefers event account and manual selection stays independent`() {
        val first = TestFactory("first")
        val second = TestFactory("second")
        val registry = SportsProviderRegistry(listOf(first, second), SportsProviderReleasePolicy(development = true))
        val eventRouter = SportsProviderEventSourceRouter(registry)
        val router = SportsProviderScoreSourceRouter(registry, eventRouter)
        val configs = listOf(config("first-account", "first", priority = 1), config("second-account", "second", priority = 0))

        val automatic = router.eligibleProviders(configs, SportsScoreSourcePreference.AUTOMATIC, "first-account")
        val selected = router.eligibleProviders(
            configs,
            SportsScoreSourcePreference.provider("second-account"),
            "first-account",
        )

        assertThat(automatic.map(SportsProviderConfig::instanceId))
            .containsExactly("first-account", "second-account").inOrder()
        assertThat(selected.map(SportsProviderConfig::instanceId)).containsExactly("second-account")
        assertThat(router.eligibleProviders(configs, SportsScoreSourcePreference.OFF, null)).isEmpty()
    }

    @Test
    fun `score refresh is cached for two minutes and stale scores survive provider failure`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        var scoreResult: SportsProviderResult<ProviderBatch<ProviderScore>> =
            SportsProviderResult.Success(ProviderBatch(listOf(ProviderScore("match", updatedAt = now))))
        val factory = TestFactory(
            type = "scores",
            eventResponse = { _, _ ->
                SportsProviderResult.Success(
                    ProviderBatch(listOf(ProviderEvent(
                        "match",
                        eventStart = now.minusSeconds(600),
                        status = ProviderGameStatus(ProviderGameState.LIVE),
                    ))),
                )
            },
            scoreResponse = { _, _ -> scoreResult },
        )
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val eventRouter = SportsProviderEventSourceRouter(registry)
        val router = SportsProviderScoreSourceRouter(registry, eventRouter)
        val config = config("scores-account", "scores")
        val providers = router.eligibleProviders(listOf(config), SportsScoreSourcePreference.AUTOMATIC, null)
        val from = now.minusSeconds(86_400)
        val until = now.plusSeconds(86_400)

        val first = router.load(providers, LoadedSportsProviderEvents(), from, until, now)
        val cached = router.load(providers, LoadedSportsProviderEvents(), from, until, now.plusSeconds(60))
        scoreResult = SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED)
        val stale = router.load(providers, LoadedSportsProviderEvents(), from, until, now.plusSeconds(180))
        val cooldownStale = router.load(providers, LoadedSportsProviderEvents(), from, until, now.plusSeconds(240))

        assertThat(first.scores).hasSize(1)
        assertThat(first.scores.single().isStale).isFalse()
        assertThat(cached.scores.single().isStale).isFalse()
        assertThat(stale.scores.single().isStale).isTrue()
        assertThat(cooldownStale.scores.single().isStale).isTrue()
        assertThat(stale.scores.single().value.providerEventId).isEqualTo("match")
        assertThat(factory.scoreCalls).isEqualTo(2)
        assertThat(factory.eventCalls).isEqualTo(1)
    }

    @Test
    fun `automatic mode falls through a failed score source`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        val failing = TestFactory(
            type = "failing",
            scoreResponse = { _, _ -> SportsProviderResult.Failure(SportsProviderError.NETWORK) },
            eventResponse = { _, _ -> liveEvent("failing-event", now) },
        )
        val working = TestFactory(
            type = "working",
            scoreResponse = { _, _ -> SportsProviderResult.Success(ProviderBatch(listOf(ProviderScore("working-event")))) },
            eventResponse = { _, _ -> liveEvent("working-event", now) },
        )
        val registry = SportsProviderRegistry(listOf(failing, working), SportsProviderReleasePolicy(development = true))
        val eventRouter = SportsProviderEventSourceRouter(registry)
        val router = SportsProviderScoreSourceRouter(registry, eventRouter)
        val configs = listOf(config("primary", "failing", priority = 0), config("fallback", "working", priority = 1))
        val providers = router.eligibleProviders(configs, SportsScoreSourcePreference.AUTOMATIC, null)

        val loaded = router.load(
            providers,
            LoadedSportsProviderEvents(),
            now.minusSeconds(86_400),
            now.plusSeconds(86_400),
            now,
        )

        assertThat(loaded.instanceId).isEqualTo("fallback")
        assertThat((loaded.scores.single().source as com.streamvault.domain.sports.provider.SportsSourceRef.Provider).instanceId)
            .isEqualTo("fallback")
    }

    @Test
    fun `score retry cooldown honors provider retry time`() = runTest {
        val now = Instant.parse("2026-10-17T12:00:00Z")
        var scoreResult: SportsProviderResult<ProviderBatch<ProviderScore>> =
            SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, retryAt = now.plusSeconds(600))
        val factory = TestFactory(
            type = "retry",
            eventResponse = { _, _ -> liveEvent("match", now) },
            scoreResponse = { _, _ -> scoreResult },
        )
        val registry = SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true))
        val router = SportsProviderScoreSourceRouter(registry, SportsProviderEventSourceRouter(registry))
        val providers = router.eligibleProviders(
            listOf(config("retry-account", "retry")),
            SportsScoreSourcePreference.AUTOMATIC,
            null,
        )
        val from = now.minusSeconds(86_400)
        val until = now.plusSeconds(86_400)

        router.load(providers, LoadedSportsProviderEvents(), from, until, now)
        router.load(providers, LoadedSportsProviderEvents(), from, until, now.plusSeconds(300))
        assertThat(factory.scoreCalls).isEqualTo(1)

        scoreResult = SportsProviderResult.Success(ProviderBatch(emptyList()))
        router.load(providers, LoadedSportsProviderEvents(), from, until, now.plusSeconds(601))

        assertThat(factory.scoreCalls).isEqualTo(2)
    }

    private fun liveEvent(id: String, now: Instant) = SportsProviderResult.Success(
        ProviderBatch(listOf(ProviderEvent(
            id,
            eventStart = now.minusSeconds(600),
            status = ProviderGameStatus(ProviderGameState.LIVE),
        ))),
    )

    private fun config(id: String, type: String, priority: Int = 0) = SportsProviderConfig(
        instanceId = id,
        typeId = type,
        priority = priority,
    )

    private class TestFactory(
        private val type: String,
        private val eventResponse: suspend (SportsProviderConfig, ProviderEventRequest) -> SportsProviderResult<ProviderBatch<ProviderEvent>> =
            { _, _ -> SportsProviderResult.Success(ProviderBatch(emptyList())) },
        private val scoreResponse: suspend (SportsProviderConfig, ProviderScoreRequest) -> SportsProviderResult<ProviderBatch<ProviderScore>> =
            { _, _ -> SportsProviderResult.Success(ProviderBatch(emptyList())) },
    ) : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = type,
            capabilities = setOf(SportsCapability.EVENTS, SportsCapability.LIVE_SCORES),
            auth = SportsProviderAuth.None,
        )
        var eventCalls = 0
        var scoreCalls = 0

        override fun create(config: SportsProviderConfig): SportsProvider = object : EventProvider, LiveScoreProvider {
            override val descriptor = this@TestFactory.descriptor
            override val instanceId = config.instanceId

            override suspend fun events(request: ProviderEventRequest): SportsProviderResult<ProviderBatch<ProviderEvent>> {
                eventCalls++
                return eventResponse(config, request)
            }

            override suspend fun liveScores(request: ProviderScoreRequest): SportsProviderResult<ProviderBatch<ProviderScore>> {
                scoreCalls++
                return scoreResponse(config, request)
            }
        }
    }
}
