package com.streamvault.domain.manager

import com.streamvault.domain.model.Provider as StableProvider

import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingRecurrence
import kotlinx.coroutines.flow.Flow
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.JellyfinConfig

data class BackupData(
    val version: Int = 13,
    val checksum: String? = null,
    val preferences: Map<String, String>? = null,
    val providers: List<Provider>? = null,
    /** v11 authoritative provider payload; v0-10 continue to use [providers]. */
    val providerSnapshots: List<ProviderBackupSnapshot>? = null,
    /** Credentials paired with provider snapshots so local restores can sync immediately. */
    val providerCredentials: List<ProviderCredentials>? = null,
    val favorites: List<com.streamvault.domain.model.Favorite>? = null,
    val virtualGroups: List<com.streamvault.domain.model.VirtualGroup>? = null,
    val playbackHistory: List<PlaybackHistory>? = null,
    val multiViewPresets: Map<String, List<Long>>? = null,
    val portableMultiViewPresets: Map<String, List<PortableChannelReference>>? = null,
    val protectedCategories: List<ProtectedCategoryBackup>? = null,
    val scheduledRecordings: List<ScheduledRecordingBackup>? = null,
    val portableProviderPreferences: PortableProviderPreferencesBackup? = null,
    val recordingStorage: RecordingStorageBackup? = null,
    val epgSources: List<com.streamvault.domain.model.EpgSource>? = null,
    val providerEpgAssignments: List<ProviderEpgAssignmentBackup>? = null,
    val manualEpgMappings: List<ManualEpgMappingBackup>? = null,
    val m3uClassificationOverrides: List<M3uClassificationOverrideBackup>? = null,
    val m3uClassificationRules: List<M3uClassificationRuleBackup>? = null,
    val programReminders: List<ProgramReminderBackup>? = null,
    /** User-created combined M3U sources, represented by portable provider identities. */
    val combinedM3uProfiles: List<CombinedM3uProfileBackup>? = null,
    /** The selected live source, represented without local provider/profile IDs. */
    val activeLiveSource: ActiveLiveSourceBackup? = null,
    /** v14 user-owned catalog state. These records never contain local Room identifiers. */
    val portableFavorites: List<PortableFavoriteBackup>? = null,
    val portableCustomGroups: List<PortableCustomGroupBackup>? = null,
    val portablePlaybackHistory: List<PortablePlaybackHistoryBackup>? = null,
    val portableProtectedContent: List<PortableProtectedContentBackup>? = null,
    val portableSearchHistory: List<PortableSearchHistoryBackup>? = null,
    val portableHiddenContent: List<PortableHiddenContentBackup>? = null,
    val portableContentPreferences: List<PortableContentPreferenceBackup>? = null,
    val portableVariantChoices: List<PortableVariantChoiceBackup>? = null,
    val portableManualEpgMappings: List<PortableManualEpgMappingV14Backup>? = null,
    val portableMultiViewPresetsV14: List<PortableMultiViewPresetV14Backup>? = null
)

data class ProviderBackupSnapshot(
    val provider: StableProvider,
    /** Runtime observations are portable and restore without becoming configuration state. */
    val accountRuntime: com.streamvault.domain.model.ProviderAccountRuntime? = null,
    val xtreamConfig: XtreamConfig? = null,
    val m3uConfig: M3uConfig? = null,
    val stalkerConfig: StalkerConfig? = null,
    val jellyfinConfig: JellyfinConfig? = null
) {
    fun configuration() = listOfNotNull(xtreamConfig, m3uConfig, stalkerConfig, jellyfinConfig)
        .singleOrNull()
        ?: throw IllegalArgumentException("Provider backup must contain exactly one typed configuration")
}

/**
 * Provider-scoped preference values expressed without local Room/DataStore identifiers.
 * Older backups omit this field and retain the legacy preference map for compatibility.
 */
