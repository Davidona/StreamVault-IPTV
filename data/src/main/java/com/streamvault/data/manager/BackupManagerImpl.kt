package com.streamvault.data.manager

import com.streamvault.domain.model.Provider as StableProvider

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.google.gson.stream.MalformedJsonException
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.BackupRestoreCheckpointDao
import com.streamvault.data.local.dao.BackupRestoreLedgerDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.M3uClassificationDao
import com.streamvault.data.local.dao.CombinedM3uProfileDao
import com.streamvault.data.local.dao.CombinedM3uProfileMemberDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.BackupRestoreCheckpointEntity
import com.streamvault.data.local.entity.BackupRestoreItemEntity
import com.streamvault.data.local.entity.BackupRestoreJobEntity
import com.streamvault.data.local.entity.ChannelPreferenceEntity
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.local.entity.M3uClassificationOverrideEntity
import com.streamvault.data.local.entity.M3uCategoryClassificationRuleEntity
import com.streamvault.data.local.entity.CombinedM3uProfileEntity
import com.streamvault.data.local.entity.CombinedM3uProfileMemberEntity
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.ParentalPinBackupData
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.provider.toTypedConfiguration
import com.streamvault.data.provider.toLegacyProvider
import com.streamvault.data.provider.ProviderConfigurationCodec
import com.streamvault.data.provider.guidePolicy
import com.streamvault.data.provider.logoPolicy
import com.streamvault.data.provider.toAccountRuntime
import com.streamvault.data.remote.stalker.StalkerCompatibilityRegistry
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.ActiveLiveSourceBackup
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.RecordingScheduleImportDisposition
import com.streamvault.domain.manager.RecordingScheduleImportOutcome
import com.streamvault.domain.manager.RecordingScheduleImportSummary
import com.streamvault.domain.manager.RecordingStorageBackup
import com.streamvault.domain.manager.ProviderEpgAssignmentBackup
import com.streamvault.domain.manager.ManualEpgMappingBackup
import com.streamvault.domain.manager.M3uClassificationOverrideBackup
import com.streamvault.domain.manager.M3uClassificationRuleBackup
import com.streamvault.domain.manager.ProgramReminderBackup
import com.streamvault.domain.manager.ProgramReminderManager
import com.streamvault.domain.model.Program
import com.streamvault.domain.manager.ProtectedCategoryBackup
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.manager.ProviderBackupSnapshot
import com.streamvault.domain.manager.CombinedM3uProfileBackup
import com.streamvault.domain.manager.CombinedM3uProfileMemberBackup
import com.streamvault.domain.manager.PortableCategoryReference
import com.streamvault.domain.manager.PortableCategorySortReference
import com.streamvault.domain.manager.PortableChannelReference
import com.streamvault.domain.manager.PortableFavoriteBackup
import com.streamvault.domain.manager.PortableCustomGroupBackup
import com.streamvault.domain.manager.PortablePlaybackHistoryBackup
import com.streamvault.domain.manager.PortableProtectedContentBackup
import com.streamvault.domain.manager.PortableHiddenContentBackup
import com.streamvault.domain.manager.PortableContentPreferenceBackup
import com.streamvault.domain.manager.PortableContentReference
import com.streamvault.domain.manager.PortableVariantChoiceBackup
import com.streamvault.domain.manager.PortableManualEpgMappingV14Backup
import com.streamvault.domain.manager.PortableMultiViewPresetV14Backup
import com.streamvault.domain.manager.PortableSearchHistoryBackup
import com.streamvault.domain.manager.PortableChannelPreferenceReference
import com.streamvault.domain.manager.PortableEpgTimeShiftReference
import com.streamvault.domain.manager.PortableProviderPreferencesBackup
import com.streamvault.domain.manager.PortableVariantSelectionReference
import com.streamvault.domain.manager.PortableVirtualGroupReference
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ProviderAccountRuntime
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.repository.CategoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.FilterReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.lang.reflect.Type
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val credentialCrypto: CredentialCrypto,
    private val providerDao: ProviderDao,
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val movieDao: MovieDao,
    private val episodeDao: EpisodeDao,
    private val categoryRepository: CategoryRepository,
    private val recordingScheduleDao: RecordingScheduleDao,
    private val recordingManager: RecordingManager,
    private val transactionRunner: DatabaseTransactionRunner,
    private val gson: Gson,
    private val providerSnapshotRepository: ProviderSnapshotRepository? = null,
    private val providerSnapshotDao: ProviderSnapshotDao? = null,
    private val providerConfigurationCodec: ProviderConfigurationCodec? = null,
    private val backupRestoreCheckpointDao: BackupRestoreCheckpointDao? = null,
    private val backupRestoreLedgerDao: BackupRestoreLedgerDao? = null,
    private val channelDao: ChannelDao,
    private val seriesDao: SeriesDao,
    private val categoryDao: CategoryDao? = null,
    private val epgSourceDao: EpgSourceDao? = null,
    private val combinedM3uProfileDao: CombinedM3uProfileDao? = null,
    private val combinedM3uProfileMemberDao: CombinedM3uProfileMemberDao? = null,
    private val channelPreferenceDao: ChannelPreferenceDao? = null,
    private val providerEpgSourceDao: ProviderEpgSourceDao? = null,
    private val channelEpgMappingDao: ChannelEpgMappingDao? = null,
    private val m3uClassificationDao: M3uClassificationDao? = null,
    private val programReminderManager: ProgramReminderManager? = null,
    private val searchHistoryDao: SearchHistoryDao? = null,
    private val pendingBackupRestoreCoordinator: PendingBackupRestoreCoordinator? = null
) : BackupManager {

    override suspend fun exportConfig(uriString: String): com.streamvault.domain.model.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val parentalPinBackup = preferencesRepository.exportParentalPinBackup()
            val providerEntities = providerDao.getAll().first()
            
            // 1. Gather Data
            val prefs = buildMap<String, String> {
                put("parentalControlLevel", preferencesRepository.parentalControlLevel.first().toString())
                put("parentalPinHash", parentalPinBackup?.hash ?: "")
                put("parentalPinSalt", parentalPinBackup?.saltBase64 ?: "")
                put("appLanguage", preferencesRepository.appLanguage.first())
                put("appTimeFormat", preferencesRepository.appTimeFormat.first().storageValue)
                put("defaultViewMode", preferencesRepository.defaultViewMode.first().orEmpty())
                put("appLandingDestination", preferencesRepository.appLandingDestination.first().storageValue)
                put(
                    "appTopLevelDestinations",
                    preferencesRepository.appTopLevelDestinations.first().joinToString(",") { it.storageValue }
                )
                put(
                    "appHomeDashboardShelves",
                    preferencesRepository.appHomeDashboardShelves.first().joinToString(",") { it.storageValue }
                )
                put("remoteShortcutPreferences", gson.toJson(preferencesRepository.remoteShortcutPreferences.first()))
                put("liveTvCategoryFilters", preferencesRepository.liveTvCategoryFilters.first().joinToString("\n"))
                put("liveTvQuickFilterVisibility", preferencesRepository.liveTvQuickFilterVisibility.first() ?: "always")
                put("liveTvChannelMode", preferencesRepository.liveTvChannelMode.first().orEmpty())
                put("showLiveSourceSwitcher", preferencesRepository.showLiveSourceSwitcher.first().toString())
                put("showFavoritesCategory", preferencesRepository.showFavoritesCategory.first().toString())
                put("showAllChannelsCategory", preferencesRepository.showAllChannelsCategory.first().toString())
                put("showRecentChannelsCategory", preferencesRepository.showRecentChannelsCategory.first().toString())
                put("hideDecorativeLiveRows", preferencesRepository.hideDecorativeLiveRows.first().toString())
                put("liveChannelNumberingMode", preferencesRepository.liveChannelNumberingMode.first().name)
                put("liveChannelGroupingMode", preferencesRepository.liveChannelGroupingMode.first().name)
                put("groupedChannelLabelMode", preferencesRepository.groupedChannelLabelMode.first().name)
                put("liveVariantPreferenceMode", preferencesRepository.liveVariantPreferenceMode.first().name)
                put("vodViewMode", preferencesRepository.vodViewMode.first().orEmpty())
                put("vodInfiniteScroll", preferencesRepository.vodInfiniteScroll.first().toString())
                put("vodCategoryLoadMode", preferencesRepository.vodCategoryLoadMode.first().storageValue)
                put("vodDuplicateHandlingMode", preferencesRepository.vodDuplicateHandlingMode.first().storageValue)
                put("vodVariantPreferenceMode", preferencesRepository.vodVariantPreferenceMode.first().storageValue)
                put("playerMediaSessionEnabled", preferencesRepository.playerMediaSessionEnabled.first().toString())
                put("playerFastRetryOnTransientFailures", preferencesRepository.playerFastRetryOnTransientFailures.first().toString())
                put("playerAudioDecoderMode", preferencesRepository.playerAudioDecoderMode.first().name)
                put("playerVideoDecoderMode", preferencesRepository.playerVideoDecoderMode.first().name)
                put("playerPlaybackBufferMode", preferencesRepository.playerPlaybackBufferMode.first().name)
                put("playerAudioOutputPreference", preferencesRepository.playerAudioOutputPreference.first().name)
                put("playerCompatibilityMemoryEnabled", preferencesRepository.playerCompatibilityMemoryEnabled.first().toString())
                put("playerSurfaceMode", preferencesRepository.playerSurfaceMode.first().name)
                put("playerLiveStreamFormatMode", preferencesRepository.playerLiveStreamFormatMode.first().name)
                put("playerVodHttpProtocolMode", preferencesRepository.playerVodHttpProtocolMode.first().name)
                put("playerPlaybackSpeed", preferencesRepository.playerPlaybackSpeed.first().toString())
                put("playerExternalPlaybackMode", preferencesRepository.playerExternalPlaybackMode.first().storageValue)
                put("playerAudioVideoSyncEnabled", preferencesRepository.playerAudioVideoSyncEnabled.first().toString())
                put("playerAudioVideoOffsetMs", preferencesRepository.playerAudioVideoOffsetMs.first().toString())
                put("playerMuted", preferencesRepository.playerMuted.first().toString())
                put("multiViewPerformanceMode", preferencesRepository.multiViewPerformanceMode.first().orEmpty())
                put("multiViewCenterTwoSlotLayout", preferencesRepository.multiViewCenterTwoSlotLayout.first().toString())
                put("multiViewRespectProviderConnectionLimit", preferencesRepository.multiViewRespectProviderConnectionLimit.first().toString())
                put("preferredAudioLanguage", preferencesRepository.preferredAudioLanguage.first() ?: "auto")
                put("playerSubtitleTextScale", preferencesRepository.playerSubtitleTextScale.first().toString())
                put("playerSubtitleTextColor", preferencesRepository.playerSubtitleTextColor.first().toString())
                put("playerSubtitleBackgroundColor", preferencesRepository.playerSubtitleBackgroundColor.first().toString())
                put("playerLiveTranslationEnabled", preferencesRepository.playerLiveTranslationEnabled.first().toString())
                put("playerLiveTranslationEndpoint", preferencesRepository.playerLiveTranslationEndpoint.first())
                put("playerControlsTimeoutSeconds", preferencesRepository.playerControlsTimeoutSeconds.first().toString())
                put("playerLiveOverlayTimeoutSeconds", preferencesRepository.playerLiveOverlayTimeoutSeconds.first().toString())
                put("playerNoticeTimeoutSeconds", preferencesRepository.playerNoticeTimeoutSeconds.first().toString())
                put("playerDiagnosticsTimeoutSeconds", preferencesRepository.playerDiagnosticsTimeoutSeconds.first().toString())
                put("playerWifiMaxVideoHeight", (preferencesRepository.playerWifiMaxVideoHeight.first() ?: 0).toString())
                put("playerEthernetMaxVideoHeight", (preferencesRepository.playerEthernetMaxVideoHeight.first() ?: 0).toString())
                put("playerTimeshiftEnabled", preferencesRepository.playerTimeshiftEnabled.first().toString())
                put("playerTimeshiftDepthMinutes", preferencesRepository.playerTimeshiftDepthMinutes.first().toString())
                put("playerTimeshiftBackend", preferencesRepository.playerTimeshiftBackend.first().name)
                put("defaultStopPlaybackTimerMinutes", preferencesRepository.defaultStopPlaybackTimerMinutes.first().toString())
                put("defaultIdleStandbyTimerMinutes", preferencesRepository.defaultIdleStandbyTimerMinutes.first().toString())
                put("preventStandbyDuringPlayback", preferencesRepository.preventStandbyDuringPlayback.first().toString())
                put("zapAutoRevert", preferencesRepository.zapAutoRevert.first().toString())
                put("autoPlayNextEpisode", preferencesRepository.autoPlayNextEpisode.first().toString())
                put("autoCheckAppUpdates", preferencesRepository.autoCheckAppUpdates.first().toString())
                put("autoDownloadAppUpdates", preferencesRepository.autoDownloadAppUpdates.first().toString())
                put("recordingWifiOnly", preferencesRepository.recordingWifiOnly.first().toString())
                put("recordingPaddingBeforeMinutes", preferencesRepository.recordingPaddingBeforeMinutes.first().toString())
                put("recordingPaddingAfterMinutes", preferencesRepository.recordingPaddingAfterMinutes.first().toString())
                put("maxConcurrentStreams", preferencesRepository.maxConcurrentStreams.first().toString())
                put("isIncognitoMode", preferencesRepository.isIncognitoMode.first().toString())
                put("useXtreamTextClassification", preferencesRepository.useXtreamTextClassification.first().toString())
                put("xtreamBase64TextCompatibility", preferencesRepository.xtreamBase64TextCompatibility.first().toString())
                put("guideDensity", preferencesRepository.guideDensity.first() ?: "")
                put("guideChannelMode", preferencesRepository.guideChannelMode.first() ?: "")
                put("guideDefaultCategoryId", (preferencesRepository.guideDefaultCategoryId.first() ?: 0L).toString())
                put("guideFavoritesOnly", preferencesRepository.guideFavoritesOnly.first().toString())
                put("guideScheduledOnly", preferencesRepository.guideScheduledOnly.first().toString())
                put("guideAnchorTime", (preferencesRepository.guideAnchorTime.first() ?: 0L).toString())
                put("lastActiveProviderId", (preferencesRepository.lastActiveProviderId.first() ?: -1L).toString())
                put("promotedLiveGroupIds", preferencesRepository.promotedLiveGroupIds.first().sorted().joinToString(","))
                // D13 — hidden channels + hidden categories per provider (per ContentType for cats)
                providerEntities.forEach { provider ->
                    val hiddenChan = preferencesRepository.getHiddenChannelIds(provider.id).first()
                    if (hiddenChan.isNotEmpty()) {
                        put("hiddenChannels_${provider.id}", hiddenChan.sorted().joinToString(","))
                    }
                    ContentType.entries.forEach { type ->
                        val hiddenCat = preferencesRepository.getHiddenCategoryIds(provider.id, type).first()
                        if (hiddenCat.isNotEmpty()) {
                            put("hiddenCategories_${provider.id}_${type.name}", hiddenCat.sorted().joinToString(","))
                        }
                    }
                }
            }

            val sourceProviders = providerEntities.mapNotNull { entity ->
                providerSnapshotRepository?.getSnapshot(entity.id)?.toLegacyProvider()
            }
            val providers = sourceProviders.map { provider ->
                provider.copy(
                    password = "",  // Strip credentials from backup export
                    stalkerLastBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
                    stalkerLastPlaybackMode = null,
                    // Transport consent is device-local. A restore must verify or ask again.
                    stalkerTransportMode = StalkerTransportMode.AUTO_STRICT,
                    stalkerTransportOrigin = "",
                    stalkerTlsSpkiSha256 = "",
                    stalkerTransportConsentAt = 0L
                )
            }
            val combinedM3uProfiles = buildCombinedM3uProfileBackups(providers)
            val activeLiveSource = buildActiveLiveSourceBackup(providers)
            if (sourceProviders.size != providerEntities.size) {
                return@withContext com.streamvault.domain.model.Result.error(
                    "Cannot export backup because one or more providers have no typed configuration snapshot"
                )
            }
            val providerCredentials = sourceProviders.mapNotNull { it.toBackupCredentials() }
            val providerSnapshots = providers.map { provider ->
                val configuration = when (val config = provider.toTypedConfiguration()) {
                    is com.streamvault.domain.model.XtreamConfig -> config.copy(password = "")
                    is com.streamvault.domain.model.M3uConfig -> config
                    is com.streamvault.domain.model.StalkerConfig -> config.copy(
                        password = "",
                        transportGrant = null
                    )
                    is com.streamvault.domain.model.JellyfinConfig -> config.copy(credential = "")
                }
                configuration.toBackupSnapshot(
                    StableProvider(
                        id = provider.id,
                        name = provider.name,
                        type = provider.type,
                        isActive = provider.isActive,
                        status = provider.status,
                        lastSyncedAt = provider.lastSyncedAt,
                        createdAt = provider.createdAt
                    ),
                    accountRuntime = provider.toAccountRuntime()
                )
            }
            val providerIds = providerEntities.map { it.id }
            val providersById = providers.associateBy { it.id }

            // Gather all favorites across all types
            val liveFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesFavs = providerIds.flatMap { providerId ->
                favoriteDao.getAllByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allFavorites = (liveFavs + movieFavs + seriesFavs).map { favorite ->
                favorite.withPortableIdentity(channelDao, movieDao, seriesDao)
            }
            val unportableFavorites = allFavorites.filter {
                it.remoteContentId.isNullOrBlank() && it.contentType != ContentType.VOD
            }
            if (unportableFavorites.isNotEmpty()) {
                return@withContext com.streamvault.domain.model.Result.error(
                    "Cannot export ${unportableFavorites.size} favorite(s) because their synced content identity is missing"
                )
            }

            // Gather all custom groups
            val liveGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "LIVE").first().map { it.toDomain() }
            }
            val movieGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "MOVIE").first().map { it.toDomain() }
            }
            val seriesGroups = providerIds.flatMap { providerId ->
                virtualGroupDao.getByType(providerId, "SERIES").first().map { it.toDomain() }
            }
            val allGroups = liveGroups + movieGroups + seriesGroups

            val playbackHistory = playbackHistoryDao.getAllSync().map { it.toDomain() }
                .map { history -> history.withPortableIdentity(channelDao, movieDao, seriesDao, episodeDao) }
            val multiViewPresets = mapOf(
                "preset_1" to preferencesRepository.getMultiViewPreset(0).first(),
                "preset_2" to preferencesRepository.getMultiViewPreset(1).first(),
                "preset_3" to preferencesRepository.getMultiViewPreset(2).first()
            )
            val portableMultiViewPresets = multiViewPresets.mapValues { (_, channelIds) ->
                channelIds.mapNotNull { channelId ->
                    channelDao.getById(channelId)?.let { channel ->
                        providersById[channel.providerId]?.let { provider ->
                            PortableChannelReference(
                                provider = provider.toBackupProviderReference(),
                                streamId = channel.streamId,
                                name = channel.name,
                                streamUrl = channel.streamUrl,
                                remoteCategoryId = channel.categoryId
                            )
                        }
                    }
                }
            }
            val expectedMultiViewEntries = multiViewPresets.values.sumOf { it.size }
            val exportedMultiViewEntries = portableMultiViewPresets.values.sumOf { it.size }
            if (expectedMultiViewEntries != exportedMultiViewEntries) {
                return@withContext com.streamvault.domain.model.Result.error(
                    "Cannot export split-screen presets because one or more channels are not synced"
                )
            }
            val protectedCategories = providers.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category ->
                        ProtectedCategoryBackup(
                            providerServerUrl = provider.serverUrl,
                            providerUsername = provider.username,
                            providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
                            categoryId = category.id,
                            categoryName = category.name,
                            type = category.type,
                            providerType = provider.type
                        )
                    }
            }
            val providerEpgAssignments = if (providerEpgSourceDao != null && epgSourceDao != null) {
                providers.flatMap { provider ->
                    providerEpgSourceDao.getForProviderSync(provider.id).map { assignment ->
                        val source = epgSourceDao.getById(assignment.epgSourceId)
                            ?: return@withContext Result.error(
                                "Cannot export EPG Provider Assignments: '${provider.name}' references missing source ${assignment.epgSourceId}"
                            )
                        ProviderEpgAssignmentBackup(
                            provider = provider.toBackupProviderReference(),
                            sourceUrl = source.url,
                            priority = assignment.priority,
                            enabled = assignment.enabled
                        )
                    }
                }
            } else {
                null
            }
            val manualEpgMappings = if (channelEpgMappingDao != null && epgSourceDao != null) {
                providers.flatMap { provider ->
                    channelEpgMappingDao.getForProvider(provider.id)
                        .filter { it.isManualOverride }
                        .map { mapping ->
                            val channel = channelDao.getById(mapping.providerChannelId)
                                ?.takeIf { it.providerId == provider.id }
                                ?: return@withContext Result.error(
                                    "Cannot export Manual EPG Mappings: channel row ${mapping.providerChannelId} is missing for '${provider.name}'"
                                )
                            val sourceUrl = mapping.epgSourceId?.let { sourceId ->
                                epgSourceDao.getById(sourceId)?.url
                                    ?: return@withContext Result.error(
                                        "Cannot export Manual EPG Mappings: source $sourceId is missing for channel '${channel.name}'"
                                    )
                            }
                            ManualEpgMappingBackup(
                                channel = PortableChannelReference(
                                    provider = provider.toBackupProviderReference(),
                                    streamId = channel.streamId,
                                    name = channel.name,
                                    streamUrl = channel.streamUrl,
                                    remoteCategoryId = channel.categoryId
                                ),
                                sourceUrl = sourceUrl,
                                xmltvChannelId = mapping.xmltvChannelId,
                                sourceType = mapping.sourceType,
                                matchType = mapping.matchType,
                                confidence = mapping.confidence,
                                source = mapping.source
                            )
                        }
                }
            } else {
                null
            }
            val m3uClassificationOverrides = if (m3uClassificationDao != null) {
                providers.filter { it.type == ProviderType.M3U }.flatMap { provider ->
                    m3uClassificationDao.getOverrides(provider.id).map { override ->
                        M3uClassificationOverrideBackup(
                            provider = provider.toBackupProviderReference(),
                            sourceKey = override.sourceKey,
                            streamId = override.streamId,
                            targetType = override.targetType,
                            groupKey = override.groupKey,
                            seriesKey = override.seriesKey,
                            seriesName = override.seriesName,
                            seasonNumber = override.seasonNumber,
                            episodeNumber = override.episodeNumber,
                            episodeTitle = override.episodeTitle
                        )
                    }
                }
            } else {
                null
            }
            val m3uClassificationRules = if (m3uClassificationDao != null) {
                providers.filter { it.type == ProviderType.M3U }.flatMap { provider ->
                    m3uClassificationDao.getCategoryRules(provider.id).map { rule ->
                        M3uClassificationRuleBackup(
                            provider = provider.toBackupProviderReference(),
                            groupKey = rule.groupKey,
                            targetType = rule.targetType
                        )
                    }
                }
            } else {
                null
            }
            val programReminders = programReminderManager?.observeUpcomingReminders()?.first()
                ?.filter { it.programStartTime > System.currentTimeMillis() && !it.isDismissed }
                ?.map { reminder ->
                    val provider = providersById[reminder.providerId]
                        ?: return@withContext Result.error(
                            "Cannot export Program Reminders: '${reminder.programTitle}' references missing provider ${reminder.providerId}"
                        )
                    ProgramReminderBackup(
                        provider = provider.toBackupProviderReference(),
                        channelId = reminder.channelId,
                        channelName = reminder.channelName,
                        programTitle = reminder.programTitle,
                        programStartTime = reminder.programStartTime,
                        leadTimeMinutes = reminder.leadTimeMinutes
                    )
                }
            val scheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED && it.scheduledEndMs > System.currentTimeMillis() }
                .mapNotNull { item ->
                    val provider = providersById[item.providerId]
                        ?: return@withContext Result.error(
                            "Cannot export Recording Schedules: '${item.channelName}' references missing provider ${item.providerId}"
                        )
                    val legacy = item.toScheduledRecordingBackup(
                        provider = provider,
                        schedule = item.scheduleId?.let { scheduleId -> recordingScheduleDao.getById(scheduleId) }
                    )
                    val channel = channelDao.getById(item.channelId)
                        ?: return@withContext Result.error(
                            "Cannot export Recording Schedules: channel '${item.channelName}' is not in the synced catalog"
                        )
                    legacy.copy(channel = channel.toPortableContentReference(provider.toBackupProviderReference()))
                }
                .normalizedRecurringBackups()
            val recordingStorage = recordingManager.observeStorageState().first().let { storage ->
                RecordingStorageBackup(
                    fileNamePattern = storage.fileNamePattern,
                    retentionDays = storage.retentionDays,
                    maxSimultaneousRecordings = storage.maxSimultaneousRecordings
                )
            }
            val portableProviderPreferences = buildPortableProviderPreferences(providers)
            if (portableProviderPreferences.unresolvedReferences.isNotEmpty()) {
                return@withContext com.streamvault.domain.model.Result.error(
                    "Cannot export provider-scoped settings: ${portableProviderPreferences.unresolvedReferences.first()}"
                )
            }
            val unportableHistory = playbackHistory.count { it.remoteContentId.isNullOrBlank() }
            if (unportableHistory > 0) {
                return@withContext com.streamvault.domain.model.Result.error(
                    "Cannot export $unportableHistory playback-history item(s) because their synced content identity is missing"
                )
            }

            val ungroupedFavorites = allFavorites.filter { it.groupId == null }
            val portableFavorites = ungroupedFavorites.mapNotNull { favorite ->
                favorite.toPortableContentReference(providersById, channelDao, movieDao, seriesDao)?.let { reference ->
                    PortableFavoriteBackup(
                        content = reference,
                        position = favorite.position,
                        addedAt = favorite.addedAt
                    )
                }
            }
            if (portableFavorites.size != ungroupedFavorites.size) {
                return@withContext Result.error(
                    "Cannot export Favorites: ${ungroupedFavorites.size - portableFavorites.size} item(s) lack portable catalog identity"
                )
            }
            val portableCustomGroups = allGroups.map { group ->
                val provider = providersById[group.providerId]
                    ?: return@withContext Result.error("Cannot export custom group '${group.name}': provider is missing")
                val members = allFavorites.filter { favorite -> favorite.groupId == group.id }
                    .mapNotNull { favorite ->
                        favorite.toPortableContentReference(providersById, channelDao, movieDao, seriesDao)?.let { reference ->
                            PortableFavoriteBackup(reference, favorite.position, favorite.addedAt)
                        }
                    }
                if (members.size != allFavorites.count { it.groupId == group.id }) {
                    return@withContext Result.error(
                        "Cannot export custom group '${group.name}': one or more members lack portable catalog identity"
                    )
                }
                PortableCustomGroupBackup(
                    provider = provider.toBackupProviderReference(),
                    contentType = group.contentType,
                    name = group.name,
                    icon = group.iconEmoji,
                    position = group.position,
                    createdAt = group.createdAt,
                    members = members
                )
            }
            val portablePlaybackHistory = playbackHistory.mapNotNull { history ->
                history.toPortableContentReference(providersById, channelDao, movieDao, seriesDao, episodeDao)?.let { reference ->
                    PortablePlaybackHistoryBackup(
                        content = reference,
                        resumePositionMs = history.resumePositionMs,
                        totalDurationMs = history.totalDurationMs,
                        lastWatchedAt = history.lastWatchedAt,
                        watchCount = history.watchCount,
                        watchedStatus = history.watchedStatus.name,
                        posterUrl = history.posterUrl,
                        seasonNumber = history.seasonNumber,
                        episodeNumber = history.episodeNumber
                    )
                }
            }
            if (portablePlaybackHistory.size != playbackHistory.size) {
                return@withContext Result.error(
                    "Cannot export Playback History: ${playbackHistory.size - portablePlaybackHistory.size} item(s) lack portable catalog identity"
                )
            }
            val portableProtectedContent = providers.flatMap { provider ->
                val reference = provider.toBackupProviderReference()
                val providerSeries = seriesDao.getByProviderSync(provider.id)
                val seriesByLocalId = providerSeries.associateBy { it.id }
                val protectedEpisodes = episodeDao.getByProviderSync(provider.id).filter { it.isUserProtected }
                val orphanedProtectedEpisode = protectedEpisodes.firstOrNull { it.seriesId !in seriesByLocalId }
                if (orphanedProtectedEpisode != null) {
                    return@withContext Result.error(
                        "Cannot export Protected Content: episode '${orphanedProtectedEpisode.title}' has no parent series identity"
                    )
                }
                buildList {
                    channelDao.getByProviderSync(provider.id).filter { it.isUserProtected }.forEach { channel ->
                        add(PortableProtectedContentBackup(channel.toPortableContentReference(reference)))
                    }
                    movieDao.getByProviderSync(provider.id).filter { it.isUserProtected }.forEach { movie ->
                        add(PortableProtectedContentBackup(movie.toPortableContentReference(reference)))
                    }
                    providerSeries.filter { it.isUserProtected }.forEach { series ->
                        add(PortableProtectedContentBackup(series.toPortableContentReference(reference)))
                    }
                    protectedEpisodes.forEach { episode ->
                        add(
                            PortableProtectedContentBackup(
                                episode.toPortableContentReference(reference, checkNotNull(seriesByLocalId[episode.seriesId]))
                            )
                        )
                    }
                }
            }
            val portableHiddenContent = portableProviderPreferences.hiddenChannels.map { channel ->
                PortableHiddenContentBackup(
                    com.streamvault.domain.manager.PortableContentReference(
                        provider = channel.provider,
                        contentType = ContentType.LIVE,
                        remoteContentId = channel.streamId.toString(),
                        remoteCategoryId = channel.remoteCategoryId?.toString(),
                        name = channel.name,
                        urlFallback = channel.streamUrl
                    )
                )
            }
            val portableContentPreferences = portableProviderPreferences.channelPreferences.map { preference ->
                PortableContentPreferenceBackup(
                    content = com.streamvault.domain.manager.PortableContentReference(
                        provider = preference.channel.provider,
                        contentType = ContentType.LIVE,
                        remoteContentId = preference.channel.streamId.toString(),
                        remoteCategoryId = preference.channel.remoteCategoryId?.toString(),
                        name = preference.channel.name,
                        urlFallback = preference.channel.streamUrl
                    ),
                    aspectRatio = preference.aspectRatio,
                    audioVideoOffsetMs = preference.audioVideoOffsetMs
                )
            }
            val portableVariantChoices = buildList {
                portableProviderPreferences.liveVariantSelections.forEach { choice ->
                    choice.remoteItemId?.let { remoteId ->
                        add(
                            PortableVariantChoiceBackup(
                                logicalGroupId = choice.logicalGroupId,
                                selectedContent = com.streamvault.domain.manager.PortableContentReference(
                                    provider = choice.provider,
                                    contentType = ContentType.LIVE,
                                    remoteContentId = remoteId,
                                    remoteCategoryId = choice.remoteCategoryId?.toString()
                                )
                            )
                        )
                    }
                }
                portableProviderPreferences.vodVariantSelections.forEach { choice ->
                    choice.remoteItemId?.let { remoteId ->
                        add(
                            PortableVariantChoiceBackup(
                                logicalGroupId = choice.logicalGroupId,
                                selectedContent = com.streamvault.domain.manager.PortableContentReference(
                                    provider = choice.provider,
                                    contentType = choice.contentType ?: ContentType.MOVIE,
                                    remoteContentId = remoteId,
                                    remoteCategoryId = choice.remoteCategoryId?.toString()
                                )
                            )
                        )
                    }
                }
            }
            val portableManualEpgMappings = manualEpgMappings.orEmpty().map { mapping ->
                PortableManualEpgMappingV14Backup(
                    content = com.streamvault.domain.manager.PortableContentReference(
                        provider = mapping.channel.provider,
                        contentType = ContentType.LIVE,
                        remoteContentId = mapping.channel.streamId.toString(),
                        remoteCategoryId = mapping.channel.remoteCategoryId?.toString(),
                        name = mapping.channel.name,
                        urlFallback = mapping.channel.streamUrl
                    ),
                    sourceUrl = mapping.sourceUrl,
                    xmltvChannelId = mapping.xmltvChannelId,
                    sourceType = mapping.sourceType,
                    matchType = mapping.matchType,
                    confidence = mapping.confidence,
                    source = mapping.source
                )
            }
            val portableMultiViewPresetsV14 = portableMultiViewPresets.map { (name, channels) ->
                PortableMultiViewPresetV14Backup(
                    name = name,
                    channels = channels.map { channel ->
                        com.streamvault.domain.manager.PortableContentReference(
                            provider = channel.provider,
                            contentType = ContentType.LIVE,
                            remoteContentId = channel.streamId.toString(),
                            remoteCategoryId = channel.remoteCategoryId?.toString(),
                            name = channel.name,
                            urlFallback = channel.streamUrl
                        )
                    }
                )
            }
            val portableSearchHistory = searchHistoryDao?.getAllSync()?.map { history ->
                val providerReference = if (history.providerId == 0L) {
                    null
                } else {
                    providersById[history.providerId]?.toBackupProviderReference()
                        ?: return@withContext Result.error(
                            "Cannot export Search History: '${history.query}' references missing provider ${history.providerId}"
                        )
                }
                PortableSearchHistoryBackup(
                    query = history.query,
                    contentScope = history.contentScope,
                    provider = providerReference,
                    usedAt = history.usedAt,
                    useCount = history.useCount
                )
            }

            val backupData = BackupData(
                version = CURRENT_BACKUP_VERSION,
                preferences = prefs,
                providers = null,
                providerSnapshots = providerSnapshots,
                favorites = null,
                virtualGroups = null,
                playbackHistory = null,
                multiViewPresets = null,
                portableMultiViewPresets = portableMultiViewPresets,
                protectedCategories = protectedCategories,
                scheduledRecordings = scheduledRecordings,
                portableProviderPreferences = portableProviderPreferences,
                recordingStorage = recordingStorage,
                providerCredentials = providerCredentials.takeIf { it.isNotEmpty() },
                epgSources = epgSourceDao?.getAllSync()?.map { it.toDomain() },
                providerEpgAssignments = providerEpgAssignments,
                manualEpgMappings = null,
                m3uClassificationOverrides = m3uClassificationOverrides,
                m3uClassificationRules = m3uClassificationRules,
                programReminders = programReminders,
                combinedM3uProfiles = combinedM3uProfiles,
                activeLiveSource = activeLiveSource,
                portableFavorites = portableFavorites,
                portableCustomGroups = portableCustomGroups,
                portablePlaybackHistory = portablePlaybackHistory,
                portableProtectedContent = portableProtectedContent,
                portableSearchHistory = portableSearchHistory,
                portableHiddenContent = portableHiddenContent,
                portableContentPreferences = portableContentPreferences,
                portableVariantChoices = portableVariantChoices,
                portableManualEpgMappings = portableManualEpgMappings,
                portableMultiViewPresetsV14 = portableMultiViewPresetsV14
            )

            // Compute checksum over the data without checksum field
            val backupWithChecksum = backupData.copy(checksum = buildSha256Checksum(backupData))

            // 2. Serialize and write to URI with the same admission limit used
            // by restore. This prevents the app from producing a backup that
            // its own importer must reject.
            val outputStream = openBackupOutputStream(uriString)
                ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open output stream")
            try {
                BoundedOutputStream(outputStream, MAX_BACKUP_BYTES).use { boundedOutput ->
                    OutputStreamWriter(boundedOutput, Charsets.UTF_8).use { writer ->
                        writeBackupDataJson(writer, backupWithChecksum)
                    }
                }
            } catch (tooLarge: BackupOutputTooLargeException) {
                deleteFileUriTarget(uriString)
                return@withContext com.streamvault.domain.model.Result.error(
                    "Backup exceeds the maximum supported size of $MAX_BACKUP_BYTES bytes",
                    tooLarge
                )
            }

            com.streamvault.domain.model.Result.success(Unit)
        } catch (e: Exception) {
            com.streamvault.domain.model.Result.error("Failed to export backup: ${e.message}", e)
        }
    }

    override suspend fun inspectBackup(uriString: String): Result<BackupPreview> = withContext(Dispatchers.IO) {
        try {
            val parsedBackup = readBackupData(uriString)
                ?: return@withContext Result.error("Failed to open input stream")
            val rawBackupData = parsedBackup.data
            if (rawBackupData.version > CURRENT_BACKUP_VERSION) {
                return@withContext Result.error("Unsupported backup version")
            }
            if (rawBackupData.isStructurallyEmpty()) {
                return@withContext Result.error("Backup file does not contain any importable data")
            }
            if (!verifyChecksum(parsedBackup)) {
                return@withContext Result.error("Backup file is corrupted (checksum mismatch)")
            }
            val backupData = rawBackupData.withLegacyProviderProjection()

            val existingProviders = loadStoredProviders()
            val existingProviderIds = existingProviders.map { it.id }
            val existingGroups = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                    addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                    addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                }
            }
            val existingFavorites = buildList {
                existingProviderIds.forEach { providerId ->
                    addAll(favoriteDao.getAllByType(providerId, "LIVE").first())
                    addAll(favoriteDao.getAllByType(providerId, "MOVIE").first())
                    addAll(favoriteDao.getAllByType(providerId, "SERIES").first())
                }
            }
            val existingHistory = playbackHistoryDao.getAllSync()
            val existingProtectedCategories = existingProviders.flatMap { provider ->
                categoryRepository.getCategories(provider.id).first()
                    .filter { it.isUserProtected }
                    .map { category -> Triple(provider, category.name.lowercase(), category.type) }
            }
            val existingScheduledRecordings = recordingManager.observeRecordingItems().first()
                .filter { it.status == RecordingStatus.SCHEDULED }

            val providersByIdentity = existingProviders.associateBy { it.backupIdentity() }
            val backupProviderIdMap = backupData.providers.orEmpty().mapNotNull { provider ->
                providersByIdentity[provider.backupIdentity()]?.let { stored ->
                    provider.id to stored.id
                }
            }.toMap()
            val groupKeys = existingGroups.mapTo(hashSetOf()) {
                SavedGroupConflictKey(it.providerId, it.name.lowercase(), it.contentType)
            }
            val groupNameById = existingGroups.associate { group ->
                group.id to SavedGroupConflictKey(group.providerId, group.name.lowercase(), group.contentType)
            }
            val favoriteKeys = existingFavorites.map { favorite ->
                val domainFavorite = favorite.toDomain().withPortableIdentity(channelDao, movieDao, seriesDao)
                SavedFavoriteConflictKey(
                    providerId = favorite.providerId,
                    contentType = favorite.contentType,
                    remoteContentId = domainFavorite.remoteContentId ?: "legacy:${favorite.contentId}",
                    group = favorite.groupId?.let { groupNameById[it]?.name }
                )
            }.toHashSet()
            val historyKeys = existingHistory.map { history ->
                val domainHistory = history.toDomain().withPortableIdentity(channelDao, movieDao, seriesDao, episodeDao)
                SavedHistoryConflictKey(
                    providerId = history.providerId,
                    contentType = history.contentType,
                    remoteContentId = domainHistory.remoteContentId ?: "legacy:${history.contentId}"
                )
            }.toHashSet()
            val protectedCategoryKeys = existingProtectedCategories.mapTo(hashSetOf()) { (provider, name, type) ->
                Triple(provider.id, name, type)
            }
            val providerConflicts = backupData.providers.orEmpty().count { it.backupIdentity() in providersByIdentity }
            val groupConflicts = backupData.virtualGroups.orEmpty().count { group ->
                val targetProviderId = backupProviderIdMap[group.providerId] ?: return@count false
                SavedGroupConflictKey(targetProviderId, group.name.lowercase(), group.contentType) in groupKeys
            }
            val favoriteConflicts = backupData.favorites.orEmpty().count {
                val targetProviderId = backupProviderIdMap[it.providerId] ?: return@count false
                SavedFavoriteConflictKey(
                    providerId = targetProviderId,
                    contentType = it.contentType,
                    remoteContentId = it.remoteContentId ?: "legacy:${it.contentId}",
                    group = it.groupId?.let { groupId ->
                        backupData.virtualGroups.orEmpty().firstOrNull { group -> group.id == groupId }?.name?.lowercase()
                    }
                ) in favoriteKeys
            }
            val historyConflicts = backupData.playbackHistory.orEmpty().count {
                val targetProviderId = backupProviderIdMap[it.providerId] ?: return@count false
                SavedHistoryConflictKey(
                    providerId = targetProviderId,
                    contentType = it.contentType,
                    remoteContentId = it.remoteContentId ?: "legacy:${it.contentId}"
                ) in historyKeys
            }
            val protectedCategoryConflicts = backupData.protectedCategories.orEmpty().count { incoming ->
                val provider = providersByIdentity[incoming.backupIdentity()] ?: return@count false
                Triple(provider.id, incoming.categoryName.lowercase(), incoming.type) in protectedCategoryKeys
            }
            val recordingConflicts = countScheduledRecordingConflicts(
                incoming = backupData.scheduledRecordings.orEmpty().normalizedRecurringBackups(),
                providersByIdentity = providersByIdentity,
                existing = existingScheduledRecordings
            )

            Result.success(
                BackupPreview(
                    version = backupData.version,
                    providerCount = backupData.providers.orEmpty().size,
                    favoriteCount = backupData.favorites.orEmpty().size,
                    groupCount = backupData.virtualGroups.orEmpty().size,
                    playbackHistoryCount = backupData.playbackHistory.orEmpty().size,
                    multiViewPresetCount = backupData.multiViewPresets.orEmpty().count { it.value.isNotEmpty() },
                    preferenceCount = backupData.preferences.orEmpty().size,
                    protectedCategoryCount = backupData.protectedCategories.orEmpty().size,
                    scheduledRecordingCount = backupData.scheduledRecordings.orEmpty().normalizedRecurringBackups().size,
                    providerConflicts = providerConflicts,
                    favoriteConflicts = favoriteConflicts,
                    groupConflicts = groupConflicts,
                    historyConflicts = historyConflicts,
                    protectedCategoryConflicts = protectedCategoryConflicts,
                    recordingConflicts = recordingConflicts
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.error("Failed to inspect backup: ${e.message}", e)
        }
    }

    override suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan
    ): com.streamvault.domain.model.Result<BackupImportResult> = withContext(Dispatchers.IO) {
        try {
            val parsedBackup = readBackupData(uriString)
                ?: return@withContext com.streamvault.domain.model.Result.error("Failed to open input stream")
            val rawBackupData = parsedBackup.data

            if (rawBackupData.version > CURRENT_BACKUP_VERSION) {
                return@withContext com.streamvault.domain.model.Result.error("Unsupported backup version")
            }
            if (rawBackupData.isStructurallyEmpty()) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file does not contain any importable data")
            }
            if (!verifyChecksum(parsedBackup)) {
                return@withContext com.streamvault.domain.model.Result.error("Backup file is corrupted (checksum mismatch)")
            }
            val backupData = rawBackupData.withLegacyProviderProjection()

            val restoreKey = buildRestoreKey(rawBackupData, plan)
            var checkpoint = loadOrCreateCheckpoint(restoreKey)
            if (checkpoint.state == RESTORE_STATE_COMPLETE) {
                // A completed restore is a durable crash-recovery boundary, not a one-time
                // consumption marker. The user may intentionally import the same backup again
                // after deleting a provider or other restored data. Reopen the checkpoint so the
                // normal conflict strategy can safely skip or replace the current destination.
                checkpoint = checkpoint.reopenForExplicitReplay()
            }

            var storedProviders = loadStoredProviders()
            val importedSections = mutableListOf<String>()
            val skippedSections = mutableListOf<String>()
            val failedSections = mutableListOf<String>()
            val unresolvedReferences = mutableListOf<String>()

            if (!checkpoint.roomComplete) {
                val roomCheckpoint = checkpoint.copy(
                    roomComplete = false,
                    state = RESTORE_STATE_RUNNING,
                    lastError = null,
                    updatedAt = System.currentTimeMillis()
                )
                val roomRestoreResult = try {
                    restoreRoomBackedSections(
                        backupData = backupData,
                        plan = plan,
                        initialStoredProviders = storedProviders
                    )
                } catch (error: Exception) {
                    checkpoint = checkpoint.copy(
                        state = RESTORE_STATE_FAILED_BEFORE_COMMIT,
                        lastError = "Room: ${error.message}",
                        updatedAt = System.currentTimeMillis()
                    ).persist()
                    return@withContext com.streamvault.domain.model.Result.success(
                        BackupImportResult(
                            outcome = BackupRestoreOutcome.FAILED_BEFORE_COMMIT,
                            failedSections = listOf("Room: ${error.message ?: "restore failed"}")
                        )
                    )
                }
                storedProviders = roomRestoreResult.storedProviders
                importedSections += roomRestoreResult.importedSections
                skippedSections += roomRestoreResult.skippedSections
                unresolvedReferences += roomRestoreResult.unresolvedReferences
                if (roomRestoreResult.unresolvedReferences.isNotEmpty()) {
                    failedSections += "Saved library/history: ${roomRestoreResult.unresolvedReferences.size} unresolved"
                }
                checkpoint = roomCheckpoint.copy(
                    roomComplete = roomRestoreResult.unresolvedReferences.isEmpty(),
                    updatedAt = System.currentTimeMillis()
                ).persist()
            } else {
                importedSections += importedRoomSections(backupData, plan)
                skippedSections += skippedRoomSections(backupData, plan)
            }

            if (plan.importPreferences) {
                val preferences = backupData.preferences
                val portablePreferences = backupData.portableProviderPreferences
                when {
                    preferences == null && portablePreferences == null -> skippedSections += "Preferences"
                    checkpoint.preferencesComplete -> importedSections += "Preferences"
                    else -> try {
                        checkpoint = checkpoint.ensurePreferenceSnapshot()
                        checkpoint.preferenceSnapshotJson?.let { snapshotJson ->
                            val snapshot = gson.fromJson<Map<String, String>>(snapshotJson, MAP_STRING_STRING_TYPE)
                            // An incomplete checkpoint may be the result of process death between
                            // DataStore writes. Restore the durable pre-import snapshot first.
                            restoreCheckpointPreferenceSnapshot(snapshot)
                        }
                        preferences?.let {
                            restorePreferences(it, skipProviderScopedReferences = portablePreferences != null)
                        }
                        val preferenceUnresolved = portablePreferences?.let { portable ->
                            restorePortableProviderPreferences(
                                if (backupData.version >= 14 && (
                                        portable.hiddenCategories.isNotEmpty() ||
                                            backupData.portableHiddenContent != null ||
                                            backupData.portableContentPreferences != null ||
                                            backupData.portableVariantChoices != null
                                        )) {
                                    portable.copy(
                                        hiddenCategories = emptyList(),
                                        hiddenChannels = emptyList(),
                                        channelPreferences = emptyList(),
                                        channelPreferencesSpecified = false,
                                        liveVariantSelections = emptyList(),
                                        liveVariantSelectionsSpecified = false,
                                        vodVariantSelections = emptyList(),
                                        vodVariantSelectionsSpecified = false
                                    )
                                } else portable,
                                storedProviders
                            )
                        }.orEmpty()
                        unresolvedReferences += preferenceUnresolved
                        val portableComplete = preferenceUnresolved.isEmpty()
                        if (!portableComplete) {
                            failedSections += "Provider-scoped preferences: ${preferenceUnresolved.size} unresolved"
                        }
                        checkpoint = checkpoint.copy(
                            preferencesComplete = portableComplete,
                            preferenceSnapshotJson = checkpoint.preferenceSnapshotJson.takeIf {
                                !portableComplete ||
                                    (plan.importMultiViewPresets &&
                                        (backupData.multiViewPresets != null || backupData.portableMultiViewPresets != null))
                            },
                            updatedAt = System.currentTimeMillis()
                        ).persist(clearPreferenceSnapshot = portableComplete && !(
                            plan.importMultiViewPresets &&
                                (backupData.multiViewPresets != null || backupData.portableMultiViewPresets != null)
                        ))
                        importedSections += "Preferences"
                    } catch (error: Exception) {
                        val suffix = if (checkpoint.preferenceSnapshotJson == null) {
                            "; no rollback snapshot was available"
                        } else {
                            runCatchingPreferenceRollback(checkpoint)
                                ?.let { "; previous settings could not be restored: ${it.message}" }
                                ?: "; previous settings restored"
                        }
                        failedSections += "Preferences: ${error.message ?: "restore failed"}$suffix"
                    }
                }
            } else skippedSections += "Preferences"

            if (plan.importMultiViewPresets) {
                val presets = backupData.multiViewPresets
                val portablePresets = backupData.portableMultiViewPresets
                when {
                    presets == null && portablePresets == null -> skippedSections += "Split Screen Presets"
                    checkpoint.presetsComplete -> importedSections += "Split Screen Presets"
                    else -> try {
                        checkpoint = checkpoint.ensurePreferenceSnapshot()
                        val portableUnresolved = portablePresets?.let {
                            if (backupData.version >= 14 && backupData.portableMultiViewPresetsV14 != null) {
                                emptyList()
                            } else {
                                restorePortableMultiViewPresets(it, storedProviders)
                            }
                        }.orEmpty()
                        unresolvedReferences += portableUnresolved
                        if (portablePresets == null) {
                            preferencesRepository.setMultiViewPreset(0, presets?.get("preset_1").orEmpty())
                            preferencesRepository.setMultiViewPreset(1, presets?.get("preset_2").orEmpty())
                            preferencesRepository.setMultiViewPreset(2, presets?.get("preset_3").orEmpty())
                        }
                        checkpoint = checkpoint.copy(
                            presetsComplete = portableUnresolved.isEmpty(),
                            preferenceSnapshotJson = checkpoint.preferenceSnapshotJson.takeIf {
                                portableUnresolved.isNotEmpty()
                            },
                            updatedAt = System.currentTimeMillis()
                        ).persist(clearPreferenceSnapshot = portableUnresolved.isEmpty())
                        if (portableUnresolved.isNotEmpty()) {
                            failedSections += "Split Screen Presets: ${portableUnresolved.size} unresolved"
                        }
                        importedSections += "Split Screen Presets"
                    } catch (error: Exception) {
                        val suffix = if (checkpoint.preferenceSnapshotJson == null) {
                            "; no rollback snapshot was available"
                        } else {
                            runCatchingPresetRollback(checkpoint)
                                ?.let { "; previous presets could not be restored: ${it.message}" }
                                ?: "; previous presets restored"
                        }
                        failedSections += "Split Screen Presets: ${error.message ?: "restore failed"}$suffix"
                    }
                }
            } else skippedSections += "Split Screen Presets"

            val recordingScheduleImport = if (
                plan.importRecordingSchedules &&
                !checkpoint.schedulesComplete &&
                !(backupData.version >= 14 && backupData.scheduledRecordings.orEmpty().any { it.channel != null })
            ) {
                backupData.scheduledRecordings?.let { recordings ->
                    try {
                        importScheduledRecordingBackups(
                            recordings = recordings.normalizedRecurringBackups(),
                            storedProviders = storedProviders,
                            existingSchedules = recordingManager.observeRecordingItems().first()
                                .filter { it.status == RecordingStatus.SCHEDULED }
                                .toMutableList(),
                            conflictStrategy = plan.conflictStrategy,
                            recordingManager = recordingManager
                        )
                    } catch (error: Exception) {
                        failedSections += "Recording Schedules: ${error.message ?: "restore failed"}"
                        null
                    }
                }
            } else null

            if (plan.importRecordingSchedules) {
                when {
                    backupData.scheduledRecordings == null -> skippedSections += "Recording Schedules"
                    checkpoint.schedulesComplete -> importedSections += "Recording Schedules"
                    recordingScheduleImport == null -> Unit
                    recordingScheduleImport.failedCount > 0 -> failedSections += "Recording Schedules: ${recordingScheduleImport.failedCount} failed"
                    else -> {
                        checkpoint = checkpoint.copy(
                            schedulesComplete = true,
                            updatedAt = System.currentTimeMillis()
                        ).persist()
                        if (recordingScheduleImport.importedCount > 0) importedSections += "Recording Schedules"
                        else skippedSections += "Recording Schedules"
                    }
                }
            } else skippedSections += "Recording Schedules"

            backupData.recordingStorage?.let { storage ->
                try {
                    val current = recordingManager.observeStorageState().first()
                    when (val result = recordingManager.updateStorageConfig(
                        com.streamvault.domain.model.RecordingStorageConfig(
                            treeUri = current.treeUri,
                            displayName = current.displayName,
                            localDirectory = current.localDirectory,
                            fileNamePattern = storage.fileNamePattern,
                            retentionDays = storage.retentionDays,
                            maxSimultaneousRecordings = storage.maxSimultaneousRecordings
                        )
                    )) {
                        is Result.Success -> importedSections.add("Recording Storage Settings")
                        is Result.Error -> failedSections.add("Recording Storage Settings: ${result.message}")
                        Result.Loading -> failedSections.add("Recording Storage Settings: update did not complete")
                    }
                } catch (error: Exception) {
                    failedSections.add("Recording Storage Settings: ${error.message ?: "restore failed"}")
                }
            } ?: skippedSections.add("Recording Storage Settings")

            var queuedRestore = queuePortableRestoreItems(
                backupData = backupData,
                plan = plan,
                restoreKey = restoreKey,
                storedProviders = storedProviders
            )
            if (queuedRestore != null) {
                pendingBackupRestoreCoordinator?.applyGlobal()
                queuedRestore.affectedProviders.forEach { reference ->
                    storedProviders.findUnambiguousPortableProvider(reference)?.id?.let { providerId ->
                        pendingBackupRestoreCoordinator?.applyForProvider(providerId)
                    }
                }
                backupRestoreLedgerDao?.getJob(queuedRestore.jobId)?.let { job ->
                    queuedRestore = queuedRestore.copy(
                        pendingCount = job.pendingCount + job.failedCount,
                        unresolvedCount = job.unresolvedCount
                    )
                }
            }
            val outcome = when {
                failedSections.isNotEmpty() -> BackupRestoreOutcome.PARTIAL
                queuedRestore != null && queuedRestore.pendingCount + queuedRestore.unresolvedCount > 0 ->
                    BackupRestoreOutcome.WAITING_FOR_SYNC
                else -> BackupRestoreOutcome.COMPLETE
            }
            checkpoint = checkpoint.copy(
                state = when (outcome) {
                    BackupRestoreOutcome.COMPLETE -> RESTORE_STATE_COMPLETE
                    BackupRestoreOutcome.WAITING_FOR_SYNC -> RESTORE_STATE_WAITING_FOR_SYNC
                    else -> RESTORE_STATE_PARTIAL
                },
                lastError = failedSections.joinToString().takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis()
            ).persist()
            com.streamvault.domain.model.Result.success(
                BackupImportResult(
                    outcome = outcome,
                    importedSections = importedSections.distinct(),
                    skippedSections = skippedSections.distinct(),
                    failedSections = failedSections.distinct(),
                    unresolvedReferences = unresolvedReferences.distinct(),
                    recordingScheduleImport = recordingScheduleImport,
                    restoreJobId = queuedRestore?.jobId,
                    pendingCount = queuedRestore?.pendingCount ?: 0,
                    unresolvedCount = queuedRestore?.unresolvedCount ?: 0,
                    affectedProviders = queuedRestore?.affectedProviders.orEmpty()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("BackupManager", "Backup import failed", e)
            com.streamvault.domain.model.Result.error("Failed to import backup: ${e.message}", e)
        }
    }

    private suspend fun loadOrCreateCheckpoint(restoreKey: String): BackupRestoreCheckpointEntity {
        val now = System.currentTimeMillis()
        val fresh = BackupRestoreCheckpointEntity(
            restoreKey = restoreKey,
            state = RESTORE_STATE_RUNNING,
            createdAt = now,
            updatedAt = now
        )
        val dao = backupRestoreCheckpointDao ?: return fresh
        dao.insertIfAbsent(fresh)
        return dao.get(restoreKey) ?: fresh
    }

    private suspend fun BackupRestoreCheckpointEntity.reopenForExplicitReplay(): BackupRestoreCheckpointEntity {
        val reopened = copy(
            roomComplete = false,
            preferencesComplete = false,
            presetsComplete = false,
            schedulesComplete = false,
            state = RESTORE_STATE_RUNNING,
            preferenceSnapshotJson = null,
            lastError = null,
            updatedAt = System.currentTimeMillis()
        )
        val dao = backupRestoreCheckpointDao ?: return reopened
        dao.update(
            restoreKey = restoreKey,
            roomComplete = false,
            preferencesComplete = false,
            presetsComplete = false,
            schedulesComplete = false,
            state = RESTORE_STATE_RUNNING,
            lastError = null,
            updatedAt = reopened.updatedAt
        )
        dao.setPreferenceSnapshot(restoreKey, null, reopened.updatedAt)
        return reopened
    }

    private suspend fun BackupRestoreCheckpointEntity.persist(
        clearPreferenceSnapshot: Boolean = false
    ): BackupRestoreCheckpointEntity {
        val dao = backupRestoreCheckpointDao ?: return this
        dao.update(
            restoreKey = restoreKey,
            roomComplete = roomComplete,
            preferencesComplete = preferencesComplete,
            presetsComplete = presetsComplete,
            schedulesComplete = schedulesComplete,
            state = state,
            lastError = lastError,
            updatedAt = updatedAt
        )
        if (clearPreferenceSnapshot) {
            dao.setPreferenceSnapshot(restoreKey, null, updatedAt)
        }
        return dao.get(restoreKey) ?: this
    }

    private suspend fun BackupRestoreCheckpointEntity.ensurePreferenceSnapshot(): BackupRestoreCheckpointEntity {
        if (preferenceSnapshotJson != null) return this
        val dao = backupRestoreCheckpointDao ?: return this
        val snapshot = gson.toJson(capturePreferenceSnapshot(providerDao.getAllSync()), MAP_STRING_STRING_TYPE)
        dao.setPreferenceSnapshot(restoreKey, snapshot, System.currentTimeMillis())
        return copy(preferenceSnapshotJson = snapshot, updatedAt = System.currentTimeMillis())
    }

    private suspend fun runCatchingPreferenceRollback(
        checkpoint: BackupRestoreCheckpointEntity
    ): Exception? = try {
        checkpoint.preferenceSnapshotJson?.let { snapshotJson ->
            restoreCheckpointPreferenceSnapshot(
                gson.fromJson<Map<String, String>>(snapshotJson, MAP_STRING_STRING_TYPE)
            )
        }
        null
    } catch (error: Exception) {
        error
    }

    private suspend fun runCatchingPresetRollback(
        checkpoint: BackupRestoreCheckpointEntity
    ): Exception? = try {
        checkpoint.preferenceSnapshotJson?.let { snapshotJson ->
            restoreCheckpointMultiViewPresets(
                gson.fromJson<Map<String, String>>(snapshotJson, MAP_STRING_STRING_TYPE)
            )
        }
        null
    } catch (error: Exception) {
        error
    }

    private fun buildRestoreKey(backupData: BackupData, plan: BackupImportPlan): String {
        val canonicalBackupChecksum = buildSha256Checksum(backupData.copy(checksum = null))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonicalBackupChecksum.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(gson.toJson(plan).toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun importedRoomSections(backupData: BackupData, plan: BackupImportPlan): List<String> = buildList {
        if (plan.importProviders && backupData.providers != null) add("Providers")
        if (plan.importProviders && backupData.combinedM3uProfiles != null) add("Combined M3U Profiles")
        if (plan.importProviders && backupData.epgSources != null && epgSourceDao != null) add("EPG Sources")
        if (plan.importSavedLibrary) add("Saved Library")
        if (plan.importPlaybackHistory && backupData.playbackHistory != null) add("Playback History")
    }

    private fun skippedRoomSections(backupData: BackupData, plan: BackupImportPlan): List<String> = buildList {
        if (!plan.importProviders || backupData.providers == null) add("Providers")
        if (!plan.importProviders || backupData.combinedM3uProfiles == null) add("Combined M3U Profiles")
        if (!plan.importProviders || backupData.epgSources == null || epgSourceDao == null) add("EPG Sources")
        if (!plan.importSavedLibrary) add("Saved Library")
        if (!plan.importPlaybackHistory || backupData.playbackHistory == null) add("Playback History")
    }

    private data class ParsedBackup(
        val data: BackupData,
        val rawJson: ByteArray,
    )

    private fun verifyChecksum(parsedBackup: ParsedBackup): Boolean {
        val backupData = parsedBackup.data
        val storedChecksum = backupData.checksum ?: return true // no checksum = legacy backup, skip verification
        val dataWithoutChecksum = backupData.copy(checksum = null)

        return if (storedChecksum.startsWith(SHA256_PREFIX)) {
            // Prefer the exact exported bytes. Release builds previously let R8 rename
            // reflected DTO fields, so parsing and serializing the object again could
            // change its wire representation even though the file itself was intact.
            buildRawSha256Checksum(parsedBackup.rawJson) == storedChecksum ||
                buildSha256Checksum(dataWithoutChecksum) == storedChecksum
        } else {
            verifyLegacyCrc32Checksum(dataWithoutChecksum, storedChecksum)
        }
    }

    private fun buildSha256Checksum(backupData: BackupData): String {
        val digest = MessageDigest.getInstance("SHA-256")
        OutputStreamWriter(MessageDigestOutputStream(digest), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return SHA256_PREFIX + digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun buildRawSha256Checksum(rawJson: ByteArray): String? {
        val json = rawJson.toString(Charsets.UTF_8)
        val match = RAW_CHECKSUM_FIELD_PATTERN.find(json) ?: return null
        var prefix = json.substring(0, match.range.first)
        val suffix = json.substring(match.range.last + 1)
        if (!match.value.endsWith(",") && prefix.endsWith(',')) {
            prefix = prefix.dropLast(1)
        }
        val payload = (prefix + suffix).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return SHA256_PREFIX + digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun verifyLegacyCrc32Checksum(backupData: BackupData, checksum: String): Boolean {
        val crc = CRC32()
        OutputStreamWriter(Crc32OutputStream(crc), Charsets.UTF_8).use { writer ->
            writeBackupDataJson(writer, backupData.copy(checksum = null))
        }
        return crc.value.toString(16) == checksum
    }

    private fun writeBackupDataJson(writer: Writer, backupData: BackupData) {
        JsonWriter(writer).use { jsonWriter ->
            jsonWriter.beginObject()
            jsonWriter.name("version").value(backupData.version.toLong())
            backupData.checksum?.let { checksum ->
                jsonWriter.name("checksum").value(checksum)
            }
            writeNamedJsonField(jsonWriter, "preferences", backupData.preferences, MAP_STRING_STRING_TYPE)
            writeNamedJsonField(jsonWriter, "providers", backupData.providers, PROVIDER_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "providerSnapshots", backupData.providerSnapshots, PROVIDER_SNAPSHOT_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "providerCredentials", backupData.providerCredentials, PROVIDER_CREDENTIALS_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "favorites", backupData.favorites, FAVORITE_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "virtualGroups", backupData.virtualGroups, VIRTUAL_GROUP_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "playbackHistory", backupData.playbackHistory, PLAYBACK_HISTORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "multiViewPresets", backupData.multiViewPresets, MULTIVIEW_PRESETS_TYPE)
            writeNamedJsonField(
                jsonWriter,
                "portableMultiViewPresets",
                backupData.portableMultiViewPresets,
                PORTABLE_MULTIVIEW_PRESETS_TYPE
            )
            writeNamedJsonField(jsonWriter, "protectedCategories", backupData.protectedCategories, PROTECTED_CATEGORY_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "scheduledRecordings", backupData.scheduledRecordings, SCHEDULED_RECORDING_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "recordingStorage", backupData.recordingStorage, RECORDING_STORAGE_TYPE)
            writeNamedJsonField(
                jsonWriter,
                "portableProviderPreferences",
                backupData.portableProviderPreferences,
                PortableProviderPreferencesBackup::class.java
            )
            writeNamedJsonField(jsonWriter, "epgSources", backupData.epgSources, EPG_SOURCE_LIST_TYPE)
            writeNamedJsonField(
                jsonWriter,
                "providerEpgAssignments",
                backupData.providerEpgAssignments,
                PROVIDER_EPG_ASSIGNMENT_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "manualEpgMappings",
                backupData.manualEpgMappings,
                MANUAL_EPG_MAPPING_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "m3uClassificationOverrides",
                backupData.m3uClassificationOverrides,
                M3U_CLASSIFICATION_OVERRIDE_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "m3uClassificationRules",
                backupData.m3uClassificationRules,
                M3U_CLASSIFICATION_RULE_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "programReminders",
                backupData.programReminders,
                PROGRAM_REMINDER_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "combinedM3uProfiles",
                backupData.combinedM3uProfiles,
                COMBINED_M3U_PROFILE_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "activeLiveSource",
                backupData.activeLiveSource,
                ACTIVE_LIVE_SOURCE_BACKUP_TYPE
            )
            writeNamedJsonField(jsonWriter, "portableFavorites", backupData.portableFavorites, PORTABLE_FAVORITE_LIST_TYPE)
            writeNamedJsonField(
                jsonWriter,
                "portableCustomGroups",
                backupData.portableCustomGroups,
                PORTABLE_CUSTOM_GROUP_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "portablePlaybackHistory",
                backupData.portablePlaybackHistory,
                PORTABLE_PLAYBACK_HISTORY_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "portableProtectedContent",
                backupData.portableProtectedContent,
                PORTABLE_PROTECTED_CONTENT_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "portableSearchHistory",
                backupData.portableSearchHistory,
                PORTABLE_SEARCH_HISTORY_LIST_TYPE
            )
            writeNamedJsonField(
                jsonWriter,
                "portableHiddenContent",
                backupData.portableHiddenContent,
                PORTABLE_HIDDEN_CONTENT_LIST_TYPE
            )
            writeNamedJsonField(jsonWriter, "portableContentPreferences", backupData.portableContentPreferences, PORTABLE_CONTENT_PREFERENCE_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "portableVariantChoices", backupData.portableVariantChoices, PORTABLE_VARIANT_CHOICE_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "portableManualEpgMappings", backupData.portableManualEpgMappings, PORTABLE_MANUAL_EPG_V14_LIST_TYPE)
            writeNamedJsonField(jsonWriter, "portableMultiViewPresetsV14", backupData.portableMultiViewPresetsV14, PORTABLE_MULTIVIEW_V14_LIST_TYPE)
            jsonWriter.endObject()
        }
    }

    private fun <T> writeNamedJsonField(jsonWriter: JsonWriter, name: String, value: T?, type: Type) {
        if (value == null) return
        jsonWriter.name(name)
        gson.toJson(value, type, jsonWriter)
    }

    private class MessageDigestOutputStream(
        private val digest: MessageDigest
    ) : OutputStream() {
        override fun write(b: Int) {
            digest.update(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
        }
    }

    private class Crc32OutputStream(
        private val crc32: CRC32
    ) : OutputStream() {
        override fun write(b: Int) {
            crc32.update(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            crc32.update(b, off, len)
        }
    }

    private fun readBackupData(uriString: String): ParsedBackup? {
        val rawJson = openBackupInputStream(uriString)?.use(::readBoundedBytes) ?: return null
        val data = try {
            AdmissionCheckingReader(
                InputStreamReader(ByteArrayInputStream(rawJson), Charsets.UTF_8),
                MAX_JSON_DEPTH,
                MAX_FIELD_CHARS
            ).use { admissionReader ->
                JsonReader(admissionReader).use { jsonReader ->
                    readBackupData(jsonReader).also(::validateBackupDataLimits)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (admission: BackupAdmissionException) {
            throw admission
        } catch (error: Exception) {
            if (error is MalformedJsonException ||
                error is EOFException ||
                error is JsonParseException ||
                error is IllegalStateException
            ) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.MALFORMED,
                    "Backup JSON is malformed or truncated"
                ).also { it.initCause(error) }
            }
            throw error
        }
        return ParsedBackup(data = data, rawJson = rawJson)
    }

    private fun readBoundedBytes(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_BYTES) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.BYTE_LIMIT,
                    "Backup exceeds the ${MAX_BACKUP_BYTES / (1024 * 1024)} MiB import limit"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readBackupData(reader: JsonReader): BackupData {
        var version = 0
        var checksum: String? = null
        var preferences: Map<String, String>? = null
        var providers: List<com.streamvault.domain.model.LegacyProvider>? = null
        var providerSnapshots: List<ProviderBackupSnapshot>? = null
        var providerCredentials: List<ProviderCredentials>? = null
        var favorites: List<com.streamvault.domain.model.Favorite>? = null
        var virtualGroups: List<com.streamvault.domain.model.VirtualGroup>? = null
        var playbackHistory: List<com.streamvault.domain.model.PlaybackHistory>? = null
        var multiViewPresets: Map<String, List<Long>>? = null
        var portableMultiViewPresets: Map<String, List<PortableChannelReference>>? = null
        var protectedCategories: List<ProtectedCategoryBackup>? = null
        var scheduledRecordings: List<ScheduledRecordingBackup>? = null
        var recordingStorage: RecordingStorageBackup? = null
        var portableProviderPreferences: PortableProviderPreferencesBackup? = null
        var epgSources: List<com.streamvault.domain.model.EpgSource>? = null
        var providerEpgAssignments: List<ProviderEpgAssignmentBackup>? = null
        var manualEpgMappings: List<ManualEpgMappingBackup>? = null
        var m3uClassificationOverrides: List<M3uClassificationOverrideBackup>? = null
        var m3uClassificationRules: List<M3uClassificationRuleBackup>? = null
        var programReminders: List<ProgramReminderBackup>? = null
        var combinedM3uProfiles: List<CombinedM3uProfileBackup>? = null
        var activeLiveSource: ActiveLiveSourceBackup? = null
        var portableFavorites: List<PortableFavoriteBackup>? = null
        var portableCustomGroups: List<PortableCustomGroupBackup>? = null
        var portablePlaybackHistory: List<PortablePlaybackHistoryBackup>? = null
        var portableProtectedContent: List<PortableProtectedContentBackup>? = null
        var portableSearchHistory: List<PortableSearchHistoryBackup>? = null
        var portableHiddenContent: List<PortableHiddenContentBackup>? = null
        var portableContentPreferences: List<PortableContentPreferenceBackup>? = null
        var portableVariantChoices: List<PortableVariantChoiceBackup>? = null
        var portableManualEpgMappings: List<PortableManualEpgMappingV14Backup>? = null
        var portableMultiViewPresetsV14: List<PortableMultiViewPresetV14Backup>? = null
        val seenFields = hashSetOf<String>()
        var headerRead = false

        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            if (!seenFields.add(field)) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.DUPLICATE_FIELD,
                    "Backup contains duplicate field '$field'"
                )
            }
            if (!headerRead && field != "version") {
                throw BackupAdmissionException(
                    BackupAdmissionReason.MALFORMED,
                    "Backup header must begin with version"
                )
            }
            when (field) {
                "version" -> {
                    version = reader.nextInt()
                    headerRead = true
                    if (version > CURRENT_BACKUP_VERSION) {
                        throw BackupAdmissionException(
                            BackupAdmissionReason.UNSUPPORTED_VERSION,
                            "Unsupported backup version $version"
                        )
                    }
                }
                "checksum" -> checksum = reader.nextString()
                "preferences" -> preferences = readStringMap(reader, MAX_PREFERENCES, "preferences")
                "providers" -> providers = readLimitedArray(reader, PROVIDER_TYPE, MAX_PROVIDERS, "providers")
                "providerSnapshots" -> providerSnapshots =
                    readLimitedArray(reader, PROVIDER_SNAPSHOT_TYPE, MAX_PROVIDERS, "provider snapshots")
                "providerCredentials" -> providerCredentials =
                    readLimitedArray(reader, PROVIDER_CREDENTIALS_TYPE, MAX_PROVIDERS, "provider credentials")
                "favorites" -> favorites = readLimitedArray(reader, FAVORITE_TYPE, MAX_SECTION_ITEMS, "favorites")
                "virtualGroups" -> virtualGroups =
                    readLimitedArray(reader, VIRTUAL_GROUP_TYPE, MAX_SECTION_ITEMS, "groups")
                "playbackHistory" -> playbackHistory =
                    readLimitedArray(reader, PLAYBACK_HISTORY_TYPE, MAX_SECTION_ITEMS, "playback history")
                "multiViewPresets" -> multiViewPresets = readMultiViewPresets(reader)
                "portableMultiViewPresets" -> portableMultiViewPresets = readPortableMultiViewPresets(reader)
                "protectedCategories" -> protectedCategories =
                    readLimitedArray(reader, PROTECTED_CATEGORY_TYPE, MAX_SECTION_ITEMS, "protected categories")
                "scheduledRecordings" -> scheduledRecordings =
                    readLimitedArray(reader, SCHEDULED_RECORDING_TYPE, MAX_SECTION_ITEMS, "recording schedules")
                "recordingStorage" -> recordingStorage =
                    readLegacyAwareValue(reader, RECORDING_STORAGE_TYPE)
                "portableProviderPreferences" -> portableProviderPreferences =
                    readPortableProviderPreferences(reader)
                "epgSources" -> epgSources =
                    readLimitedArray(reader, EPG_SOURCE_TYPE, MAX_EPG_SOURCES, "EPG sources")
                "providerEpgAssignments" -> providerEpgAssignments =
                    readLimitedArray(
                        reader,
                        PROVIDER_EPG_ASSIGNMENT_TYPE,
                        MAX_SECTION_ITEMS,
                        "provider EPG assignments"
                    )
                "manualEpgMappings" -> manualEpgMappings =
                    readLimitedArray(
                        reader,
                        MANUAL_EPG_MAPPING_TYPE,
                        MAX_SECTION_ITEMS,
                        "manual EPG mappings"
                    )
                "m3uClassificationOverrides" -> m3uClassificationOverrides =
                    readLimitedArray(
                        reader,
                        M3U_CLASSIFICATION_OVERRIDE_TYPE,
                        MAX_SECTION_ITEMS,
                        "M3U classification overrides"
                    )
                "m3uClassificationRules" -> m3uClassificationRules =
                    readLimitedArray(
                        reader,
                        M3U_CLASSIFICATION_RULE_TYPE,
                        MAX_SECTION_ITEMS,
                        "M3U classification rules"
                    )
                "programReminders" -> programReminders =
                    readLimitedArray(
                        reader,
                        PROGRAM_REMINDER_TYPE,
                        MAX_SECTION_ITEMS,
                        "program reminders"
                    )
                "combinedM3uProfiles" -> combinedM3uProfiles =
                    readLimitedArray(
                        reader,
                        COMBINED_M3U_PROFILE_TYPE,
                        MAX_SECTION_ITEMS,
                        "combined M3U profiles"
                    )
                "activeLiveSource" -> activeLiveSource =
                    readLegacyAwareValue(reader, ACTIVE_LIVE_SOURCE_BACKUP_TYPE)
                "portableFavorites" -> portableFavorites =
                    readLimitedArray(reader, PORTABLE_FAVORITE_TYPE, MAX_SECTION_ITEMS, "portable favorites")
                "portableCustomGroups" -> portableCustomGroups =
                    readLimitedArray(reader, PORTABLE_CUSTOM_GROUP_TYPE, MAX_SECTION_ITEMS, "portable custom groups")
                "portablePlaybackHistory" -> portablePlaybackHistory =
                    readLimitedArray(reader, PORTABLE_PLAYBACK_HISTORY_TYPE, MAX_SECTION_ITEMS, "portable playback history")
                "portableProtectedContent" -> portableProtectedContent =
                    readLimitedArray(reader, PORTABLE_PROTECTED_CONTENT_TYPE, MAX_SECTION_ITEMS, "portable protected content")
                "portableSearchHistory" -> portableSearchHistory =
                    readLimitedArray(reader, PORTABLE_SEARCH_HISTORY_TYPE, MAX_SECTION_ITEMS, "portable search history")
                "portableHiddenContent" -> portableHiddenContent =
                    readLimitedArray(reader, PORTABLE_HIDDEN_CONTENT_TYPE, MAX_SECTION_ITEMS, "portable hidden content")
                "portableContentPreferences" -> portableContentPreferences =
                    readLimitedArray(reader, PORTABLE_CONTENT_PREFERENCE_TYPE, MAX_SECTION_ITEMS, "portable content preferences")
                "portableVariantChoices" -> portableVariantChoices =
                    readLimitedArray(reader, PORTABLE_VARIANT_CHOICE_TYPE, MAX_SECTION_ITEMS, "portable variant choices")
                "portableManualEpgMappings" -> portableManualEpgMappings =
                    readLimitedArray(reader, PORTABLE_MANUAL_EPG_V14_TYPE, MAX_SECTION_ITEMS, "portable manual EPG mappings")
                "portableMultiViewPresetsV14" -> portableMultiViewPresetsV14 =
                    readLimitedArray(reader, PORTABLE_MULTIVIEW_V14_TYPE, MAX_SECTION_ITEMS, "portable multiview presets")
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!headerRead) {
            throw BackupAdmissionException(
                BackupAdmissionReason.MALFORMED,
                "Backup header is missing version"
            )
        }
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw BackupAdmissionException(
                BackupAdmissionReason.MALFORMED,
                "Backup contains trailing JSON content"
            )
        }

        return BackupData(
            version = version,
            checksum = checksum,
            preferences = preferences,
            providers = providers,
            providerSnapshots = providerSnapshots,
            providerCredentials = providerCredentials,
            favorites = favorites,
            virtualGroups = virtualGroups,
            playbackHistory = playbackHistory,
            multiViewPresets = multiViewPresets,
            portableMultiViewPresets = portableMultiViewPresets,
            protectedCategories = protectedCategories,
            scheduledRecordings = scheduledRecordings,
            portableProviderPreferences = portableProviderPreferences,
            recordingStorage = recordingStorage,
            epgSources = epgSources,
            providerEpgAssignments = providerEpgAssignments,
            manualEpgMappings = manualEpgMappings,
            m3uClassificationOverrides = m3uClassificationOverrides,
            m3uClassificationRules = m3uClassificationRules,
            programReminders = programReminders,
            combinedM3uProfiles = combinedM3uProfiles,
            activeLiveSource = activeLiveSource,
            portableFavorites = portableFavorites,
            portableCustomGroups = portableCustomGroups,
            portablePlaybackHistory = portablePlaybackHistory,
            portableProtectedContent = portableProtectedContent,
            portableSearchHistory = portableSearchHistory,
            portableHiddenContent = portableHiddenContent,
            portableContentPreferences = portableContentPreferences,
            portableVariantChoices = portableVariantChoices,
            portableManualEpgMappings = portableManualEpgMappings,
            portableMultiViewPresetsV14 = portableMultiViewPresetsV14
        )
    }

    private fun <T : Any> readLimitedArray(
        reader: JsonReader,
        type: Type,
        maxItems: Int,
        label: String
    ): List<T>? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val result = ArrayList<T>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (result.size >= maxItems) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.SECTION_LIMIT,
                    "Backup has too many $label"
                )
            }
            val item = readLegacyAwareValue<T>(reader, type)
                ?: throw BackupAdmissionException(
                    BackupAdmissionReason.MALFORMED,
                    "Backup contains a null $label entry"
                )
            result += item
        }
        reader.endArray()
        return result
    }

    private fun <T> readLegacyAwareValue(reader: JsonReader, type: Type): T? {
        val element = gson.fromJson<JsonElement>(reader, JsonElement::class.java)
            ?: return null
        if (element.isJsonNull) return null
        return gson.fromJson(normalizeLegacyBackupElement(element, type), type)
    }

    /**
     * Release builds before the backup wire schema was pinned let R8 rename fields in
     * domain.manager DTOs to a, b, c, ... . Accept those files while writing the stable
     * source field names in new builds.
     */
    private fun normalizeLegacyBackupElement(element: JsonElement, type: Type): JsonElement {
        if (!element.isJsonObject) return element
        val fieldNames = when (type) {
            PROVIDER_SNAPSHOT_TYPE -> listOf(
                "provider", "accountRuntime", "xtreamConfig", "m3uConfig", "stalkerConfig", "jellyfinConfig"
            )
            PROVIDER_CREDENTIALS_TYPE -> listOf("serverUrl", "username", "password", "providerType")
            BACKUP_PROVIDER_REFERENCE_TYPE -> listOf("serverUrl", "username", "stalkerMacAddress", "providerType")
            PORTABLE_CATEGORY_REFERENCE_TYPE -> listOf("provider", "name", "type", "remoteCategoryId")
            PORTABLE_CATEGORY_SORT_REFERENCE_TYPE -> listOf("provider", "type", "mode")
            PORTABLE_GROUP_REFERENCE_TYPE -> listOf("provider", "name", "contentType")
            PORTABLE_CHANNEL_REFERENCE_TYPE -> listOf("provider", "streamId", "name", "streamUrl")
            PORTABLE_CHANNEL_PREFERENCE_REFERENCE_TYPE -> listOf("channel", "aspectRatio", "audioVideoOffsetMs")
            PORTABLE_EPG_TIME_SHIFT_REFERENCE_TYPE -> listOf("provider", "minutes")
            PORTABLE_VARIANT_SELECTION_REFERENCE_TYPE -> listOf(
                "provider", "logicalGroupId", "rawItemId", "remoteItemId", "contentType"
            )
            COMBINED_M3U_PROFILE_TYPE -> listOf("name", "enabled", "members", "createdAt", "updatedAt")
            ACTIVE_LIVE_SOURCE_BACKUP_TYPE -> listOf(
                "type", "provider", "combinedProfileName", "combinedProfileProviders"
            )
            PROTECTED_CATEGORY_TYPE -> listOf(
                "providerServerUrl", "providerUsername", "providerStalkerMacAddress",
                "categoryId", "categoryName", "type", "providerType"
            )
            SCHEDULED_RECORDING_TYPE -> listOf(
                "providerServerUrl", "providerUsername", "providerStalkerMacAddress", "channelId",
                "channelName", "streamUrl", "scheduledStartMs", "scheduledEndMs", "requestedStartMs",
                "requestedEndMs", "paddingBeforeMs", "paddingAfterMs", "programTitle", "recurrence",
                "recurringRuleId", "providerType"
            )
            RECORDING_STORAGE_TYPE -> listOf("fileNamePattern", "retentionDays", "maxSimultaneousRecordings")
            PROVIDER_EPG_ASSIGNMENT_TYPE -> listOf("provider", "sourceUrl", "priority", "enabled")
            MANUAL_EPG_MAPPING_TYPE -> listOf(
                "channel", "sourceUrl", "xmltvChannelId", "sourceType", "matchType", "confidence", "source"
            )
            M3U_CLASSIFICATION_OVERRIDE_TYPE -> listOf(
                "provider", "sourceKey", "streamId", "targetType", "groupKey", "seriesKey", "seriesName",
                "seasonNumber", "episodeNumber", "episodeTitle"
            )
            M3U_CLASSIFICATION_RULE_TYPE -> listOf("provider", "groupKey", "targetType")
            PROGRAM_REMINDER_TYPE -> listOf(
                "provider", "channelId", "channelName", "programTitle", "programStartTime", "leadTimeMinutes"
            )
            else -> return element
        }
        val objectValue = element.asJsonObject
        fieldNames.forEachIndexed { index, fieldName ->
            val legacyName = ('a'.code + index).toChar().toString()
            if (!objectValue.has(fieldName) && objectValue.has(legacyName)) {
                objectValue.add(fieldName, objectValue.remove(legacyName))
            }
        }
        when (type) {
            PORTABLE_CATEGORY_REFERENCE_TYPE,
            PORTABLE_CATEGORY_SORT_REFERENCE_TYPE,
            PORTABLE_GROUP_REFERENCE_TYPE,
            PORTABLE_CHANNEL_REFERENCE_TYPE,
            PORTABLE_CHANNEL_PREFERENCE_REFERENCE_TYPE,
            PORTABLE_EPG_TIME_SHIFT_REFERENCE_TYPE,
            PORTABLE_VARIANT_SELECTION_REFERENCE_TYPE -> {
                if (type == PORTABLE_CHANNEL_PREFERENCE_REFERENCE_TYPE) {
                    objectValue.get("channel")?.let { channel ->
                        objectValue.add(
                            "channel",
                            normalizeLegacyBackupElement(channel, PORTABLE_CHANNEL_REFERENCE_TYPE)
                        )
                    }
                } else {
                    objectValue.get("provider")?.let { provider ->
                        objectValue.add(
                            "provider",
                            normalizeLegacyBackupElement(provider, BACKUP_PROVIDER_REFERENCE_TYPE)
                        )
                    }
                }
            }
            COMBINED_M3U_PROFILE_TYPE -> {
                objectValue.get("members")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { member ->
                    if (member.isJsonObject) {
                        val memberObject = member.asJsonObject
                        listOf("provider", "priority", "enabled").forEachIndexed { index, fieldName ->
                            val legacyName = ('a'.code + index).toChar().toString()
                            if (!memberObject.has(fieldName) && memberObject.has(legacyName)) {
                                memberObject.add(fieldName, memberObject.remove(legacyName))
                            }
                        }
                        memberObject.get("provider")?.let { provider ->
                            memberObject.add(
                                "provider",
                                normalizeLegacyBackupElement(provider, BACKUP_PROVIDER_REFERENCE_TYPE)
                            )
                        }
                    }
                }
            }
            ACTIVE_LIVE_SOURCE_BACKUP_TYPE -> {
                objectValue.get("provider")?.let { provider ->
                    objectValue.add(
                        "provider",
                        normalizeLegacyBackupElement(provider, BACKUP_PROVIDER_REFERENCE_TYPE)
                    )
                }
                objectValue.get("combinedProfileProviders")?.takeIf { it.isJsonArray }?.let { providers ->
                    val normalizedProviders = com.google.gson.JsonArray()
                    providers.asJsonArray.forEach { provider ->
                        normalizedProviders.add(
                            normalizeLegacyBackupElement(provider, BACKUP_PROVIDER_REFERENCE_TYPE)
                        )
                    }
                    objectValue.add("combinedProfileProviders", normalizedProviders)
                }
            }
            else -> Unit
        }
        return objectValue
    }

    private fun readStringMap(
        reader: JsonReader,
        maxItems: Int,
        label: String
    ): Map<String, String>? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val result = linkedMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            if (result.size >= maxItems) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.SECTION_LIMIT,
                    "Backup has too many $label"
                )
            }
            val key = reader.nextName()
            if (key in result) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.DUPLICATE_FIELD,
                    "Backup contains duplicate $label key '$key'"
                )
            }
            result[key] = reader.nextString()
        }
        reader.endObject()
        return result
    }

    private fun <T> readLimitedJsonArray(
        element: JsonElement?,
        type: Type,
        maxItems: Int,
        label: String
    ): List<T> {
        if (element == null || element.isJsonNull) return emptyList()
        if (!element.isJsonArray) {
            throw BackupAdmissionException(
                BackupAdmissionReason.MALFORMED,
                "Backup field '$label' must be an array"
            )
        }
        if (element.asJsonArray.size() > maxItems) {
            throw BackupAdmissionException(
                BackupAdmissionReason.SECTION_LIMIT,
                "Backup has too many $label"
            )
        }
        return element.asJsonArray.map { item ->
            if (item.isJsonNull) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.MALFORMED,
                    "Backup contains a null $label entry"
                )
            }
            gson.fromJson(normalizeLegacyBackupElement(item, type), type)
        }
    }

    private fun readMultiViewPresets(reader: JsonReader): Map<String, List<Long>>? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val result = linkedMapOf<String, List<Long>>()
        var totalEntries = 0
        reader.beginObject()
        while (reader.hasNext()) {
            if (result.size >= MAX_PREFERENCES) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.SECTION_LIMIT,
                    "Backup has too many split-screen presets"
                )
            }
            val name = reader.nextName()
            if (name in result) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.DUPLICATE_FIELD,
                    "Backup contains duplicate split-screen preset '$name'"
                )
            }
            val values = readLimitedArray<Long>(
                reader,
                LONG_TYPE,
                MAX_SECTION_ITEMS - totalEntries,
                "preset entries"
            ).orEmpty()
            totalEntries += values.size
            result[name] = values
        }
        reader.endObject()
        return result
    }

    private fun readPortableMultiViewPresets(
        reader: JsonReader
    ): Map<String, List<PortableChannelReference>>? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val result = linkedMapOf<String, List<PortableChannelReference>>()
        var totalEntries = 0
        reader.beginObject()
        while (reader.hasNext()) {
            if (result.size >= MAX_PREFERENCES) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.SECTION_LIMIT,
                    "Backup has too many portable split-screen presets"
                )
            }
            val name = reader.nextName()
            if (name in result) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.DUPLICATE_FIELD,
                    "Backup contains duplicate portable split-screen preset '$name'"
                )
            }
            val values = readLimitedArray<PortableChannelReference>(
                reader,
                PORTABLE_CHANNEL_REFERENCE_TYPE,
                MAX_SECTION_ITEMS - totalEntries,
                "portable preset entries"
            ).orEmpty()
            totalEntries += values.size
            result[name] = values
        }
        reader.endObject()
        return result
    }

    private fun readPortableProviderPreferences(reader: JsonReader): PortableProviderPreferencesBackup? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        var providers = emptyList<BackupProviderReference>()
        var activeProvider: BackupProviderReference? = null
        var guideDefaultCategory: PortableCategoryReference? = null
        var guideDefaultVirtualCategoryId: Long? = null
        var guideDefaultCategorySpecified = false
        var promotedLiveGroups = emptyList<PortableVirtualGroupReference>()
        var hiddenChannels = emptyList<PortableChannelReference>()
        var hiddenCategories = emptyList<PortableCategoryReference>()
        var pinnedCategories = emptyList<PortableCategoryReference>()
        var pinnedCategoriesSpecified = false
        var categorySortModes = emptyList<PortableCategorySortReference>()
        var categorySortModesSpecified = false
        var epgTimeShifts = emptyList<PortableEpgTimeShiftReference>()
        var epgTimeShiftsSpecified = false
        var liveVariantSelections = emptyList<PortableVariantSelectionReference>()
        var liveVariantSelectionsSpecified = false
        var vodVariantSelections = emptyList<PortableVariantSelectionReference>()
        var vodVariantSelectionsSpecified = false
        var unresolvedReferences = emptyList<String>()
        var channelPreferences = emptyList<PortableChannelPreferenceReference>()
        var channelPreferencesSpecified = false
        var homeDefaultCategory: PortableCategoryReference? = null
        var homeDefaultVirtualCategoryId: Long? = null
        var homeDefaultCategorySpecified = false
        val seenFields = hashSetOf<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            if (!seenFields.add(field)) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.DUPLICATE_FIELD,
                    "Portable preferences contain duplicate field '$field'"
                )
            }
            when (field) {
                "providers", "a" -> providers =
                    readLimitedArray<BackupProviderReference>(
                        reader,
                        BACKUP_PROVIDER_REFERENCE_TYPE,
                        MAX_PROVIDERS,
                        "portable providers"
                    ).orEmpty()
                "activeProvider", "b" -> activeProvider =
                    readLegacyAwareValue(reader, BACKUP_PROVIDER_REFERENCE_TYPE)
                "guideDefaultCategory", "c" -> guideDefaultCategory =
                    readLegacyAwareValue(reader, PORTABLE_CATEGORY_REFERENCE_TYPE)
                "guideDefaultVirtualCategoryId", "d" -> guideDefaultVirtualCategoryId =
                    reader.nextNullableLong()
                "guideDefaultCategorySpecified", "e" -> guideDefaultCategorySpecified =
                    reader.nextBoolean()
                "promotedLiveGroups", "f" -> promotedLiveGroups =
                    readLimitedArray<PortableVirtualGroupReference>(
                        reader,
                        PORTABLE_GROUP_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "promoted groups"
                    ).orEmpty()
                "hiddenChannels", "g" -> hiddenChannels =
                    readLimitedArray<PortableChannelReference>(
                        reader,
                        PORTABLE_CHANNEL_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "hidden channels"
                    ).orEmpty()
                "hiddenCategories", "h" -> hiddenCategories =
                    readLimitedArray<PortableCategoryReference>(
                        reader,
                        PORTABLE_CATEGORY_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "hidden categories"
                    ).orEmpty()
                "pinnedCategories" -> pinnedCategories =
                    readLimitedArray<PortableCategoryReference>(
                        reader,
                        PORTABLE_CATEGORY_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "pinned categories"
                    ).orEmpty()
                "pinnedCategoriesSpecified", "j" -> pinnedCategoriesSpecified = reader.nextBoolean()
                "categorySortModes", "k" -> categorySortModes =
                    readLimitedArray<PortableCategorySortReference>(
                        reader,
                        PORTABLE_CATEGORY_SORT_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "category sort modes"
                    ).orEmpty()
                "categorySortModesSpecified", "l" -> categorySortModesSpecified = reader.nextBoolean()
                "epgTimeShifts", "m" -> epgTimeShifts =
                    readLimitedArray<PortableEpgTimeShiftReference>(
                        reader,
                        PORTABLE_EPG_TIME_SHIFT_REFERENCE_TYPE,
                        MAX_PROVIDERS,
                        "EPG time shifts"
                    ).orEmpty()
                "epgTimeShiftsSpecified", "n" -> epgTimeShiftsSpecified = reader.nextBoolean()
                "liveVariantSelections", "o" -> liveVariantSelections =
                    readLimitedArray<PortableVariantSelectionReference>(
                        reader,
                        PORTABLE_VARIANT_SELECTION_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "live variant selections"
                    ).orEmpty()
                "liveVariantSelectionsSpecified", "p" -> liveVariantSelectionsSpecified = reader.nextBoolean()
                "vodVariantSelections", "q" -> vodVariantSelections =
                    readLimitedArray<PortableVariantSelectionReference>(
                        reader,
                        PORTABLE_VARIANT_SELECTION_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "VOD variant selections"
                    ).orEmpty()
                "vodVariantSelectionsSpecified", "r" -> vodVariantSelectionsSpecified = reader.nextBoolean()
                "unresolvedReferences", "s" -> unresolvedReferences =
                    readLimitedArray<String>(
                        reader,
                        STRING_TYPE,
                        MAX_SECTION_ITEMS,
                        "unresolved references"
                    ).orEmpty()
                "channelPreferences", "t" -> channelPreferences =
                    readLimitedArray<PortableChannelPreferenceReference>(
                        reader,
                        PORTABLE_CHANNEL_PREFERENCE_REFERENCE_TYPE,
                        MAX_SECTION_ITEMS,
                        "channel preferences"
                    ).orEmpty()
                "channelPreferencesSpecified", "u" -> channelPreferencesSpecified = reader.nextBoolean()
                "homeDefaultCategory", "v" -> homeDefaultCategory =
                    readLegacyAwareValue(reader, PORTABLE_CATEGORY_REFERENCE_TYPE)
                "homeDefaultVirtualCategoryId", "w" -> homeDefaultVirtualCategoryId = reader.nextNullableLong()
                "homeDefaultCategorySpecified", "x" -> homeDefaultCategorySpecified = reader.nextBoolean()
                "i" -> {
                    val element = gson.fromJson<JsonElement>(reader, JsonElement::class.java)
                    if (element?.isJsonArray == true && element.asJsonArray.any { it.isJsonObject }) {
                        pinnedCategories = readLimitedJsonArray(
                            element,
                            PORTABLE_CATEGORY_REFERENCE_TYPE,
                            MAX_SECTION_ITEMS,
                            "pinned categories"
                        )
                    } else {
                        unresolvedReferences = readLimitedJsonArray(
                            element,
                            STRING_TYPE,
                            MAX_SECTION_ITEMS,
                            "unresolved references"
                        )
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return PortableProviderPreferencesBackup(
            providers = providers,
            activeProvider = activeProvider,
            guideDefaultCategory = guideDefaultCategory,
            guideDefaultVirtualCategoryId = guideDefaultVirtualCategoryId,
            guideDefaultCategorySpecified = guideDefaultCategorySpecified,
            promotedLiveGroups = promotedLiveGroups,
            hiddenChannels = hiddenChannels,
            hiddenCategories = hiddenCategories,
            pinnedCategories = pinnedCategories,
            pinnedCategoriesSpecified = pinnedCategoriesSpecified,
            categorySortModes = categorySortModes,
            categorySortModesSpecified = categorySortModesSpecified,
            epgTimeShifts = epgTimeShifts,
            epgTimeShiftsSpecified = epgTimeShiftsSpecified,
            liveVariantSelections = liveVariantSelections,
            liveVariantSelectionsSpecified = liveVariantSelectionsSpecified,
            vodVariantSelections = vodVariantSelections,
            vodVariantSelectionsSpecified = vodVariantSelectionsSpecified,
            unresolvedReferences = unresolvedReferences,
            channelPreferences = channelPreferences,
            channelPreferencesSpecified = channelPreferencesSpecified,
            homeDefaultCategory = homeDefaultCategory,
            homeDefaultVirtualCategoryId = homeDefaultVirtualCategoryId,
            homeDefaultCategorySpecified = homeDefaultCategorySpecified
        )
    }

    private fun JsonReader.nextNullableLong(): Long? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextLong()
        }

    private fun validateBackupDataLimits(data: BackupData) {
        require(data.preferences.orEmpty().size <= MAX_PREFERENCES) { "Backup has too many preferences" }
        require(data.providers.orEmpty().size <= MAX_PROVIDERS) { "Backup has too many providers" }
        require(data.providerSnapshots.orEmpty().size <= MAX_PROVIDERS) { "Backup has too many provider snapshots" }
        require(data.providerCredentials.orEmpty().size <= MAX_PROVIDERS) { "Backup has too many provider credentials" }
        data.providerSnapshots.orEmpty().forEach { it.configuration() }
        require(data.favorites.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many favorites" }
        require(data.virtualGroups.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many groups" }
        require(data.playbackHistory.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too much playback history" }
        require(data.protectedCategories.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many protected categories" }
        require(data.scheduledRecordings.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many recording schedules" }
        require(data.combinedM3uProfiles.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many combined M3U profiles" }
        require(data.combinedM3uProfiles.orEmpty().sumOf { it.members.size } <= MAX_SECTION_ITEMS) {
            "Backup has too many combined M3U profile members"
        }
        require(data.portableProviderPreferences?.channelPreferences.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many channel preferences"
        }
        require(data.epgSources.orEmpty().size <= MAX_EPG_SOURCES) { "Backup has too many EPG sources" }
        require(data.providerEpgAssignments.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many provider EPG assignments"
        }
        require(data.manualEpgMappings.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many manual EPG mappings"
        }
        require(data.m3uClassificationOverrides.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many M3U classification overrides"
        }
        require(data.m3uClassificationRules.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many M3U classification rules"
        }
        require(data.programReminders.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too many program reminders"
        }
        require(data.portableFavorites.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many portable favorites" }
        require(data.portableCustomGroups.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many portable custom groups" }
        require(data.portableCustomGroups.orEmpty().sumOf { it.members.size } <= MAX_SECTION_ITEMS) {
            "Backup has too many portable custom-group members"
        }
        require(data.portablePlaybackHistory.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too much portable playback history"
        }
        require(data.portableProtectedContent.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too much portable protected content"
        }
        require(data.portableSearchHistory.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too much portable search history"
        }
        require(data.portableHiddenContent.orEmpty().size <= MAX_SECTION_ITEMS) {
            "Backup has too much portable hidden content"
        }
        require(data.portableContentPreferences.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many portable content preferences" }
        require(data.portableVariantChoices.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many portable variant choices" }
        require(data.portableManualEpgMappings.orEmpty().size <= MAX_SECTION_ITEMS) { "Backup has too many portable manual EPG mappings" }
        require(data.portableMultiViewPresetsV14.orEmpty().sumOf { it.channels.size } <= MAX_SECTION_ITEMS) { "Backup has too many portable multiview entries" }
        require(data.multiViewPresets.orEmpty().values.sumOf { it.size } <= MAX_SECTION_ITEMS) { "Backup has too many preset entries" }
        require(data.portableMultiViewPresets.orEmpty().values.sumOf { it.size } <= MAX_SECTION_ITEMS) {
            "Backup has too many portable preset entries"
        }
        data.epgSources.orEmpty().forEach { source ->
            require(source.name.length <= MAX_FIELD_CHARS && source.url.length <= MAX_FIELD_CHARS) {
                "Backup contains an overlong EPG source"
            }
            require(source.timezoneId.orEmpty().length <= MAX_FIELD_CHARS) {
                "Backup contains an overlong EPG timezone"
            }
            if (source.timezonePolicy == com.streamvault.domain.model.XmltvTimezonePolicy.EXPLICIT_ZONE) {
                requireNotNull(source.timezoneId?.trim()?.takeIf { it.isNotEmpty() }) {
                    "Backup contains an EPG source without its explicit timezone"
                }.also(java.time.ZoneId::of)
            }
        }
        data.preferences.orEmpty().forEach { (key, value) ->
            require(key.length <= MAX_FIELD_CHARS && value.length <= MAX_FIELD_CHARS) { "Backup contains an overlong preference" }
        }
    }

    private fun openBackupOutputStream(uriString: String): OutputStream? {
        return if (uriString.isFileUriString()) {
            val target = uriString.toFileUriTarget() ?: return null
            target.parentFile?.mkdirs()
            FileOutputStream(target, false)
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openOutputStream(uri, "wt")
                ?: context.contentResolver.openOutputStream(uri)
        }
    }

    private fun openBackupInputStream(uriString: String) =
        if (uriString.isFileUriString()) {
            uriString.toFileUriTarget()?.takeIf { it.isFile }?.let(::FileInputStream)
        } else {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)
        }

    private fun String.isFileUriString(): Boolean =
        startsWith("$FILE_URI_SCHEME:", ignoreCase = true)

    private fun String.toFileUriTarget(): File? =
        runCatching { File(java.net.URI(this)) }.getOrNull()
            ?: Uri.parse(this).path?.let(::File)

    private suspend fun buildCombinedM3uProfileBackups(
        providers: List<Provider>
    ): List<CombinedM3uProfileBackup>? {
        val profileDao = combinedM3uProfileDao ?: return null
        val memberDao = combinedM3uProfileMemberDao ?: return null
        val referencesById = providers.associate { it.id to it.toBackupProviderReference() }
        return profileDao.getAll().first().map { profile ->
            CombinedM3uProfileBackup(
                name = profile.name,
                enabled = profile.enabled,
                members = memberDao.getForProfileSync(profile.id).map { member ->
                    val provider = referencesById[member.providerId]
                        ?: error("Cannot export Combined M3U profile '${profile.name}': provider ${member.providerId} is missing")
                    CombinedM3uProfileMemberBackup(
                        provider = provider,
                        priority = member.priority,
                        enabled = member.enabled
                    )
                },
                createdAt = profile.createdAt,
                updatedAt = profile.updatedAt
            )
        }
    }

    private suspend fun buildActiveLiveSourceBackup(
        providers: List<Provider>
    ): ActiveLiveSourceBackup? {
        val source = (preferencesRepository.activeLiveSource ?: flowOf(null)).first() ?: return null
        val referencesById = providers.associate { it.id to it.toBackupProviderReference() }
        return when (source) {
            is ActiveLiveSource.ProviderSource -> ActiveLiveSourceBackup(
                type = "provider",
                provider = referencesById[source.providerId]
                    ?: error("Cannot export Active Live Source: provider ${source.providerId} is missing")
            )
            is ActiveLiveSource.CombinedM3uSource -> {
                val profile = combinedM3uProfileDao?.getById(source.profileId)
                    ?: error("Cannot export Active Live Source: combined profile ${source.profileId} is missing")
                val memberProviders = combinedM3uProfileMemberDao?.getForProfileSync(profile.id)
                    ?.sortedWith(compareBy({ member -> member.priority }, { member -> member.id }))
                    ?.map { member ->
                        referencesById[member.providerId]
                            ?: error("Cannot export Active Live Source: profile '${profile.name}' references missing provider ${member.providerId}")
                    }
                    ?: error("Cannot export Active Live Source: combined profile members are unavailable")
                ActiveLiveSourceBackup(
                    type = "combined_m3u",
                    combinedProfileName = profile.name,
                    combinedProfileProviders = memberProviders
                )
            }
        }
    }

    private suspend fun Map<String, Long>.toPortableVariantSelections(
        referencesById: Map<Long, BackupProviderReference>,
        unresolved: MutableList<String>,
        label: String,
        contentType: ContentType
    ): List<PortableVariantSelectionReference> = mapNotNull { (key, rawItemId) ->
        val separator = key.indexOf('|')
        val providerId = key.substringBefore('|').toLongOrNull()
        val logicalGroupId = key.substringAfter('|', "")
        if (separator <= 0 || providerId == null || logicalGroupId.isBlank() || rawItemId <= 0L) {
            unresolved += "$label variant selection '$key' is malformed"
            return@mapNotNull null
        }
        val provider = referencesById[providerId]
        if (provider == null) {
            unresolved += "$label variant selection provider $providerId was not found during export"
            return@mapNotNull null
        }
        val resolvedVariant = when (contentType) {
            ContentType.LIVE -> channelDao.getById(rawItemId)
                ?.takeIf { it.providerId == providerId }
                ?.takeIf { it.streamId > 0L }
                ?.let { Triple(it.streamId.toString(), ContentType.LIVE, it.categoryId) }
            ContentType.MOVIE -> movieDao.getById(rawItemId)
                ?.takeIf { it.providerId == providerId }
                ?.takeIf { it.streamId > 0L }
                ?.let { Triple(it.streamId.toString(), ContentType.MOVIE, it.categoryId) }
                ?: seriesDao.getById(rawItemId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { series -> series.remoteKey()?.let { Triple(it, ContentType.SERIES, series.categoryId) } }
            ContentType.SERIES -> seriesDao.getById(rawItemId)
                ?.takeIf { it.providerId == providerId }
                ?.let { series -> series.remoteKey()?.let { Triple(it, ContentType.SERIES, series.categoryId) } }
            ContentType.SERIES_EPISODE,
            ContentType.VOD -> null
        }
        if (resolvedVariant == null) {
            unresolved += "$label variant selection $key could not be resolved during export"
            return@mapNotNull null
        }
        PortableVariantSelectionReference(
            provider = provider,
            logicalGroupId = logicalGroupId,
            rawItemId = rawItemId,
            remoteItemId = resolvedVariant.first,
            contentType = resolvedVariant.second,
            remoteCategoryId = resolvedVariant.third
        )
    }

    private data class QueuedPortableRestore(
        val jobId: String,
        val pendingCount: Int,
        val unresolvedCount: Int,
        val affectedProviders: List<BackupProviderReference>
    )

    private data class ReplaceScopePayload(
        val provider: BackupProviderReference?,
        val targetSection: String,
        val contentType: ContentType? = null
    )

    private suspend fun queuePortableRestoreItems(
        backupData: BackupData,
        plan: BackupImportPlan,
        restoreKey: String,
        storedProviders: List<Provider>
    ): QueuedPortableRestore? {
        val legacyProviders = backupData.providers.orEmpty().associate { provider ->
            provider.id to provider.toBackupProviderReference()
        }
        val legacyGroupsById = backupData.virtualGroups.orEmpty().associateBy { it.id }
        val allLegacyFavoritePayloads = mutableListOf<Triple<Long?, PortableFavoriteBackup, Boolean>>()
        if (backupData.version < 14 && plan.importSavedLibrary) {
            backupData.favorites.orEmpty().forEach { favorite ->
                val provider = legacyProviders[favorite.providerId] ?: return@forEach
                val reference = PortableContentReference(
                    provider = provider,
                    contentType = favorite.contentType,
                    remoteContentId = favorite.remoteContentId
                        ?: "$LEGACY_LOCAL_ID_PREFIX${favorite.contentId}"
                )
                val localProviderId = storedProviders.findUnambiguousPortableProvider(provider)?.id
                val resolved = localProviderId != null && resolvePortableContentId(
                    localProviderId,
                    favorite.contentType,
                    favorite.remoteContentId,
                    favorite.contentId
                ) != null
                allLegacyFavoritePayloads += Triple(
                    favorite.groupId,
                    PortableFavoriteBackup(reference, favorite.position, favorite.addedAt),
                    resolved
                )
            }
        }
        val unresolvedFavoriteScopes = allLegacyFavoritePayloads.filterNot { it.third }
            .map { (_, backup) -> backup.content.provider.stableIdentityKey() to backup.content.contentType }
            .toSet()
        val legacyFavoritePayloads = allLegacyFavoritePayloads.filter { (_, backup, resolved) ->
            !resolved || (
                plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING &&
                    backup.content.provider.stableIdentityKey() to backup.content.contentType in unresolvedFavoriteScopes
                )
        }.map { (groupId, backup) -> groupId to backup }
        val legacyCustomGroupPayloads = if (backupData.version < 14 && plan.importSavedLibrary) {
            legacyGroupsById.values.mapNotNull { group ->
                val provider = legacyProviders[group.providerId] ?: return@mapNotNull null
                val members = legacyFavoritePayloads.filter { (groupId) -> groupId == group.id }
                    .map { (_, favorite) -> favorite }
                if (members.isEmpty()) return@mapNotNull null
                PortableCustomGroupBackup(
                    provider = provider,
                    contentType = group.contentType,
                    name = group.name,
                    icon = group.iconEmoji,
                    position = group.position,
                    createdAt = group.createdAt,
                    members = members
                )
            }
        } else emptyList()
        val allLegacyHistoryPayloads = mutableListOf<Pair<PortablePlaybackHistoryBackup, Boolean>>()
        if (backupData.version < 14 && plan.importPlaybackHistory) {
            backupData.playbackHistory.orEmpty().forEach { history ->
                val provider = legacyProviders[history.providerId] ?: return@forEach
                val payload = PortablePlaybackHistoryBackup(
                    content = PortableContentReference(
                        provider = provider,
                        contentType = history.contentType,
                        remoteContentId = history.remoteContentId
                            ?: "$LEGACY_LOCAL_ID_PREFIX${history.contentId}",
                        parentRemoteContentId = history.remoteSeriesId,
                        name = history.title,
                        urlFallback = history.streamUrl
                    ),
                    resumePositionMs = history.resumePositionMs,
                    totalDurationMs = history.totalDurationMs,
                    lastWatchedAt = history.lastWatchedAt,
                    watchCount = history.watchCount,
                    watchedStatus = history.watchedStatus.name,
                    posterUrl = history.posterUrl,
                    seasonNumber = history.seasonNumber,
                    episodeNumber = history.episodeNumber
                )
                val localProviderId = storedProviders.findUnambiguousPortableProvider(provider)?.id
                val resolved = localProviderId != null && resolvePortableHistoryIdentity(
                    localProviderId,
                    history
                ) != null
                allLegacyHistoryPayloads += payload to resolved
            }
        }
        val unresolvedHistoryProviders = allLegacyHistoryPayloads.filterNot { it.second }
            .map { it.first.content.provider.stableIdentityKey() }
            .toSet()
        val legacyHistoryPayloads = allLegacyHistoryPayloads.filter { (backup, resolved) ->
            !resolved || (
                plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING &&
                    backup.content.provider.stableIdentityKey() in unresolvedHistoryProviders
                )
        }.map { it.first }
        val contentPayloads = buildList<Pair<String, Any>> {
            if (plan.importSavedLibrary) {
                backupData.portableFavorites.orEmpty().forEach { add(RESTORE_SECTION_FAVORITES to it) }
                backupData.portableCustomGroups.orEmpty().forEach { add(RESTORE_SECTION_CUSTOM_GROUPS to it) }
                backupData.portableProtectedContent.orEmpty().forEach { add(RESTORE_SECTION_PROTECTED_CONTENT to it) }
                backupData.portableHiddenContent.orEmpty().forEach { add(RESTORE_SECTION_HIDDEN_CONTENT to it) }
                backupData.portableManualEpgMappings.orEmpty().forEach { add(RESTORE_SECTION_MANUAL_EPG to it) }
                legacyFavoritePayloads.filter { (groupId) -> groupId == null }
                    .forEach { (_, favorite) -> add(RESTORE_SECTION_FAVORITES to favorite) }
                legacyCustomGroupPayloads.forEach { add(RESTORE_SECTION_CUSTOM_GROUPS to it) }
            }
            if (plan.importPlaybackHistory) {
                backupData.portablePlaybackHistory.orEmpty().forEach { add(RESTORE_SECTION_PLAYBACK_HISTORY to it) }
                legacyHistoryPayloads.forEach { add(RESTORE_SECTION_PLAYBACK_HISTORY to it) }
            }
            if (plan.importPreferences) {
                backupData.portableProviderPreferences?.hiddenCategories.orEmpty()
                    .forEach { add(RESTORE_SECTION_HIDDEN_CATEGORIES to it) }
                backupData.portableSearchHistory.orEmpty().forEach { add(RESTORE_SECTION_SEARCH_HISTORY to it) }
                backupData.portableContentPreferences.orEmpty().forEach { add(RESTORE_SECTION_CONTENT_PREFERENCES to it) }
                backupData.portableVariantChoices.orEmpty().forEach { add(RESTORE_SECTION_VARIANT_CHOICES to it) }
            }
            if (plan.importMultiViewPresets) {
                backupData.portableMultiViewPresetsV14.orEmpty().forEach { add(RESTORE_SECTION_MULTIVIEW to it) }
            }
            if (plan.importRecordingSchedules) {
                backupData.scheduledRecordings.orEmpty().filter { it.channel != null }
                    .forEach { add(RESTORE_SECTION_RECORDING_SCHEDULES to it) }
            }
        }
        val backupProviderReferences = buildSet {
            addAll(backupData.portableProviderPreferences?.providers.orEmpty())
            contentPayloads.forEach { (_, payload) ->
                when (payload) {
                    is PortableCustomGroupBackup -> add(payload.provider)
                    is PortableSearchHistoryBackup -> payload.provider?.let(::add)
                    is PortableFavoriteBackup -> add(payload.content.provider)
                    is PortablePlaybackHistoryBackup -> add(payload.content.provider)
                    is PortableProtectedContentBackup -> add(payload.content.provider)
                    is PortableHiddenContentBackup -> add(payload.content.provider)
                    is PortableContentPreferenceBackup -> add(payload.content.provider)
                    is PortableVariantChoiceBackup -> add(payload.selectedContent.provider)
                    is PortableManualEpgMappingV14Backup -> add(payload.content.provider)
                    is PortableMultiViewPresetV14Backup -> addAll(payload.channels.map { it.provider })
                    is PortableCategoryReference -> add(payload.provider)
                    is ScheduledRecordingBackup -> payload.channel?.provider?.let(::add)
                }
            }
        }
        val replacementMarkers = if (plan.conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            buildList<Pair<String, Any>> {
                val persistedContentTypes = listOf(
                    ContentType.LIVE,
                    ContentType.MOVIE,
                    ContentType.SERIES,
                    ContentType.SERIES_EPISODE
                )
                backupProviderReferences.forEach { provider ->
                    if (plan.importSavedLibrary) {
                        persistedContentTypes.forEach { type ->
                            add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_FAVORITES, type))
                            add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_CUSTOM_GROUPS, type))
                            add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_PROTECTED_CONTENT, type))
                        }
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_HIDDEN_CONTENT, ContentType.LIVE))
                        ContentType.entries.forEach { type ->
                            add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_HIDDEN_CATEGORIES, type))
                        }
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_MANUAL_EPG, ContentType.LIVE))
                    }
                    if (plan.importPlaybackHistory) persistedContentTypes.forEach { type ->
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_PLAYBACK_HISTORY, type))
                    }
                    if (plan.importPreferences) {
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_SEARCH_HISTORY))
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_CONTENT_PREFERENCES, ContentType.LIVE))
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_VARIANT_CHOICES))
                    }
                    if (plan.importRecordingSchedules) {
                        add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(provider, RESTORE_SECTION_RECORDING_SCHEDULES, ContentType.LIVE))
                    }
                }
                if (plan.importMultiViewPresets) {
                    add(RESTORE_SECTION_REPLACE_SCOPE to ReplaceScopePayload(null, RESTORE_SECTION_MULTIVIEW))
                }
            }
        } else emptyList()
        val pendingPayloads = replacementMarkers + contentPayloads
        if (pendingPayloads.isEmpty()) return null
        val ledgerDao = backupRestoreLedgerDao ?: if (backupData.version < 14) {
            // Compatibility for callers built against the pre-ledger constructor. Production
            // schema 77 always supplies the DAO; unresolved legacy references remain reported.
            return null
        } else {
            error("Durable backup restore storage is unavailable")
        }
        val now = System.currentTimeMillis()
        val items = pendingPayloads.map { (section, payload) ->
            val reference = when (payload) {
                is PortableFavoriteBackup -> payload.content
                is PortableCustomGroupBackup -> null
                is PortablePlaybackHistoryBackup -> payload.content
                is PortableProtectedContentBackup -> payload.content
                is PortableHiddenContentBackup -> payload.content
                is PortableContentPreferenceBackup -> payload.content
                is PortableVariantChoiceBackup -> payload.selectedContent
                is PortableManualEpgMappingV14Backup -> payload.content
                is PortableMultiViewPresetV14Backup -> null
                is PortableCategoryReference -> payload
                is ScheduledRecordingBackup -> payload.channel
                is PortableSearchHistoryBackup -> null
                is ReplaceScopePayload -> null
                else -> error("Unsupported portable restore payload ${payload::class.java.simpleName}")
            }
            val contentReference = reference as? PortableContentReference
            val providerReference = when (payload) {
                is PortableCustomGroupBackup -> payload.provider
                is PortableSearchHistoryBackup -> payload.provider
                is PortableMultiViewPresetV14Backup -> null
                is PortableCategoryReference -> payload.provider
                is ScheduledRecordingBackup -> payload.channel?.provider
                is ReplaceScopePayload -> payload.provider
                else -> checkNotNull(contentReference).provider
            }
            val providerKey = providerReference?.stableIdentityKey() ?: GLOBAL_RESTORE_PROVIDER_KEY
            val stableKey = when (payload) {
                is PortableCustomGroupBackup ->
                    "$providerKey|${payload.contentType.name}:group:${payload.name.trim().lowercase(Locale.ROOT)}"
                is PortableCategoryReference ->
                    "$providerKey|${payload.type.name}:category:${payload.remoteCategoryId ?: payload.name.trim().lowercase(Locale.ROOT)}"
                is PortableSearchHistoryBackup ->
                    "$providerKey|search:${payload.contentScope}:${payload.query.trim().lowercase(Locale.ROOT)}"
                is PortableMultiViewPresetV14Backup -> "$providerKey|multiview:${payload.name}"
                is ScheduledRecordingBackup -> "$providerKey|recording:${payload.recurringRuleId ?: "${payload.scheduledStartMs}:${payload.channel?.remoteContentId}"}"
                is ReplaceScopePayload -> "$providerKey|scope:${payload.targetSection}:${payload.contentType?.name.orEmpty()}"
                else -> "$providerKey|${contentReference!!.contentType.name}:${contentReference.remoteContentId}"
            }
            BackupRestoreItemEntity(
                jobId = restoreKey,
                providerIdentityKey = providerKey,
                localProviderId = providerReference?.let { portable ->
                    storedProviders.findUnambiguousPortableProvider(portable)?.id
                },
                section = section,
                contentType = contentReference?.contentType?.name
                    ?: (payload as? PortableCustomGroupBackup)?.contentType?.name
                    ?: (payload as? ReplaceScopePayload)?.contentType?.name,
                stableReferenceKey = stableKey,
                referenceJson = gson.toJson(reference ?: providerReference),
                payloadJson = gson.toJson(payload),
                createdAt = now,
                updatedAt = now
            )
        }
        ledgerDao.insertLedger(
            BackupRestoreJobEntity(
                id = restoreKey,
                restoreKey = restoreKey,
                backupVersion = backupData.version,
                conflictStrategy = plan.conflictStrategy.name,
                status = RESTORE_STATE_WAITING_FOR_SYNC,
                createdAt = now,
                updatedAt = now
            ),
            items
        )
        return QueuedPortableRestore(
            jobId = restoreKey,
            pendingCount = items.size,
            unresolvedCount = 0,
            affectedProviders = buildList {
                pendingPayloads.forEach { (_, payload) ->
                when (payload) {
                    is PortableCustomGroupBackup -> add(payload.provider)
                    is PortableSearchHistoryBackup -> payload.provider?.let(::add)
                    is PortableFavoriteBackup -> add(payload.content.provider)
                    is PortablePlaybackHistoryBackup -> add(payload.content.provider)
                    is PortableProtectedContentBackup -> add(payload.content.provider)
                    is PortableHiddenContentBackup -> add(payload.content.provider)
                    is PortableContentPreferenceBackup -> add(payload.content.provider)
                    is PortableVariantChoiceBackup -> add(payload.selectedContent.provider)
                    is PortableManualEpgMappingV14Backup -> add(payload.content.provider)
                    is PortableMultiViewPresetV14Backup -> addAll(payload.channels.map { it.provider })
                    is PortableCategoryReference -> add(payload.provider)
                    is ScheduledRecordingBackup -> payload.channel?.provider?.let(::add)
                    is ReplaceScopePayload -> payload.provider?.let(::add)
                }
                }
            }.distinct()
        )
    }

    internal suspend fun buildPortableProviderPreferences(
        providers: List<Provider>
    ): PortableProviderPreferencesBackup {
        val channels = channelDao
        val referencesById = providers.associate { it.id to it.toBackupProviderReference() }
        val unresolved = mutableListOf<String>()
        val categoriesByProvider = providers.associate { provider ->
            provider.id to ContentType.entries.flatMap { type ->
                categoryRepository.getCategories(provider.id).first().filter { it.type == type }
            }
        }
        // Older app versions persisted -1 when there was no active provider. It is a
        // sentinel, not a provider reference, and must not make an otherwise valid
        // backup partial on restore.
        val activeProviderId = preferencesRepository.lastActiveProviderId.first()?.takeIf { it > 0L }
        val activeProvider = activeProviderId?.let(referencesById::get).also { reference ->
            if (activeProviderId != null && reference == null) {
                unresolved += "Active provider id $activeProviderId was not found during export"
            }
        }
        val guideId = preferencesRepository.guideDefaultCategoryId.first()
        val guideDefaultCategory = guideId
            ?.takeUnless { it == 0L }
            ?.takeUnless { it in PORTABLE_VIRTUAL_CATEGORY_IDS }
            ?.let { categoryId ->
                val preferredProvider = activeProviderId?.let(categoriesByProvider::get)
                    ?.filter { it.id == categoryId }
                    ?.singleOrNull()
                val match = preferredProvider ?: categoriesByProvider.entries
                    .mapNotNull { (providerId, categories) ->
                        categories.filter { it.id == categoryId }.singleOrNull()?.let { providerId to it }
                    }
                    .singleOrNull()
                    ?.second
                val providerId = when {
                    preferredProvider != null -> activeProviderId
                    else -> categoriesByProvider.entries.firstOrNull { (_, categories) ->
                        categories.any { it.id == categoryId && it == match }
                    }?.key
                }
                providerId?.let { id ->
                    match?.let { category ->
                        referencesById[id]?.let { provider ->
                            PortableCategoryReference(provider, category.name, category.type, category.id)
                        }
                    }
                }
            }.also { reference ->
                if (guideId != null &&
                    guideId != 0L &&
                    guideId !in PORTABLE_VIRTUAL_CATEGORY_IDS &&
                    reference == null
                ) {
                    unresolved += "Guide category id $guideId was not found unambiguously during export"
                }
            }
        val homeDefaultId = preferencesRepository.defaultCategoryId.first()
        val homeDefaultCategory = homeDefaultId
            ?.takeUnless { it in PORTABLE_VIRTUAL_CATEGORY_IDS }
            ?.let { categoryId ->
                val preferredProvider = activeProviderId?.let(categoriesByProvider::get)
                    ?.filter { it.id == categoryId }
                    ?.singleOrNull()
                val matches = categoriesByProvider.entries.mapNotNull { (providerId, categories) ->
                    categories.firstOrNull { it.id == categoryId }?.let { providerId to it }
                }
                val match = preferredProvider ?: matches.singleOrNull()?.second
                val providerId = if (preferredProvider != null) activeProviderId else matches.singleOrNull()?.first
                providerId?.let { id ->
                    match?.let { category ->
                        referencesById[id]?.let { provider ->
                            PortableCategoryReference(provider, category.name, category.type, category.id)
                        }
                    }
                }
            }.also { reference ->
                if (homeDefaultId != null && homeDefaultId !in PORTABLE_VIRTUAL_CATEGORY_IDS && reference == null) {
                    unresolved += "Home default category id $homeDefaultId was not found unambiguously during export"
                }
            }
        val promotedGroups = preferencesRepository.promotedLiveGroupIds.first().mapNotNull { groupId ->
            virtualGroupDao.getById(groupId)?.let { group ->
                referencesById[group.providerId]?.let { provider ->
                    PortableVirtualGroupReference(provider, group.name, group.contentType)
                }
            } ?: run {
                unresolved += "Promoted group id $groupId was not found during export"
                null
            }
        }
        val hiddenChannels = providers.flatMap { provider ->
            preferencesRepository.getHiddenChannelIds(provider.id).first().mapNotNull { channelId ->
                channels.getById(channelId)?.takeIf { it.providerId == provider.id }?.let { channel ->
                    referencesById[provider.id]?.let { reference ->
                        PortableChannelReference(
                            reference,
                            channel.streamId,
                            channel.name,
                            channel.streamUrl,
                            channel.categoryId
                        )
                    }
                } ?: run {
                    unresolved += "Hidden channel id $channelId for provider ${provider.name} was not found during export"
                    null
                }
            }
        }
        val hiddenCategories = providers.flatMap { provider ->
            val categories = categoriesByProvider[provider.id].orEmpty()
            ContentType.entries.flatMap { type ->
                preferencesRepository.getHiddenCategoryIds(provider.id, type).first().mapNotNull { categoryId ->
                    categories.firstOrNull { it.id == categoryId && it.type == type }?.let { category ->
                        referencesById[provider.id]?.let { reference ->
                            PortableCategoryReference(reference, category.name, type, category.id)
                        }
                    } ?: run {
                        unresolved += "Hidden ${type.name} category id $categoryId for provider ${provider.name} was not found during export"
                        null
                    }
                }
            }
        }
        val pinnedCategories = providers.flatMap { provider ->
            val categories = categoriesByProvider[provider.id].orEmpty()
            ContentType.entries.flatMap { type ->
                (preferencesRepository.getPinnedCategoryIds(provider.id, type) ?: flowOf(emptySet())).first().mapNotNull { categoryId ->
                    categories.firstOrNull { it.id == categoryId && it.type == type }?.let { category ->
                        referencesById[provider.id]?.let { reference ->
                            PortableCategoryReference(reference, category.name, type, category.id)
                        }
                    } ?: run {
                        unresolved += "Pinned ${type.name} category id $categoryId for provider ${provider.name} was not found during export"
                        null
                    }
                }
            }
        }
        val categorySortModes = providers.flatMap { provider ->
            referencesById[provider.id]?.let { reference ->
                ContentType.entries.map { type ->
                    PortableCategorySortReference(
                        provider = reference,
                        type = type,
                        mode = (preferencesRepository.getCategorySortMode(provider.id, type)
                            ?: flowOf(com.streamvault.domain.model.CategorySortMode.DEFAULT)).first().name
                    )
                }
            }.orEmpty()
        }
        val epgTimeShifts = (preferencesRepository.epgTimeShiftsByProvider ?: flowOf(emptyMap()))
            .first().mapNotNull { (providerId, minutes) ->
                referencesById[providerId]?.let { reference ->
                    PortableEpgTimeShiftReference(reference, minutes)
                } ?: run {
                    unresolved += "EPG time shift provider $providerId was not found during export"
                    null
                }
            }
        val liveVariantSelections = (preferencesRepository.liveVariantSelections ?: flowOf(emptyMap()))
            .first()
            .toPortableVariantSelections(referencesById, unresolved, "Live", ContentType.LIVE)
        val vodVariantSelections = (preferencesRepository.vodVariantSelections ?: flowOf(emptyMap()))
            .first()
            .toPortableVariantSelections(referencesById, unresolved, "VOD", ContentType.MOVIE)
        val channelPreferences = channelPreferenceDao?.getAllSync().orEmpty().mapNotNull { preference ->
            val channel = channels.getById(preference.channelId)
            val provider = channel?.let { referencesById[it.providerId] }
            val aspectRatio = preference.aspectRatio?.trim()?.takeIf { it.isNotEmpty() }
            val audioVideoOffsetMs = preference.audioVideoOffsetMs?.coerceIn(-2_000, 2_000)
            if (channel == null || provider == null) {
                unresolved += "Channel preference for channel ${preference.channelId} was not found during export"
                null
            } else if (aspectRatio == null && audioVideoOffsetMs == null) {
                null
            } else {
                PortableChannelPreferenceReference(
                    channel = PortableChannelReference(
                        provider = provider,
                        streamId = channel.streamId,
                        name = channel.name,
                        streamUrl = channel.streamUrl,
                        remoteCategoryId = channel.categoryId
                    ),
                    aspectRatio = aspectRatio,
                    audioVideoOffsetMs = audioVideoOffsetMs
                )
            }
        }
        return PortableProviderPreferencesBackup(
            providers = referencesById.values.toList(),
            activeProvider = activeProvider,
            guideDefaultCategory = guideDefaultCategory,
            guideDefaultVirtualCategoryId = guideId?.takeIf { it in PORTABLE_VIRTUAL_CATEGORY_IDS },
            guideDefaultCategorySpecified = guideId != null && guideId != 0L,
            promotedLiveGroups = promotedGroups.distinct(),
            hiddenChannels = hiddenChannels.distinct(),
            hiddenCategories = hiddenCategories.distinct(),
            pinnedCategories = pinnedCategories.distinct(),
            pinnedCategoriesSpecified = true,
            categorySortModes = categorySortModes.distinct(),
            categorySortModesSpecified = true,
            epgTimeShifts = epgTimeShifts.distinct(),
            epgTimeShiftsSpecified = true,
            liveVariantSelections = liveVariantSelections.distinct(),
            liveVariantSelectionsSpecified = true,
            vodVariantSelections = vodVariantSelections.distinct(),
            vodVariantSelectionsSpecified = true,
            unresolvedReferences = unresolved.distinct(),
            channelPreferences = channelPreferences.distinct(),
            channelPreferencesSpecified = channelPreferenceDao != null,
            homeDefaultCategory = homeDefaultCategory,
            homeDefaultVirtualCategoryId = homeDefaultId?.takeIf { it in PORTABLE_VIRTUAL_CATEGORY_IDS },
            homeDefaultCategorySpecified = homeDefaultId != null
        )
    }

    /**
     * v11 stores typed snapshots as the authoritative provider section. Keep the legacy
     * projection out of parsing/checksum verification, then materialize it only for the older
     * restore code that still expects the compatibility envelope.
     */
    private fun BackupData.withLegacyProviderProjection(): BackupData {
        val snapshots = providerSnapshots
        // Treat an explicitly empty legacy projection like an omitted one. This keeps v11's
        // typed section authoritative while still honoring non-empty v0-10 provider payloads.
        val projectedProviders = if (!providers.isNullOrEmpty()) {
            providers.orEmpty()
        } else if (snapshots != null) {
            snapshots.map { snapshot ->
                snapshot.configuration().toLegacyProvider(
                    snapshot.provider,
                    snapshot.accountRuntime ?: ProviderAccountRuntime()
                )
            }
        } else {
            return this
        }
        val hydratedProviders = projectedProviders.map { provider ->
            val credentials = providerCredentials.orEmpty().firstOrNull { credential ->
                normalizeProviderServerUrl(credential.serverUrl) == normalizeProviderServerUrl(provider.serverUrl) &&
                    credential.username.trim() == provider.username.trim() &&
                    (credential.providerType == null || credential.providerType == provider.type)
            }
            if (credentials == null || provider.password.isNotBlank()) {
                provider
            } else {
                provider.copy(password = credentials.password)
            }
        }
        return copy(providers = hydratedProviders)
    }

    private fun deleteFileUriTarget(uriString: String) {
        if (uriString.isFileUriString()) {
            uriString.toFileUriTarget()?.delete()
        }
    }

    private fun BackupData.isStructurallyEmpty(): Boolean =
        preferences.isNullOrEmpty() &&
            providers.isNullOrEmpty() &&
            providerSnapshots.isNullOrEmpty() &&
            providerCredentials.isNullOrEmpty() &&
            favorites.isNullOrEmpty() &&
            virtualGroups.isNullOrEmpty() &&
            playbackHistory.isNullOrEmpty() &&
            multiViewPresets.orEmpty().all { it.value.isEmpty() } &&
            portableMultiViewPresets.orEmpty().all { it.value.isEmpty() } &&
            protectedCategories.isNullOrEmpty() &&
            scheduledRecordings.isNullOrEmpty() &&
            portableProviderPreferences == null &&
            recordingStorage == null &&
            epgSources.isNullOrEmpty() &&
            providerEpgAssignments.isNullOrEmpty() &&
            manualEpgMappings.isNullOrEmpty() &&
            m3uClassificationOverrides.isNullOrEmpty() &&
            m3uClassificationRules.isNullOrEmpty() &&
            programReminders.isNullOrEmpty() &&
            combinedM3uProfiles.isNullOrEmpty() &&
            activeLiveSource == null &&
            portableFavorites.isNullOrEmpty() &&
            portableCustomGroups.isNullOrEmpty() &&
            portablePlaybackHistory.isNullOrEmpty() &&
            portableProtectedContent.isNullOrEmpty() &&
            portableSearchHistory.isNullOrEmpty() &&
            portableHiddenContent.isNullOrEmpty()
            && portableContentPreferences.isNullOrEmpty()
            && portableVariantChoices.isNullOrEmpty()
            && portableManualEpgMappings.isNullOrEmpty()
            && portableMultiViewPresetsV14.isNullOrEmpty()

    private suspend fun capturePreferenceSnapshot(providerEntities: List<ProviderEntity>): Map<String, String> {
        val parentalPinBackup = preferencesRepository.exportParentalPinBackup()
        return buildMap {
            put("parentalControlLevel", preferencesRepository.parentalControlLevel.first().toString())
            put("parentalPinHash", parentalPinBackup?.hash.orEmpty())
            put("parentalPinSalt", parentalPinBackup?.saltBase64.orEmpty())
            put("appLanguage", preferencesRepository.appLanguage.first())
            put("appTimeFormat", preferencesRepository.appTimeFormat.first().storageValue)
            put("defaultViewMode", preferencesRepository.defaultViewMode.first().orEmpty())
            put("appLandingDestination", preferencesRepository.appLandingDestination.first().storageValue)
            put("appTopLevelDestinations", preferencesRepository.appTopLevelDestinations.first().joinToString(",") { it.storageValue })
            put("appHomeDashboardShelves", preferencesRepository.appHomeDashboardShelves.first().joinToString(",") { it.storageValue })
            put("remoteShortcutPreferences", gson.toJson(preferencesRepository.remoteShortcutPreferences.first()))
            put("liveTvCategoryFilters", preferencesRepository.liveTvCategoryFilters.first().joinToString("\n"))
            put("liveTvQuickFilterVisibility", preferencesRepository.liveTvQuickFilterVisibility.first() ?: "always")
            put("liveTvChannelMode", preferencesRepository.liveTvChannelMode.first().orEmpty())
            put("showLiveSourceSwitcher", preferencesRepository.showLiveSourceSwitcher.first().toString())
            put("showFavoritesCategory", preferencesRepository.showFavoritesCategory.first().toString())
            put("showAllChannelsCategory", preferencesRepository.showAllChannelsCategory.first().toString())
            put("showRecentChannelsCategory", preferencesRepository.showRecentChannelsCategory.first().toString())
            put("hideDecorativeLiveRows", preferencesRepository.hideDecorativeLiveRows.first().toString())
            put("liveChannelNumberingMode", preferencesRepository.liveChannelNumberingMode.first().name)
            put("liveChannelGroupingMode", preferencesRepository.liveChannelGroupingMode.first().name)
            put("groupedChannelLabelMode", preferencesRepository.groupedChannelLabelMode.first().name)
            put("liveVariantPreferenceMode", preferencesRepository.liveVariantPreferenceMode.first().name)
            put("vodViewMode", preferencesRepository.vodViewMode.first().orEmpty())
            put("vodInfiniteScroll", preferencesRepository.vodInfiniteScroll.first().toString())
            put("vodCategoryLoadMode", preferencesRepository.vodCategoryLoadMode.first().storageValue)
            put("vodDuplicateHandlingMode", preferencesRepository.vodDuplicateHandlingMode.first().storageValue)
            put("vodVariantPreferenceMode", preferencesRepository.vodVariantPreferenceMode.first().storageValue)
            put("playerMediaSessionEnabled", preferencesRepository.playerMediaSessionEnabled.first().toString())
            put("playerFastRetryOnTransientFailures", preferencesRepository.playerFastRetryOnTransientFailures.first().toString())
            put("playerAudioDecoderMode", preferencesRepository.playerAudioDecoderMode.first().name)
            put("playerVideoDecoderMode", preferencesRepository.playerVideoDecoderMode.first().name)
            put("playerPlaybackBufferMode", preferencesRepository.playerPlaybackBufferMode.first().name)
            put("playerAudioOutputPreference", preferencesRepository.playerAudioOutputPreference.first().name)
            put("playerCompatibilityMemoryEnabled", preferencesRepository.playerCompatibilityMemoryEnabled.first().toString())
            put("playerSurfaceMode", preferencesRepository.playerSurfaceMode.first().name)
            put("playerLiveStreamFormatMode", preferencesRepository.playerLiveStreamFormatMode.first().name)
            put("playerVodHttpProtocolMode", preferencesRepository.playerVodHttpProtocolMode.first().name)
            put("playerPlaybackSpeed", preferencesRepository.playerPlaybackSpeed.first().toString())
            put("playerExternalPlaybackMode", preferencesRepository.playerExternalPlaybackMode.first().storageValue)
            put("playerAudioVideoSyncEnabled", preferencesRepository.playerAudioVideoSyncEnabled.first().toString())
            put("playerAudioVideoOffsetMs", preferencesRepository.playerAudioVideoOffsetMs.first().toString())
            put("playerMuted", preferencesRepository.playerMuted.first().toString())
            put("multiViewPerformanceMode", preferencesRepository.multiViewPerformanceMode.first().orEmpty())
            put("multiViewCenterTwoSlotLayout", preferencesRepository.multiViewCenterTwoSlotLayout.first().toString())
            put("multiViewRespectProviderConnectionLimit", preferencesRepository.multiViewRespectProviderConnectionLimit.first().toString())
            put("preferredAudioLanguage", preferencesRepository.preferredAudioLanguage.first() ?: "auto")
            put("playerSubtitleTextScale", preferencesRepository.playerSubtitleTextScale.first().toString())
            put("playerSubtitleTextColor", preferencesRepository.playerSubtitleTextColor.first().toString())
            put("playerSubtitleBackgroundColor", preferencesRepository.playerSubtitleBackgroundColor.first().toString())
            put("playerLiveTranslationEnabled", preferencesRepository.playerLiveTranslationEnabled.first().toString())
            put("playerLiveTranslationEndpoint", preferencesRepository.playerLiveTranslationEndpoint.first())
            put("playerControlsTimeoutSeconds", preferencesRepository.playerControlsTimeoutSeconds.first().toString())
            put("playerLiveOverlayTimeoutSeconds", preferencesRepository.playerLiveOverlayTimeoutSeconds.first().toString())
            put("playerNoticeTimeoutSeconds", preferencesRepository.playerNoticeTimeoutSeconds.first().toString())
            put("playerDiagnosticsTimeoutSeconds", preferencesRepository.playerDiagnosticsTimeoutSeconds.first().toString())
            put("playerWifiMaxVideoHeight", (preferencesRepository.playerWifiMaxVideoHeight.first() ?: 0).toString())
            put("playerEthernetMaxVideoHeight", (preferencesRepository.playerEthernetMaxVideoHeight.first() ?: 0).toString())
            put("playerTimeshiftEnabled", preferencesRepository.playerTimeshiftEnabled.first().toString())
            put("playerTimeshiftDepthMinutes", preferencesRepository.playerTimeshiftDepthMinutes.first().toString())
            put("playerTimeshiftBackend", preferencesRepository.playerTimeshiftBackend.first().name)
            put("defaultStopPlaybackTimerMinutes", preferencesRepository.defaultStopPlaybackTimerMinutes.first().toString())
            put("defaultIdleStandbyTimerMinutes", preferencesRepository.defaultIdleStandbyTimerMinutes.first().toString())
            put("preventStandbyDuringPlayback", preferencesRepository.preventStandbyDuringPlayback.first().toString())
            put("zapAutoRevert", preferencesRepository.zapAutoRevert.first().toString())
            put("autoPlayNextEpisode", preferencesRepository.autoPlayNextEpisode.first().toString())
            put("autoCheckAppUpdates", preferencesRepository.autoCheckAppUpdates.first().toString())
            put("autoDownloadAppUpdates", preferencesRepository.autoDownloadAppUpdates.first().toString())
            put("recordingWifiOnly", preferencesRepository.recordingWifiOnly.first().toString())
            put("recordingPaddingBeforeMinutes", preferencesRepository.recordingPaddingBeforeMinutes.first().toString())
            put("recordingPaddingAfterMinutes", preferencesRepository.recordingPaddingAfterMinutes.first().toString())
            put("maxConcurrentStreams", preferencesRepository.maxConcurrentStreams.first().toString())
            put("isIncognitoMode", preferencesRepository.isIncognitoMode.first().toString())
            put("useXtreamTextClassification", preferencesRepository.useXtreamTextClassification.first().toString())
            put("xtreamBase64TextCompatibility", preferencesRepository.xtreamBase64TextCompatibility.first().toString())
            put("guideDensity", preferencesRepository.guideDensity.first() ?: "")
            put("guideChannelMode", preferencesRepository.guideChannelMode.first() ?: "")
            put("guideDefaultCategoryId", (preferencesRepository.guideDefaultCategoryId.first() ?: 0L).toString())
            put("guideFavoritesOnly", preferencesRepository.guideFavoritesOnly.first().toString())
            put("guideScheduledOnly", preferencesRepository.guideScheduledOnly.first().toString())
            put("guideAnchorTime", (preferencesRepository.guideAnchorTime.first() ?: 0L).toString())
            put("lastActiveProviderId", (preferencesRepository.lastActiveProviderId.first() ?: -1L).toString())
            put("promotedLiveGroupIds", preferencesRepository.promotedLiveGroupIds.first().sorted().joinToString(","))
            put(RESTORE_SNAPSHOT_PRESET_1, preferencesRepository.getMultiViewPreset(0).first().joinToString(","))
            put(RESTORE_SNAPSHOT_PRESET_2, preferencesRepository.getMultiViewPreset(1).first().joinToString(","))
            put(RESTORE_SNAPSHOT_PRESET_3, preferencesRepository.getMultiViewPreset(2).first().joinToString(","))
            channelPreferenceDao?.getAllSync()?.let { preferences ->
                put(RESTORE_SNAPSHOT_CHANNEL_PREFERENCES, gson.toJson(preferences, CHANNEL_PREFERENCE_ENTITY_LIST_TYPE))
            }
            providerEntities.forEach { provider ->
                put(
                    "hiddenChannels_${provider.id}",
                    preferencesRepository.getHiddenChannelIds(provider.id).first().sorted().joinToString(",")
                )
                ContentType.entries.forEach { type ->
                    put(
                        "hiddenCategories_${provider.id}_${type.name}",
                        preferencesRepository.getHiddenCategoryIds(provider.id, type).first().sorted().joinToString(",")
                    )
                    put(
                        "pinnedCategories_${provider.id}_${type.name}",
                        (preferencesRepository.getPinnedCategoryIds(provider.id, type) ?: flowOf(emptySet()))
                            .first().sorted().joinToString(",")
                    )
                    put(
                        "categorySortMode_${provider.id}_${type.name}",
                        (preferencesRepository.getCategorySortMode(provider.id, type)
                            ?: flowOf(com.streamvault.domain.model.CategorySortMode.DEFAULT)).first().name
                    )
                }
                put(
                    "epgTimeShift_${provider.id}",
                    preferencesRepository.epgTimeShiftMinutes(provider.id).first().toString()
                )
                put(
                    "liveVariantSelections_${provider.id}",
                    (preferencesRepository.liveVariantSelections ?: flowOf(emptyMap()))
                        .first()
                        .filterKeys { it.substringBefore('|').toLongOrNull() == provider.id }
                        .entries
                        .joinToString("\n") { (key, rawItemId) -> "${key.substringAfter('|')}=$rawItemId" }
                )
                put(
                    "vodVariantSelections_${provider.id}",
                    (preferencesRepository.vodVariantSelections ?: flowOf(emptyMap()))
                        .first()
                        .filterKeys { it.substringBefore('|').toLongOrNull() == provider.id }
                        .entries
                        .joinToString("\n") { (key, rawItemId) -> "${key.substringAfter('|')}=$rawItemId" }
                )
            }
        }.also { snapshot ->
            PreferenceBackupRegistry.requirePortableCodecs(snapshot.keys)
        }
    }

    private suspend fun restoreCheckpointPreferenceSnapshot(snapshot: Map<String, String>) {
        restorePreferences(snapshot)
        snapshot[RESTORE_SNAPSHOT_CHANNEL_PREFERENCES]?.let { encoded ->
            restoreChannelPreferenceSnapshot(encoded)
        }
        snapshot["lastActiveProviderId"]?.toLongOrNull()?.let {
            preferencesRepository.setLastActiveProviderId(it)
        }
        restoreCheckpointMultiViewPresets(snapshot)
    }

    private suspend fun restoreChannelPreferenceSnapshot(encoded: String) {
        val dao = channelPreferenceDao ?: return
        val preferences = gson.fromJson<List<ChannelPreferenceEntity>>(
            encoded,
            CHANNEL_PREFERENCE_ENTITY_LIST_TYPE
        ).orEmpty()
        dao.deleteAll()
        preferences.forEach { preference -> dao.upsert(preference) }
    }

    private suspend fun restoreCheckpointMultiViewPresets(snapshot: Map<String, String>) {
        preferencesRepository.setMultiViewPreset(
            0,
            snapshot[RESTORE_SNAPSHOT_PRESET_1].orEmpty().split(",").mapNotNull(String::toLongOrNull)
        )
        preferencesRepository.setMultiViewPreset(
            1,
            snapshot[RESTORE_SNAPSHOT_PRESET_2].orEmpty().split(",").mapNotNull(String::toLongOrNull)
        )
        preferencesRepository.setMultiViewPreset(
            2,
            snapshot[RESTORE_SNAPSHOT_PRESET_3].orEmpty().split(",").mapNotNull(String::toLongOrNull)
        )
    }

    private suspend fun restorePortableMultiViewPresets(
        presets: Map<String, List<PortableChannelReference>>,
        storedProviders: List<Provider>
    ): List<String> {
        val unresolved = mutableListOf<String>()
        listOf("preset_1", "preset_2", "preset_3").forEachIndexed { index, presetName ->
            val presetUnresolved = mutableListOf<String>()
            val channelIds = presets[presetName].orEmpty().mapNotNull { reference ->
                val provider = storedProviders.findUnambiguousPortableProvider(reference.provider)
                if (provider == null) {
                    presetUnresolved += reference.unresolvedLabel("Split-screen channel provider")
                    return@mapNotNull null
                }
                channelDao.getByProviderSync(provider.id)
                    .resolvePortableChannel(reference)
                    ?.id
                    ?: run {
                        presetUnresolved += reference.unresolvedLabel("Split-screen channel")
                        null
                    }
            }
            if (presetUnresolved.isEmpty()) {
                preferencesRepository.setMultiViewPreset(index, channelIds)
            } else {
                unresolved += presetUnresolved
            }
        }
        return unresolved.distinct()
    }

    private suspend fun restorePortableProviderPreferences(
        portable: PortableProviderPreferencesBackup,
        storedProviders: List<Provider>
    ): List<String> {
        // Backups written by older releases may contain this known false-positive warning.
        // It describes a missing active-provider sentinel, not a broken provider reference.
        val unresolved = portable.unresolvedReferences
            .filterNot { it == LEGACY_NO_ACTIVE_PROVIDER_WARNING }
            .toMutableList()
        val channels = channelDao
        fun resolveProvider(reference: BackupProviderReference) =
            storedProviders.findUnambiguousPortableProvider(reference)
        val referencedProviders = buildSet {
            addAll(portable.providers)
            portable.activeProvider?.let(::add)
            portable.guideDefaultCategory?.provider?.let(::add)
            portable.promotedLiveGroups.forEach { add(it.provider) }
            portable.hiddenChannels.forEach { add(it.provider) }
            portable.hiddenCategories.forEach { add(it.provider) }
            portable.pinnedCategories.forEach { add(it.provider) }
            portable.categorySortModes.forEach { add(it.provider) }
            portable.epgTimeShifts.forEach { add(it.provider) }
            portable.liveVariantSelections.forEach { add(it.provider) }
            portable.vodVariantSelections.forEach { add(it.provider) }
            portable.channelPreferences.forEach { add(it.channel.provider) }
        }
        val resolvedProviders = referencedProviders.mapNotNull { reference ->
            resolveProvider(reference)?.let { reference to it }
                ?: run { unresolved += reference.unresolvedLabel("Provider"); null }
        }.toMap()
        val categories = resolvedProviders.values.associate { provider ->
            provider.id to categoryRepository.getCategories(provider.id).first()
        }
        portable.activeProvider?.let { reference ->
            resolveProvider(reference)?.let { provider ->
                preferencesRepository.setLastActiveProviderId(provider.id)
            } ?: run {
                unresolved += reference.unresolvedLabel("Active provider")
            }
        }
        val guideVirtualCategoryId = portable.guideDefaultVirtualCategoryId
        val guideCategoryReference = portable.guideDefaultCategory
        when {
            guideVirtualCategoryId != null ->
                preferencesRepository.setGuideDefaultCategoryId(guideVirtualCategoryId)
            guideCategoryReference != null -> {
                val reference = guideCategoryReference
                val provider = resolveProvider(reference.provider)
                categories[provider?.id].orEmpty()
                    .resolvePortableCategoryId(reference)
                    ?.let { preferencesRepository.setGuideDefaultCategoryId(it) }
                    ?: run { unresolved += reference.unresolvedLabel("Guide category") }
            }
            !portable.guideDefaultCategorySpecified ->
                preferencesRepository.clearGuideDefaultCategoryId()
        }
        when {
            portable.homeDefaultVirtualCategoryId != null -> {
                val virtualCategoryId = portable.homeDefaultVirtualCategoryId
                preferencesRepository.setDefaultCategory(virtualCategoryId!!)
            }
            portable.homeDefaultCategory != null -> {
                val reference = portable.homeDefaultCategory!!
                val provider = resolveProvider(reference.provider)
                categories[provider?.id].orEmpty()
                    .resolvePortableCategoryId(reference)
                    ?.let { categoryId -> preferencesRepository.setDefaultCategory(categoryId) }
                    ?: run { unresolved.add(reference.unresolvedLabel("Home default category")) }
            }
        }
        var promotedCanApply = true
        val promoted = portable.promotedLiveGroups.mapNotNull { reference ->
            val provider = resolveProvider(reference.provider)
            if (provider == null) {
                promotedCanApply = false
                unresolved += reference.unresolvedLabel("Group provider")
                return@mapNotNull null
            }
            virtualGroupDao.getByType(provider.id, reference.contentType.name).first()
                .filter { it.name.equals(reference.name, true) }.singleOrNull()?.id
                ?: run {
                    promotedCanApply = false
                    unresolved += reference.unresolvedLabel("Group")
                    null
                }
        }.toSet()
        if (promotedCanApply) {
            preferencesRepository.setPromotedLiveGroupIds(promoted)
        }
        resolvedProviders.forEach { (reference, provider) ->
            val matchingChannels = portable.hiddenChannels.filter { it.provider == reference }
            val providerChannels = channels.getByProviderSync(provider.id)
            val hiddenChannelUnresolved = mutableListOf<String>()
            val channelIds = matchingChannels.mapNotNull { requested ->
                providerChannels.resolvePortableChannel(requested)?.id
                    ?: run {
                        hiddenChannelUnresolved += requested.unresolvedLabel("Hidden channel")
                        null
                    }
            }.toSet()
            if (hiddenChannelUnresolved.isEmpty()) {
                preferencesRepository.setHiddenChannelIds(provider.id, channelIds)
            } else {
                unresolved += hiddenChannelUnresolved
            }
            ContentType.entries.forEach { type ->
                val requested = portable.hiddenCategories.filter { it.provider == reference && it.type == type }
                val categoryUnresolved = mutableListOf<String>()
                val ids = requested.mapNotNull { categoryReference ->
                    categories[provider.id].orEmpty().resolvePortableCategoryId(categoryReference)
                        ?: run {
                            categoryUnresolved += categoryReference.unresolvedLabel("Hidden ${type.name} category")
                            null
                        }
                }.toSet()
                if (categoryUnresolved.isEmpty()) {
                    preferencesRepository.setHiddenCategoryIds(provider.id, type, ids)
                } else {
                    unresolved += categoryUnresolved
                }
            }
            if (portable.pinnedCategoriesSpecified) {
                ContentType.entries.forEach { type ->
                    val requested = portable.pinnedCategories.filter { it.provider == reference && it.type == type }
                    val categoryUnresolved = mutableListOf<String>()
                    val ids = requested.mapNotNull { categoryReference ->
                        categories[provider.id].orEmpty().resolvePortableCategoryId(categoryReference)
                            ?: run {
                                categoryUnresolved += categoryReference.unresolvedLabel("Pinned ${type.name} category")
                                null
                            }
                    }.toSet()
                    if (categoryUnresolved.isEmpty()) {
                        preferencesRepository.setPinnedCategoryIds(provider.id, type, ids)
                    } else {
                        unresolved += categoryUnresolved
                    }
                }
            }
            if (portable.categorySortModesSpecified) {
                ContentType.entries.forEach { type ->
                    val savedMode = portable.categorySortModes
                        .firstOrNull { it.provider == reference && it.type == type }
                        ?.mode
                        ?.let { mode -> com.streamvault.domain.model.CategorySortMode.entries.firstOrNull { it.name == mode } }
                        ?: com.streamvault.domain.model.CategorySortMode.DEFAULT
                    preferencesRepository.setCategorySortMode(provider.id, type, savedMode)
                }
            }
        }
        if (portable.epgTimeShiftsSpecified) {
            resolvedProviders.forEach { (reference, provider) ->
                val minutes = portable.epgTimeShifts.firstOrNull { it.provider == reference }?.minutes ?: 0
                preferencesRepository.setEpgTimeShiftMinutes(provider.id, minutes)
            }
        }
        if (portable.liveVariantSelectionsSpecified) {
            resolvedProviders.forEach { (reference, provider) ->
                val providerSelections = portable.liveVariantSelections.filter { it.provider == reference }
                val unresolvedForProvider = mutableListOf<String>()
                val selections = providerSelections.mapNotNull { selection ->
                    val rawItemId = resolvePortableVariantItemId(selection, ContentType.LIVE, provider.id)
                    rawItemId?.let { selection.logicalGroupId to it } ?: run {
                        unresolvedForProvider += selection.unresolvedLabel("Live variant selection")
                        null
                    }
                }.toMap()
                if (unresolvedForProvider.isEmpty()) {
                    preferencesRepository.replacePreferredLiveVariants(provider.id, selections)
                } else {
                    unresolved += unresolvedForProvider
                }
            }
        }
        if (portable.vodVariantSelectionsSpecified) {
            resolvedProviders.forEach { (reference, provider) ->
                val providerSelections = portable.vodVariantSelections.filter { it.provider == reference }
                val unresolvedForProvider = mutableListOf<String>()
                val selections = providerSelections.mapNotNull { selection ->
                    val rawItemId = resolvePortableVariantItemId(selection, ContentType.MOVIE, provider.id)
                    rawItemId?.let { selection.logicalGroupId to it } ?: run {
                        unresolvedForProvider += selection.unresolvedLabel("VOD variant selection")
                        null
                    }
                }.toMap()
                if (unresolvedForProvider.isEmpty()) {
                    preferencesRepository.replacePreferredVodVariants(provider.id, selections)
                } else {
                    unresolved += unresolvedForProvider
                }
            }
        }
        if (portable.channelPreferencesSpecified) {
            val preferenceDao = channelPreferenceDao
            if (preferenceDao == null) {
                unresolved += "Per-channel playback preference storage is unavailable"
            } else {
                val resolvedPreferences = portable.channelPreferences.mapNotNull { preference ->
                    val reference = preference.channel
                    val provider = resolveProvider(reference.provider)
                    val channel = provider?.let { resolvedProvider ->
                        channels.getByProviderSync(resolvedProvider.id).resolvePortableChannel(reference)
                    }
                    if (channel == null) {
                        unresolved += reference.unresolvedLabel("Channel playback preference")
                        null
                    } else if (preference.aspectRatio.isNullOrBlank() && preference.audioVideoOffsetMs == null) {
                        null
                    } else {
                        ChannelPreferenceEntity(
                            channelId = channel.id,
                            aspectRatio = preference.aspectRatio?.trim()?.takeIf { it.isNotEmpty() },
                            audioVideoOffsetMs = preference.audioVideoOffsetMs?.coerceIn(-2_000, 2_000)
                        )
                    }
                }
                val preferenceReferenceCount = portable.channelPreferences.size
                val unresolvedPreferenceCount = unresolved.count { it.startsWith("Channel playback preference") }
                if (unresolvedPreferenceCount == 0 && resolvedPreferences.size == preferenceReferenceCount) {
                    preferenceDao.deleteAll()
                    resolvedPreferences.forEach { preference -> preferenceDao.upsert(preference) }
                }
            }
        }
        return unresolved.distinct()
    }

    private suspend fun resolvePortableVariantItemId(
        selection: PortableVariantSelectionReference,
        defaultType: ContentType,
        providerId: Long
    ): Long? {
        val remoteItemId = selection.remoteItemId ?: return selection.rawItemId.takeIf { it > 0L }
        return when (selection.contentType ?: defaultType) {
            ContentType.LIVE -> remoteItemId.toLongOrNull()
                ?.let { channelDao.getByStreamId(providerId, it)?.id }
            ContentType.MOVIE -> remoteItemId.toLongOrNull()
                ?.let { movieDao.getByStreamId(providerId, it)?.id }
            ContentType.SERIES -> resolveSeries(providerId, remoteItemId)?.id
            ContentType.SERIES_EPISODE,
            ContentType.VOD -> null
        }
    }

    private suspend fun restorePreferences(
        prefs: Map<String, String>,
        skipProviderScopedReferences: Boolean = false
    ) {
        prefs["parentalControlLevel"]?.toIntOrNull()?.let {
            preferencesRepository.setParentalControlLevel(it)
        }
        preferencesRepository.restoreParentalPinBackup(
            ParentalPinBackupData(
                hash = prefs["parentalPinHash"].orEmpty(),
                saltBase64 = prefs["parentalPinSalt"].orEmpty()
            ).takeIf { it.hash.isNotBlank() && it.saltBase64.isNotBlank() }
        )
        prefs["appLanguage"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setAppLanguage(it) }
        prefs["appTimeFormat"]?.takeIf { it.isNotBlank() }?.let { savedFormat ->
            preferencesRepository.setAppTimeFormat(
                com.streamvault.domain.model.AppTimeFormat.fromStorage(savedFormat)
            )
        }
        prefs["defaultViewMode"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setDefaultViewMode(it) }
        prefs["appLandingDestination"]?.takeIf { it.isNotBlank() }?.let { savedDestination ->
            preferencesRepository.setAppLandingDestination(
                com.streamvault.domain.model.AppLandingDestination.fromStorage(savedDestination)
            )
        }
        prefs["appTopLevelDestinations"]?.let { encoded ->
            val destinations = encoded
                .split(',')
                .mapNotNull { token -> AppTopLevelDestination.fromStorage(token.trim()) }
            if (destinations.isNotEmpty()) {
                preferencesRepository.setAppTopLevelDestinations(destinations)
            }
        }
        if (prefs.containsKey("appHomeDashboardShelves")) {
            val shelves = prefs["appHomeDashboardShelves"]
                .orEmpty()
                .split(',')
                .mapNotNull { token -> AppHomeDashboardShelf.fromStorage(token.trim()) }
            preferencesRepository.setAppHomeDashboardShelves(shelves)
        }
        prefs["remoteShortcutPreferences"]?.let { encoded ->
            restoreRemoteShortcutPreferences(encoded)
        }
        prefs["liveTvCategoryFilters"]?.let { preferencesRepository.setLiveTvCategoryFilters(it.split('\n')) }
        prefs["liveTvQuickFilterVisibility"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setLiveTvQuickFilterVisibility(it) }
        prefs["liveTvChannelMode"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setLiveTvChannelMode(it) }
        prefs["showLiveSourceSwitcher"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setShowLiveSourceSwitcher(it) }
        prefs["showFavoritesCategory"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setShowFavoritesCategory(it) }
        prefs["showAllChannelsCategory"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setShowAllChannelsCategory(it) }
        prefs["showRecentChannelsCategory"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setShowRecentChannelsCategory(it) }
        prefs["hideDecorativeLiveRows"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setHideDecorativeLiveRows(it) }
        prefs["liveChannelNumberingMode"]?.let { savedMode ->
            preferencesRepository.setLiveChannelNumberingMode(
                com.streamvault.domain.model.ChannelNumberingMode.fromStorage(savedMode)
            )
        }
        prefs["liveChannelGroupingMode"]?.let { savedMode ->
            com.streamvault.domain.model.LiveChannelGroupingMode.entries
                .firstOrNull { it.name == savedMode }
                ?.let { preferencesRepository.setLiveChannelGroupingMode(it) }
        }
        prefs["groupedChannelLabelMode"]?.let { savedMode ->
            com.streamvault.domain.model.GroupedChannelLabelMode.fromStorage(savedMode)
                .let { preferencesRepository.setGroupedChannelLabelMode(it) }
        }
        prefs["liveVariantPreferenceMode"]?.let { savedMode ->
            com.streamvault.domain.model.LiveVariantPreferenceMode.fromStorage(savedMode)
                .let { preferencesRepository.setLiveVariantPreferenceMode(it) }
        }
        prefs["vodViewMode"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setVodViewMode(it) }
        prefs["vodInfiniteScroll"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setVodInfiniteScroll(it) }
        prefs["vodCategoryLoadMode"]?.let { savedMode ->
            preferencesRepository.setVodCategoryLoadMode(
                com.streamvault.domain.model.VodCategoryLoadMode.fromStorage(savedMode)
            )
        }
        prefs["vodDuplicateHandlingMode"]?.let { savedMode ->
            preferencesRepository.setVodDuplicateHandlingMode(
                com.streamvault.domain.model.VodDuplicateHandlingMode.fromStorage(savedMode)
            )
        }
        prefs["vodVariantPreferenceMode"]?.let { savedMode ->
            preferencesRepository.setVodVariantPreferenceMode(
                com.streamvault.domain.model.VodVariantPreferenceMode.fromStorage(savedMode)
            )
        }
        prefs["playerMediaSessionEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerMediaSessionEnabled(it) }
        prefs["playerFastRetryOnTransientFailures"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerFastRetryOnTransientFailures(it) }
        val legacyDecoderMode = prefs["playerDecoderMode"]
            ?.takeIf { it.isNotBlank() }
            ?.let { savedMode ->
                com.streamvault.domain.model.DecoderMode.entries
                    .firstOrNull { entry -> entry.name == savedMode }
            }
        prefs["playerAudioDecoderMode"]
            ?.takeIf { it.isNotBlank() }
            ?.let { savedMode ->
                com.streamvault.domain.model.DecoderMode.entries
                    .firstOrNull { entry -> entry.name == savedMode }
            }
            ?.let { preferencesRepository.setPlayerAudioDecoderMode(it) }
            ?: legacyDecoderMode?.let { preferencesRepository.setPlayerAudioDecoderMode(it) }
        prefs["playerVideoDecoderMode"]
            ?.takeIf { it.isNotBlank() }
            ?.let { savedMode ->
                com.streamvault.domain.model.DecoderMode.entries
                    .firstOrNull { entry -> entry.name == savedMode }
            }
            ?.let { preferencesRepository.setPlayerVideoDecoderMode(it) }
            ?: legacyDecoderMode?.let { preferencesRepository.setPlayerVideoDecoderMode(it) }
        prefs["playerPlaybackBufferMode"]?.let { savedMode ->
            com.streamvault.domain.model.PlaybackBufferMode.entries
                .firstOrNull { it.name == savedMode }
                ?.let { preferencesRepository.setPlayerPlaybackBufferMode(it) }
        }
        prefs["playerAudioOutputPreference"]?.takeIf { it.isNotBlank() }?.let { savedPreference ->
            val preference = com.streamvault.domain.model.AudioOutputPreference.entries
                .firstOrNull { entry -> entry.name == savedPreference }
            if (preference != null) {
                preferencesRepository.setPlayerAudioOutputPreference(preference)
            }
        }
        prefs["playerCompatibilityMemoryEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerCompatibilityMemoryEnabled(it) }
        prefs["playerSurfaceMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val surfaceMode = com.streamvault.domain.model.PlayerSurfaceMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (surfaceMode != null) {
                preferencesRepository.setPlayerSurfaceMode(surfaceMode)
            }
        }
        prefs["playerLiveStreamFormatMode"]?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val formatMode = com.streamvault.domain.model.LiveStreamFormatMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (formatMode != null) {
                preferencesRepository.setPlayerLiveStreamFormatMode(formatMode)
            }
        }
        (prefs["playerVodHttpProtocolMode"] ?: prefs["playerMovieHttpProtocolMode"])?.takeIf { it.isNotBlank() }?.let { savedMode ->
            val protocolMode = com.streamvault.domain.model.VodHttpProtocolMode.entries
                .firstOrNull { entry -> entry.name == savedMode }
            if (protocolMode != null) {
                preferencesRepository.setPlayerVodHttpProtocolMode(protocolMode)
            }
        }
        prefs["playerPlaybackSpeed"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerPlaybackSpeed(it) }
        prefs["playerExternalPlaybackMode"]?.let { savedMode ->
            preferencesRepository.setPlayerExternalPlaybackMode(
                com.streamvault.domain.model.ExternalPlaybackMode.fromStorageValue(savedMode)
            )
        }
        prefs["playerAudioVideoSyncEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerAudioVideoSyncEnabled(it) }
        prefs["playerAudioVideoOffsetMs"]?.toIntOrNull()?.let {
            preferencesRepository.setPlayerAudioVideoOffsetMs(it)
        }
        prefs["playerMuted"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerMuted(it) }
        prefs["multiViewPerformanceMode"]?.takeIf { it.isNotBlank() }
            ?.let { preferencesRepository.setMultiViewPerformanceMode(it) }
        prefs["multiViewCenterTwoSlotLayout"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setMultiViewCenterTwoSlotLayout(it) }
        prefs["multiViewRespectProviderConnectionLimit"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setMultiViewRespectProviderConnectionLimit(it) }
        preferencesRepository.setPreferredAudioLanguage(prefs["preferredAudioLanguage"])
        prefs["playerSubtitleTextScale"]?.toFloatOrNull()?.let { preferencesRepository.setPlayerSubtitleTextScale(it) }
        prefs["playerSubtitleTextColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleTextColor(it) }
        prefs["playerSubtitleBackgroundColor"]?.toIntOrNull()?.let { preferencesRepository.setPlayerSubtitleBackgroundColor(it) }
        prefs["playerLiveTranslationEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerLiveTranslationEnabled(it) }
        prefs["playerLiveTranslationEndpoint"]?.let { preferencesRepository.setPlayerLiveTranslationEndpoint(it) }
        prefs["playerControlsTimeoutSeconds"]?.toIntOrNull()
            ?.let { preferencesRepository.setPlayerControlsTimeoutSeconds(it) }
        prefs["playerLiveOverlayTimeoutSeconds"]?.toIntOrNull()
            ?.let { preferencesRepository.setPlayerLiveOverlayTimeoutSeconds(it) }
        prefs["playerNoticeTimeoutSeconds"]?.toIntOrNull()
            ?.let { preferencesRepository.setPlayerNoticeTimeoutSeconds(it) }
        prefs["playerDiagnosticsTimeoutSeconds"]?.toIntOrNull()
            ?.let { preferencesRepository.setPlayerDiagnosticsTimeoutSeconds(it) }
        preferencesRepository.setPlayerWifiMaxVideoHeight(prefs["playerWifiMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
        preferencesRepository.setPlayerEthernetMaxVideoHeight(prefs["playerEthernetMaxVideoHeight"]?.toIntOrNull()?.takeIf { it > 0 })
        prefs["playerTimeshiftEnabled"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPlayerTimeshiftEnabled(it) }
        prefs["playerTimeshiftDepthMinutes"]?.toIntOrNull()
            ?.let { preferencesRepository.setPlayerTimeshiftDepthMinutes(it) }
        prefs["playerTimeshiftBackend"]?.let { savedMode ->
            com.streamvault.domain.model.TimeshiftBackendPreference.entries
                .firstOrNull { it.name == savedMode }
                ?.let { preferencesRepository.setPlayerTimeshiftBackend(it) }
        }
        prefs["defaultStopPlaybackTimerMinutes"]?.toIntOrNull()
            ?.let { preferencesRepository.setDefaultStopPlaybackTimerMinutes(it) }
        prefs["defaultIdleStandbyTimerMinutes"]?.toIntOrNull()
            ?.let { preferencesRepository.setDefaultIdleStandbyTimerMinutes(it) }
        prefs["preventStandbyDuringPlayback"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setPreventStandbyDuringPlayback(it) }
        prefs["zapAutoRevert"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setZapAutoRevert(it) }
        prefs["autoPlayNextEpisode"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setAutoPlayNextEpisode(it) }
        prefs["autoCheckAppUpdates"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setAutoCheckAppUpdates(it) }
        prefs["autoDownloadAppUpdates"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setAutoDownloadAppUpdates(it) }
        prefs["recordingWifiOnly"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setRecordingWifiOnly(it) }
        prefs["recordingPaddingBeforeMinutes"]?.toIntOrNull()
            ?.let { preferencesRepository.setRecordingPaddingBeforeMinutes(it) }
        prefs["recordingPaddingAfterMinutes"]?.toIntOrNull()
            ?.let { preferencesRepository.setRecordingPaddingAfterMinutes(it) }
        prefs["maxConcurrentStreams"]?.toIntOrNull()
            ?.let { preferencesRepository.setMaxConcurrentStreams(it) }
        prefs["isIncognitoMode"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setIncognitoMode(it) }
        prefs["useXtreamTextClassification"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setUseXtreamTextClassification(it) }
        prefs["xtreamBase64TextCompatibility"]?.toBooleanStrictOrNull()
            ?.let { preferencesRepository.setXtreamBase64TextCompatibility(it) }
        prefs["guideDensity"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideDensity(it) }
        prefs["guideChannelMode"]?.takeIf { it.isNotBlank() }?.let { preferencesRepository.setGuideChannelMode(it) }
        prefs["guideDefaultCategoryId"]?.toLongOrNull()?.takeUnless { skipProviderScopedReferences }?.let { categoryId ->
            if (categoryId == 0L) {
                preferencesRepository.clearGuideDefaultCategoryId()
            } else {
                preferencesRepository.setGuideDefaultCategoryId(categoryId)
            }
        }
        prefs["guideFavoritesOnly"]?.toBooleanStrictOrNull()?.let { preferencesRepository.setGuideFavoritesOnly(it) }
        prefs["guideScheduledOnly"]?.toBooleanStrictOrNull()?.let { preferencesRepository.setGuideScheduledOnly(it) }
        prefs["guideAnchorTime"]?.toLongOrNull()?.let { anchorTime ->
            if (anchorTime <= 0L) {
                preferencesRepository.clearGuideAnchorTime()
            } else {
                preferencesRepository.setGuideAnchorTime(anchorTime)
            }
        }
        prefs["promotedLiveGroupIds"]?.takeUnless { skipProviderScopedReferences }?.let { token ->
            preferencesRepository.setPromotedLiveGroupIds(
                token.split(",").mapNotNull { it.toLongOrNull() }.toSet()
            )
        }
        // D13 — restore hidden channels per provider
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("hiddenChannels_") }
            .forEach { (key, value) ->
                val providerId = key.removePrefix("hiddenChannels_").toLongOrNull() ?: return@forEach
                val ids = value.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                preferencesRepository.setHiddenChannelIds(providerId, ids)
            }
        // D13 — restore hidden categories per provider per content type
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("hiddenCategories_") }
            .forEach { (key, value) ->
                val rest = key.removePrefix("hiddenCategories_").split("_")
                if (rest.size < 2) return@forEach
                val providerId = rest[0].toLongOrNull() ?: return@forEach
                val type = ContentType.entries.firstOrNull { it.name == rest[1] } ?: return@forEach
                val ids = value.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                preferencesRepository.setHiddenCategoryIds(providerId, type, ids)
            }
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("pinnedCategories_") }
            .forEach { (key, value) ->
                val rest = key.removePrefix("pinnedCategories_").split("_")
                if (rest.size < 2) return@forEach
                val providerId = rest[0].toLongOrNull() ?: return@forEach
                val type = ContentType.entries.firstOrNull { it.name == rest[1] } ?: return@forEach
                val ids = value.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                preferencesRepository.setPinnedCategoryIds(providerId, type, ids)
            }
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("categorySortMode_") }
            .forEach { (key, value) ->
                val rest = key.removePrefix("categorySortMode_").split("_")
                if (rest.size < 2) return@forEach
                val providerId = rest[0].toLongOrNull() ?: return@forEach
                val type = ContentType.entries.firstOrNull { it.name == rest[1] } ?: return@forEach
                val mode = com.streamvault.domain.model.CategorySortMode.entries
                    .firstOrNull { it.name == value }
                    ?: com.streamvault.domain.model.CategorySortMode.DEFAULT
                preferencesRepository.setCategorySortMode(providerId, type, mode)
            }
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("epgTimeShift_") }
            .forEach { (key, value) ->
                key.removePrefix("epgTimeShift_").toLongOrNull()?.let { providerId ->
                    preferencesRepository.setEpgTimeShiftMinutes(providerId, value.toIntOrNull() ?: 0)
                }
            }
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("liveVariantSelections_") }
            .forEach { (key, value) ->
                val providerId = key.removePrefix("liveVariantSelections_").toLongOrNull() ?: return@forEach
                val selections = value.lineSequence().mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val logicalGroupId = line.substring(0, separator).trim()
                    val rawChannelId = line.substring(separator + 1).trim().toLongOrNull()
                    if (logicalGroupId.isBlank() || rawChannelId == null) null
                    else logicalGroupId to rawChannelId
                }.toMap()
                preferencesRepository.replacePreferredLiveVariants(providerId, selections)
            }
        prefs.entries.takeUnless { skipProviderScopedReferences }.orEmpty()
            .filter { it.key.startsWith("vodVariantSelections_") }
            .forEach { (key, value) ->
                val providerId = key.removePrefix("vodVariantSelections_").toLongOrNull() ?: return@forEach
                val selections = value.lineSequence().mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val logicalGroupId = line.substring(0, separator).trim()
                    val rawItemId = line.substring(separator + 1).trim().toLongOrNull()
                    if (logicalGroupId.isBlank() || rawItemId == null) null
                    else logicalGroupId to rawItemId
                }.toMap()
                preferencesRepository.replacePreferredVodVariants(providerId, selections)
            }
    }

    private suspend fun restoreRemoteShortcutPreferences(encoded: String) {
        val restored = runCatching {
            gson.fromJson(
                encoded,
                com.streamvault.domain.model.RemoteShortcutPreferences::class.java
            )
        }.getOrNull() ?: return
        restored.selections.forEach { (profile, buttons) ->
            buttons.forEach { (button, selection) ->
                preferencesRepository.setRemoteShortcutSelection(profile, button, selection)
            }
        }
    }

    private suspend fun restoreRoomBackedSections(
        backupData: BackupData,
        plan: BackupImportPlan,
        initialStoredProviders: List<Provider>
    ): RoomRestoreResult {
        val importProviders = plan.importProviders ||
            (backupData.version >= 14 && backupData.providerSnapshots != null)
        if (!importProviders && !plan.importSavedLibrary && !plan.importPlaybackHistory) {
            return RoomRestoreResult(
                storedProviders = initialStoredProviders,
                importedSections = emptyList(),
                skippedSections = buildList {
                    add("Providers")
                    add("Combined M3U Profiles")
                    add("Saved Library")
                    add("Playback History")
                },
                unresolvedReferences = emptyList()
            )
        }

        var storedProviders = initialStoredProviders
        val importedSections = mutableListOf<String>()
        val skippedSections = mutableListOf<String>()
        val unresolvedReferences = mutableListOf<String>()

        transactionRunner.inTransaction {
            if (importProviders) {
                backupData.providers?.let { providers ->
                    providers.forEach { provider ->
                        val existing = storedProviders
                            .filter { it.type == provider.type }
                            .findMatchingProvider(
                                serverUrl = provider.serverUrl,
                                username = provider.username,
                                stalkerMacAddress = provider.stalkerMacAddress
                            )
                        if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                            return@forEach
                        }
                        val restoredProvider = provider.copy(
                            id = existing?.id ?: 0L,
                            stalkerCatalogMode = if (backupData.version < 9) {
                                StalkerCatalogMode.ON_DEMAND
                            } else {
                                provider.stalkerCatalogMode
                            },
                            stalkerProtocolPreference = if (backupData.version < 10) {
                                StalkerProtocolPreference.AUTO
                            } else provider.stalkerProtocolPreference,
                            stalkerRequestedProfileId = if (backupData.version < 10) {
                                StalkerCompatibilityRegistry.idForLegacyPreset(provider.stalkerMagPreset)
                            } else provider.stalkerRequestedProfileId,
                            stalkerLearnedProfileId = if (backupData.version < 10) {
                                StalkerCompatibilityRegistry.idForLegacyPreset(provider.stalkerMagPreset)
                            } else provider.stalkerLearnedProfileId,
                            stalkerProfileRevision = if (backupData.version < 10) {
                                StalkerCompatibilityRegistry.REVISION
                            } else provider.stalkerProfileRevision,
                            stalkerProfileVerification = if (backupData.version < 10) {
                                StalkerProfileVerification.VERIFIED
                            } else provider.stalkerProfileVerification,
                            stalkerProtocolFamily = if (backupData.version < 10) {
                                StalkerProtocolFamily.CLASSIC_MAG
                            } else provider.stalkerProtocolFamily,
                            stalkerLastBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
                            stalkerLastPlaybackMode = null,
                            stalkerTransportMode = StalkerTransportMode.AUTO_STRICT,
                            stalkerTransportOrigin = "",
                            stalkerTlsSpkiSha256 = "",
                            stalkerTransportConsentAt = 0L,
                            isActive = if (
                                provider.type == ProviderType.STALKER_PORTAL &&
                                provider.serverUrl.startsWith("http://", ignoreCase = true)
                            ) {
                                false
                            } else {
                                provider.isActive
                            },
                            status = if (
                                provider.type == ProviderType.STALKER_PORTAL &&
                                provider.serverUrl.startsWith("http://", ignoreCase = true)
                            ) {
                                ProviderStatus.PARTIAL
                            } else {
                                provider.status
                            }
                        )
                        val restoredEntity = restoredProvider.toSecureEntityForBackup(credentialCrypto)
                        val restoredId = if (existing == null) {
                            providerDao.insert(restoredEntity)
                        } else {
                            providerDao.update(restoredEntity)
                            existing.id
                        }
                        persistRestoredProvider(restoredId, restoredProvider.copy(id = restoredId))
                    }
                    storedProviders = loadStoredProviders()
                    backupData.preferences
                        ?.get("lastActiveProviderId")
                        ?.toLongOrNull()
                        ?.let { backupProviderId ->
                            resolveProviderIdMap(storedProviders, providers)[backupProviderId]
                        }
                        ?.let { resolvedProviderId ->
                            providerDao.setActive(resolvedProviderId)
                        }
                    backupData.combinedM3uProfiles?.let { profiles ->
                        unresolvedReferences += restoreCombinedM3uProfiles(
                            profiles = profiles,
                            activeLiveSource = backupData.activeLiveSource,
                            storedProviders = storedProviders,
                            conflictStrategy = plan.conflictStrategy
                        )
                    }
                    importedSections += "Providers"
                    if (backupData.combinedM3uProfiles != null) importedSections += "Combined M3U Profiles"
                } ?: run { skippedSections += "Providers" }
                backupData.epgSources?.let { sources ->
                    val dao = epgSourceDao
                    if (dao == null) {
                        skippedSections += "EPG Sources"
                    } else {
                        sources.forEach { source ->
                            val existing = dao.getByUrl(source.url)
                            if (existing != null && plan.conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                                return@forEach
                            }
                            val entity = source.copy(
                                id = existing?.id ?: 0L,
                                lastRefreshAt = 0L,
                                lastSuccessAt = 0L,
                                lastError = null,
                                etag = null,
                                lastModifiedHeader = null
                            ).toEntity()
                            if (existing == null) dao.insert(entity) else dao.update(entity)
                        }
                        importedSections += "EPG Sources"
                    }
                }
                backupData.providerEpgAssignments?.let { assignments ->
                    unresolvedReferences += restoreProviderEpgAssignments(
                        assignments = assignments,
                        backupProviders = backupData.providers.orEmpty(),
                        storedProviders = storedProviders,
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "Provider EPG Assignments"
                }
                backupData.manualEpgMappings?.let { mappings ->
                    unresolvedReferences += restoreManualEpgMappings(
                        mappings = mappings,
                        backupProviders = backupData.providers.orEmpty(),
                        storedProviders = storedProviders,
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "Manual EPG Mappings"
                }
                backupData.m3uClassificationOverrides?.let { overrides ->
                    unresolvedReferences += restoreM3uClassification(
                        overrides = overrides,
                        rules = backupData.m3uClassificationRules.orEmpty(),
                        backupProviders = backupData.providers.orEmpty(),
                        storedProviders = storedProviders,
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "M3U Classification"
                } ?: backupData.m3uClassificationRules?.let { rules ->
                    unresolvedReferences += restoreM3uClassification(
                        overrides = emptyList(),
                        rules = rules,
                        backupProviders = backupData.providers.orEmpty(),
                        storedProviders = storedProviders,
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "M3U Classification"
                }
                backupData.programReminders?.let { reminders ->
                    unresolvedReferences += restoreProgramReminders(
                        reminders = reminders,
                        storedProviders = storedProviders,
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "Program Reminders"
                }
            } else {
                skippedSections += "Providers"
                if (backupData.combinedM3uProfiles != null) skippedSections += "Combined M3U Profiles"
                if (!backupData.epgSources.isNullOrEmpty()) skippedSections += "EPG Sources"
                if (backupData.providerEpgAssignments != null) skippedSections += "Provider EPG Assignments"
                if (backupData.manualEpgMappings != null) skippedSections += "Manual EPG Mappings"
                if (backupData.m3uClassificationOverrides != null || backupData.m3uClassificationRules != null) {
                    skippedSections += "M3U Classification"
                }
                if (backupData.programReminders != null) skippedSections += "Program Reminders"
            }

            if (plan.importSavedLibrary) {
                unresolvedReferences += restoreSavedLibrary(
                    backupData = backupData,
                    storedProviders = storedProviders,
                    conflictStrategy = plan.conflictStrategy
                )
                importedSections += "Saved Library"
            } else {
                skippedSections += "Saved Library"
            }

            if (plan.importPlaybackHistory) {
                backupData.playbackHistory?.let { history ->
                    unresolvedReferences += restorePlaybackHistory(
                        history = history,
                        storedProviders = storedProviders,
                        backupProviders = backupData.providers.orEmpty(),
                        conflictStrategy = plan.conflictStrategy
                    )
                    importedSections += "Playback History"
                } ?: run { skippedSections += "Playback History" }
            } else {
                skippedSections += "Playback History"
            }
        }

        return RoomRestoreResult(
            storedProviders = storedProviders,
            importedSections = importedSections,
            skippedSections = skippedSections,
            unresolvedReferences = unresolvedReferences
        )
    }

    private suspend fun restoreProviderEpgAssignments(
        assignments: List<ProviderEpgAssignmentBackup>,
        backupProviders: List<Provider>,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val assignmentDao = providerEpgSourceDao
            ?: return listOf("Provider EPG assignment storage is unavailable")
        val sourceDao = epgSourceDao
            ?: return listOf("EPG source storage is unavailable for provider assignments")
        val unresolved = mutableListOf<String>()
        val byProvider = assignments.groupBy { it.provider }
        val providerReferences = (byProvider.keys + backupProviders.map { it.toBackupProviderReference() }).distinct()
        providerReferences.forEach { reference ->
            val provider = storedProviders.findUnambiguousPortableProvider(reference)
            if (provider == null) {
                if (byProvider[reference].orEmpty().isNotEmpty()) {
                    unresolved += reference.unresolvedLabel("Provider EPG assignment provider")
                }
                return@forEach
            }
            val resolved = mutableListOf<ProviderEpgSourceEntity>()
            var providerComplete = true
            byProvider[reference].orEmpty().forEach { assignment ->
                val source = sourceDao.getByUrl(assignment.sourceUrl)
                if (source == null) {
                    providerComplete = false
                    unresolved += "EPG source ${assignment.sourceUrl} was not found for ${reference.serverUrl}"
                } else {
                    resolved += ProviderEpgSourceEntity(
                        providerId = provider.id,
                        epgSourceId = source.id,
                        priority = assignment.priority,
                        enabled = assignment.enabled
                    )
                }
            }
            if (!providerComplete && conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) return@forEach
            if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                assignmentDao.deleteByProvider(provider.id)
            }
            resolved.forEach { assignmentDao.insert(it) }
        }
        return unresolved
    }

    private suspend fun restoreManualEpgMappings(
        mappings: List<ManualEpgMappingBackup>,
        backupProviders: List<Provider>,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val mappingDao = channelEpgMappingDao
            ?: return listOf("Manual EPG mapping storage is unavailable")
        val sourceDao = epgSourceDao
            ?: return listOf("EPG source storage is unavailable for manual mappings")
        val unresolved = mutableListOf<String>()
        val byProvider = mappings.groupBy { it.channel.provider }
        val providerReferences = (byProvider.keys + backupProviders.map { it.toBackupProviderReference() }).distinct()
        providerReferences.forEach { reference ->
            val provider = storedProviders.findUnambiguousPortableProvider(reference)
            if (provider == null) {
                if (byProvider[reference].orEmpty().isNotEmpty()) {
                    unresolved += reference.unresolvedLabel("Manual EPG mapping provider")
                }
                return@forEach
            }
            val localChannels = channelDao.getByProviderSync(provider.id)
            val resolved = mutableListOf<ChannelEpgMappingEntity>()
            var providerComplete = true
            byProvider[reference].orEmpty().forEach { mapping ->
                val channel = localChannels.resolvePortableChannel(mapping.channel)
                if (channel == null) {
                    providerComplete = false
                    unresolved += mapping.channel.unresolvedLabel("Manual EPG mapping channel")
                    return@forEach
                }
                val sourceId = mapping.sourceUrl?.let { sourceUrl ->
                    sourceDao.getByUrl(sourceUrl)?.id ?: run {
                        providerComplete = false
                        unresolved += "EPG source $sourceUrl was not found for manual mapping"
                        null
                    }
                }
                if (mapping.sourceUrl != null && sourceId == null) return@forEach
                resolved += ChannelEpgMappingEntity(
                    providerChannelId = channel.id,
                    providerId = provider.id,
                    sourceType = mapping.sourceType,
                    epgSourceId = sourceId,
                    xmltvChannelId = mapping.xmltvChannelId,
                    matchType = mapping.matchType,
                    confidence = mapping.confidence,
                    source = mapping.source,
                    isManualOverride = true
                )
            }
            if (providerComplete && conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                mappingDao.getForProvider(provider.id)
                    .filter { it.isManualOverride && it.providerChannelId !in resolved.map { item -> item.providerChannelId } }
                    .forEach { mappingDao.deleteForChannel(provider.id, it.providerChannelId) }
            } else if (!providerComplete && conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                return@forEach
            }
            resolved.forEach { mappingDao.upsert(it) }
        }
        return unresolved
    }

    private suspend fun restoreM3uClassification(
        overrides: List<M3uClassificationOverrideBackup>,
        rules: List<M3uClassificationRuleBackup>,
        backupProviders: List<Provider>,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val classificationDao = m3uClassificationDao
            ?: return listOf("M3U classification storage is unavailable")
        val unresolved = mutableListOf<String>()
        val providerReferences = (
            overrides.map { it.provider } +
                rules.map { it.provider } +
                backupProviders.filter { it.type == ProviderType.M3U }.map { it.toBackupProviderReference() }
            ).distinct()
        val overridesByProvider = overrides.groupBy { it.provider }
        val rulesByProvider = rules.groupBy { it.provider }
        providerReferences.forEach { reference ->
            val provider = storedProviders.findUnambiguousPortableProvider(reference)
            if (provider == null) {
                if (overridesByProvider[reference].orEmpty().isNotEmpty() || rulesByProvider[reference].orEmpty().isNotEmpty()) {
                    unresolved += reference.unresolvedLabel("M3U classification provider")
                }
                return@forEach
            }
            if (provider.type != ProviderType.M3U) {
                unresolved += "${reference.unresolvedLabel("M3U classification provider")} is not an M3U provider"
                return@forEach
            }
            if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                classificationDao.deleteOverridesByProvider(provider.id)
                classificationDao.deleteCategoryRulesByProvider(provider.id)
            }
            overridesByProvider[reference].orEmpty().forEach { override ->
                classificationDao.upsertOverride(
                    M3uClassificationOverrideEntity(
                        providerId = provider.id,
                        sourceKey = override.sourceKey,
                        streamId = override.streamId,
                        targetType = override.targetType,
                        groupKey = override.groupKey,
                        seriesKey = override.seriesKey,
                        seriesName = override.seriesName,
                        seasonNumber = override.seasonNumber,
                        episodeNumber = override.episodeNumber,
                        episodeTitle = override.episodeTitle
                    )
                )
            }
            rulesByProvider[reference].orEmpty().forEach { rule ->
                classificationDao.upsertCategoryRule(
                    M3uCategoryClassificationRuleEntity(
                        providerId = provider.id,
                        groupKey = rule.groupKey,
                        targetType = rule.targetType
                    )
                )
            }
        }
        return unresolved
    }

    private suspend fun restoreProgramReminders(
        reminders: List<ProgramReminderBackup>,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val reminderManager = programReminderManager
            ?: return listOf("Program reminder storage is unavailable")
        val unresolved = mutableListOf<String>()
        reminders.forEach { reminder ->
            val provider = storedProviders.findUnambiguousPortableProvider(reminder.provider)
            if (provider == null) {
                unresolved += reminder.provider.unresolvedLabel("Program reminder provider")
                return@forEach
            }
            if (reminder.programStartTime <= System.currentTimeMillis()) return@forEach
            if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING && reminderManager.isReminderScheduled(
                    providerId = provider.id,
                    channelId = reminder.channelId,
                    programTitle = reminder.programTitle,
                    programStartTime = reminder.programStartTime
                )
            ) {
                return@forEach
            }
            when (val result = reminderManager.scheduleReminder(
                providerId = provider.id,
                channelId = reminder.channelId,
                channelName = reminder.channelName,
                program = Program(
                    channelId = reminder.channelId,
                    title = reminder.programTitle,
                    startTime = reminder.programStartTime,
                    endTime = reminder.programStartTime + 60_000L,
                    providerId = provider.id
                ),
                leadTimeMinutes = reminder.leadTimeMinutes
            )) {
                is Result.Success -> Unit
                is Result.Error -> unresolved += "Program reminder '${reminder.programTitle}' could not be restored: ${result.message}"
                Result.Loading -> unresolved += "Program reminder '${reminder.programTitle}' did not finish restoring"
            }
        }
        return unresolved
    }

    private suspend fun restoreCombinedM3uProfiles(
        profiles: List<CombinedM3uProfileBackup>,
        activeLiveSource: ActiveLiveSourceBackup?,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val profileDao = combinedM3uProfileDao
            ?: return listOf("Combined M3U profile storage is unavailable")
        val memberDao = combinedM3uProfileMemberDao
            ?: return listOf("Combined M3U profile member storage is unavailable")
        val unresolved = mutableListOf<String>()
        val restoredProfileIdsByName = linkedMapOf<String, Long>()
        var existingProfiles = profileDao.getAll().first()

        profiles.forEach { backup ->
            val existing = existingProfiles.firstOrNull { it.name.equals(backup.name, ignoreCase = true) }
            val profileId = if (existing != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                existing.id
            } else {
                val now = System.currentTimeMillis()
                val entity = CombinedM3uProfileEntity(
                    id = existing?.id ?: 0L,
                    name = backup.name,
                    enabled = backup.enabled,
                    createdAt = backup.createdAt.takeIf { it > 0L } ?: existing?.createdAt ?: now,
                    updatedAt = backup.updatedAt.takeIf { it > 0L } ?: now
                )
                val id = if (existing == null) profileDao.insert(entity) else {
                    profileDao.update(entity)
                    existing.id
                }
                val members = backup.members.mapNotNull { member ->
                    val provider = storedProviders.findUnambiguousPortableProvider(member.provider)
                    if (provider == null) {
                        unresolved += "Combined M3U profile '${backup.name}' provider ${member.provider.serverUrl}/${member.provider.username} was not found"
                        null
                    } else {
                        CombinedM3uProfileMemberEntity(
                            profileId = id,
                            providerId = provider.id,
                            priority = member.priority,
                            enabled = member.enabled
                        )
                    }
                }
                memberDao.replacePriorities(id, members)
                id
            }
            restoredProfileIdsByName.putIfAbsent(backup.name.lowercase(), profileId)
            existingProfiles = profileDao.getAll().first()
        }

        activeLiveSource?.let { source ->
            when (source.type.lowercase()) {
                "provider" -> {
                    val provider = source.provider?.let { storedProviders.findUnambiguousPortableProvider(it) }
                    if (provider == null) {
                        unresolved += "Active live source provider was not found"
                    } else {
                        preferencesRepository.setActiveLiveSource(ActiveLiveSource.ProviderSource(provider.id))
                    }
                }
                "combined_m3u" -> {
                    val profilesByName = profileDao.getAll().first()
                        .filter { profile -> profile.name.equals(source.combinedProfileName, true) }
                    val profileByMembers = if (source.combinedProfileProviders.isEmpty()) {
                        null
                    } else {
                        profilesByName.firstOrNull { profile ->
                            val localProviders = memberDao.getForProfileSync(profile.id)
                                .sortedWith(compareBy({ member -> member.priority }, { member -> member.id }))
                                .mapNotNull { member ->
                                    storedProviders.firstOrNull { provider -> provider.id == member.providerId }
                                        ?.toBackupProviderReference()
                                }
                            localProviders == source.combinedProfileProviders
                        }
                    }
                    val profileId = profileByMembers?.id
                        ?: source.combinedProfileName
                            ?.let { restoredProfileIdsByName[it.lowercase()] }
                        ?: profilesByName.firstOrNull()?.id
                    if (profileId == null) {
                        unresolved += "Active combined M3U profile '${source.combinedProfileName.orEmpty()}' was not found"
                    } else {
                        preferencesRepository.setActiveLiveSource(ActiveLiveSource.CombinedM3uSource(profileId))
                    }
                }
                else -> unresolved += "Unknown active live source type '${source.type}'"
            }
        }
        return unresolved
    }

    private suspend fun restoreSavedLibrary(
        backupData: BackupData,
        storedProviders: List<Provider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val unresolvedReferences = mutableListOf<String>()
        val providerIdMap = resolveProviderIdMap(storedProviders, backupData.providers.orEmpty())
        val groupIdMap = mutableMapOf<Long, Long>()
        val blockedReplaceScopes = mutableSetOf<Pair<Long, ContentType>>()
        if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            backupData.favorites.orEmpty().forEach { favorite ->
                val resolvedProviderId = providerIdMap[favorite.providerId]
                if (resolvedProviderId == null) return@forEach
                val scope = resolvedProviderId to favorite.contentType
                if (favorite.groupId != null && backupData.virtualGroups.orEmpty().none { it.id == favorite.groupId }) {
                    blockedReplaceScopes += scope
                    unresolvedReferences += "Favorite ${favorite.contentType} refers to missing group ${favorite.groupId}"
                } else if (resolvePortableContentId(
                        providerId = resolvedProviderId,
                        contentType = favorite.contentType,
                        remoteContentId = favorite.remoteContentId,
                        legacyContentId = favorite.contentId
                    ) == null
                ) {
                    blockedReplaceScopes += scope
                    unresolvedReferences += "Favorite ${favorite.contentType} ${favorite.remoteContentId} could not be matched"
                }
            }
        }
        if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            val replaceScopes = buildSet<Pair<Long, ContentType>> {
                backupData.virtualGroups.orEmpty().forEach { group ->
                    providerIdMap[group.providerId]?.let { providerId ->
                        add(providerId to group.contentType)
                    }
                }
                backupData.favorites.orEmpty().forEach { favorite ->
                    providerIdMap[favorite.providerId]?.let { providerId ->
                        add(providerId to favorite.contentType)
                    }
                }
            }
            replaceScopes.filterNot { it in blockedReplaceScopes }.forEach { (providerId, contentType) ->
                favoriteDao.deleteByProviderAndType(providerId, contentType.name)
                if (backupData.virtualGroups != null) {
                    virtualGroupDao.deleteByProviderAndType(providerId, contentType.name)
                }
            }
        }
        backupData.virtualGroups?.let { groups ->
            val existingGroups = buildList {
                if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                    providerIdMap.values.distinct().forEach { providerId ->
                        addAll(virtualGroupDao.getByType(providerId, "LIVE").first())
                        addAll(virtualGroupDao.getByType(providerId, "MOVIE").first())
                        addAll(virtualGroupDao.getByType(providerId, "SERIES").first())
                    }
                }
            }
            groups.forEach { group ->
                val resolvedProviderId = providerIdMap[group.providerId]
                if (resolvedProviderId == null) {
                    unresolvedReferences += "Group ${group.name} provider ${group.providerId} could not be matched"
                    return@forEach
                }
                if (resolvedProviderId to group.contentType in blockedReplaceScopes) {
                    return@forEach
                }
                val conflict = existingGroups.firstOrNull {
                    it.providerId == resolvedProviderId &&
                        it.name.equals(group.name, ignoreCase = true) &&
                        it.contentType == group.contentType
                }
                if (conflict != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                    groupIdMap[group.id] = conflict.id
                    return@forEach
                }
                val insertedId = virtualGroupDao.insert(
                    group.copy(id = 0L, providerId = resolvedProviderId).toEntity()
                )
                groupIdMap[group.id] = insertedId
            }
        }

        backupData.favorites?.let { favorites ->
            favorites.forEach { favorite ->
                val resolvedProviderId = providerIdMap[favorite.providerId]
                if (resolvedProviderId == null) {
                    unresolvedReferences += "Favorite provider ${favorite.providerId} could not be matched"
                    return@forEach
                }
                if (resolvedProviderId to favorite.contentType in blockedReplaceScopes) {
                    return@forEach
                }
                val resolvedGroupId = favorite.groupId?.let { groupId ->
                    groupIdMap[groupId] ?: run {
                        unresolvedReferences += "Favorite ${favorite.contentType} refers to missing group $groupId"
                        return@forEach
                    }
                }
                val resolvedContentId = resolvePortableContentId(
                    providerId = resolvedProviderId,
                    contentType = favorite.contentType,
                    remoteContentId = favorite.remoteContentId,
                    legacyContentId = favorite.contentId
                )
                if (resolvedContentId == null) {
                    unresolvedReferences += "Favorite ${favorite.contentType} ${favorite.remoteContentId} could not be matched"
                    return@forEach
                }
                val existing = favoriteDao.get(
                    providerId = resolvedProviderId,
                    contentId = resolvedContentId,
                    contentType = favorite.contentType.name,
                    groupId = resolvedGroupId
                )
                if (existing != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                    return@forEach
                }
                favoriteDao.insert(
                    favorite.copy(
                        id = 0L,
                        providerId = resolvedProviderId,
                        contentId = resolvedContentId,
                        groupId = resolvedGroupId
                    ).toEntity()
                )
            }
        }

        val categoriesByProviderId = mutableMapOf<Long, List<com.streamvault.domain.model.Category>>()
        val protectedByScope = mutableMapOf<Pair<Long, ContentType>, MutableSet<Long>>()
        val blockedProtectionScopes = mutableSetOf<Pair<Long, ContentType>>()
        backupData.protectedCategories?.forEach { protectedCategory ->
            val provider = storedProviders.findMatchingProvider(
                serverUrl = protectedCategory.providerServerUrl,
                username = protectedCategory.providerUsername,
                stalkerMacAddress = protectedCategory.providerStalkerMacAddress,
                providerType = protectedCategory.providerType
            )
            if (provider == null) {
                unresolvedReferences += "Protected category provider ${protectedCategory.providerServerUrl} was not found"
                return@forEach
            }
            val scope = provider.id to protectedCategory.type
            val categories = categoriesByProviderId.getOrPut(provider.id) {
                categoryRepository.getCategories(provider.id).first()
            }
            val resolvedCategory = categories.firstOrNull {
                it.type == protectedCategory.type && it.id == protectedCategory.categoryId
            } ?: categories.firstOrNull {
                it.type == protectedCategory.type &&
                    it.name.equals(protectedCategory.categoryName, ignoreCase = true)
            }
            if (resolvedCategory == null) {
                val placeholderDao = categoryDao
                if (placeholderDao == null) {
                    blockedProtectionScopes += scope
                    unresolvedReferences += "Protected category '${protectedCategory.categoryName}' was not found for ${provider.serverUrl}"
                    return@forEach
                }
                // Provider rows may have been deleted before import. Create the remote
                // category shell now so the first catalog sync can update it in place and
                // retain the user's protection choice.
                placeholderDao.insertAll(
                    listOf(
                        CategoryEntity(
                            categoryId = protectedCategory.categoryId,
                            name = protectedCategory.categoryName,
                            type = protectedCategory.type,
                            providerId = provider.id,
                            isUserProtected = true
                        )
                    )
                )
            }
            protectedByScope.getOrPut(scope) { linkedSetOf() } +=
                (resolvedCategory?.id ?: protectedCategory.categoryId)
        }
        protectedByScope.forEach { (scope, categoryIds) ->
            if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING && scope in blockedProtectionScopes) {
                return@forEach
            }
            val (providerId, type) = scope
            val categories = categoriesByProviderId[providerId].orEmpty()
            if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                categories.filter { it.type == type && it.isUserProtected && it.id !in categoryIds }
                    .forEach { category ->
                        categoryRepository.setCategoryProtection(
                            providerId = providerId,
                            categoryId = category.id,
                            type = type,
                            isProtected = false
                        )
                    }
            }
            categoryIds.forEach { categoryId ->
                val current = categories.firstOrNull { it.id == categoryId && it.type == type }
                if (conflictStrategy != BackupConflictStrategy.KEEP_EXISTING || current?.isUserProtected != true) {
                    categoryRepository.setCategoryProtection(
                        providerId = providerId,
                        categoryId = categoryId,
                        type = type,
                        isProtected = true
                    )
                }
            }
        }
        return unresolvedReferences
    }

    private suspend fun resolvePortableContentId(
        providerId: Long,
        contentType: ContentType,
        remoteContentId: String?,
        legacyContentId: Long
    ): Long? {
        if (remoteContentId == null) {
            return when (contentType) {
                ContentType.LIVE -> channelDao.getById(legacyContentId)
                    ?.takeIf { it.providerId == providerId }?.id
                ContentType.MOVIE, ContentType.VOD -> movieDao.getById(legacyContentId)
                    ?.takeIf { it.providerId == providerId }?.id
                ContentType.SERIES -> seriesDao.getById(legacyContentId)
                    ?.takeIf { it.providerId == providerId }?.id
                ContentType.SERIES_EPISODE -> episodeDao.getById(legacyContentId)
                    ?.takeIf { it.providerId == providerId }?.id
            }
        }
        return when (contentType) {
            ContentType.LIVE -> remoteContentId.toLongOrNull()?.let { channelDao.getByStreamId(providerId, it)?.id }
            ContentType.MOVIE -> remoteContentId.toLongOrNull()?.let { movieDao.getByStreamId(providerId, it)?.id }
            ContentType.SERIES -> resolveSeries(providerId, remoteContentId)?.id
            ContentType.SERIES_EPISODE,
            ContentType.VOD -> null
        }
    }

    private suspend fun resolveSeries(providerId: Long, remoteSeriesId: String): com.streamvault.data.local.entity.SeriesEntity? =
        seriesDao.getByProviderSeriesId(providerId, remoteSeriesId)
            ?: remoteSeriesId.toLongOrNull()?.let { seriesDao.getBySeriesId(providerId, it) }

    private fun resolveProviderIdMap(
        storedProviders: List<Provider>,
        backupProviders: List<com.streamvault.domain.model.LegacyProvider>
    ): Map<Long, Long> = backupProviders.mapNotNull { provider ->
        storedProviders.findMatchingProvider(
            serverUrl = provider.serverUrl,
            username = provider.username,
            stalkerMacAddress = provider.stalkerMacAddress,
            providerType = provider.type
        )?.let { stored ->
            provider.id to stored.id
        }
    }.toMap()

    private suspend fun restorePlaybackHistory(
        history: List<com.streamvault.domain.model.PlaybackHistory>,
        storedProviders: List<Provider>,
        backupProviders: List<com.streamvault.domain.model.LegacyProvider>,
        conflictStrategy: BackupConflictStrategy
    ): List<String> {
        val unresolvedReferences = mutableListOf<String>()
        val providerIdMap = resolveProviderIdMap(storedProviders, backupProviders)

        if (providerIdMap.isEmpty()) {
            return history.map { "History provider ${it.providerId} could not be matched" }
        }

        val resolvedItems = mutableListOf<ResolvedHistoryItem>()
        val blockedProviderIds = mutableSetOf<Long>()
        history.forEach { item ->
            val resolvedProviderId = providerIdMap[item.providerId]
            if (resolvedProviderId == null) {
                unresolvedReferences += "History provider ${item.providerId} could not be matched"
                return@forEach
            }
            val resolvedIdentity = resolvePortableHistoryIdentity(
                providerId = resolvedProviderId,
                item = item
            )
            if (resolvedIdentity == null) {
                unresolvedReferences += "History ${item.contentType} ${item.remoteContentId} could not be matched"
                blockedProviderIds += resolvedProviderId
                return@forEach
            }
            resolvedItems += ResolvedHistoryItem(
                providerId = resolvedProviderId,
                item = item,
                identity = resolvedIdentity
            )
        }

        val providersToResync = when (conflictStrategy) {
            BackupConflictStrategy.REPLACE_EXISTING -> providerIdMap.values
                .filterNot { it in blockedProviderIds }
                .toMutableSet()
            BackupConflictStrategy.KEEP_EXISTING -> linkedSetOf()
        }

        if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
            providersToResync.forEach { providerId ->
                playbackHistoryDao.deleteByProvider(providerId)
            }
        }

        resolvedItems.forEach { resolvedItem ->
            if (conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING &&
                resolvedItem.providerId in blockedProviderIds
            ) {
                return@forEach
            }
            val item = resolvedItem.item
            val resolvedProviderId = resolvedItem.providerId
            val resolvedIdentity = resolvedItem.identity
            if (conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
                val existing = playbackHistoryDao.get(
                    contentId = resolvedIdentity.contentId,
                    contentType = item.contentType.name,
                    providerId = resolvedProviderId
                )
                if (existing != null) return@forEach
            }
            playbackHistoryDao.insertOrUpdate(
                item.copy(
                    providerId = resolvedProviderId,
                    contentId = resolvedIdentity.contentId,
                    seriesId = resolvedIdentity.seriesId
                ).toEntity()
            )
            providersToResync += resolvedProviderId
        }

        providersToResync.forEach { providerId ->
            movieDao.syncWatchProgressFromHistoryByProvider(providerId)
            episodeDao.syncWatchProgressFromHistoryByProvider(providerId)
        }
        return unresolvedReferences
    }

    private data class ResolvedHistoryItem(
        val providerId: Long,
        val item: com.streamvault.domain.model.PlaybackHistory,
        val identity: ResolvedContentIdentity
    )

    private suspend fun resolvePortableHistoryIdentity(
        providerId: Long,
        item: com.streamvault.domain.model.PlaybackHistory
    ): ResolvedContentIdentity? {
        if (item.remoteContentId == null && item.remoteSeriesId == null) {
            return when (item.contentType) {
                ContentType.LIVE -> channelDao.getById(item.contentId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { ResolvedContentIdentity(it.id) }
                ContentType.MOVIE, ContentType.VOD -> movieDao.getById(item.contentId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { ResolvedContentIdentity(it.id) }
                ContentType.SERIES -> seriesDao.getById(item.contentId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { ResolvedContentIdentity(it.id) }
                ContentType.SERIES_EPISODE -> episodeDao.getById(item.contentId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { episode ->
                        val parentId = item.seriesId?.takeIf { seriesId ->
                            seriesDao.getById(seriesId)?.providerId == providerId
                        }
                        ResolvedContentIdentity(episode.id, parentId)
                    }
            }
        }

        return when (item.contentType) {
            ContentType.LIVE -> item.remoteContentId
                ?.toLongOrNull()
                ?.let { channelDao.getByStreamId(providerId, it)?.id }
                ?.let { ResolvedContentIdentity(contentId = it) }
            ContentType.MOVIE -> item.remoteContentId
                ?.toLongOrNull()
                ?.let { movieDao.getByStreamId(providerId, it)?.id }
                ?.let { ResolvedContentIdentity(contentId = it) }
            ContentType.SERIES -> item.remoteContentId
                ?.let { resolveSeries(providerId, it)?.id }
                ?.let { ResolvedContentIdentity(contentId = it) }
            ContentType.SERIES_EPISODE -> {
                val remoteSeriesId = item.remoteSeriesId ?: return null
                val localSeries = resolveSeries(providerId, remoteSeriesId) ?: return null
                val remoteEpisodeId = item.remoteContentId?.toLongOrNull() ?: return null
                episodeDao.getByProviderSeriesAndEpisodeId(
                    providerId = providerId,
                    seriesId = localSeries.id,
                    episodeId = remoteEpisodeId
                )?.let { episode ->
                    ResolvedContentIdentity(contentId = episode.id, seriesId = localSeries.id)
                }
            }
            ContentType.VOD -> null
        }
    }

    private data class ResolvedContentIdentity(
        val contentId: Long,
        val seriesId: Long? = null
    )

    private data class RoomRestoreResult(
        val storedProviders: List<Provider>,
        val importedSections: List<String>,
        val skippedSections: List<String>,
        val unresolvedReferences: List<String>
    )

    private suspend fun loadStoredProviders(): List<Provider> = providerDao.getAllSync().mapNotNull { entity ->
        providerSnapshotRepository?.getSnapshot(entity.id)?.toLegacyProvider()
    }

    /** Backup restore deliberately writes configuration/runtime only; learned Stalker state stays absent. */
    private suspend fun persistRestoredProvider(providerId: Long, provider: Provider) {
        val configuration = provider.toTypedConfiguration()
        val snapshotDao = requireNotNull(providerSnapshotDao) { "Typed provider snapshot DAO is required for restore" }
        val configurationCodec = requireNotNull(providerConfigurationCodec) { "Typed provider configuration codec is required for restore" }
        val nextGeneration = (snapshotDao.getConfig(providerId)?.configurationGeneration ?: 0L) + 1L
        check(
            snapshotDao.commitConfiguration(
                ProviderConfigEntity(
                    providerId = providerId,
                    type = configuration.type,
                    schemaVersion = configuration.schemaVersion,
                    configurationGeneration = nextGeneration,
                    identityKey = configurationCodec.identityKey(configuration),
                    encryptedConfigJson = configurationCodec.encode(configuration),
                    guideSourcePolicy = configuration.guidePolicy(),
                    channelLogoSourcePolicy = configuration.logoPolicy(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        ) { "Restored provider configuration was superseded" }
        val runtime = provider.toAccountRuntime()
        snapshotDao.upsertRuntime(
            ProviderAccountRuntimeEntity(
                providerId = providerId,
                maxConnections = runtime.maxConnections,
                expirationDate = runtime.expirationDate,
                apiVersion = runtime.apiVersion,
                allowedOutputFormatsJson = gson.toJson(runtime.allowedOutputFormats),
                catalogLayout = runtime.catalogLayout,
                catalogLayoutDetectionVersion = runtime.catalogLayoutDetectionVersion,
                observedAt = runtime.observedAt
            )
        )
    }

private data class RecordingChannelConflictKey(
    val providerId: Long,
    val startMs: Long,
    val channelId: Long
)

private data class RecordingUrlConflictKey(
    val providerId: Long,
    val startMs: Long,
    val streamUrl: String
)

internal fun countScheduledRecordingConflicts(
    incoming: List<ScheduledRecordingBackup>,
    providersByIdentity: Map<Triple<String, String, String>, Provider>,
    existing: List<com.streamvault.domain.model.RecordingItem>
): Int {
    val channelKeys = existing.mapTo(hashSetOf()) {
        RecordingChannelConflictKey(it.providerId, it.scheduledStartMs, it.channelId)
    }
    val urlKeys = existing.mapTo(hashSetOf()) {
        RecordingUrlConflictKey(it.providerId, it.scheduledStartMs, it.streamUrl)
    }
    return incoming.count { recording ->
        val provider = providersByIdentity[recording.backupIdentity()]
            ?.takeIf { recording.providerType == null || it.type == recording.providerType }
            ?: return@count false
        RecordingChannelConflictKey(provider.id, recording.scheduledStartMs, recording.channelId) in channelKeys ||
            RecordingUrlConflictKey(provider.id, recording.scheduledStartMs, recording.streamUrl) in urlKeys
    }
}

    private class BoundedInputStream(input: java.io.InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int = super.read().also { if (it >= 0) consume(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) consume(it.toLong()) }

        private fun consume(count: Long) {
            bytesRead += count
            if (bytesRead > maxBytes) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.BYTE_LIMIT,
                    "Backup exceeds the ${maxBytes / (1024 * 1024)} MiB import limit"
                )
            }
        }
    }

    private class BoundedOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
    ) : OutputStream() {
        private var bytesWritten = 0L

        override fun write(value: Int) {
            consume(1L)
            delegate.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            consume(length.toLong())
            delegate.write(buffer, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun consume(count: Long) {
            if (count < 0L || bytesWritten > maxBytes - count) {
                throw BackupOutputTooLargeException(
                    "Backup exceeds the ${maxBytes / (1024 * 1024)} MiB export limit"
                )
            }
            bytesWritten += count
        }
    }

    private class BackupOutputTooLargeException(message: String) : IOException(message)

    /**
     * Rejects hostile JSON structure while characters are still being consumed by Gson.
     * This keeps deeply nested or overlong values from becoming a large object graph first.
     */
    private class AdmissionCheckingReader(
        reader: java.io.Reader,
        private val maxDepth: Int,
        private val maxStringChars: Int
    ) : FilterReader(reader) {
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var stringChars = 0

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) inspect(value.toChar())
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) {
                    for (index in offset until offset + count) inspect(buffer[index])
                }
            }

        private fun inspect(char: Char) {
            if (inString) {
                when {
                    escaped -> {
                        escaped = false
                        countStringChar()
                    }
                    char == '\\' -> {
                        escaped = true
                        countStringChar()
                    }
                    char == '"' -> {
                        inString = false
                        stringChars = 0
                    }
                    else -> countStringChar()
                }
                return
            }

            when (char) {
                '"' -> {
                    inString = true
                    stringChars = 0
                }
                '{', '[' -> {
                    depth += 1
                    if (depth > maxDepth) {
                        throw BackupAdmissionException(
                            BackupAdmissionReason.DEPTH_LIMIT,
                            "Backup JSON exceeds nesting limit $maxDepth"
                        )
                    }
                }
                '}', ']' -> depth -= 1
            }
        }

        private fun countStringChar() {
            stringChars += 1
            if (stringChars > maxStringChars) {
                throw BackupAdmissionException(
                    BackupAdmissionReason.FIELD_LIMIT,
                    "Backup contains a string longer than $maxStringChars characters"
                )
            }
        }
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 16L * 1024 * 1024
        const val MAX_JSON_DEPTH = 64
        const val MAX_PREFERENCES = 5_000
        const val MAX_PROVIDERS = 1_000
        const val MAX_EPG_SOURCES = 10_000
        const val MAX_SECTION_ITEMS = 100_000
        const val MAX_FIELD_CHARS = 8_192
    }
}

internal enum class BackupAdmissionReason {
    BYTE_LIMIT,
    DEPTH_LIMIT,
    FIELD_LIMIT,
    SECTION_LIMIT,
    DUPLICATE_FIELD,
    UNSUPPORTED_VERSION,
    MALFORMED
}

internal class BackupAdmissionException(
    val reason: BackupAdmissionReason,
    message: String
) : IOException(message)

private fun Provider.backupIdentity(): Triple<String, String, String> =
    Triple(normalizeProviderServerUrl(serverUrl), username.trim(), stalkerMacAddress.normalizedIdentity())

private fun Provider.toBackupProviderReference() = BackupProviderReference(
    serverUrl = normalizeProviderServerUrl(serverUrl),
    username = username.trim().takeUnless { type == ProviderType.M3U }.orEmpty(),
    stalkerMacAddress = stalkerMacAddress.takeIf { it.isNotBlank() },
    providerType = type
)

private suspend fun com.streamvault.domain.model.Favorite.toPortableContentReference(
    providersById: Map<Long, Provider>,
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao
): com.streamvault.domain.manager.PortableContentReference? {
    val remoteId = remoteContentId?.takeIf { it.isNotBlank() } ?: return null
    val provider = providersById[providerId] ?: return null
    val providerReference = provider.toBackupProviderReference()
    when (contentType) {
        ContentType.LIVE -> channelDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.MOVIE, ContentType.VOD -> movieDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.SERIES -> seriesDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.SERIES_EPISODE -> Unit
    }
    return com.streamvault.domain.manager.PortableContentReference(
        provider = providerReference,
        contentType = contentType,
        remoteContentId = remoteId
    )
}

private suspend fun com.streamvault.domain.model.PlaybackHistory.toPortableContentReference(
    providersById: Map<Long, Provider>,
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    episodeDao: EpisodeDao
): com.streamvault.domain.manager.PortableContentReference? {
    val remoteId = remoteContentId?.takeIf { it.isNotBlank() } ?: return null
    val provider = providersById[providerId] ?: return null
    val providerReference = provider.toBackupProviderReference()
    when (contentType) {
        ContentType.LIVE -> channelDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.MOVIE, ContentType.VOD -> movieDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.SERIES -> seriesDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { return it.toPortableContentReference(providerReference) }
        ContentType.SERIES_EPISODE -> episodeDao.getById(contentId)
            ?.takeIf { it.providerId == providerId }
            ?.let { episode ->
                seriesDao.getById(episode.seriesId)
                    ?.takeIf { it.providerId == providerId }
                    ?.let { series -> return episode.toPortableContentReference(providerReference, series) }
            }
    }
    return com.streamvault.domain.manager.PortableContentReference(
        provider = providerReference,
        contentType = contentType,
        remoteContentId = remoteId,
        parentRemoteContentId = remoteSeriesId,
        name = title,
        urlFallback = streamUrl.takeIf { it.isNotBlank() }
    )
}

private fun com.streamvault.data.local.entity.ChannelEntity.toPortableContentReference(
    provider: BackupProviderReference
) = com.streamvault.domain.manager.PortableContentReference(
    provider = provider,
    contentType = ContentType.LIVE,
    remoteContentId = streamId.toString(),
    remoteCategoryId = categoryId?.toString(),
    name = name,
    urlFallback = streamUrl.takeIf { it.isNotBlank() }
)

private fun com.streamvault.data.local.entity.MovieEntity.toPortableContentReference(
    provider: BackupProviderReference
) = com.streamvault.domain.manager.PortableContentReference(
    provider = provider,
    contentType = ContentType.MOVIE,
    remoteContentId = streamId.toString(),
    remoteCategoryId = categoryId?.toString(),
    name = name,
    urlFallback = streamUrl.takeIf { it.isNotBlank() }
)

private fun com.streamvault.data.local.entity.SeriesEntity.toPortableContentReference(
    provider: BackupProviderReference
) = com.streamvault.domain.manager.PortableContentReference(
    provider = provider,
    contentType = ContentType.SERIES,
    remoteContentId = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString(),
    remoteCategoryId = categoryId?.toString(),
    name = name
)

private fun com.streamvault.data.local.entity.EpisodeEntity.toPortableContentReference(
    provider: BackupProviderReference,
    series: com.streamvault.data.local.entity.SeriesEntity
) = com.streamvault.domain.manager.PortableContentReference(
    provider = provider,
    contentType = ContentType.SERIES_EPISODE,
    remoteContentId = episodeId.toString(),
    parentRemoteContentId = series.providerSeriesId?.takeIf { it.isNotBlank() } ?: series.seriesId.toString(),
    remoteCategoryId = series.categoryId?.toString(),
    name = title,
    urlFallback = streamUrl.takeIf { it.isNotBlank() }
)

private fun BackupProviderReference.stableIdentityKey(): String = listOf(
    normalizeProviderServerUrl(serverUrl),
    username.trim(),
    providerType?.name.orEmpty(),
    stalkerMacAddress.normalizedIdentity()
).joinToString("|")

private fun Iterable<Provider>.findUnambiguousPortableProvider(
    reference: BackupProviderReference
): Provider? {
    val normalizedMac = reference.stalkerMacAddress.normalizedIdentity()
    return filter { provider ->
        normalizeProviderServerUrl(provider.serverUrl) == normalizeProviderServerUrl(reference.serverUrl) &&
            (reference.providerType == null || provider.type == reference.providerType) &&
            provider.username.trim() == reference.username.trim() &&
            provider.stalkerMacAddress.normalizedIdentity() == normalizedMac
    }.singleOrNull()
}

/**
 * Provider URLs are identities, not display strings. The setup flow normally stores a
 * canonical value, but backups can cross releases/devices where harmless differences such as
 * a trailing slash, URL scheme/host case, or an explicit default port are present.
 */
private fun normalizeProviderServerUrl(value: String): String {
    val trimmed = value.trim()
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            return@runCatching trimmed.trimEnd('/').lowercase(Locale.ROOT)
        }
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$scheme://$host$port$path$query"
    }.getOrElse {
        trimmed.trimEnd('/').lowercase(Locale.ROOT)
    }
}

