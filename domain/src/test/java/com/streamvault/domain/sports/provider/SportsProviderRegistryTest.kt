package com.streamvault.domain.sports.provider

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class SportsProviderRegistryTest {
    private val descriptor = SportsProviderDescriptor(
        "fixtures", setOf(SportsCapability.EVENTS), SportsProviderAuth.Bearer,
        configurationVersion = 2, defaultBaseUrl = "https://fixtures.test/api",
    )
    private val factory = object : SportsProviderFactory {
        override val descriptor = this@SportsProviderRegistryTest.descriptor
        override fun migrate(config: SportsProviderConfig) = config.copy(configurationVersion = 2)
        override fun create(config: SportsProviderConfig) = object : EventProvider {
            override val descriptor = this@SportsProviderRegistryTest.descriptor
            override val instanceId = config.instanceId
            override suspend fun events(request: ProviderEventRequest) =
                SportsProviderResult.Success(ProviderBatch<ProviderEvent>(emptyList()))
        }
    }
    private val registry = SportsProviderRegistry(listOf(factory))

    @Test fun `empty registry supports local only startup`() {
        val empty = SportsProviderRegistry(emptyList())
        assertThat(empty.descriptors).isEmpty()
        assertThat(empty.eligible(emptyList(), SportsCapability.EVENTS)).isEmpty()
    }

    @Test fun `capability selection filters disabled unknown and score only accounts with stable ties`() {
        val scores = object : SportsProviderFactory by factory {
            override val descriptor = this@SportsProviderRegistryTest.descriptor.copy(
                typeId = "scores", capabilities = setOf(SportsCapability.LIVE_SCORES),
            )

            override fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> = descriptor.capabilities
        }
        val registry = SportsProviderRegistry(listOf(scores, factory))
        val configs = listOf(
            config("b"), config("a"), config("disabled").copy(enabled = false),
            config("unknown").copy(typeId = "unknown"), config("score").copy(typeId = "scores"),
            config("first").copy(priority = -1),
        )
        assertThat(registry.eligible(configs, SportsCapability.EVENTS).map { it.instanceId })
            .containsExactly("first", "a", "b").inOrder()
        assertThat(registry.eligible(configs, SportsCapability.STANDINGS)).isEmpty()
    }

    @Test fun `migrates configuration without changing account identity`() {
        assertThat(registry.prepare(config("personal")).configurationVersion).isEqualTo(2)
        assertThat(registry.create(config("personal")).instanceId).isEqualTo("personal")
        assertThat(registry.create(config("family")).instanceId).isEqualTo("family")
    }

    @Test fun `duplicate adapter registration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SportsProviderRegistry(listOf(factory, factory)) }
    }

    @Test fun `factory cannot advertise a capability without implementing its interface`() {
        for (capability in SportsCapability.entries) {
            val unsupported = descriptor.copy(capabilities = setOf(capability))
            val bad = object : SportsProviderFactory {
                override val descriptor = unsupported
                override fun create(config: SportsProviderConfig) = object : SportsProvider {
                    override val descriptor = unsupported
                    override val instanceId = config.instanceId
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                SportsProviderRegistry(listOf(bad)).create(config("a").copy(configurationVersion = 2))
            }
        }
    }

    @Test fun `implemented interface does not silently enable an undeclared capability`() {
        val hidden = descriptor.copy(capabilities = emptySet())
        val optional = object : SportsProviderFactory {
            override val descriptor = hidden
            override fun create(config: SportsProviderConfig) = object : EventProvider {
                override val descriptor = hidden
                override val instanceId = config.instanceId
                override suspend fun events(request: ProviderEventRequest) =
                    SportsProviderResult.Success(ProviderBatch<ProviderEvent>(emptyList()))
            }
        }
        val registry = SportsProviderRegistry(listOf(optional))
        registry.create(config("a").copy(configurationVersion = 2))
        assertThat(registry.eligible(listOf(config("a")), SportsCapability.EVENTS)).isEmpty()
    }

    @Test fun `future versions and forbidden endpoint overrides are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.prepare(config("a").copy(configurationVersion = 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.prepare(config("a").copy(baseUrl = "https://other.test"))
        }
        assertThat(registry.prepare(config("a").copy(baseUrl = descriptor.defaultBaseUrl)).baseUrl)
            .isEqualTo(descriptor.defaultBaseUrl)
    }

    @Test fun `migration cannot replace account or provider identity`() {
        val bad = object : SportsProviderFactory by factory {
            override fun migrate(config: SportsProviderConfig) = config.copy(instanceId = "other", configurationVersion = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SportsProviderRegistry(listOf(bad)).prepare(config("a"))
        }
    }

    @Test fun `base urls cannot carry credentials queries fragments or cleartext`() {
        listOf("http://api.test", "https://user:secret@api.test", "https://api.test?key=secret",
            "https://api.test#secret", "https:///missing-host", "bad secret url").forEach { url ->
            val error = assertThrows(IllegalArgumentException::class.java) { config("a").copy(baseUrl = url) }
            assertThat(error.toString()).doesNotContain("secret")
        }
    }

    @Test fun `credentials have no secret string representation`() {
        assertThat(SportsProviderCredentials("private-key", "private-user").toString())
            .isEqualTo("SportsProviderCredentials([REDACTED])")
    }

    @Test fun `unsafe auth header names are rejected`() {
        listOf("Host", "Cookie", "Content-Length", "Transfer-Encoding", "X-Key\r\nInjected").forEach {
            assertThrows(IllegalArgumentException::class.java) { SportsProviderAuth.ApiKeyHeader(it) }
        }
    }

    @Test fun `health preserves last success through failure and clears failure after recovery`() {
        val first = Instant.parse("2026-10-17T00:00:00Z")
        val success = SportsProviderHealth().after(SportsProviderResult.Success(Unit), first)
        val failure = SportsProviderResult.Failure(SportsProviderError.RATE_LIMITED, 429, first.plusSeconds(60))
        val failed = success.after(failure, first.plusSeconds(1))
        assertThat(failed.lastSuccessAt).isEqualTo(first)
        assertThat(failed.lastFailure).isEqualTo(failure)
        assertThat(failed.after(SportsProviderResult.Success(Unit), first.plusSeconds(61)).lastFailure).isNull()
    }

    private fun config(id: String) = SportsProviderConfig(id, "fixtures")
}