data class PortableProviderPreferencesBackup(
    val providers: List<BackupProviderReference> = emptyList(),
    val activeProvider: BackupProviderReference? = null,
    val guideDefaultCategory: PortableCategoryReference? = null,
    val guideDefaultVirtualCategoryId: Long? = null,
    val guideDefaultCategorySpecified: Boolean = false,
    val promotedLiveGroups: List<PortableVirtualGroupReference> = emptyList(),
    val hiddenChannels: List<PortableChannelReference> = emptyList(),
    val hiddenCategories: List<PortableCategoryReference> = emptyList(),
    /** Categories explicitly pinned by the user, resolved by provider identity on import. */
    val pinnedCategories: List<PortableCategoryReference> = emptyList(),
    val pinnedCategoriesSpecified: Boolean = false,
    /** Per-provider category ordering choices, resolved by provider identity on import. */
    val categorySortModes: List<PortableCategorySortReference> = emptyList(),
    val categorySortModesSpecified: Boolean = false,
    /** Per-provider EPG time corrections, resolved by provider identity on import. */
    val epgTimeShifts: List<PortableEpgTimeShiftReference> = emptyList(),
    val epgTimeShiftsSpecified: Boolean = false,
    /** Explicit live alternate-stream selections made by the user. */
    val liveVariantSelections: List<PortableVariantSelectionReference> = emptyList(),
    val liveVariantSelectionsSpecified: Boolean = false,
    /** Explicit movie/series alternate-stream selections made by the user. */
    val vodVariantSelections: List<PortableVariantSelectionReference> = emptyList(),
    val vodVariantSelectionsSpecified: Boolean = false,
    val unresolvedReferences: List<String> = emptyList(),
    /** Per-channel playback choices, resolved through portable channel identity on import. */
    val channelPreferences: List<PortableChannelPreferenceReference> = emptyList(),
    val channelPreferencesSpecified: Boolean = false,
    /** The user's Home start category, resolved by provider/category identity on import. */
    val homeDefaultCategory: PortableCategoryReference? = null,
    val homeDefaultVirtualCategoryId: Long? = null,
    val homeDefaultCategorySpecified: Boolean = false
)

data class BackupProviderReference(
    val serverUrl: String,
    val username: String,
    val stalkerMacAddress: String? = null,
    /** Added in v13; null keeps old backups importable. */
    val providerType: ProviderType? = null
)

data class PortableCategoryReference(
    val provider: BackupProviderReference,
    val name: String,
    val type: ContentType,
    val remoteCategoryId: Long? = null
)

data class PortableVirtualGroupReference(
    val provider: BackupProviderReference,
    val name: String,
    val contentType: ContentType
)

data class PortableChannelReference(
    val provider: BackupProviderReference,
    val streamId: Long,
    val name: String,
    val streamUrl: String,
    /** Remote category identity used to hydrate hidden categories before deferred restore. */
    val remoteCategoryId: Long? = null
)

data class PortableChannelPreferenceReference(
    val channel: PortableChannelReference,
    val aspectRatio: String? = null,
    val audioVideoOffsetMs: Int? = null
)

data class PortableCategorySortReference(
    val provider: BackupProviderReference,
    val type: ContentType,
    val mode: String
)

data class PortableEpgTimeShiftReference(
    val provider: BackupProviderReference,
    val minutes: Int
)

data class PortableVariantSelectionReference(
    val provider: BackupProviderReference,
    val logicalGroupId: String,
    /** Legacy local catalog id. Kept for importing backups written before portable ids existed. */
    val rawItemId: Long,
    /** Provider/catalog identity used by current backups; never a local Room id. */
    val remoteItemId: String? = null,
    val contentType: ContentType? = null,
    val remoteCategoryId: Long? = null
)

/**
 * Stable identity for provider-owned catalog content in v14 backups.
 *
 * [remoteContentId] is the provider's identity, never a Room primary key. Parent and category
 * identities are included where providers only guarantee identity inside that scope. Name and URL
 * are matching fallbacks and may only be used when they identify exactly one local row.
 */
data class PortableContentReference(
    val provider: BackupProviderReference,
    val contentType: ContentType,
    val remoteContentId: String,
    val parentRemoteContentId: String? = null,
    val remoteCategoryId: String? = null,
    val name: String? = null,
    val urlFallback: String? = null
)

data class PortableFavoriteBackup(
    val content: PortableContentReference,
    val position: Int = 0,
    val addedAt: Long = 0L
)

data class PortableCustomGroupBackup(
    val provider: BackupProviderReference,
    val contentType: ContentType,
    val name: String,
    val icon: String? = null,
    val position: Int = 0,
    val createdAt: Long = 0L,
    val members: List<PortableFavoriteBackup> = emptyList()
)