private fun List<com.streamvault.domain.model.Category>.resolvePortableCategory(
    reference: PortableCategoryReference
): com.streamvault.domain.model.Category? {
    val candidates = filter { it.type == reference.type }
    reference.remoteCategoryId?.let { remoteId ->
        candidates.singleOrNull { it.id == remoteId }?.let { return it }
    }
    val normalizedName = reference.name.normalizedIdentity()
    return candidates.filter { it.name.normalizedIdentity() == normalizedName }.singleOrNull()
}

private fun List<com.streamvault.domain.model.Category>.resolvePortableCategoryId(
    reference: PortableCategoryReference
): Long? {
    if (none { it.type == reference.type }) return reference.remoteCategoryId
    return resolvePortableCategory(reference)?.id
}

private fun List<com.streamvault.data.local.entity.ChannelEntity>.resolvePortableChannel(
    reference: PortableChannelReference
): com.streamvault.data.local.entity.ChannelEntity? {
    filter { it.streamId == reference.streamId }.singleOrNull()?.let { return it }
    val normalizedName = reference.name.normalizedIdentity()
    val normalizedUrl = reference.streamUrl.trim()
    val exactMatches = filter {
        it.streamId == reference.streamId &&
            it.name.normalizedIdentity() == normalizedName &&
            it.streamUrl.trim() == normalizedUrl
    }
    exactMatches.singleOrNull()?.let { return it }
    return filter {
        it.name.normalizedIdentity() == normalizedName &&
            it.streamUrl.trim() == normalizedUrl
    }.singleOrNull()
}

