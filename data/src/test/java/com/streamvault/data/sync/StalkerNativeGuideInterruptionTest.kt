package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException
import java.util.Base64

/** Regression contract for native bulk guide bodies interrupted after streaming a prefix. */
class StalkerNativeGuideInterruptionTest {
    @Test
    fun `interrupted bulk prefix remains useful but does not make guide fresh or hide retry`() = runTest {
        val fixture = Fixture(bulkFailsAfterPrefix = true)
        fixture.setUp()

        val outcome = fixture.run()

        assertThat(outcome.hasRetryableFailure).isTrue()
        assertThat(outcome.warnings).isNotEmpty()
        assertThat(fixture.savedPrograms.count { it.channelId == "100" }).isEqualTo(6_000)
        assertThat(fixture.savedPrograms.any { it.channelId == "200" && it.title == "Old uncovered guide" }).isTrue()
        assertThat(fixture.savedPrograms.none { it.channelId == "100" && it.title == "Old covered guide" }).isTrue()
        verify(fixture.metadataRepository).updateMetadata(argThat {
            lastEpgSync == fixture.now && lastEpgSuccess == fixture.previousSuccess && epgCount == 6_001
        })
    }

    @Test
    fun `complete bulk guide remains healthy and advances freshness`() = runTest {
        val fixture = Fixture(bulkFailsAfterPrefix = false)
        fixture.setUp()

        val outcome = fixture.run()

        assertThat(outcome.hasRetryableFailure).isFalse()
        assertThat(outcome.warnings).isEmpty()
        assertThat(fixture.savedPrograms.count { it.channelId == "100" }).isEqualTo(6_000)
        assertThat(fixture.savedPrograms.any { it.channelId == "200" && it.title == "Old uncovered guide" }).isTrue()
        verify(fixture.metadataRepository).updateMetadata(argThat {
            lastEpgSync == fixture.now && lastEpgSuccess == fixture.now && epgCount == 6_001
        })
    }

    @Test
    fun `zero-prefix bulk failure can recover through complete per-channel fallback`() = runTest {
        val fixture = Fixture(bulkFailsAfterPrefix = false, bulkFailsBeforePrefix = true)
        fixture.setUp()

        val outcome = fixture.run()

        assertThat(outcome.hasRetryableFailure).isFalse()
        assertThat(outcome.warnings).isEmpty()
        assertThat(fixture.savedPrograms.count { it.channelId == "100" }).isEqualTo(2)
        assertThat(fixture.savedPrograms.count { it.channelId == "200" }).isEqualTo(2)
        assertThat(fixture.savedPrograms.any { it.title == "Old covered guide" || it.title == "Old uncovered guide" }).isFalse()
        verify(fixture.metadataRepository).updateMetadata(argThat {
            lastEpgSync == fixture.now && lastEpgSuccess == fixture.now && epgCount == 4
        })
    }

    @Test
    fun `transient guide failure preserves data and retries despite old unsupported capability`() = runTest {
        val fixture = Fixture(
            bulkFailsAfterPrefix = false,
            bulkFailsBeforePrefix = true,
            perChannelFailsInitially = true
        )
        fixture.setUp()

        val first = fixture.run()

        assertThat(first.hasRetryableFailure).isTrue()
        assertThat(fixture.savedPrograms.map { it.title })
            .containsExactly("Old covered guide", "Old uncovered guide")
        assertThat(fixture.portalState.getValidated(7L)?.epgSupported).isNull()
        val callsBeforeRestoration = fixture.bulkCallCount to fixture.channelCallCount
        assertThat(callsBeforeRestoration.first).isEqualTo(1)
        assertThat(callsBeforeRestoration.second).isEqualTo(2)

        fixture.restoreGuideBackend()
        // Simulate a bad observation persisted by an earlier app version.
        fixture.portalState.recordEpg(7L, false)
        val forcedRetry = fixture.run()

        assertThat(forcedRetry.hasRetryableFailure).isFalse()
        assertThat(fixture.bulkCallCount).isEqualTo(callsBeforeRestoration.first + 1)
        assertThat(fixture.channelCallCount).isEqualTo(callsBeforeRestoration.second)
        assertThat(fixture.savedPrograms.map { it.title })
            .containsExactly("Restored bulk guide", "Old uncovered guide")
        assertThat(fixture.portalState.getValidated(7L)?.epgSupported).isTrue()
    }

