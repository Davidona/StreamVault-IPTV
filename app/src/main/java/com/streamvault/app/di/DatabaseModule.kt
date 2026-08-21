package com.streamvault.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.streamvault.app.BuildConfig
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.StreamVaultDatabaseMigrationRegistry
import com.streamvault.data.local.dao.*
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.google.gson.Gson
import okhttp3.OkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    private const val DEBUG_SLOW_QUERY_THRESHOLD_MS = 100L

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StreamVaultDatabase =
        Room.databaseBuilder(
            context,
            StreamVaultDatabase::class.java,
            "streamvault.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(
                if (BuildConfig.DEBUG) {
                    SlowQueryLoggingOpenHelperFactory(
                        delegate = FrameworkSQLiteOpenHelperFactory(),
                        slowQueryThresholdMs = DEBUG_SLOW_QUERY_THRESHOLD_MS
                    )
                } else {
                    FrameworkSQLiteOpenHelperFactory()
                }
            )
            .addMigrations(*StreamVaultDatabaseMigrationRegistry.all.toTypedArray())
            // NOTE: fallbackToDestructiveMigration() intentionally removed.
            // All future schema changes MUST add a corresponding Migration in StreamVaultDatabase.
            .build()

    @Provides @Singleton
    fun provideJellyfinProvider(okHttpClient: OkHttpClient, gson: Gson): JellyfinProvider = JellyfinProvider(okHttpClient, gson)

    @Provides fun provideProviderDao(db: StreamVaultDatabase): ProviderDao = db.providerDao()
    @Provides fun provideProviderSnapshotDao(db: StreamVaultDatabase): ProviderSnapshotDao = db.providerSnapshotDao()
    @Provides fun provideChannelDao(db: StreamVaultDatabase): ChannelDao = db.channelDao()
    @Provides fun provideChannelPreferenceDao(db: StreamVaultDatabase): ChannelPreferenceDao = db.channelPreferenceDao()
    @Provides fun provideMovieDao(db: StreamVaultDatabase): MovieDao = db.movieDao()
    @Provides fun provideSeriesDao(db: StreamVaultDatabase): SeriesDao = db.seriesDao()
    @Provides fun provideEpisodeDao(db: StreamVaultDatabase): EpisodeDao = db.episodeDao()
    @Provides fun provideCategoryDao(db: StreamVaultDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideCatalogSyncDao(db: StreamVaultDatabase): CatalogSyncDao = db.catalogSyncDao()
    @Provides fun provideProgramDao(db: StreamVaultDatabase): ProgramDao = db.programDao()
    @Provides fun provideFavoriteDao(db: StreamVaultDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideVirtualGroupDao(db: StreamVaultDatabase): VirtualGroupDao = db.virtualGroupDao()
    @Provides fun providePlaybackHistoryDao(db: StreamVaultDatabase): PlaybackHistoryDao = db.playbackHistoryDao()
    @Provides fun provideTmdbIdentityDao(db: StreamVaultDatabase): TmdbIdentityDao = db.tmdbIdentityDao()
    @Provides fun provideSearchHistoryDao(db: StreamVaultDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideSearchDao(db: StreamVaultDatabase): SearchDao = db.searchDao()
    @Provides fun provideSyncMetadataDao(db: StreamVaultDatabase): SyncMetadataDao = db.syncMetadataDao()
    @Provides fun provideMovieCategoryHydrationDao(db: StreamVaultDatabase): MovieCategoryHydrationDao = db.movieCategoryHydrationDao()
    @Provides fun provideSeriesCategoryHydrationDao(db: StreamVaultDatabase): SeriesCategoryHydrationDao = db.seriesCategoryHydrationDao()
    @Provides fun provideVodCategoryHydrationDao(db: StreamVaultDatabase): VodCategoryHydrationDao = db.vodCategoryHydrationDao()
    @Provides fun provideVodCatalogEntryDao(db: StreamVaultDatabase): VodCatalogEntryDao = db.vodCatalogEntryDao()
    @Provides fun provideEpgSourceDao(db: StreamVaultDatabase): EpgSourceDao = db.epgSourceDao()
    @Provides fun provideProviderEpgSourceDao(db: StreamVaultDatabase): ProviderEpgSourceDao = db.providerEpgSourceDao()
    @Provides fun provideEpgChannelDao(db: StreamVaultDatabase): EpgChannelDao = db.epgChannelDao()
    @Provides fun provideEpgProgrammeDao(db: StreamVaultDatabase): EpgProgrammeDao = db.epgProgrammeDao()
    @Provides fun provideChannelEpgMappingDao(db: StreamVaultDatabase): ChannelEpgMappingDao = db.channelEpgMappingDao()
    @Provides fun provideCombinedM3uProfileDao(db: StreamVaultDatabase): CombinedM3uProfileDao = db.combinedM3uProfileDao()
    @Provides fun provideCombinedM3uProfileMemberDao(db: StreamVaultDatabase): CombinedM3uProfileMemberDao = db.combinedM3uProfileMemberDao()
    @Provides fun provideRecordingScheduleDao(db: StreamVaultDatabase): RecordingScheduleDao = db.recordingScheduleDao()
    @Provides fun provideRecordingRunDao(db: StreamVaultDatabase): RecordingRunDao = db.recordingRunDao()
    @Provides fun provideProgramReminderDao(db: StreamVaultDatabase): ProgramReminderDao = db.programReminderDao()
    @Provides fun provideRecordingStorageDao(db: StreamVaultDatabase): RecordingStorageDao = db.recordingStorageDao()
    @Provides fun providePlaybackCompatibilityDao(db: StreamVaultDatabase): PlaybackCompatibilityDao = db.playbackCompatibilityDao()
    @Provides fun provideXtreamContentIndexDao(db: StreamVaultDatabase): XtreamContentIndexDao = db.xtreamContentIndexDao()
    @Provides fun provideXtreamIndexJobDao(db: StreamVaultDatabase): XtreamIndexJobDao = db.xtreamIndexJobDao()
    @Provides fun provideXtreamLiveOnboardingDao(db: StreamVaultDatabase): XtreamLiveOnboardingDao = db.xtreamLiveOnboardingDao()
    @Provides fun provideStalkerIndexJobDao(db: StreamVaultDatabase): StalkerIndexJobDao = db.stalkerIndexJobDao()
    @Provides fun provideStalkerPortalStateDao(db: StreamVaultDatabase): StalkerPortalStateDao = db.stalkerPortalStateDao()
    @Provides fun provideStalkerRemoteIdentityDao(db: StreamVaultDatabase): StalkerRemoteIdentityDao = db.stalkerRemoteIdentityDao()
    @Provides fun provideStalkerDiscoveryStageDao(db: StreamVaultDatabase): StalkerDiscoveryStageDao = db.stalkerDiscoveryStageDao()
    @Provides fun provideDownloadDao(db: StreamVaultDatabase): DownloadDao = db.downloadDao()
    @Provides fun provideProviderDeletionCleanupDao(db: StreamVaultDatabase): ProviderDeletionCleanupDao = db.providerDeletionCleanupDao()
    @Provides fun provideProviderConfigRevisionDao(db: StreamVaultDatabase): ProviderConfigRevisionDao = db.providerConfigRevisionDao()
    @Provides fun provideBackupRestoreCheckpointDao(db: StreamVaultDatabase): BackupRestoreCheckpointDao = db.backupRestoreCheckpointDao()
    @Provides fun provideBackupRestoreLedgerDao(db: StreamVaultDatabase): BackupRestoreLedgerDao = db.backupRestoreLedgerDao()
    @Provides fun provideProviderWorkflowDao(db: StreamVaultDatabase): ProviderWorkflowDao = db.providerWorkflowDao()
    @Provides fun provideM3uClassificationDao(db: StreamVaultDatabase): M3uClassificationDao = db.m3uClassificationDao()
    @Provides fun providePluginProviderOwnershipDao(db: StreamVaultDatabase): PluginProviderOwnershipDao = db.pluginProviderOwnershipDao()
}