private fun BackupProviderReference.unresolvedLabel(kind: String): String =
    "$kind ${serverUrl} (${username.ifBlank { "anonymous" }})"

private fun PortableCategoryReference.unresolvedLabel(kind: String): String =
    "$kind ${name} [${type.name}] at ${provider.serverUrl}"

private fun PortableVirtualGroupReference.unresolvedLabel(kind: String): String =
    "$kind ${name} [${contentType.name}] at ${provider.serverUrl}"

private fun PortableChannelReference.unresolvedLabel(kind: String): String =
    "$kind ${name} (stream $streamId) at ${provider.serverUrl}"

private fun PortableVariantSelectionReference.unresolvedLabel(kind: String): String =
    "$kind ${logicalGroupId} (item ${remoteItemId ?: rawItemId}) at ${provider.serverUrl}"

private fun String?.normalizedIdentity(): String = orEmpty().trim().lowercase()

private fun ProtectedCategoryBackup.backupIdentity(): Triple<String, String, String> =
    Triple(
        normalizeProviderServerUrl(providerServerUrl),
        providerUsername.trim(),
        providerStalkerMacAddress.normalizedIdentity()
    )

private fun ScheduledRecordingBackup.backupIdentity(): Triple<String, String, String> =
    Triple(
        normalizeProviderServerUrl(providerServerUrl),
        providerUsername.trim(),
        providerStalkerMacAddress.normalizedIdentity()
    )