data class PortablePlaybackHistoryBackup(
    val content: PortableContentReference,
    val resumePositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val lastWatchedAt: Long = 0L,
    val watchCount: Int = 1,
    val watchedStatus: String = "IN_PROGRESS",
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
)

data class PortableProtectedContentBackup(
    val content: PortableContentReference
)

data class PortableHiddenContentBackup(
    val content: PortableContentReference
)

data class PortableContentPreferenceBackup(
    val content: PortableContentReference,
    val aspectRatio: String? = null,
    val audioVideoOffsetMs: Int? = null
)

data class PortableVariantChoiceBackup(
    val logicalGroupId: String,
    val selectedContent: PortableContentReference
)

data class PortableManualEpgMappingV14Backup(
    val content: PortableContentReference,
    val sourceUrl: String? = null,
    val xmltvChannelId: String? = null,
    val sourceType: String = "NONE",
    val matchType: String? = null,
    val confidence: Float = 0f,
    val source: String? = null
)

data class PortableMultiViewPresetV14Backup(
    val name: String,
    val channels: List<PortableContentReference>
)

data class PortableSearchHistoryBackup(
    val query: String,
    val contentScope: String,
    /** Null means the search was global rather than provider-scoped. */
    val provider: BackupProviderReference? = null,
    val usedAt: Long,
    val useCount: Int = 1
)

