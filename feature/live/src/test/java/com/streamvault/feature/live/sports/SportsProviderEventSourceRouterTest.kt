package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.SportsProviderReleasePolicy
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sports.provider.EventProvider
import com.streamvault.domain.sports.provider.ProviderBatch
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderError
import com.streamvault.domain.sports.provider.SportsProviderFactory
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsProviderEventSourceRouterTest {
    @Test
    fun `EPG selection returns no event providers`() {
        val factory = TestFactory("events")
        val router = SportsProviderEventSourceRouter(SportsProviderRegistry(listOf(factory), SportsProviderReleasePolicy(development = true)))

        val eligible = router.eligibleProviders(
            listOf(config("account", "events")),
            SportsEventSourcePreference.EPG,
        )

        assertThat(eligible).isEmpty()
    }

    @Test
    fun `automatic filters capabilities and orders enabled event providers`() {
        val eventFactory = TestFactory("events", setOf(SportsCapability.EVENTS))
        val metadataFactory = TestFactory("metadata", setOf(SportsCapability.STANDINGS))
        val router = SportsProviderEventSourceRouter(SportsProviderRegistry(listOf(eventFactory, metadataFactory), SportsProviderReleasePolicy(development = true)))

        val eligible = router.eligibleProviders(
            listOf(
                config("disabled", "events", enabled = false),
                config("metadata", "metadata", priority = -1),
                config("later", "events", priority = 2),
                config("first", "events", priority = 1),
            ),
            SportsEventSourcePreference.AUTOMATIC,
        )

        assertThat(eligible.map { it.instanceId }).containsExactly("first", "later").inOrder()
    }

    @Test
    fun `automatic falls through a failed provider and queries every configured league`() = runTest {
        val failureFactory = TestFactory("failure", eventResponse = { _, _ ->
            SportsProviderResult.Failure(SportsProviderError.NETWORK)
        })
        val leagueCalls = mutableListOf<String?>()
        val scopedFactory = TestFactory("scoped", eventResponse = { _, request ->
            leagueCalls += request.leagueId
            val items = when (request.leagueId) {
                "4328" -> listOf(ProviderEvent("shared"))
                "4380" -> listOf(ProviderEvent("shared"), ProviderEvent("second"))
                else -> emptyList()
            }
            SportsProviderResult.Success(ProviderBatch(items))
        })
        val router = SportsProviderEventSourceRouter(SportsProviderRegistry(listOf(failureFactory, scopedFactory), SportsProviderReleasePolicy(development = true)))
        val providers = router.eligibleProviders(
            listOf(
                config("primary", "failure", priority = 0),
                config("secondary", "scoped", priority = 1, leagueIds = listOf("4328", "4380")),
            ),
            SportsEventSourcePreference.AUTOMATIC,
        )

        val loaded = router.load(providers, Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"))

        assertThat(loaded.instanceId).isEqualTo("secondary")
        assertThat(loaded.events.map(ProviderEvent::providerEventId)).containsExactly("shared", "second").inOrder()
        assertThat(leagueCalls).containsExactly("4328", "4380").inOrder()
    }

    @Test
    fun `explicit provider selection does not query another provider after failure`() = runTest {
        val selected = TestFactory("selected", eventResponse = { _, _ ->
            SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED)
        })
        val other = TestFactory("other", eventResponse = { _, _ ->
            SportsProviderResult.Success(ProviderBatch(listOf(ProviderEvent("unexpected"))))
        })
        val router = SportsProviderEventSourceRouter(SportsProviderRegistry(listOf(selected, other), SportsProviderReleasePolicy(development = true)))
        val providers = router.eligibleProviders(
            listOf(config("selected-account", "selected"), config("other-account", "other")),
            SportsEventSourcePreference.provider("selected-account"),
        )

        val loaded = router.load(providers, Instant.parse("2026-10-17T00:00:00Z"), Instant.parse("2026-10-18T00:00:00Z"))

        assertThat(providers.map { it.instanceId }).containsExactly("selected-account")
        assertThat(loaded.instanceId).isNull()
        assertThat(loaded.events).isEmpty()
    }

    private fun config(
        id: String,
        type: String,
        enabled: Boolean = true,
        priority: Int = 0,
        leagueIds: List<String> = emptyList(),
    ) = SportsProviderConfig(
        instanceId = id,
        typeId = type,
        enabled = enabled,
        priority = priority,
        eventLeagueIds = leagueIds,
    )

    private class TestFactory(
        private val type: String,
        capabilities: Set<SportsCapability> = setOf(SportsCapability.EVENTS),
        private val eventResponse: suspend (SportsProviderConfig, ProviderEventRequest) -> SportsProviderResult<ProviderBatch<ProviderEvent>> =
            { _, _ -> SportsProviderResult.Success(ProviderBatch(emptyList())) },
    ) : SportsProviderFactory {
        override val descriptor = SportsProviderDescriptor(
            typeId = type,
            capabilities = capabilities,
            auth = SportsProviderAuth.None,
        )

        override fun create(config: SportsProviderConfig): SportsProvider = object : EventProvider {
            override val descriptor = this@TestFactory.descriptor
            override val instanceId = config.instanceId

            override suspend fun events(request: ProviderEventRequest) = eventResponse(config, request)
        }
    }
}