internal fun com.streamvault.domain.model.RecordingItem.toScheduledRecordingBackup(
    provider: com.streamvault.domain.model.LegacyProvider,
    schedule: RecordingScheduleEntity?
): ScheduledRecordingBackup {
    val requestedStart = schedule?.requestedStartMs ?: scheduledStartMs
    val requestedEnd = schedule?.requestedEndMs ?: scheduledEndMs
    val paddingBeforeMs = (requestedStart - scheduledStartMs).coerceAtLeast(0L)
    val paddingAfterMs = (scheduledEndMs - requestedEnd).coerceAtLeast(0L)
    return ScheduledRecordingBackup(
        providerServerUrl = provider.serverUrl,
        providerUsername = provider.username,
        providerStalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
        channelId = channelId,
        channelName = channelName,
        streamUrl = streamUrl,
        scheduledStartMs = scheduledStartMs,
        scheduledEndMs = scheduledEndMs,
        requestedStartMs = requestedStart,
        requestedEndMs = requestedEnd,
        paddingBeforeMs = paddingBeforeMs,
        paddingAfterMs = paddingAfterMs,
        programTitle = programTitle,
        recurrence = recurrence,
        recurringRuleId = schedule?.recurringRuleId ?: recurringRuleId,
        providerType = provider.type
    )
}

