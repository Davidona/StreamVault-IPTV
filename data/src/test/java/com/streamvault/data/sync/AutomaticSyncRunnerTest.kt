package com.streamvault.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.StalkerIndexJobEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AutomaticSyncRunnerTest {
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
    private val now = System.currentTimeMillis()
    private val fresh = SyncMetadata(7L, lastLiveSuccess = now, lastMovieSuccess = now, lastSeriesSuccess = now, lastEpgSuccess = now)

    @Before fun setup() = runTest {
        whenever(context.getSystemService(ConnectivityManager::class.java)).thenReturn(network)
        whenever(network.activeNetwork).thenReturn(mock<Network>())
        whenever(preferences.automaticSyncConfiguration).thenReturn(flowOf(AutomaticSyncConfiguration()))
        whenever(recordings.getByStatus(RecordingStatus.RECORDING)).thenReturn(emptyList())
        whenever(metadata.getMetadata(7L)).thenReturn(fresh)
        whenever(commands.refreshScheduledSection(any(), any())).thenReturn(Result.success(ProviderSectionSyncResult(catalogChanged = true)))
        whenever(commands.refreshScheduledPlaylist(any(), any(), any())).thenReturn(Result.success(ProviderSectionSyncResult(catalogChanged = true)))
    }

    private suspend fun snapshot(config: ProviderConfiguration, active: Boolean = false) {
        whenever(snapshots.getSnapshot(7L)).thenReturn(ProviderSnapshot(Provider(7L, "Provider", config.type, isActive = active), config, 1L))
    }

    @Test fun `inactive M3U is refreshed with one selected playlist import`() = runTest {
        snapshot(M3uConfig("https://example.test/list"))
        whenever(metadata.getMetadata(7L)).thenReturn(fresh.copy(lastLiveSuccess = 0, lastMovieSuccess = 0))
        assertThat(runner.check(7L)).isInstanceOf(Result.Success::class.java)
        verify(commands).refreshScheduledPlaylist(7L, true, true)
        verify(commands, never()).refreshScheduledSection(any(), any())
    }

    @Test fun `fresh Jellyfin performs no catalog fetch`() = runTest {
        snapshot(JellyfinConfig("https://example.test", "user", "token"))
        runner.check(7L)
        verify(commands, never()).refreshScheduledSection(any(), any())
        verify(preferences).recordAutomaticSyncStatus(eq(7L), argThat { outcome == AutomaticSyncOutcome.UP_TO_DATE })
    }

    @Test fun `Stalker freshness reads Stalker jobs rather than Xtream jobs`() = runTest {
        snapshot(StalkerConfig(portalUrl = "https://example.test", device = StalkerDeviceIdentity(macAddress = "00:11:22:33:44:55"), catalogMode = StalkerCatalogMode.BACKGROUND_INDEX))
        whenever(metadata.getMetadata(7L)).thenReturn(fresh.copy(lastMovieSuccess = 0, lastSeriesSuccess = 0))
        whenever(stalker.get(7L, ContentType.MOVIE)).thenReturn(StalkerIndexJobEntity(7L, ContentType.MOVIE, StalkerIndexState.COMPLETE, lastSuccessAt = now))
        whenever(stalker.get(7L, ContentType.SERIES)).thenReturn(StalkerIndexJobEntity(7L, ContentType.SERIES, StalkerIndexState.COMPLETE, lastSuccessAt = now))
        runner.check(7L)
        verify(commands, never()).refreshScheduledSection(any(), any())
        verify(xtream, never()).get(any(), any())
    }

    @Test fun `disabled automatic sync never reaches the network or engine`() = runTest {
        snapshot(M3uConfig("https://example.test/list"))
        whenever(preferences.automaticSyncConfiguration).thenReturn(flowOf(AutomaticSyncConfiguration(AutomaticSyncSettings(enabled = false))))
        runner.check(7L)
        verify(context, never()).getSystemService(ConnectivityManager::class.java)
        verify(commands, never()).refreshScheduledPlaylist(any(), any(), any())
        verify(preferences).recordAutomaticSyncStatus(eq(7L), argThat { outcome == AutomaticSyncOutcome.DISABLED })
    }

    @Test fun `playback defers all automatic provider requests`() = runTest {
        snapshot(M3uConfig("https://example.test/list"))
        whenever(metadata.getMetadata(7L)).thenReturn(null)
        val player = Any()
        try {
            AutomaticSyncActivity.started(player)
            runner.check(7L)
            verify(commands, never()).refreshScheduledPlaylist(any(), any(), any())
            verify(preferences).recordAutomaticSyncStatus(eq(7L), argThat { outcome == AutomaticSyncOutcome.WAITING_PLAYBACK })
        } finally { AutomaticSyncActivity.stopped(player) }
    }

    @Test fun `permanent Stalker failure is not reset by automatic checks`() = runTest {
        snapshot(StalkerConfig(portalUrl = "https://example.test", device = StalkerDeviceIdentity(macAddress = "00:11:22:33:44:55"), catalogMode = StalkerCatalogMode.BACKGROUND_INDEX))
        whenever(metadata.getMetadata(7L)).thenReturn(fresh.copy(lastMovieSuccess = 0))
        whenever(stalker.get(7L, ContentType.MOVIE)).thenReturn(StalkerIndexJobEntity(7L, ContentType.MOVIE, StalkerIndexState.FAILED))
        whenever(stalker.get(7L, ContentType.SERIES)).thenReturn(StalkerIndexJobEntity(7L, ContentType.SERIES, StalkerIndexState.COMPLETE, lastSuccessAt = now))
        runner.check(7L)
        verify(commands, never()).refreshScheduledSection(7L, SyncRepairSection.MOVIES)
        verify(preferences).recordAutomaticSyncStatus(eq(7L), argThat { outcome == AutomaticSyncOutcome.PARTIAL })
    }
}
