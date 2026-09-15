package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.StalkerObservation
import com.streamvault.domain.model.StalkerObservationSource
import com.streamvault.domain.model.StalkerPortalLearning
import com.streamvault.domain.model.XtreamConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RoomProviderSnapshotRepositoryTest {

    private val gson = Gson()
    private val codec = ProviderConfigurationCodec(
        gson = gson,
        credentialCrypto = object : com.streamvault.data.security.CredentialCrypto {
            override fun encryptIfNeeded(value: String): String = value
            override fun decryptIfNeeded(value: String): String = value
        }
    )

    @Test
    fun `snapshot reads and decodes run on supplied dispatcher`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = providerEntity()
        val config = configEntity(XtreamConfig("https://example.test", "user", "password"))
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(config)
        whenever(snapshotDao.getRuntime(provider.id)).thenReturn(null)
        whenever(stalkerPortalStateDao.get(provider.id)).thenReturn(null)

        val repository = repository(providerDao, snapshotDao, stalkerPortalStateDao, dispatcher)

        val result = async(start = CoroutineStart.UNDISPATCHED) {
            repository.getSnapshot(provider.id)
        }

        assertThat(result.isCompleted).isFalse()
        verifyNoInteractions(providerDao, snapshotDao, stalkerPortalStateDao)

        advanceUntilIdle()

        assertThat(result.await()?.provider?.id).isEqualTo(provider.id)
        verify(providerDao).getById(provider.id)
        verify(snapshotDao).getConfig(provider.id)
        verify(snapshotDao).getRuntime(provider.id)
    }

    @Test
    fun `snapshot preserves credentials identity and runtime fields`() = runTest {
        val provider = providerEntity().copy(
            isActive = false,
            status = ProviderStatus.ACTIVE,
            lastSyncedAt = 41L,
            createdAt = 42L
        )
        val configuration = XtreamConfig(
            serverUrl = "https://example.test",
            username = "user",
            password = "secret"
        )
        val runtime = ProviderAccountRuntimeEntity(
            providerId = provider.id,
            maxConnections = 4,
            expirationDate = 1234L,
            apiVersion = "v2",
            allowedOutputFormatsJson = "[\"m3u8\",\"ts\"]",
            catalogLayout = CatalogLayout.UNIFIED_VOD,
            catalogLayoutDetectionVersion = 3,
            observedAt = 99L
        )
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(configEntity(configuration))
        whenever(snapshotDao.getRuntime(provider.id)).thenReturn(runtime)

        val result = repository(
            providerDao,
            snapshotDao,
            stalkerPortalStateDao,
            UnconfinedTestDispatcher(testScheduler)
        ).getSnapshot(provider.id)

        assertThat(result?.configuration).isEqualTo(configuration)
        assertThat((result?.configuration as XtreamConfig).password).isEqualTo("secret")
        assertThat(result.provider.isActive).isFalse()
        assertThat(result.provider.status).isEqualTo(ProviderStatus.ACTIVE)
        assertThat(result.provider.lastSyncedAt).isEqualTo(41L)
        assertThat(result.provider.createdAt).isEqualTo(42L)
        assertThat(result.accountRuntime.maxConnections).isEqualTo(4)
        assertThat(result.accountRuntime.expirationDate).isEqualTo(1234L)
        assertThat(result.accountRuntime.apiVersion).isEqualTo("v2")
        assertThat(result.accountRuntime.allowedOutputFormats).containsExactly("m3u8", "ts").inOrder()
        assertThat(result.accountRuntime.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(result.accountRuntime.catalogLayoutDetectionVersion).isEqualTo(3)
        assertThat(result.accountRuntime.observedAt).isEqualTo(99L)
        verifyNoInteractions(stalkerPortalStateDao)
    }

    @Test
    fun `missing provider returns null without reading snapshot rows`() = runTest {
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(9L)).thenReturn(null)

        val result = repository(
            providerDao,
            snapshotDao,
            stalkerPortalStateDao,
            UnconfinedTestDispatcher(testScheduler)
        ).getSnapshot(9L)

        assertThat(result).isNull()
        verifyNoInteractions(snapshotDao, stalkerPortalStateDao)
    }

    @Test
    fun `missing configuration returns null without reading runtime or learning`() = runTest {
        val provider = providerEntity()
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(null)

        val result = repository(
            providerDao,
            snapshotDao,
            stalkerPortalStateDao,
            UnconfinedTestDispatcher(testScheduler)
        ).getSnapshot(provider.id)

        assertThat(result).isNull()
        verify(snapshotDao, never()).getRuntime(provider.id)
        verifyNoInteractions(stalkerPortalStateDao)
    }

    @Test
    fun `provider and configuration type mismatch fails before decoding`() = runTest {
        val provider = providerEntity(type = ProviderType.M3U)
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(
            configEntity(XtreamConfig("https://example.test", "user", "secret"))
        )

        val failure = runCatching {
            repository(
                providerDao,
                snapshotDao,
                stalkerPortalStateDao,
                UnconfinedTestDispatcher(testScheduler)
            ).getSnapshot(provider.id)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("type mismatch")
        verify(snapshotDao, never()).getRuntime(provider.id)
        verifyNoInteractions(stalkerPortalStateDao)
    }

    @Test
    fun `generation-valid Stalker learning is preserved in snapshot`() = runTest {
        val generation = 7L
        val provider = providerEntity(type = ProviderType.STALKER_PORTAL)
        val configuration = StalkerConfig(
            portalUrl = "https://portal.example.test",
            device = StalkerDeviceIdentity("00:11:22:33:44:55"),
            password = "secret"
        )
        val learning = StalkerPortalLearning(
            configurationGeneration = generation,
            workingEndpoint = StalkerObservation(
                value = "/stalker_portal/server/load.php",
                configurationGeneration = generation,
                source = StalkerObservationSource.DISCOVERY,
                observedAt = 81L
            )
        )
        val providerDao = mock<ProviderDao>()
        val snapshotDao = mock<ProviderSnapshotDao>()
        val stalkerPortalStateDao = mock<StalkerPortalStateDao>()
        whenever(providerDao.getById(provider.id)).thenReturn(provider)
        whenever(snapshotDao.getConfig(provider.id)).thenReturn(configEntity(configuration, generation))
        whenever(snapshotDao.getRuntime(provider.id)).thenReturn(null)
        whenever(stalkerPortalStateDao.get(provider.id)).thenReturn(
            StalkerPortalStateEntity(
                providerId = provider.id,
                configurationGeneration = generation,
                learningJson = gson.toJson(learning)
            )
        )

        val result = repository(
            providerDao,
            snapshotDao,
            stalkerPortalStateDao,
            UnconfinedTestDispatcher(testScheduler)
        ).getSnapshot(provider.id)

        assertThat(result?.configuration).isEqualTo(configuration)
        assertThat(result?.configurationGeneration).isEqualTo(generation)
        assertThat(result?.stalkerLearning).isEqualTo(learning)
    }

    private fun providerEntity(type: ProviderType = ProviderType.XTREAM_CODES) = ProviderEntity(
        id = 9L,
        name = "Living room",
        type = type
    )

    private fun configEntity(
        configuration: ProviderConfiguration,
        generation: Long = 3L
    ) = ProviderConfigEntity(
        providerId = 9L,
        type = configuration.type,
        schemaVersion = configuration.schemaVersion,
        configurationGeneration = generation,
        identityKey = "identity",
        encryptedConfigJson = codec.encode(configuration),
        updatedAt = 1L
    )

    private fun repository(
        providerDao: ProviderDao,
        snapshotDao: ProviderSnapshotDao,
        stalkerPortalStateDao: StalkerPortalStateDao,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) = RoomProviderSnapshotRepository(
        providerDao = providerDao,
        snapshotDao = snapshotDao,
        stalkerPortalStateDao = stalkerPortalStateDao,
        codec = codec,
        gson = gson,
        workDispatcher = dispatcher
    )
}
