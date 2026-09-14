package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderObservationStreamsTest {

    private data class DecodeGate(
        val started: CountDownLatch,
        val release: CountDownLatch
    )

    @Test
    fun `multiple collectors reuse one decoded redacted projection`() = runTest {
        val fixture = stateFixture()
        val streams = fixture.streams(StandardTestDispatcher(testScheduler))

        val providers = async { streams.providers.first() }
        val secondProviders = async { streams.providers.first() }
        val active = async { streams.activeProvider.first() }
        val secondActive = async { streams.activeProvider.first() }
        advanceUntilIdle()

        assertThat(providers.await()).hasSize(1)
        assertThat(secondProviders.await()).hasSize(1)
        assertThat(active.await()?.id).isEqualTo(9L)
        assertThat(secondActive.await()?.id).isEqualTo(9L)
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(1)
        assertThat(providers.getCompleted().single().password).isEmpty()
    }

    @Test
    fun `concurrent collectors decode one row only`() = runTest {
        val fixture = stateFixture()
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        fixture.projection.decodeGate = DecodeGate(decodeStarted, releaseDecode)
        val streams = fixture.streams(Dispatchers.Default)

        val first = async(Dispatchers.Default) { streams.providers.first() }
        assertThat(decodeStarted.await(2, TimeUnit.SECONDS)).isTrue()

        val second = async(Dispatchers.Default) { streams.providers.first() }
        Thread.sleep(100)

        assertThat(first.isCompleted).isFalse()
        assertThat(second.isCompleted).isFalse()
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(1)

        releaseDecode.countDown()
        assertThat(first.await()).hasSize(1)
        assertThat(second.await()).hasSize(1)
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `runtime-only changes assemble without another decode`() = runTest {
        val fixture = stateFixture()
        val streams = fixture.streams(StandardTestDispatcher(testScheduler))
        val results = mutableListOf<List<LegacyProvider>>()
        val collector = launch {
            streams.providers.take(2).toList(results)
        }
        advanceUntilIdle()

        fixture.runtimes.value = listOf(
            ProviderAccountRuntimeEntity(
                providerId = 9L,
                maxConnections = 4,
                catalogLayout = CatalogLayout.UNIFIED_VOD,
                catalogLayoutDetectionVersion = 2
            )
        )
        advanceUntilIdle()
        collector.join()

        assertThat(results).hasSize(2)
        assertThat(results.last().single().maxConnections).isEqualTo(4)
        assertThat(results.last().single().catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `changed configuration row decodes once and removed rows are evicted`() = runTest {
        val fixture = stateFixture()
        val streams = fixture.streams(StandardTestDispatcher(testScheduler))
        streams.providers.first()
        advanceUntilIdle()
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(1)

        fixture.configs.value = listOf(
            fixture.config.copy(
                encryptedConfigJson = com.google.gson.Gson().toJson(
                    XtreamConfig(
                        serverUrl = "https://changed.example",
                        username = "changed-user",
                        password = "changed-secret"
                    )
                )
            )
        )
        streams.providers.first()
        advanceUntilIdle()
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(2)

        fixture.configs.value = emptyList()
        val fallback = streams.providers.first()
        advanceUntilIdle()
        assertThat(fallback.single().password).isEmpty()

        fixture.configs.value = listOf(fixture.config)
        streams.providers.first()
        advanceUntilIdle()
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(3)
    }

    @Test
    fun `first waits for real DAO emissions`() = runTest {
        val identities = MutableSharedFlow<List<ProviderEntity>>()
        val configs = MutableSharedFlow<List<ProviderConfigEntity>>()
        val runtimes = MutableSharedFlow<List<ProviderAccountRuntimeEntity>>()
        val portalStates = MutableSharedFlow<List<StalkerPortalStateEntity>>()
        val projection = CountingProjection()
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(configs, runtimes, portalStates),
            projection = projection,
            workDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = async(start = CoroutineStart.UNDISPATCHED) { streams.providers.first() }
        advanceUntilIdle()
        assertThat(result.isCompleted).isFalse()

        identities.emit(listOf(providerEntity()))
        configs.emit(listOf(configEntity()))
        runtimes.emit(emptyList())
        portalStates.emit(emptyList())
        advanceUntilIdle()

        assertThat(result.await()).hasSize(1)
    }

    @Test
    fun `failed decode reaches collector and is retried without caching failure`() = runTest {
        val fixture = stateFixture()
        fixture.projection.failure = IllegalStateException("decode failed")
        val streams = fixture.streams(StandardTestDispatcher(testScheduler))

        val firstFailure = try {
            streams.providers.first()
            null
        } catch (error: IllegalStateException) {
            error
        }
        assertThat(firstFailure).isNotNull()
        assertThat(firstFailure?.message).isEqualTo("decode failed")

        fixture.projection.failure = null
        assertThat(streams.providers.first()).hasSize(1)
        advanceUntilIdle()
        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(2)
    }

    @Test
    fun `cold provider flow completes when all DAO flows complete`() = runTest {
        val projection = CountingProjection()
        val streams = ProviderObservationStreams(
            providerDao = providerDao(flowOf(listOf(providerEntity()))),
            providerSnapshotDao = snapshotDao(
                configs = flowOf(listOf(configEntity())),
                runtimes = flowOf(emptyList()),
                portalStates = flowOf(emptyList())
            ),
            projection = projection,
            workDispatcher = StandardTestDispatcher(testScheduler)
        )

        val values = streams.providers.toList()

        assertThat(values).hasSize(1)
        assertThat(values.single()).hasSize(1)
    }

    private fun stateFixture(): Fixture {
        val identities = MutableStateFlow(listOf(providerEntity()))
        val configs = MutableStateFlow(listOf(configEntity()))
        val runtimes = MutableStateFlow(emptyList<ProviderAccountRuntimeEntity>())
        val portalStates = MutableStateFlow(emptyList<StalkerPortalStateEntity>())
        return Fixture(
            identities = identities,
            configs = configs,
            runtimes = runtimes,
            portalStates = portalStates,
            config = configs.value.single(),
            projection = CountingProjection()
        )
    }

    private inner class Fixture(
        val identities: MutableStateFlow<List<ProviderEntity>>,
        val configs: MutableStateFlow<List<ProviderConfigEntity>>,
        val runtimes: MutableStateFlow<List<ProviderAccountRuntimeEntity>>,
        val portalStates: MutableStateFlow<List<StalkerPortalStateEntity>>,
        val config: ProviderConfigEntity,
        val projection: CountingProjection
    ) {
        fun streams(workDispatcher: CoroutineDispatcher) = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(configs, runtimes, portalStates),
            projection = projection,
            workDispatcher = workDispatcher
        )
    }

    private class CountingProjection : ProviderPublicProjection {
        private val delegate = DefaultProviderPublicProjection(
            codec = ProviderConfigurationCodec(
                gson = com.google.gson.Gson(),
                credentialCrypto = object : com.streamvault.data.security.CredentialCrypto {
                    override fun encryptIfNeeded(value: String): String = value
                    override fun decryptIfNeeded(value: String): String = value
                }
            ),
            gson = com.google.gson.Gson()
        )
        val decodeCalls = AtomicInteger()
        var failure: RuntimeException? = null
        var decodeGate: DecodeGate? = null

        override fun decode(entity: ProviderConfigEntity): RedactedProviderConfigurationProjection {
            val call = decodeCalls.incrementAndGet()
            failure?.let { throw it }
            decodeGate?.takeIf { call == 1 }?.let { gate ->
                gate.started.countDown()
                check(gate.release.await(2, TimeUnit.SECONDS)) {
                    "timed out waiting for concurrent decode test"
                }
            }
            return delegate.decode(entity)
        }

        override fun assemble(
            identity: ProviderEntity,
            configuration: RedactedProviderConfigurationProjection?,
            runtime: ProviderAccountRuntimeEntity?,
            portalState: StalkerPortalStateEntity?
        ): LegacyProvider = delegate.assemble(identity, configuration, runtime, portalState)
    }

    private fun providerDao(flow: Flow<List<ProviderEntity>>): ProviderDao = mock<ProviderDao>().also {
        whenever(it.getAll()).thenReturn(flow)
    }

    private fun snapshotDao(
        configs: Flow<List<ProviderConfigEntity>>,
        runtimes: Flow<List<ProviderAccountRuntimeEntity>>,
        portalStates: Flow<List<StalkerPortalStateEntity>>
    ): ProviderSnapshotDao = mock<ProviderSnapshotDao>().also {
        whenever(it.observeConfigs()).thenReturn(configs)
        whenever(it.observeRuntimes()).thenReturn(runtimes)
        whenever(it.observeStalkerPortalStates()).thenReturn(portalStates)
    }

    private fun providerEntity() = ProviderEntity(
        id = 9L,
        name = "Living room",
        type = ProviderType.XTREAM_CODES,
        isActive = true
    )

    private fun configEntity() = ProviderConfigEntity(
        providerId = 9L,
        type = ProviderType.XTREAM_CODES,
        schemaVersion = 1,
        configurationGeneration = 7L,
        identityKey = "identity",
        encryptedConfigJson = com.google.gson.Gson().toJson(
            XtreamConfig(
                serverUrl = "https://example.test",
                username = "user",
                password = "secret"
            )
        ),
        updatedAt = 1L
    )

}
