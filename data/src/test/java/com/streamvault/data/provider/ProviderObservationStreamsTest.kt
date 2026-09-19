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
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.StalkerObservation
import com.streamvault.domain.model.StalkerObservationSource
import com.streamvault.domain.model.StalkerPortalLearning
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
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
import java.util.concurrent.ConcurrentHashMap
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
        val identities = MutableStateFlow(listOf(providerEntity()))
        val configs = MutableSharedFlow<List<ProviderConfigEntity>>()
        val runtimes = MutableStateFlow(emptyList<ProviderAccountRuntimeEntity>())
        val portalStates = MutableStateFlow(emptyList<StalkerPortalStateEntity>())
        val configSubscriptions = CountDownLatch(2)
        val configDeliveries = CountDownLatch(2)
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val projection = CountingProjection().apply {
            decodeGate = DecodeGate(decodeStarted, releaseDecode)
        }
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(
                configs = configs
                    .onSubscription { configSubscriptions.countDown() }
                    .onEach { configDeliveries.countDown() },
                runtimes = runtimes,
                portalStates = portalStates
            ),
            projection = projection,
            workDispatcher = Dispatchers.Default
        )

        val first = async(Dispatchers.Default) { streams.providers.first() }
        val second = async(Dispatchers.Default) { streams.providers.first() }
        assertThat(configSubscriptions.await(2, TimeUnit.SECONDS)).isTrue()

        val emitter = launch(Dispatchers.Default) {
            configs.emit(listOf(configEntity()))
        }
        assertThat(configDeliveries.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(decodeStarted.await(2, TimeUnit.SECONDS)).isTrue()

        assertThat(first.isCompleted).isFalse()
        assertThat(second.isCompleted).isFalse()
        assertThat(projection.decodeCalls.get()).isEqualTo(1)

        releaseDecode.countDown()
        emitter.join()
        assertThat(first.await()).hasSize(1)
        assertThat(second.await()).hasSize(1)
        assertThat(projection.decodeCalls.get()).isEqualTo(1)
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
    fun `Stalker learning-only changes assemble without another decode`() = runTest {
        val generation = 4L
        val identities = MutableStateFlow(
            listOf(providerEntity(id = 9L, isActive = true, type = ProviderType.STALKER_PORTAL))
        )
        val configs = MutableStateFlow(listOf(stalkerConfigEntity(generation)))
        val runtimes = MutableStateFlow(emptyList<ProviderAccountRuntimeEntity>())
        val portalStates = MutableStateFlow(emptyList<StalkerPortalStateEntity>())
        val projection = CountingProjection()
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(configs, runtimes, portalStates),
            projection = projection,
            workDispatcher = StandardTestDispatcher(testScheduler)
        )
        val results = mutableListOf<List<LegacyProvider>>()
        val collector = launch { streams.providers.take(2).toList(results) }
        advanceUntilIdle()

        portalStates.value = listOf(
            StalkerPortalStateEntity(
                providerId = 9L,
                configurationGeneration = generation,
                learningJson = com.google.gson.Gson().toJson(
                    StalkerPortalLearning(
                        configurationGeneration = generation,
                        profileId = StalkerObservation(
                            value = "learned-profile",
                            configurationGeneration = generation,
                            source = StalkerObservationSource.DISCOVERY,
                            observedAt = 10L
                        )
                    )
                )
            )
        )
        advanceUntilIdle()
        collector.join()

        assertThat(results).hasSize(2)
        assertThat(results.last().single().stalkerLearnedProfileId).isEqualTo("learned-profile")
        assertThat(projection.decodeCalls.get()).isEqualTo(1)
    }

    @Test
    fun `generation-only configuration change triggers one new decode`() = runTest {
        val fixture = stateFixture()
        val streams = fixture.streams(StandardTestDispatcher(testScheduler))

        streams.providers.first()
        fixture.configs.value = listOf(
            fixture.config.copy(configurationGeneration = fixture.config.configurationGeneration + 1)
        )
        streams.providers.first()

        assertThat(fixture.projection.decodeCalls.get()).isEqualTo(2)
    }

    @Test
    fun `provider order and active selection follow identity rows`() = runTest {
        val identities = MutableStateFlow(
            listOf(
                providerEntity(id = 10L, isActive = false),
                providerEntity(id = 9L, isActive = true)
            )
        )
        val configs = MutableStateFlow(listOf(configEntity(9L), configEntity(10L)))
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(
                configs,
                MutableStateFlow(emptyList()),
                MutableStateFlow(emptyList())
            ),
            projection = CountingProjection(),
            workDispatcher = StandardTestDispatcher(testScheduler)
        )

        assertThat(streams.providers.first().map(LegacyProvider::id))
            .containsExactly(10L, 9L)
            .inOrder()
        assertThat(streams.activeProvider.first()?.id).isEqualTo(9L)
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
    fun `removed rows are evicted even when another row fails to decode`() = runTest {
        val firstConfig = configEntity(providerId = 9L)
        val secondConfig = configEntity(providerId = 10L)
        val identities = MutableStateFlow(
            listOf(
                providerEntity(id = 9L, isActive = true),
                providerEntity(id = 10L, isActive = false)
            )
        )
        val configs = MutableStateFlow(listOf(firstConfig, secondConfig))
        val runtimes = MutableStateFlow(emptyList<ProviderAccountRuntimeEntity>())
        val portalStates = MutableStateFlow(emptyList<StalkerPortalStateEntity>())
        val projection = CountingProjection()
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(configs, runtimes, portalStates),
            projection = projection,
            workDispatcher = StandardTestDispatcher(testScheduler)
        )

        assertThat(streams.providers.first()).hasSize(2)
        assertThat(projection.decodeCallsFor(9L)).isEqualTo(1)

        val changedSecondConfig = secondConfig.copy(updatedAt = 2L)
        projection.failingProviderId = 10L
        configs.value = listOf(changedSecondConfig)
        val failure = runCatching { streams.providers.first() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)

        projection.failingProviderId = null
        configs.value = listOf(firstConfig, changedSecondConfig)
        assertThat(streams.providers.first()).hasSize(2)

        assertThat(projection.decodeCallsFor(9L)).isEqualTo(2)
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
    fun `failed decode reaches every concurrent collector without partial output`() = runTest {
        val identities = MutableStateFlow(listOf(providerEntity()))
        val configs = MutableSharedFlow<List<ProviderConfigEntity>>()
        val configSubscriptions = CountDownLatch(2)
        val configDeliveries = CountDownLatch(2)
        val projection = CountingProjection().apply {
            failure = IllegalStateException("decode failed")
        }
        val streams = ProviderObservationStreams(
            providerDao = providerDao(identities),
            providerSnapshotDao = snapshotDao(
                configs = configs
                    .onSubscription { configSubscriptions.countDown() }
                    .onEach { configDeliveries.countDown() },
                runtimes = MutableStateFlow(emptyList()),
                portalStates = MutableStateFlow(emptyList())
            ),
            projection = projection,
            workDispatcher = Dispatchers.Default
        )

        val first = async(Dispatchers.Default) { runCatching { streams.providers.first() } }
        val second = async(Dispatchers.Default) { runCatching { streams.providers.first() } }
        assertThat(configSubscriptions.await(2, TimeUnit.SECONDS)).isTrue()

        val emitter = launch(Dispatchers.Default) { configs.emit(listOf(configEntity())) }
        assertThat(configDeliveries.await(2, TimeUnit.SECONDS)).isTrue()
        emitter.join()

        assertThat(first.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(second.await().exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(projection.decodeCalls.get()).isEqualTo(2)
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
        private val decodeCallsByProvider = ConcurrentHashMap<Long, AtomicInteger>()
        var failure: RuntimeException? = null
        var failingProviderId: Long? = null
        var decodeGate: DecodeGate? = null

        override fun decode(entity: ProviderConfigEntity): RedactedProviderConfigurationProjection {
            val call = decodeCalls.incrementAndGet()
            decodeCallsByProvider
                .computeIfAbsent(entity.providerId) { AtomicInteger() }
                .incrementAndGet()
            failure?.let { throw it }
            if (failingProviderId == entity.providerId) {
                throw IllegalStateException("decode failed for ${entity.providerId}")
            }
            decodeGate?.takeIf { call == 1 }?.let { gate ->
                gate.started.countDown()
                check(gate.release.await(2, TimeUnit.SECONDS)) {
                    "timed out waiting for concurrent decode test"
                }
            }
            return delegate.decode(entity)
        }

        fun decodeCallsFor(providerId: Long): Int =
            decodeCallsByProvider[providerId]?.get() ?: 0

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

    private fun providerEntity(
        id: Long = 9L,
        isActive: Boolean = true,
        type: ProviderType = ProviderType.XTREAM_CODES
    ) = ProviderEntity(
        id = id,
        name = "Living room",
        type = type,
        isActive = isActive
    )

    private fun configEntity(providerId: Long = 9L) = ProviderConfigEntity(
        providerId = providerId,
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

    private fun stalkerConfigEntity(generation: Long) = ProviderConfigEntity(
        providerId = 9L,
        type = ProviderType.STALKER_PORTAL,
        schemaVersion = 1,
        configurationGeneration = generation,
        identityKey = "stalker-identity",
        encryptedConfigJson = com.google.gson.Gson().toJson(
            StalkerConfig(
                portalUrl = "https://portal.example.test",
                device = StalkerDeviceIdentity("00:11:22:33:44:55")
            )
        ),
        updatedAt = 1L
    )

}