internal fun ScheduledRecordingBackup.toRecordingRequest(providerId: Long): RecordingRequest {
    val restoredRequestedStartMs = requestedStartMs ?: scheduledStartMs
    val restoredRequestedEndMs = requestedEndMs ?: scheduledEndMs
    val restoredPaddingBeforeMs = paddingBeforeMs
        ?: requestedStartMs?.let { (it - scheduledStartMs).coerceAtLeast(0L) }
        ?: 0L
    val restoredPaddingAfterMs = paddingAfterMs
        ?: requestedEndMs?.let { (scheduledEndMs - it).coerceAtLeast(0L) }
        ?: 0L
    return RecordingRequest(
        providerId = providerId,
        channelId = channelId,
        channelName = channelName,
        streamUrl = streamUrl,
        scheduledStartMs = restoredRequestedStartMs,
        scheduledEndMs = restoredRequestedEndMs,
        programTitle = programTitle,
        recurrence = recurrence,
        recurringRuleId = recurringRuleId,
        paddingBeforeMs = restoredPaddingBeforeMs,
        paddingAfterMs = restoredPaddingAfterMs
    )
}

internal fun List<ScheduledRecordingBackup>.normalizedRecurringBackups(): List<ScheduledRecordingBackup> {
    if (isEmpty()) return emptyList()
    val oneShot = filterNot { it.hasStableRecurringIdentity() }
    val recurring = filter { it.hasStableRecurringIdentity() }
        .groupBy { it.recurringRuleId!! }
        .values
        .map { group ->
            group.minWithOrNull(compareBy<ScheduledRecordingBackup> { it.requestedStartMs ?: it.scheduledStartMs }
                .thenBy { it.requestedEndMs ?: it.scheduledEndMs }
                .thenBy { it.scheduledStartMs }) ?: group.first()
        }
    return (oneShot + recurring).sortedBy { it.requestedStartMs ?: it.scheduledStartMs }
}

