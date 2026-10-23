package com.streamvault.data.manager

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import java.io.File
import kotlinx.coroutines.flow.first
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
class ProviderlessBackupExportTest {
    private lateinit var database: StreamVaultDatabase
    private lateinit var preferences: PreferencesRepository
    private lateinit var manager: BackupManagerImpl
    private lateinit var output: File

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).allowMainThreadQueries().build()
        preferences = PreferencesRepository(context, database.channelPreferenceDao(), database.playbackQualityPreferenceDao(),
            database.searchHistoryDao(), mock())
        val recordings: RecordingManager = mock()
        whenever(recordings.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(recordings.observeStorageState()).thenReturn(flowOf(RecordingStorageState()))
        manager = BackupManagerImpl(
            context, preferences, mock(), database.providerDao(), database.favoriteDao(), database.virtualGroupDao(),
            database.playbackHistoryDao(), database.movieDao(), database.episodeDao(), mock(),
            database.recordingScheduleDao(), recordings,
            object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            }, Gson(), channelDao = database.channelDao(), seriesDao = database.seriesDao(),
            stremioAddonDao = database.stremioAddonDao()
        )
        output = File.createTempFile("providerless-export-", ".json", context.cacheDir)
    }

    @After
    fun tearDown() { database.close(); output.delete() }

    @Test
    fun `fresh installation exports without ever adding a provider`() = runBlocking {
        assertThat(database.providerDao().getAllSync()).isEmpty()
        assertThat(preferences.lastActiveProviderId.first()).isNull()
        assertExportSucceeds()
        Unit
    }

    @Test
    fun `deleting last provider leaves selections but no longer prevents full export`() = runBlocking {
        database.providerDao().insert(ProviderEntity(id = 18, name = "Deleted", type = ProviderType.XTREAM_CODES))
        preferences.setLastActiveProviderId(18)
        preferences.setGuideDefaultCategoryId(55)
        preferences.setDefaultCategory(55)
        preferences.setPromotedLiveGroupIds(setOf(24))
        preferences.setEpgTimeShiftMinutes(18, 60)
        preferences.setPreferredLiveVariant(18, "news", 900)
        preferences.setPreferredVodVariant(18, "movie", 901)
        preferences.setMultiViewPreset(0, listOf(900))
        preferences.setAppTheme(AppTheme.AMOLED_BLACK)
        database.providerDao().delete(18)
        // Deliberately retain the stale DataStore values that produced the reported error.
        assertThat(preferences.lastActiveProviderId.first()).isEqualTo(18)
        val backup = assertExportSucceeds()
        val portable = requireNotNull(backup.portableProviderPreferences)
        assertThat(portable.guideDefaultCategory).isNull()
        assertThat(portable.guideDefaultCategorySpecified).isFalse()
        assertThat(portable.homeDefaultCategorySpecified).isFalse()
        assertThat(portable.promotedLiveGroups).isEmpty()
        assertThat(portable.epgTimeShifts).isEmpty()
        assertThat(portable.liveVariantSelections).isEmpty()
        assertThat(portable.vodVariantSelections).isEmpty()
        assertThat(backup.preferences?.get("appTheme")).isEqualTo(AppTheme.AMOLED_BLACK.storageValue)
        assertThat(backup.activeLiveSource).isNull()
        assertThat(backup.portableMultiViewPresets?.values?.flatten()).isEmpty()
        // Export is read-only; it sanitizes the snapshot, not the user's preferences.
        assertThat(preferences.lastActiveProviderId.first()).isEqualTo(18)
    }

    @Test
    fun `providerless export preserves built in Guide categories and ignores deleted live sources`() = runBlocking {
        for (categoryId in listOf(-998L, -999L, -1_000_000L)) {
            preferences.setGuideDefaultCategoryId(categoryId)
            preferences.setDefaultCategory(categoryId)
            preferences.setActiveLiveSource(ActiveLiveSource.ProviderSource(18))
            val backup = assertExportSucceeds()
            assertThat(backup.portableProviderPreferences?.guideDefaultVirtualCategoryId).isEqualTo(categoryId)
            assertThat(backup.portableProviderPreferences?.homeDefaultVirtualCategoryId).isEqualTo(categoryId)
            assertThat(backup.activeLiveSource).isNull()
        }
        preferences.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(31))
        assertThat(assertExportSucceeds().activeLiveSource).isNull()
    }

    private suspend fun assertExportSucceeds(): BackupData {
        val result = manager.exportConfig(output.toURI().toString())
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(output.length()).isGreaterThan(0)
        assertThat(manager.inspectBackup(output.toURI().toString())).isInstanceOf(Result.Success::class.java)
        return Gson().fromJson(output.readText(), BackupData::class.java).also {
            assertThat(it.providerSnapshots).isEmpty()
            assertThat(it.portableProviderPreferences?.activeProvider).isNull()
            assertThat(it.portableProviderPreferences?.unresolvedReferences).isEmpty()
        }
    }

    @Test
    fun `each explicitly empty section remains a valid selective backup without providers`() = runBlocking {
        for (section in 0..6) {
            val plan = com.streamvault.domain.manager.BackupExportPlan(
                section == 0, section == 1, section == 2, section == 3, section == 4, section == 5, section == 6
            )
            assertThat(manager.exportConfig(output.toURI().toString(), plan)).isInstanceOf(Result.Success::class.java)
            val inspected = manager.inspectBackup(output.toURI().toString())
            assertThat(inspected).isInstanceOf(Result.Success::class.java)
            assertThat((inspected as Result.Success).data.availableSections).isEqualTo(plan)
        }
    }
}

