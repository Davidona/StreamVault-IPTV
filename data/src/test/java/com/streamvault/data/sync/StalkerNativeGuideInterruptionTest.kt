package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.preferences.PreferencesRepository
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

    private class Fixture(
        private val bulkFailsAfterPrefix: Boolean,
        private val bulkFailsBeforePrefix: Boolean = false
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
        private val portalState: StalkerPortalStateStore = mock()
        private val stalkerProvider: StalkerProvider = mock()
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
            whenever(portalState.getValidated(7L)).thenReturn(null)
            whenever(stalkerProvider.recordEpgCapability(any())).thenReturn(Unit)
            whenever(stalkerProvider.streamBulkEpg(eq(6), any())).doSuspendableAnswer { invocation ->
                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(1)
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
                }
                else Result.success(6_000)
            }
            whenever(stalkerProvider.streamEpg(any(), eq(6), any())).doSuspendableAnswer { invocation ->
                val channelId = invocation.getArgument<String>(0)
                val onProgram = invocation.getArgument<suspend (Program) -> Unit>(2)
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
    }
}