internal suspend fun importScheduledRecordingBackups(
    recordings: List<ScheduledRecordingBackup>,
    storedProviders: List<Provider>,
    existingSchedules: MutableList<com.streamvault.domain.model.RecordingItem>,
    conflictStrategy: BackupConflictStrategy,
    recordingManager: RecordingManager,
    nowMs: Long = System.currentTimeMillis()
): RecordingScheduleImportSummary {
    val outcomes = mutableListOf<RecordingScheduleImportOutcome>()
    recordings.forEach { scheduled ->
        if (scheduled.scheduledEndMs <= nowMs) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_EXPIRED,
                reason = "Recording window has already ended."
            )
            return@forEach
        }

        val provider = storedProviders.findMatchingProvider(
            serverUrl = scheduled.providerServerUrl,
            username = scheduled.providerUsername,
            stalkerMacAddress = scheduled.providerStalkerMacAddress,
            providerType = scheduled.providerType
        )
        if (provider == null) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_MISSING_PROVIDER,
                reason = "Matching provider was not found during import."
            )
            return@forEach
        }

        val conflict = existingSchedules.firstOrNull {
            it.providerId == provider.id &&
                it.scheduledStartMs == scheduled.scheduledStartMs &&
                (it.channelId == scheduled.channelId || it.streamUrl == scheduled.streamUrl)
        }
        if (conflict != null && conflictStrategy == BackupConflictStrategy.KEEP_EXISTING) {
            outcomes += scheduled.toImportOutcome(
                disposition = RecordingScheduleImportDisposition.SKIPPED_EXISTING,
                reason = "Keeping existing schedule for the same provider, start time, and channel/stream."
            )
            return@forEach
        }
        when (val result = recordingManager.scheduleRecording(scheduled.toRecordingRequest(provider.id))) {
            is Result.Success -> {
                if (conflict != null && conflictStrategy == BackupConflictStrategy.REPLACE_EXISTING) {
                    when (val cancelResult = recordingManager.cancelRecording(conflict.id)) {
                        is Result.Success -> existingSchedules.remove(conflict)
                        is Result.Error -> {
                            // The replacement is already armed; remove it again so a failed
                            // promotion leaves the prior schedule intact.
                            recordingManager.cancelRecording(result.data.id)
                            outcomes += scheduled.toImportOutcome(
                                disposition = RecordingScheduleImportDisposition.FAILED,
                                reason = "Could not replace existing schedule: ${cancelResult.message}"
                            )
                            return@forEach
                        }
                        Result.Loading -> {
                            recordingManager.cancelRecording(result.data.id)
                            outcomes += scheduled.toImportOutcome(
                                disposition = RecordingScheduleImportDisposition.FAILED,
                                reason = "Could not replace existing schedule because cancellation did not complete."
                            )
                            return@forEach
                        }
                    }
                }
                existingSchedules += result.data
                outcomes += scheduled.toImportOutcome(
                    disposition = if (conflict != null) {
                        RecordingScheduleImportDisposition.REPLACED_EXISTING
                    } else {
                        RecordingScheduleImportDisposition.IMPORTED
                    }
                )
            }
            is Result.Error -> {
                outcomes += scheduled.toImportOutcome(
                    disposition = RecordingScheduleImportDisposition.FAILED,
                    reason = result.message
                )
            }
            Result.Loading -> {
                outcomes += scheduled.toImportOutcome(
                    disposition = RecordingScheduleImportDisposition.FAILED,
                    reason = "Scheduling did not complete."
                )
            }
        }
    }
    return RecordingScheduleImportSummary(outcomes = outcomes)
}

