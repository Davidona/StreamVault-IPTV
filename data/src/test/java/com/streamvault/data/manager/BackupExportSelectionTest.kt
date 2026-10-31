package com.streamvault.data.manager

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StremioAddonEntity
import com.streamvault.data.local.entity.StremioPlaybackHistoryEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupExportSelectionTest {
    private lateinit var database: StreamVaultDatabase
    private lateinit var preferences: PreferencesRepository
    private lateinit var manager: BackupManagerImpl
    private lateinit var recordings: RecordingManager
    private lateinit var output: File

    private fun plan(mask: Int) = BackupExportPlan(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
        mask and 8 != 0, mask and 16 != 0, mask and 32 != 0, mask and 64 != 0)

    @Before
    fun setUp() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).allowMainThreadQueries().build()
        preferences = PreferencesRepository(context, database.channelPreferenceDao(), database.playbackQualityPreferenceDao(),
            database.searchHistoryDao(), mock())
        recordings = mock()
        whenever(recordings.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(recordings.observeStorageState()).thenReturn(flowOf(RecordingStorageState()))
        val provider = LegacyProvider(id = 18, name = "Source", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user", password = "provider-secret")
        val snapshots: ProviderSnapshotRepository = mock()
        whenever(snapshots.getSnapshot(18)).thenReturn(provider.toProviderSnapshot())
        database.providerDao().insert(ProviderEntity(id = 18, name = provider.name, type = provider.type))
        database.channelDao().insert(ChannelEntity(id = 900, streamId = 42, providerId = 18, name = "News",
            streamUrl = "https://example.com/news"))
        database.favoriteDao().insert(FavoriteEntity(providerId = 18, contentId = 900, contentType = ContentType.LIVE))
        database.playbackHistoryDao().insertOrUpdate(PlaybackHistoryEntity(providerId = 18, contentId = 900,
            contentType = ContentType.LIVE, title = "News", streamUrl = "https://example.com/news", resumePositionMs = 1234))
        database.stremioAddonDao().insert(StremioAddonEntity(installUrl = "https://addon.example/addon-secret/manifest.json",
            manifestId = "test", name = "Add-on", version = "1.0.0", manifestJson = "{}", enabled = true,
            sortOrder = 0, catalogSettingsJson = "{}", createdAt = 1, updatedAt = 1))
        database.stremioPlaybackHistoryDao().upsert(StremioPlaybackHistoryEntity(
            contentKey = "movie:tt123", mediaType = "movie", exactVideoId = "tt123", parentSeriesId = null,
            title = "Movie", posterUrl = null, resumePositionMs = 2000, totalDurationMs = 9000, isWatched = false,
            lastWatchedAt = 1, catalogOriginKey = "test", streamOriginKeyHint = null
        ))
        preferences.setLastActiveProviderId(18)
        preferences.setChannelHidden(18, 900, true)
        preferences.setMultiViewPreset(0, listOf(900))
        manager = BackupManagerImpl(
            context, preferences, mock(), database.providerDao(), database.favoriteDao(), database.virtualGroupDao(),
            database.playbackHistoryDao(), database.movieDao(), database.episodeDao(),
            mock<com.streamvault.domain.repository.CategoryRepository>().also {
                whenever(it.getCategories(18)).thenReturn(flowOf(emptyList()))
            }, database.recordingScheduleDao(), recordings,
            object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            }, Gson(), providerSnapshotRepository = snapshots, channelDao = database.channelDao(), seriesDao = database.seriesDao(),
            channelPreferenceDao = database.channelPreferenceDao(), stremioAddonDao = database.stremioAddonDao(),
            stremioPlaybackHistoryDao = database.stremioPlaybackHistoryDao()
        )
        output = File.createTempFile("selective-export-", ".json", context.cacheDir)
        Unit
    }

    @After
    fun tearDown() { database.close(); output.delete() }

    @Test
    fun `all 127 nonempty selections omit unselected fields and inspect with matching import choices`() = runBlocking {
        for (mask in 1..127) {
            val selection = plan(mask)
            val result = manager.exportConfig(output.toURI().toString(), selection)
            assertWithMessage("selection mask=%s", mask).that(result).isInstanceOf(Result.Success::class.java)
            val json = JsonParser.parseString(output.readText()).asJsonObject
            val fields = mapOf(
                "preferences" to selection.exportPreferences,
                "portableProviderPreferences" to selection.exportPreferences,
                "providerSnapshots" to selection.exportProviders,
                "providerCredentials" to selection.exportProviders,
                "portableFavorites" to selection.exportSavedLibrary,
                "portableCustomGroups" to selection.exportSavedLibrary,
                "portableProtectedContent" to selection.exportSavedLibrary,
                "portableHiddenContent" to selection.exportSavedLibrary,
                "portableManualEpgMappings" to selection.exportSavedLibrary,
                "portablePlaybackHistory" to selection.exportPlaybackHistory,
                "stremioPlaybackHistory" to selection.exportPlaybackHistory,
                "portableMultiViewPresetsV14" to selection.exportMultiViewPresets,
                "scheduledRecordings" to selection.exportRecordingSchedules,
                "recordingStorage" to selection.exportRecordingSchedules,
                "stremioAddons" to selection.exportStremioAddons
            )
            fields.forEach { (field, selected) ->
                assertWithMessage("selection mask=%s field=%s", mask, field).that(json.has(field) && !json[field].isJsonNull).isEqualTo(selected)
            }
            assertThat(output.readText().contains("provider-secret")).isEqualTo(selection.exportProviders)
            assertThat(output.readText().contains("addon-secret")).isEqualTo(selection.exportStremioAddons)
            if (selection.exportPreferences) {
                assertThat(json["preferences"].asJsonObject.has("hiddenChannels_18")).isEqualTo(selection.exportSavedLibrary)
            }
            val inspected = ImportBackup(manager).inspect(InspectBackupCommand(output.toURI().toString()))
            assertWithMessage("inspect mask=%s", mask).that(inspected).isInstanceOf(InspectBackupResult.Success::class.java)
            val success = inspected as InspectBackupResult.Success
            assertWithMessage("availability mask=%s", mask).that(success.preview.availableSections).isEqualTo(selection)
            assertThat(success.defaultPlan.importProviders).isEqualTo(selection.exportProviders)
            assertThat(success.defaultPlan.importSavedLibrary).isEqualTo(selection.exportSavedLibrary)
            assertThat(success.defaultPlan.importStremioAddons).isEqualTo(selection.exportStremioAddons)
        }
    }

    @Test
    fun `unselected corrupt preferences and split screen do not prevent providers only export`() = runBlocking {
        preferences.setGuideDefaultCategoryId(9999)
        preferences.setMultiViewPreset(0, listOf(9999))
        val selection = plan(2)
        assertThat(manager.exportConfig(output.toURI().toString(), selection)).isInstanceOf(Result.Success::class.java)
        assertThat(manager.exportConfig(output.toURI().toString(), plan(1))).isInstanceOf(Result.Error::class.java)
        assertThat(manager.exportConfig(output.toURI().toString(), plan(16))).isInstanceOf(Result.Error::class.java)
    }

    @Test
    fun `stremio only export does not require a valid provider snapshot`() = runBlocking {
        database.providerDao().insert(ProviderEntity(id = 99, name = "Incomplete", type = ProviderType.XTREAM_CODES))
        assertThat(manager.exportConfig(output.toURI().toString(), plan(64))).isInstanceOf(Result.Success::class.java)
        assertThat(manager.exportConfig(output.toURI().toString(), plan(2))).isInstanceOf(Result.Error::class.java)
    }

    @Test
    fun `empty export selection is rejected before creating a backup`() = runBlocking {
        output.delete()
        var rejected = false
        try { manager.exportConfig(output.toURI().toString(), plan(0)) } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun `unchecked recording schedules also leave recording storage settings unchanged on import`() = runBlocking {
        output.writeText(Gson().toJson(com.streamvault.domain.manager.BackupData(
            recordingStorage = com.streamvault.domain.manager.RecordingStorageBackup("{title}", retentionDays = 30)
        )))
        val result = manager.importConfig(output.toURI().toString(), com.streamvault.domain.manager.BackupImportPlan(
            importPreferences = false, importProviders = false, importSavedLibrary = false, importPlaybackHistory = false,
            importMultiViewPresets = false, importRecordingSchedules = false, importStremioAddons = false
        ))
        assertThat(result).isInstanceOf(Result.Success::class.java)
        org.mockito.kotlin.verify(recordings, org.mockito.kotlin.never()).updateStorageConfig(org.mockito.kotlin.any())
        Unit
    }
}