data class CombinedM3uProfileBackup(
    val name: String,
    val enabled: Boolean = true,
    val members: List<CombinedM3uProfileMemberBackup> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class CombinedM3uProfileMemberBackup(
    val provider: BackupProviderReference,
    val priority: Int,
    val enabled: Boolean = true
)

data class ActiveLiveSourceBackup(
    val type: String,
    val provider: BackupProviderReference? = null,
    val combinedProfileName: String? = null,
    /** Ordered provider identities disambiguate duplicate combined-profile names. */
    val combinedProfileProviders: List<BackupProviderReference> = emptyList()
)

data class ProtectedCategoryBackup(
    val providerServerUrl: String,
    val providerUsername: String,
    val providerStalkerMacAddress: String? = null,
    val categoryId: Long,
    val categoryName: String,
    val type: ContentType,
    val providerType: ProviderType? = null
)

data class ScheduledRecordingBackup(
    val providerServerUrl: String,
    val providerUsername: String,
    val providerStalkerMacAddress: String? = null,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val requestedStartMs: Long? = null,
    val requestedEndMs: Long? = null,
    val paddingBeforeMs: Long? = null,
    val paddingAfterMs: Long? = null,
    val programTitle: String? = null,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    val recurringRuleId: String? = null,
    val providerType: ProviderType? = null,
    /** v14 portable channel identity; [channelId] remains only for v0-v13 compatibility. */
    val channel: PortableContentReference? = null
)

data class RecordingStorageBackup(
    val fileNamePattern: String,
    val retentionDays: Int? = null,
    val maxSimultaneousRecordings: Int = 2
)

data class ProviderEpgAssignmentBackup(
    val provider: BackupProviderReference,
    val sourceUrl: String,
    val priority: Int = 0,
    val enabled: Boolean = true
)

data class ManualEpgMappingBackup(
    val channel: PortableChannelReference,
    val sourceUrl: String? = null,
    val xmltvChannelId: String? = null,
    val sourceType: String = "NONE",
    val matchType: String? = null,
    val confidence: Float = 0f,
    val source: String? = null
)

data class M3uClassificationOverrideBackup(
    val provider: BackupProviderReference,
    val sourceKey: String,
    val streamId: Long,
    val targetType: String,
    val groupKey: String = "",
    val seriesKey: String? = null,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null
)

data class M3uClassificationRuleBackup(
    val provider: BackupProviderReference,
    val groupKey: String,
    val targetType: String
)

data class ProgramReminderBackup(
    val provider: BackupProviderReference,
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val programStartTime: Long,
    val leadTimeMinutes: Int = 5
)

enum class BackupConflictStrategy {
    KEEP_EXISTING,
    REPLACE_EXISTING
}

data class BackupPreview(
    val version: Int,
    val providerCount: Int,
    val favoriteCount: Int,
    val groupCount: Int,
    val playbackHistoryCount: Int,
    val multiViewPresetCount: Int,
    val preferenceCount: Int,
    val protectedCategoryCount: Int,
    val scheduledRecordingCount: Int,
    val providerConflicts: Int,
    val favoriteConflicts: Int,
    val groupConflicts: Int,
    val historyConflicts: Int,
    val protectedCategoryConflicts: Int,
    val recordingConflicts: Int
)

data class BackupImportPlan(
    val importPreferences: Boolean = true,
    val importProviders: Boolean = true,
    val importSavedLibrary: Boolean = true,
    val importPlaybackHistory: Boolean = true,
    val importMultiViewPresets: Boolean = true,
    val importRecordingSchedules: Boolean = true,
    val conflictStrategy: BackupConflictStrategy = BackupConflictStrategy.KEEP_EXISTING
)

enum class RecordingScheduleImportDisposition {
    IMPORTED,
    REPLACED_EXISTING,
    SKIPPED_EXISTING,
    SKIPPED_EXPIRED,
    SKIPPED_MISSING_PROVIDER,
    FAILED
}

data class RecordingScheduleImportOutcome(
    val channelName: String,
    val programTitle: String? = null,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    val disposition: RecordingScheduleImportDisposition,
    val reason: String? = null
)

data class RecordingScheduleImportSummary(
    val outcomes: List<RecordingScheduleImportOutcome> = emptyList()
) {
    val importedCount: Int
        get() = outcomes.count {
            it.disposition == RecordingScheduleImportDisposition.IMPORTED ||
                it.disposition == RecordingScheduleImportDisposition.REPLACED_EXISTING
        }

    val skippedCount: Int
        get() = outcomes.count {
            it.disposition == RecordingScheduleImportDisposition.SKIPPED_EXISTING ||
                it.disposition == RecordingScheduleImportDisposition.SKIPPED_EXPIRED ||
                it.disposition == RecordingScheduleImportDisposition.SKIPPED_MISSING_PROVIDER
        }

    val failedCount: Int
        get() = outcomes.count { it.disposition == RecordingScheduleImportDisposition.FAILED }
}

/**
 * The durable outcome of a restore operation. A successful [Result] means the outcome below is
 * authoritative; callers must not present a partial restore as a total failure.
 */
enum class BackupRestoreOutcome {
    COMPLETE,
    WAITING_FOR_SYNC,
    PARTIAL,
    FAILED_BEFORE_COMMIT
}

data class BackupImportResult(
    val outcome: BackupRestoreOutcome = BackupRestoreOutcome.COMPLETE,
    val importedSections: List<String> = emptyList(),
    val skippedSections: List<String> = emptyList(),
    val failedSections: List<String> = emptyList(),
    val unresolvedReferences: List<String> = emptyList(),
    val recordingScheduleImport: RecordingScheduleImportSummary? = null,
    val restoreJobId: String? = null,
    val pendingCount: Int = 0,
    val unresolvedCount: Int = 0,
    val affectedProviders: List<BackupProviderReference> = emptyList()
)

interface BackupManager {
    /**
     * Exports the configuration to the provided URI string (SAF document URI)
     */
    suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit>

    /**
     * Reads a backup and returns a preview with conflict counts before importing.
     */
    suspend fun inspectBackup(uriString: String): Result<BackupPreview>

    /**
     * Imports the configuration from the provided URI string (SAF document URI)
     */
    suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan = BackupImportPlan()
    ): Result<BackupImportResult>
}

data class BackupRestoreProviderStatus(
    val providerIdentityKey: String,
    val localProviderId: Long? = null,
    val appliedCount: Int = 0,
    val pendingCount: Int = 0,
    val unresolvedCount: Int = 0,
    val failedCount: Int = 0,
    val items: List<BackupRestoreItemStatus> = emptyList()
)

data class BackupRestoreItemStatus(
    val id: Long,
    val section: String,
    val contentType: String? = null,
    val status: String,
    val attempts: Int,
    val lastError: String? = null
)

data class BackupRestoreJobStatus(
    val jobId: String,
    val backupVersion: Int,
    val status: String,
    val providers: List<BackupRestoreProviderStatus>,
    val updatedAt: Long
)

interface BackupRestoreStatusStore {
    fun observeRestoreJobs(): Flow<List<BackupRestoreJobStatus>>
    suspend fun retryProviders(providerIds: Set<Long>)
    suspend fun dismissItem(itemId: Long)
    suspend fun dismissProvider(jobId: String, providerIdentityKey: String)
    suspend fun dismissRestore(jobId: String)
}