private fun ScheduledRecordingBackup.toImportOutcome(
    disposition: RecordingScheduleImportDisposition,
    reason: String? = null
): RecordingScheduleImportOutcome = RecordingScheduleImportOutcome(
    channelName = channelName,
    programTitle = programTitle,
    scheduledStartMs = scheduledStartMs,
    scheduledEndMs = scheduledEndMs,
    recurrence = recurrence,
    disposition = disposition,
    reason = reason
)

private fun ScheduledRecordingBackup.hasStableRecurringIdentity(): Boolean =
    recurrence != RecordingRecurrence.NONE && !recurringRuleId.isNullOrBlank()

private suspend fun com.streamvault.domain.model.Favorite.withPortableIdentity(
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao
): com.streamvault.domain.model.Favorite =
    copy(
        remoteContentId = remoteContentId ?: resolvePortableContentId(
            channelDao = channelDao,
            movieDao = movieDao,
            seriesDao = seriesDao,
            providerId = providerId,
            contentType = contentType,
            localContentId = contentId
        )
    )

private suspend fun com.streamvault.domain.model.PlaybackHistory.withPortableIdentity(
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    episodeDao: EpisodeDao
): com.streamvault.domain.model.PlaybackHistory {
    val episode = if (contentType == ContentType.SERIES_EPISODE) {
        episodeDao.getById(contentId)?.takeIf { it.providerId == providerId }
    } else {
        null
    }
    val series = when {
        episode != null -> seriesDao.getById(episode.seriesId)?.takeIf { it.providerId == providerId }
        else -> seriesId?.let { id -> seriesDao.getById(id)?.takeIf { it.providerId == providerId } }
    }
    return copy(
        remoteContentId = remoteContentId ?: when (contentType) {
            ContentType.LIVE -> channelDao.getById(contentId)
                ?.takeIf { it.providerId == providerId }
                ?.streamId
                ?.takeIf { it > 0L }
                ?.toString()
            ContentType.MOVIE -> movieDao.getById(contentId)
                ?.takeIf { it.providerId == providerId }
                ?.streamId
                ?.takeIf { it > 0L }
                ?.toString()
            ContentType.SERIES -> series?.remoteKey()
            ContentType.SERIES_EPISODE -> episode?.episodeId
                ?.takeIf { it > 0L }
                ?.toString()
            ContentType.VOD -> null
        },
        remoteSeriesId = remoteSeriesId ?: if (contentType == ContentType.SERIES_EPISODE) {
            series?.remoteKey()
        } else {
            null
        }
    )
}

