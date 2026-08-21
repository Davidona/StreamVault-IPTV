
package com.streamvault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

internal const val STREAM_VAULT_DATABASE_VERSION = 77

@Database(
    entities = [
        ProviderEntity::class,
        ProviderConfigEntity::class,
        ProviderAccountRuntimeEntity::class,
        ChannelEntity::class,
        ChannelPreferenceEntity::class,
        ChannelFtsEntity::class,
        MovieEntity::class,
        MovieFtsEntity::class,
        SeriesEntity::class,
        SeriesFtsEntity::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        ChannelImportStageEntity::class,
        MovieImportStageEntity::class,
        SeriesImportStageEntity::class,
        CategoryImportStageEntity::class,
        ProgramEntity::class,
        FavoriteEntity::class,
        VirtualGroupEntity::class,
        PlaybackHistoryEntity::class,
        TmdbIdentityEntity::class,
        SearchHistoryEntity::class,
        SyncMetadataEntity::class,
        MovieCategoryHydrationEntity::class,
        SeriesCategoryHydrationEntity::class,
        VodCategoryHydrationEntity::class,
        VodCatalogEntryEntity::class,
        EpgSourceEntity::class,
        ProviderEpgSourceEntity::class,
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        ChannelEpgMappingEntity::class,
        CombinedM3uProfileEntity::class,
        CombinedM3uProfileMemberEntity::class,
        RecordingScheduleEntity::class,
        RecordingRunEntity::class,
        ProgramReminderEntity::class,
        RecordingStorageEntity::class,
        PlaybackCompatibilityRecordEntity::class,
        XtreamContentIndexEntity::class,
        XtreamIndexJobEntity::class,
        XtreamLiveOnboardingStateEntity::class,
        StalkerIndexJobEntity::class,
        StalkerPortalStateEntity::class,
        StalkerRemoteIdentityEntity::class,
        StalkerDiscoveryStageEntity::class,
        DownloadEntity::class,
        ProviderDeletionCleanupEntity::class,
        PluginProviderOwnershipEntity::class,
        ProviderConfigRevisionEntity::class,
        BackupRestoreCheckpointEntity::class,
        BackupRestoreJobEntity::class,
        BackupRestoreItemEntity::class,
        ProviderWorkflowEntity::class,
        ProviderWorkflowPhaseEntity::class,
        M3uClassificationOverrideEntity::class,
        M3uCategoryClassificationRuleEntity::class
    ],
    version = STREAM_VAULT_DATABASE_VERSION,
    exportSchema = true   // ← was false; schema JSON now tracked in version control
)
@TypeConverters(RoomEnumConverters::class)
abstract class StreamVaultDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun providerSnapshotDao(): ProviderSnapshotDao
    abstract fun channelDao(): ChannelDao
    abstract fun channelPreferenceDao(): ChannelPreferenceDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun catalogSyncDao(): CatalogSyncDao
    abstract fun programDao(): ProgramDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun virtualGroupDao(): VirtualGroupDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun tmdbIdentityDao(): TmdbIdentityDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun searchDao(): SearchDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun movieCategoryHydrationDao(): MovieCategoryHydrationDao
    abstract fun seriesCategoryHydrationDao(): SeriesCategoryHydrationDao
    abstract fun vodCategoryHydrationDao(): VodCategoryHydrationDao
    abstract fun vodCatalogEntryDao(): VodCatalogEntryDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun providerEpgSourceDao(): ProviderEpgSourceDao
    abstract fun epgChannelDao(): EpgChannelDao
    abstract fun epgProgrammeDao(): EpgProgrammeDao
    abstract fun channelEpgMappingDao(): ChannelEpgMappingDao
    abstract fun combinedM3uProfileDao(): CombinedM3uProfileDao
    abstract fun combinedM3uProfileMemberDao(): CombinedM3uProfileMemberDao
    abstract fun recordingScheduleDao(): RecordingScheduleDao
    abstract fun recordingRunDao(): RecordingRunDao
    abstract fun programReminderDao(): ProgramReminderDao
    abstract fun recordingStorageDao(): RecordingStorageDao
    abstract fun playbackCompatibilityDao(): PlaybackCompatibilityDao
    abstract fun xtreamContentIndexDao(): XtreamContentIndexDao
    abstract fun xtreamIndexJobDao(): XtreamIndexJobDao
    abstract fun xtreamLiveOnboardingDao(): XtreamLiveOnboardingDao
    abstract fun stalkerIndexJobDao(): StalkerIndexJobDao
    abstract fun stalkerPortalStateDao(): StalkerPortalStateDao
    abstract fun stalkerRemoteIdentityDao(): StalkerRemoteIdentityDao
    abstract fun stalkerDiscoveryStageDao(): StalkerDiscoveryStageDao
    abstract fun downloadDao(): DownloadDao
    abstract fun providerDeletionCleanupDao(): ProviderDeletionCleanupDao
    abstract fun pluginProviderOwnershipDao(): PluginProviderOwnershipDao
    abstract fun providerConfigRevisionDao(): ProviderConfigRevisionDao
    abstract fun backupRestoreCheckpointDao(): BackupRestoreCheckpointDao
    abstract fun backupRestoreLedgerDao(): BackupRestoreLedgerDao
    abstract fun providerWorkflowDao(): ProviderWorkflowDao
    abstract fun m3uClassificationDao(): M3uClassificationDao

    companion object {
        val MIGRATION_1_2 = LegacyMigrationsV1To24.MIGRATION_1_2
        val MIGRATION_2_3 = LegacyMigrationsV1To24.MIGRATION_2_3
        val MIGRATION_3_4 = LegacyMigrationsV1To24.MIGRATION_3_4
        val MIGRATION_4_5 = LegacyMigrationsV1To24.MIGRATION_4_5
        val MIGRATION_5_6 = LegacyMigrationsV1To24.MIGRATION_5_6
        val MIGRATION_6_7 = LegacyMigrationsV1To24.MIGRATION_6_7
        val MIGRATION_7_8 = LegacyMigrationsV1To24.MIGRATION_7_8
        val MIGRATION_8_9 = LegacyMigrationsV1To24.MIGRATION_8_9
        val MIGRATION_9_10 = LegacyMigrationsV1To24.MIGRATION_9_10
        val MIGRATION_10_11 = LegacyMigrationsV1To24.MIGRATION_10_11
        val MIGRATION_11_12 = LegacyMigrationsV1To24.MIGRATION_11_12
        val MIGRATION_12_13 = LegacyMigrationsV1To24.MIGRATION_12_13
        val MIGRATION_13_14 = LegacyMigrationsV1To24.MIGRATION_13_14
        val MIGRATION_14_15 = LegacyMigrationsV1To24.MIGRATION_14_15
        val MIGRATION_15_16 = LegacyMigrationsV1To24.MIGRATION_15_16
        val MIGRATION_16_17 = LegacyMigrationsV1To24.MIGRATION_16_17
        val MIGRATION_17_18 = LegacyMigrationsV1To24.MIGRATION_17_18
        val MIGRATION_18_19 = LegacyMigrationsV1To24.MIGRATION_18_19
        val MIGRATION_19_20 = LegacyMigrationsV1To24.MIGRATION_19_20
        val MIGRATION_20_21 = LegacyMigrationsV1To24.MIGRATION_20_21
        val MIGRATION_21_22 = LegacyMigrationsV1To24.MIGRATION_21_22
        val MIGRATION_22_23 = LegacyMigrationsV1To24.MIGRATION_22_23
        val MIGRATION_23_24 = LegacyMigrationsV1To24.MIGRATION_23_24
        val MIGRATION_24_25 = LegacyMigrationsV24To49.MIGRATION_24_25
        val MIGRATION_25_26 = LegacyMigrationsV24To49.MIGRATION_25_26
        val MIGRATION_26_27 = LegacyMigrationsV24To49.MIGRATION_26_27
        val MIGRATION_27_28 = LegacyMigrationsV24To49.MIGRATION_27_28
        val MIGRATION_28_29 = LegacyMigrationsV24To49.MIGRATION_28_29
        val MIGRATION_29_30 = LegacyMigrationsV24To49.MIGRATION_29_30
        val MIGRATION_30_31 = LegacyMigrationsV24To49.MIGRATION_30_31
        val MIGRATION_31_32 = LegacyMigrationsV24To49.MIGRATION_31_32
        val MIGRATION_32_33 = LegacyMigrationsV24To49.MIGRATION_32_33
        val MIGRATION_33_34 = LegacyMigrationsV24To49.MIGRATION_33_34
        val MIGRATION_34_35 = LegacyMigrationsV24To49.MIGRATION_34_35
        val MIGRATION_35_36 = LegacyMigrationsV24To49.MIGRATION_35_36
        val MIGRATION_36_37 = LegacyMigrationsV24To49.MIGRATION_36_37
        val MIGRATION_37_38 = LegacyMigrationsV24To49.MIGRATION_37_38
        val MIGRATION_38_39 = LegacyMigrationsV24To49.MIGRATION_38_39
        val MIGRATION_39_40 = LegacyMigrationsV24To49.MIGRATION_39_40
        val MIGRATION_40_41 = LegacyMigrationsV24To49.MIGRATION_40_41
        val MIGRATION_41_42 = LegacyMigrationsV24To49.MIGRATION_41_42
        val MIGRATION_42_43 = LegacyMigrationsV24To49.MIGRATION_42_43
        val MIGRATION_43_44 = LegacyMigrationsV24To49.MIGRATION_43_44
        val MIGRATION_44_45 = LegacyMigrationsV24To49.MIGRATION_44_45
        val MIGRATION_45_46 = LegacyMigrationsV24To49.MIGRATION_45_46
        val MIGRATION_46_47 = LegacyMigrationsV24To49.MIGRATION_46_47
        val MIGRATION_47_48 = LegacyMigrationsV24To49.MIGRATION_47_48
        val MIGRATION_48_49 = LegacyMigrationsV24To49.MIGRATION_48_49
        val MIGRATION_49_50 = FeatureMigrationsV49To75.MIGRATION_49_50
        val MIGRATION_50_51 = FeatureMigrationsV49To75.MIGRATION_50_51
        val MIGRATION_51_52 = FeatureMigrationsV49To75.MIGRATION_51_52
        val MIGRATION_52_53 = FeatureMigrationsV49To75.MIGRATION_52_53
        val MIGRATION_53_54 = FeatureMigrationsV49To75.MIGRATION_53_54
        val MIGRATION_54_55 = FeatureMigrationsV49To75.MIGRATION_54_55
        val MIGRATION_55_56 = FeatureMigrationsV49To75.MIGRATION_55_56
        val MIGRATION_56_57 = FeatureMigrationsV49To75.MIGRATION_56_57
        val MIGRATION_57_58 = FeatureMigrationsV49To75.MIGRATION_57_58
        val MIGRATION_58_59 = FeatureMigrationsV49To75.MIGRATION_58_59
        val MIGRATION_59_60 = FeatureMigrationsV49To75.MIGRATION_59_60
        val MIGRATION_60_61 = FeatureMigrationsV49To75.MIGRATION_60_61
        val MIGRATION_61_62 = FeatureMigrationsV49To75.MIGRATION_61_62
        val MIGRATION_62_63 = FeatureMigrationsV49To75.MIGRATION_62_63
        val MIGRATION_63_64 = FeatureMigrationsV49To75.MIGRATION_63_64
        val MIGRATION_64_65 = FeatureMigrationsV49To75.MIGRATION_64_65
        val MIGRATION_65_66 = FeatureMigrationsV49To75.MIGRATION_65_66
        val MIGRATION_66_67 = FeatureMigrationsV49To75.MIGRATION_66_67
        val MIGRATION_67_68 = FeatureMigrationsV49To75.MIGRATION_67_68
        val MIGRATION_68_69 = FeatureMigrationsV49To75.MIGRATION_68_69
        val MIGRATION_69_70 = FeatureMigrationsV49To75.MIGRATION_69_70
        val MIGRATION_70_71 = FeatureMigrationsV49To75.MIGRATION_70_71
        val MIGRATION_71_72 = FeatureMigrationsV49To75.MIGRATION_71_72
        val MIGRATION_72_73 = FeatureMigrationsV49To75.MIGRATION_72_73
        val MIGRATION_73_74 = FeatureMigrationsV49To75.MIGRATION_73_74
        val MIGRATION_74_75 = FeatureMigrationsV49To75.MIGRATION_74_75
        val MIGRATION_75_76 = FeatureMigrationsV75To76.MIGRATION_75_76
        val MIGRATION_76_77 = FeatureMigrationsV76To77.MIGRATION_76_77
    }
}
