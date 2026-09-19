package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.Provider as StableProvider
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.StalkerObservation
import com.streamvault.domain.model.StalkerObservationSource
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.model.XtreamConfig
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderPublicProjectionTest {

    private val gson = Gson()
    private val crypto = CountingCredentialCrypto()
    private val codec = ProviderConfigurationCodec(gson, crypto)

    @Test
    fun `decode returns a redacted template and preserves public configuration fields`() {
        val projection = defaultPublicProjection()

        val decoded = projection.decode(
            configEntity(
                XtreamConfig(
                    serverUrl = "https://example.test",
                    username = "user",
                    password = "secret"
                )
            )
        )

        assertThat(decoded.providerType).isEqualTo(ProviderType.XTREAM_CODES)
        assertThat(decoded.configurationGeneration).isEqualTo(7L)
        assertThat(decoded.providerTemplate.serverUrl).isEqualTo("https://example.test")
        assertThat(decoded.providerTemplate.username).isEqualTo("user")
        assertThat(decoded.providerTemplate.password).isEmpty()
        assertThat(crypto.decryptCalls.get()).isEqualTo(1)
    }

    @Test
    fun `assemble overlays identity runtime and generation-valid Stalker learning`() {
        val projection = defaultPublicProjection()
        val configuration = StalkerConfig(
            portalUrl = "https://portal.example",
            device = StalkerDeviceIdentity("00:11:22:33:44:55"),
            username = "user",
            password = "secret"
        )
        val row = configEntity(configuration, generation = 4L)
        val decoded = projection.decode(row)
        val portalState = StalkerPortalStateEntity(
            providerId = 9L,
            configurationGeneration = 4L,
            learningJson = gson.toJson(
                StalkerPortalLearning(
                    configurationGeneration = 4L,
                    profileId = StalkerObservation(
                        value = "learned-profile",
                        configurationGeneration = 4L,
                        source = StalkerObservationSource.DISCOVERY,
                        observedAt = 10L
                    )
                )
            )
        )

        val result = projection.assemble(
            identity = providerEntity(id = 9L, name = "Living room", type = ProviderType.STALKER_PORTAL),
            configuration = decoded,
            runtime = ProviderAccountRuntimeEntity(
                providerId = 9L,
                maxConnections = 3,
                allowedOutputFormatsJson = "[\"m3u8\"]",
                catalogLayout = CatalogLayout.UNIFIED_VOD,
                catalogLayoutDetectionVersion = 2
            ),
            portalState = portalState
        )

        assertThat(result.name).isEqualTo("Living room")
        assertThat(result.maxConnections).isEqualTo(3)
        assertThat(result.allowedOutputFormats).containsExactly("m3u8")
        assertThat(result.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(result.stalkerConfigurationGeneration).isEqualTo(4L)
        assertThat(result.stalkerLearnedProfileId).isEqualTo("learned-profile")
        assertThat(result.password).isEmpty()
    }

    @Test
    fun `assemble rejects a configuration type mismatch`() {
        val projection = defaultPublicProjection()
        val mismatched = RedactedProviderConfigurationProjection(
            providerType = ProviderType.M3U,
            configurationGeneration = 1L,
            providerTemplate = M3uConfig(
                playlistUrl = "https://playlist.example"
            ).toLegacyProvider(
                identity = StableProvider(
                    id = 1L,
                    name = "template",
                    type = ProviderType.M3U
                ),
                runtime = com.streamvault.domain.model.ProviderAccountRuntime()
            )
        )

        assertThrows(IllegalStateException::class.java) {
            projection.assemble(
                identity = providerEntity(id = 1L, name = "Xtream", type = ProviderType.XTREAM_CODES),
                configuration = mismatched,
                runtime = null,
                portalState = null
            )
        }
    }

    @Test
    fun `assembly matches the snapshot mapper for every provider subtype`() {
        val cases = listOf<ProviderConfiguration>(
            XtreamConfig("https://x.test", "alice", "secret"),
            M3uConfig("https://m.test/list.m3u", epgUrl = "https://m.test/epg.xml"),
            StalkerConfig(
                portalUrl = "https://s.test",
                device = StalkerDeviceIdentity("00:11:22:33:44:55"),
                username = "bob",
                password = "secret2"
            ),
            JellyfinConfig("https://j.test", "carol", "token")
        )

        cases.forEach { configuration ->
            val row = configEntity(configuration, providerId = 5L, generation = 3L)
            val identity = providerEntity(id = 5L, name = "Provider", type = configuration.type)
            val runtime = com.streamvault.domain.model.ProviderAccountRuntime(
                maxConnections = 2,
                catalogLayout = CatalogLayout.UNIFIED_VOD,
                catalogLayoutDetectionVersion = 4
            )
            val expected = ProviderSnapshot(
                provider = StableProvider(
                    id = identity.id,
                    name = identity.name,
                    type = identity.type,
                    isActive = identity.isActive,
                    status = identity.status,
                    lastSyncedAt = identity.lastSyncedAt,
                    createdAt = identity.createdAt
                ),
                configuration = configuration,
                configurationGeneration = row.configurationGeneration,
                accountRuntime = runtime
            ).toLegacyProvider().redactedCredentials()
            val actual = defaultPublicProjection().assemble(
                identity = identity,
                configuration = defaultPublicProjection().decode(row),
                runtime = ProviderAccountRuntimeEntity(
                    providerId = identity.id,
                    maxConnections = runtime.maxConnections,
                    allowedOutputFormatsJson = "[]",
                    catalogLayout = runtime.catalogLayout,
                    catalogLayoutDetectionVersion = runtime.catalogLayoutDetectionVersion
                ),
                portalState = null
            )

            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun `missing configuration returns the redacted provider identity`() {
        val identity = providerEntity(id = 2L, name = "Legacy", type = ProviderType.M3U)

        assertThat(defaultPublicProjection().assemble(identity, null, null, null))
            .isEqualTo(identity.toPublicDomain())
    }

    private fun defaultPublicProjection() = DefaultProviderPublicProjection(codec, gson)

    private fun configEntity(
        configuration: ProviderConfiguration,
        providerId: Long = 9L,
        generation: Long = 7L
    ) = ProviderConfigEntity(
        providerId = providerId,
        type = configuration.type,
        schemaVersion = configuration.schemaVersion,
        configurationGeneration = generation,
        identityKey = codec.identityKey(configuration),
        encryptedConfigJson = codec.encode(configuration),
        updatedAt = 1L
    )

    private fun providerEntity(
        id: Long,
        name: String,
        type: ProviderType
    ) = ProviderEntity(
        id = id,
        name = name,
        type = type,
        isActive = false,
        status = ProviderStatus.ACTIVE,
        lastSyncedAt = 11L,
        createdAt = 12L
    )

    private class CountingCredentialCrypto : CredentialCrypto {
        val decryptCalls = AtomicInteger()

        override fun encryptIfNeeded(value: String): String = value

        override fun decryptIfNeeded(value: String): String {
            decryptCalls.incrementAndGet()
            return value
        }
    }
}