private suspend fun resolvePortableContentId(
    channelDao: ChannelDao,
    movieDao: MovieDao,
    seriesDao: SeriesDao,
    providerId: Long,
    contentType: ContentType,
    localContentId: Long
): String? = when (contentType) {
    ContentType.LIVE -> channelDao.getById(localContentId)
        ?.takeIf { it.providerId == providerId }
        ?.streamId
        ?.takeIf { it > 0L }
        ?.toString()
    ContentType.MOVIE -> movieDao.getById(localContentId)
        ?.takeIf { it.providerId == providerId }
        ?.streamId
        ?.takeIf { it > 0L }
        ?.toString()
    ContentType.SERIES -> seriesDao.getById(localContentId)
        ?.takeIf { it.providerId == providerId }
        ?.remoteKey()
    ContentType.SERIES_EPISODE,
    ContentType.VOD -> null
}

private fun com.streamvault.data.local.entity.SeriesEntity.remoteKey(): String? =
    providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.takeIf { it > 0L }?.toString()

private data class SavedGroupConflictKey(
    val providerId: Long,
    val name: String,
    val contentType: ContentType
)

private data class SavedFavoriteConflictKey(
    val providerId: Long,
    val contentType: ContentType,
    val remoteContentId: String,
    val group: String?
)

private data class SavedHistoryConflictKey(
    val providerId: Long,
    val contentType: ContentType,
    val remoteContentId: String
)

private fun com.streamvault.domain.model.LegacyProvider.toSecureEntityForBackup(
    credentialCrypto: CredentialCrypto
) = copy(password = credentialCrypto.encryptIfNeeded(password)).toEntity()

private fun Provider.toBackupCredentials(): ProviderCredentials? {
    if (username.isBlank() || password.isBlank()) return null
    return ProviderCredentials(
        serverUrl = serverUrl,
        username = username,
        password = password,
        providerType = type,
    )
}

private fun Iterable<Provider>.findMatchingProvider(
    serverUrl: String,
    username: String,
    stalkerMacAddress: String?,
    providerType: ProviderType? = null
): Provider? {
    val candidates = filter {
        normalizeProviderServerUrl(it.serverUrl) == normalizeProviderServerUrl(serverUrl) &&
            (providerType == null || it.type == providerType) &&
            (it.type == ProviderType.M3U || it.username.trim() == username.trim())
    }
    val normalizedMacAddress = stalkerMacAddress
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()

    if (normalizedMacAddress != null) {
        return candidates.firstOrNull { it.stalkerMacAddress.equals(normalizedMacAddress, ignoreCase = true) }
    }

    return candidates.singleOrNull { it.stalkerMacAddress.isBlank() }
        ?: candidates.singleOrNull()
}

private const val SHA256_PREFIX = "sha256:"
private val RAW_CHECKSUM_FIELD_PATTERN = Regex("\\\"checksum\\\"\\s*:\\s*\\\"[^\\\"]*\\\"\\s*,?")

    private fun com.streamvault.domain.model.ProviderConfiguration.toBackupSnapshot(
    provider: StableProvider,
    accountRuntime: ProviderAccountRuntime
): ProviderBackupSnapshot = when (this) {
    is com.streamvault.domain.model.XtreamConfig -> ProviderBackupSnapshot(provider, accountRuntime, xtreamConfig = this)
    is com.streamvault.domain.model.M3uConfig -> ProviderBackupSnapshot(provider, accountRuntime, m3uConfig = this)
    is com.streamvault.domain.model.StalkerConfig -> ProviderBackupSnapshot(provider, accountRuntime, stalkerConfig = this)
    is com.streamvault.domain.model.JellyfinConfig -> ProviderBackupSnapshot(provider, accountRuntime, jellyfinConfig = this)
}

private const val RESTORE_STATE_RUNNING = "RUNNING"
private const val RESTORE_STATE_COMPLETE = "COMPLETE"
private const val RESTORE_STATE_PARTIAL = "PARTIAL"
private const val RESTORE_STATE_WAITING_FOR_SYNC = "WAITING_FOR_SYNC"
private const val RESTORE_STATE_FAILED_BEFORE_COMMIT = "FAILED_BEFORE_COMMIT"
private const val RESTORE_SNAPSHOT_PRESET_1 = "__restore_snapshot_preset_1"
private const val RESTORE_SNAPSHOT_PRESET_2 = "__restore_snapshot_preset_2"
private const val RESTORE_SNAPSHOT_PRESET_3 = "__restore_snapshot_preset_3"
private const val RESTORE_SNAPSHOT_CHANNEL_PREFERENCES = "__restore_snapshot_channel_preferences"
private const val CURRENT_BACKUP_VERSION = 14
private const val RESTORE_SECTION_FAVORITES = "FAVORITES"
private const val RESTORE_SECTION_CUSTOM_GROUPS = "CUSTOM_GROUPS"
private const val RESTORE_SECTION_PLAYBACK_HISTORY = "PLAYBACK_HISTORY"
private const val RESTORE_SECTION_PROTECTED_CONTENT = "PROTECTED_CONTENT"
private const val RESTORE_SECTION_HIDDEN_CONTENT = "HIDDEN_CONTENT"
private const val RESTORE_SECTION_HIDDEN_CATEGORIES = "HIDDEN_CATEGORIES"
private const val RESTORE_SECTION_CONTENT_PREFERENCES = "CONTENT_PREFERENCES"
private const val RESTORE_SECTION_VARIANT_CHOICES = "VARIANT_CHOICES"
private const val RESTORE_SECTION_MANUAL_EPG = "MANUAL_EPG"
private const val RESTORE_SECTION_MULTIVIEW = "MULTIVIEW"
private const val RESTORE_SECTION_RECORDING_SCHEDULES = "RECORDING_SCHEDULES"
private const val RESTORE_SECTION_SEARCH_HISTORY = "SEARCH_HISTORY"
private const val RESTORE_SECTION_REPLACE_SCOPE = "REPLACE_SCOPE"
private const val LEGACY_LOCAL_ID_PREFIX = "legacy-local-id:"
private const val GLOBAL_RESTORE_PROVIDER_KEY = "__GLOBAL__"
private const val LEGACY_NO_ACTIVE_PROVIDER_WARNING =
    "Active provider id -1 was not found during export"
private const val FILE_URI_SCHEME = "file"
private val PORTABLE_VIRTUAL_CATEGORY_IDS = setOf(-998L, -999L)
private val MAP_STRING_STRING_TYPE: Type = object : TypeToken<Map<String, String>>() {}.type
private val PROVIDER_TYPE: Type = com.streamvault.domain.model.LegacyProvider::class.java
private val PROVIDER_SNAPSHOT_TYPE: Type = ProviderBackupSnapshot::class.java
private val PROVIDER_CREDENTIALS_TYPE: Type = ProviderCredentials::class.java
private val FAVORITE_TYPE: Type = com.streamvault.domain.model.Favorite::class.java
private val VIRTUAL_GROUP_TYPE: Type = com.streamvault.domain.model.VirtualGroup::class.java
private val PLAYBACK_HISTORY_TYPE: Type = com.streamvault.domain.model.PlaybackHistory::class.java
private val PROTECTED_CATEGORY_TYPE: Type = ProtectedCategoryBackup::class.java
private val SCHEDULED_RECORDING_TYPE: Type = ScheduledRecordingBackup::class.java
private val RECORDING_STORAGE_TYPE: Type = RecordingStorageBackup::class.java
private val BACKUP_PROVIDER_REFERENCE_TYPE: Type = BackupProviderReference::class.java
private val PORTABLE_CATEGORY_REFERENCE_TYPE: Type = PortableCategoryReference::class.java
private val PORTABLE_CATEGORY_SORT_REFERENCE_TYPE: Type = PortableCategorySortReference::class.java
private val PORTABLE_GROUP_REFERENCE_TYPE: Type = PortableVirtualGroupReference::class.java
private val PORTABLE_CHANNEL_REFERENCE_TYPE: Type = PortableChannelReference::class.java
private val PORTABLE_CHANNEL_PREFERENCE_REFERENCE_TYPE: Type = PortableChannelPreferenceReference::class.java
private val PORTABLE_EPG_TIME_SHIFT_REFERENCE_TYPE: Type = PortableEpgTimeShiftReference::class.java
private val PORTABLE_VARIANT_SELECTION_REFERENCE_TYPE: Type = PortableVariantSelectionReference::class.java
private val COMBINED_M3U_PROFILE_TYPE: Type = CombinedM3uProfileBackup::class.java
private val COMBINED_M3U_PROFILE_LIST_TYPE: Type = object : TypeToken<List<CombinedM3uProfileBackup>>() {}.type
private val ACTIVE_LIVE_SOURCE_BACKUP_TYPE: Type = ActiveLiveSourceBackup::class.java
private val LONG_TYPE: Type = java.lang.Long::class.java
private val STRING_TYPE: Type = String::class.java
private val PROVIDER_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.LegacyProvider>>() {}.type
private val PROVIDER_SNAPSHOT_LIST_TYPE: Type = object : TypeToken<List<ProviderBackupSnapshot>>() {}.type
private val PROVIDER_CREDENTIALS_LIST_TYPE: Type = object : TypeToken<List<ProviderCredentials>>() {}.type
private val EPG_SOURCE_TYPE: Type = com.streamvault.domain.model.EpgSource::class.java
private val EPG_SOURCE_LIST_TYPE: Type =
    object : TypeToken<List<com.streamvault.domain.model.EpgSource>>() {}.type
private val PROVIDER_EPG_ASSIGNMENT_TYPE: Type = ProviderEpgAssignmentBackup::class.java
private val PROVIDER_EPG_ASSIGNMENT_LIST_TYPE: Type =
    object : TypeToken<List<ProviderEpgAssignmentBackup>>() {}.type
private val MANUAL_EPG_MAPPING_TYPE: Type = ManualEpgMappingBackup::class.java
private val MANUAL_EPG_MAPPING_LIST_TYPE: Type =
    object : TypeToken<List<ManualEpgMappingBackup>>() {}.type
private val M3U_CLASSIFICATION_OVERRIDE_TYPE: Type = M3uClassificationOverrideBackup::class.java
private val M3U_CLASSIFICATION_OVERRIDE_LIST_TYPE: Type =
    object : TypeToken<List<M3uClassificationOverrideBackup>>() {}.type
private val M3U_CLASSIFICATION_RULE_TYPE: Type = M3uClassificationRuleBackup::class.java
private val M3U_CLASSIFICATION_RULE_LIST_TYPE: Type =
    object : TypeToken<List<M3uClassificationRuleBackup>>() {}.type
private val PROGRAM_REMINDER_TYPE: Type = ProgramReminderBackup::class.java
private val PORTABLE_FAVORITE_TYPE: Type = PortableFavoriteBackup::class.java
private val PORTABLE_CUSTOM_GROUP_TYPE: Type = PortableCustomGroupBackup::class.java
private val PORTABLE_PLAYBACK_HISTORY_TYPE: Type = PortablePlaybackHistoryBackup::class.java
private val PORTABLE_PROTECTED_CONTENT_TYPE: Type = PortableProtectedContentBackup::class.java
private val PORTABLE_SEARCH_HISTORY_TYPE: Type = PortableSearchHistoryBackup::class.java
private val PORTABLE_HIDDEN_CONTENT_TYPE: Type = PortableHiddenContentBackup::class.java
private val PORTABLE_CONTENT_PREFERENCE_TYPE: Type = PortableContentPreferenceBackup::class.java
private val PORTABLE_VARIANT_CHOICE_TYPE: Type = PortableVariantChoiceBackup::class.java
private val PORTABLE_MANUAL_EPG_V14_TYPE: Type = PortableManualEpgMappingV14Backup::class.java
private val PORTABLE_MULTIVIEW_V14_TYPE: Type = PortableMultiViewPresetV14Backup::class.java
private val PROGRAM_REMINDER_LIST_TYPE: Type =
    object : TypeToken<List<ProgramReminderBackup>>() {}.type
private val PORTABLE_FAVORITE_LIST_TYPE: Type = object : TypeToken<List<PortableFavoriteBackup>>() {}.type
private val PORTABLE_CUSTOM_GROUP_LIST_TYPE: Type = object : TypeToken<List<PortableCustomGroupBackup>>() {}.type
private val PORTABLE_PLAYBACK_HISTORY_LIST_TYPE: Type =
    object : TypeToken<List<PortablePlaybackHistoryBackup>>() {}.type
private val PORTABLE_PROTECTED_CONTENT_LIST_TYPE: Type =
    object : TypeToken<List<PortableProtectedContentBackup>>() {}.type
private val PORTABLE_SEARCH_HISTORY_LIST_TYPE: Type =
    object : TypeToken<List<PortableSearchHistoryBackup>>() {}.type
private val PORTABLE_HIDDEN_CONTENT_LIST_TYPE: Type =
    object : TypeToken<List<PortableHiddenContentBackup>>() {}.type
private val PORTABLE_CONTENT_PREFERENCE_LIST_TYPE: Type = object : TypeToken<List<PortableContentPreferenceBackup>>() {}.type
private val PORTABLE_VARIANT_CHOICE_LIST_TYPE: Type = object : TypeToken<List<PortableVariantChoiceBackup>>() {}.type
private val PORTABLE_MANUAL_EPG_V14_LIST_TYPE: Type = object : TypeToken<List<PortableManualEpgMappingV14Backup>>() {}.type
private val PORTABLE_MULTIVIEW_V14_LIST_TYPE: Type = object : TypeToken<List<PortableMultiViewPresetV14Backup>>() {}.type
private val FAVORITE_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.Favorite>>() {}.type
private val VIRTUAL_GROUP_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.VirtualGroup>>() {}.type
private val PLAYBACK_HISTORY_LIST_TYPE: Type = object : TypeToken<List<com.streamvault.domain.model.PlaybackHistory>>() {}.type
private val MULTIVIEW_PRESETS_TYPE: Type = object : TypeToken<Map<String, List<Long>>>() {}.type
private val PORTABLE_MULTIVIEW_PRESETS_TYPE: Type = object : TypeToken<Map<String, List<PortableChannelReference>>>() {}.type
private val PROTECTED_CATEGORY_LIST_TYPE: Type = object : TypeToken<List<ProtectedCategoryBackup>>() {}.type
private val SCHEDULED_RECORDING_LIST_TYPE: Type = object : TypeToken<List<ScheduledRecordingBackup>>() {}.type
private val CHANNEL_PREFERENCE_ENTITY_LIST_TYPE: Type = object : TypeToken<List<ChannelPreferenceEntity>>() {}.type