    private class Fixture(
        private val bulkFailsAfterPrefix: Boolean,
        private val bulkFailsBeforePrefix: Boolean = false,
        perChannelFailsInitially: Boolean = false
    ) {
        val now = 2_000L
        val previousSuccess = 1_000L
        val metadataRepository: SyncMetadataRepository = mock()
        val savedPrograms = mutableListOf(
            ProgramEntity(providerId = 7L, channelId = "100", title = "Old covered guide"),
            ProgramEntity(providerId = 7L, channelId = "200", title = "Old uncovered guide")
        )

        private val preferences: PreferencesRepository = mock()
        private val channelDao: ChannelDao = mock()
        private val programDao: ProgramDao = mock()
        private val epgSources: EpgSourceRepository = mock()
        private val portalStateDao = FakePortalStateDao()
        val portalState = StalkerPortalStateStore(portalStateDao, TestCredentialCrypto())
        private val stalkerProvider: StalkerProvider = mock()
        var backendRestored = false
            private set
        private var perChannelFails = perChannelFailsInitially
        var bulkCallCount = 0
            private set
        var channelCallCount = 0
            private set
        private val transaction = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
        private val executor = ProviderEpgSyncExecutor(
            preferencesRepository = preferences,
            epgRepository = mock<EpgRepository>(),
            epgSourceRepository = epgSources,
            channelDao = channelDao,
            programDao = programDao,
            transactionRunner = transaction,
            syncMetadataRepository = metadataRepository,
            stalkerPortalStateStore = portalState,
            stalkerRequestCoordinator = StalkerRequestCoordinator(null, false, { now }),
            createStalkerProvider = { stalkerProvider },
            xtreamSupport = SyncManagerXtreamSupport(
                adaptiveSyncPolicy = mock(),
                shouldRememberSequentialPreference = { false },
                sanitizeThrowableMessage = { it?.message.orEmpty() },
                progress = { _, _, _ -> },
                movieRequestTimeoutMillis = 1_000L,
                seriesRequestTimeoutMillis = 1_000L,
                recoveryAbortWarningSuffix = "fixture"
            ),
            progress = { _, _, _ -> },
            sanitizeThrowableMessage = { it?.message.orEmpty() }
        )

        suspend fun setUp() {
            whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
            whenever(epgSources.resolveForProvider(7L, emptySet())).thenReturn(EpgResolutionSummary())
            whenever(channelDao.getGuideSyncEntriesByProvider(7L)).thenReturn(
                listOf(
                    ChannelGuideSyncEntity(name = "Covered", streamId = 100L, epgChannelId = "100"),
                    ChannelGuideSyncEntity(name = "Uncovered", streamId = 200L, epgChannelId = "200")
                )
            )
            whenever(programDao.countByProvider(7L)).thenAnswer { savedPrograms.size }
            whenever(programDao.deleteForChannel(eq(7L), any())).thenAnswer { invocation ->
                val channelId = invocation.getArgument<String>(1)
                savedPrograms.removeAll { it.channelId == channelId }
                Unit
            }
            whenever(programDao.insertAll(any<List<ProgramEntity>>())).doSuspendableAnswer { invocation ->
                savedPrograms += invocation.getArgument<List<ProgramEntity>>(0)
            }
            whenever(metadataRepository.getMetadata(7L)).thenReturn(
                SyncMetadata(providerId = 7L, lastEpgSuccess = previousSuccess)
            )
            whenever(stalkerProvider.recordEpgCapability(any())).doSuspendableAnswer { invocation ->
                portalState.recordEpg(7L, invocation.getArgument(0), now = System.currentTimeMillis())
            }
            whenever(stalkerProvider.streamBulkEpg(eq(6), any())).doSuspendableAnswer { invocation ->
                bulkCallCount += 1
                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(1)
                if (backendRestored) {
                    onProgram(Program(channelId = "100", title = "Restored bulk guide", startTime = 0L, endTime = 60_000L))
                    Result.success(1)
                } else {
                    if (!bulkFailsBeforePrefix) {
                        repeat(6_000) { index ->
                            onProgram(
                                Program(
                                    channelId = "100",
                                    title = "Portal programme ${index + 1}",
                                    startTime = index * 60_000L,
                                    endTime = (index + 1) * 60_000L
                                )
                            )
                        }
                    }
                    if (bulkFailsAfterPrefix || bulkFailsBeforePrefix) {
                        Result.error("Interrupted native guide", IOException("fixture EOF"))
                    } else Result.success(6_000)
                }
            }
            whenever(stalkerProvider.streamEpg(any(), eq(6), any())).doSuspendableAnswer { invocation ->
                channelCallCount += 1
                val channelId = invocation.getArgument<String>(0)
                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(2)
                if (perChannelFails) {
                    return@doSuspendableAnswer Result.error("Temporary guide outage", IOException("fixture reset"))
                }
                repeat(2) { index ->
                    onProgram(
                        Program(
                            channelId = channelId,
                            title = "Fallback $channelId ${index + 1}",
                            startTime = index * 60_000L,
                            endTime = (index + 1) * 60_000L
                        )
                    )
                }
                Result.success(2)
            }
        }

        suspend fun run() = executor.syncStalkerProviderEpg(
            provider = LegacyProvider(
                id = 7L,
                name = "Native guide fixture",
                type = ProviderType.STALKER_PORTAL,
                serverUrl = "http://portal.test",
                stalkerMacAddress = "00:11:22:33:44:55",
                guideSourcePolicy = GuideSourcePolicy.PROVIDER_ONLY
            ),
            metadata = SyncMetadata(providerId = 7L, lastEpgSuccess = previousSuccess),
            now = now,
            force = true,
            onProgress = null
        )

        fun restoreGuideBackend() {
            backendRestored = true
            perChannelFails = false
        }
    }

    private class FakePortalStateDao : StalkerPortalStateDao {
        private val rows = mutableMapOf<Long, StalkerPortalStateEntity>()
        override suspend fun get(providerId: Long): StalkerPortalStateEntity? = rows[providerId]
        override suspend fun upsert(entity: StalkerPortalStateEntity) { rows[entity.providerId] = entity }
        override suspend fun invalidate(providerId: Long): Int = if (rows.remove(providerId) != null) 1 else 0
    }

    private class TestCredentialCrypto : CredentialCrypto {
        override fun encryptIfNeeded(value: String): String =
            "enc:test:" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        override fun decryptIfNeeded(value: String): String =
            String(Base64.getDecoder().decode(value.removePrefix("enc:test:")), Charsets.UTF_8)
    }
}
