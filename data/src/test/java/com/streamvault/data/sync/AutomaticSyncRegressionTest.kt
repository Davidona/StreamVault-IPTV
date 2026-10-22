package com.streamvault.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/** Regression coverage for automatic section admission, completion, and guide freshness. */
class AutomaticSyncRegressionTest {
    private val context: Context = mock()
    private val network: ConnectivityManager = mock()
    private val preferences: PreferencesRepository = mock()
    private val snapshots: ProviderSnapshotRepository = mock()
    private val metadata: SyncMetadataRepository = mock()
    private val commands: ProviderSyncCommands = mock()
    private val xtream: XtreamIndexJobDao = mock()
    private val stalker: StalkerIndexJobStore = mock()
    private val recordings: RecordingRunDao = mock()
    private val runner = AutomaticSyncRunner(context, preferences, snapshots, metadata, commands, xtream, stalker, recordings)

    @Before fun setup() = runTest {
        whenever(context.getSystemService(ConnectivityManager::class.java)).thenReturn(network)
        whenever(network.activeNetwork).thenReturn(mock<Network>())
        whenever(preferences.automaticSyncConfiguration).thenReturn(flowOf(AutomaticSyncConfiguration()))
        whenever(preferences.automaticSyncStatuses).thenReturn(flowOf(emptyMap()))
        whenever(recordings.getByStatus(RecordingStatus.RECORDING)).thenReturn(emptyList())
    }

    @Test fun `Live-only automatic policy admits its Live search-index continuation`() = runTest {
        whenever(preferences.automaticSyncConfiguration).thenReturn(flowOf(AutomaticSyncConfiguration(
            AutomaticSyncSettings(live = true, movies = false, series = false, epg = false)
        )))
        assertThat(runner.indexEnabled(7L, ContentType.LIVE)).isTrue()
    }

    @Test fun `disabled Live section rejects its automatic index continuation`() = runTest {
        whenever(preferences.automaticSyncConfiguration).thenReturn(flowOf(AutomaticSyncConfiguration(
            AutomaticSyncSettings(live = false, movies = true, series = false, epg = false)
        )))
        assertThat(runner.indexEnabled(7L, ContentType.LIVE)).isFalse()
    }

    @Test fun `automatic completion status accounts for queued Live backfill`() = runTest {
        whenever(snapshots.getSnapshot(7L)).thenReturn(ProviderSnapshot(
            Provider(7L, "Xtream", ProviderType.XTREAM_CODES),
            XtreamConfig("https://example.test", "user", "password"), 1L
        ))
        whenever(xtream.get(7L, ContentType.LIVE.name)).thenReturn(
            XtreamIndexJobEntity(providerId = 7L, section = "LIVE", state = "QUEUED")
        )
        runner.reportIndexProgress(7L, failed = false)
        verify(preferences).recordAutomaticSyncStatus(eq(7L), argThat {
            outcome == AutomaticSyncOutcome.QUEUED
        })
    }

    @Test fun `successful external-only guide refresh advances EPG freshness`() = runTest {
        val sources: EpgSourceRepository = mock()
        val programs: com.streamvault.data.local.dao.ProgramDao = mock()
        whenever(programs.countByProvider(7L)).thenReturn(0)
        whenever(preferences.getHiddenCategoryIds(7L, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(sources.refreshAllForProvider(7L)).thenReturn(Result.success(Unit))
        whenever(sources.resolveForProvider(7L, emptySet()))
            .thenReturn(com.streamvault.domain.model.EpgResolutionSummary())
        val executor = ProviderEpgSyncExecutor(
            preferencesRepository = preferences,
            epgRepository = mock(),
            epgSourceRepository = sources,
            channelDao = mock(),
            programDao = programs,
            transactionRunner = object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            },
            syncMetadataRepository = metadata,
            stalkerPortalStateStore = mock(),
            stalkerRequestCoordinator = mock(),
            createStalkerProvider = { error("Unexpected Stalker client") },
            xtreamSupport = SyncManagerXtreamSupport(
                adaptiveSyncPolicy = mock(),
                shouldRememberSequentialPreference = { false },
                sanitizeThrowableMessage = { it?.message.orEmpty() },
                progress = { _, _, _ -> },
                movieRequestTimeoutMillis = 1000L,
                seriesRequestTimeoutMillis = 1000L,
                recoveryAbortWarningSuffix = "Review probe"
            ),
            progress = { _, _, _ -> },
            sanitizeThrowableMessage = { it?.message.orEmpty() }
        )
        val provider = LegacyProvider(
            id = 7L, name = "Playlist", type = ProviderType.M3U,
            serverUrl = "https://example.test/list.m3u",
            guideSourcePolicy = GuideSourcePolicy.EXTERNAL_ONLY
        )
        val result = executor.syncM3uProviderEpg(provider, SyncMetadata(7L), 1000L, true, null)
        assertThat(result.warnings).isEmpty()
        verify(sources).refreshAllForProvider(7L)
        verify(metadata).updateMetadata(argThat { lastEpgSuccess > 0L })
    }
}
