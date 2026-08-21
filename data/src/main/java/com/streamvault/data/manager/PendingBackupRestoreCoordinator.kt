package com.streamvault.data.manager

import com.google.gson.Gson
import com.streamvault.data.local.dao.BackupRestoreLedgerDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.BackupRestoreItemEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ChannelPreferenceEntity
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.PlaybackHistoryEntity
import com.streamvault.data.local.entity.SearchHistoryEntity
import com.streamvault.data.local.entity.VirtualGroupEntity
import com.streamvault.domain.manager.PortableContentReference
import com.streamvault.domain.manager.PortableCategoryReference
import com.streamvault.domain.manager.PortableCustomGroupBackup
import com.streamvault.domain.manager.PortableFavoriteBackup
import com.streamvault.domain.manager.PortablePlaybackHistoryBackup
import com.streamvault.domain.manager.PortableProtectedContentBackup
import com.streamvault.domain.manager.PortableHiddenContentBackup
import com.streamvault.domain.manager.PortableContentPreferenceBackup
import com.streamvault.domain.manager.PortableVariantChoiceBackup
import com.streamvault.domain.manager.PortableManualEpgMappingV14Backup
import com.streamvault.domain.manager.PortableMultiViewPresetV14Backup
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.manager.ScheduledRecordingBackup
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import com.streamvault.data.provider.toLegacyProvider
import java.net.URI
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.PortableSearchHistoryBackup
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RestoreResolutionSummary(
    val appliedCount: Int = 0,
    val unresolvedCount: Int = 0,
    val failedCount: Int = 0
)

/** Applies durable v14 restore intentions after provider catalog transactions commit. */
@Singleton
class PendingBackupRestoreCoordinator @Inject constructor(
    private val ledgerDao: BackupRestoreLedgerDao,
    private val providerDao: ProviderDao,
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val channelPreferenceDao: ChannelPreferenceDao,
    private val channelEpgMappingDao: ChannelEpgMappingDao,
    private val epgSourceDao: EpgSourceDao,
    private val preferencesRepository: PreferencesRepository,
    private val providerSnapshotRepository: ProviderSnapshotRepository,
    private val recordingManager: RecordingManager,
    private val gson: Gson,
    private val categoryDao: CategoryDao? = null
) {
    private data class ReplaceScopePayload(
        val provider: BackupProviderReference?,
        val targetSection: String,
        val contentType: ContentType? = null
    )

    private val providerLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun applyForProvider(providerId: Long): RestoreResolutionSummary {
        val providerSummary = providerLocks.getOrPut(providerId) { Mutex() }.withLock {
            providerDao.getById(providerId) ?: return@withLock RestoreResolutionSummary()
            applyItems(retryableItemsForProvider(providerId), providerId)
        }
        // Global records such as multiview presets can span providers. Retry them after every
        // provider commit so the final required catalog becoming available applies the preset.
        return providerSummary + applyGlobal()
    }

    suspend fun applyGlobal(): RestoreResolutionSummary = providerLocks.getOrPut(0L) { Mutex() }.withLock {
        applyItems(ledgerDao.getRetryableItems(GLOBAL_PROVIDER_KEY).orEmpty(), providerId = 0L)
    }

    suspend fun applyAllAvailable() {
        applyGlobal()
        providerDao.getAllSync().forEach { provider -> applyForProvider(provider.id) }
    }

    /** Hidden categories normally skip item downloads. Pending user state is the one-time exception. */
    suspend fun requiredHiddenCategoryIds(providerId: Long, contentType: ContentType): Set<Long> {
        val hidden = preferencesRepository.getHiddenCategoryIds(providerId, contentType).first()
        if (hidden.isEmpty()) return emptySet()
        val providerItems = retryableItemsForProvider(providerId)
        val globalItems = ledgerDao.getRetryableItems(GLOBAL_PROVIDER_KEY).orEmpty()
            .filterNot { global -> providerItems.any { it.id == global.id } }
        val required = linkedSetOf<Long>()
        (providerItems.map { it to false } + globalItems.map { it to true }).forEach { (item, isGlobal) ->
            portableReferences(item).forEach { reference ->
                if (reference.contentType != contentType) return@forEach
                if (isGlobal && resolveProviderId(reference.provider) != providerId) return@forEach
                reference.remoteCategoryId?.toLongOrNull()?.let(required::add)
            }
        }
        return required.intersect(hidden)
    }

    /** Some restore sections contain several content references inside their payload. */
    private fun portableReferences(item: BackupRestoreItemEntity): List<PortableContentReference> = runCatching {
        when (item.section) {
            SECTION_FAVORITES -> listOf(gson.fromJson(item.payloadJson, PortableFavoriteBackup::class.java).content)
            SECTION_CUSTOM_GROUPS -> gson.fromJson(item.payloadJson, PortableCustomGroupBackup::class.java)
                .members.map { it.content }
            SECTION_PLAYBACK_HISTORY -> listOf(gson.fromJson(item.payloadJson, PortablePlaybackHistoryBackup::class.java).content)
            SECTION_PROTECTED_CONTENT -> listOf(gson.fromJson(item.payloadJson, PortableProtectedContentBackup::class.java).content)
            SECTION_HIDDEN_CONTENT -> listOf(gson.fromJson(item.payloadJson, PortableHiddenContentBackup::class.java).content)
            SECTION_CONTENT_PREFERENCES -> listOf(gson.fromJson(item.payloadJson, PortableContentPreferenceBackup::class.java).content)
            SECTION_VARIANT_CHOICES -> listOf(gson.fromJson(item.payloadJson, PortableVariantChoiceBackup::class.java).selectedContent)
            SECTION_MANUAL_EPG -> listOf(gson.fromJson(item.payloadJson, PortableManualEpgMappingV14Backup::class.java).content)
            SECTION_MULTIVIEW -> gson.fromJson(item.payloadJson, PortableMultiViewPresetV14Backup::class.java).channels
            SECTION_RECORDING_SCHEDULES -> listOfNotNull(
                gson.fromJson(item.payloadJson, ScheduledRecordingBackup::class.java).channel
            )
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private suspend fun applyItems(
        items: List<BackupRestoreItemEntity>,
        providerId: Long
    ): RestoreResolutionSummary {
        var applied = 0
        var unresolved = 0
        var failed = 0
        val affectedJobs = linkedSetOf<String>()
        val itemsAppliedByScopeMarker = mutableSetOf<Long>()
        items.forEach { item ->
            if (item.id in itemsAppliedByScopeMarker) return@forEach
            affectedJobs += item.jobId
            val now = System.currentTimeMillis()
            try {
                val didApply = applyItem(item, providerId, itemsAppliedByScopeMarker)
                if (didApply) {
                    applied += 1
                    ledgerDao.updateItemStatus(
                        item.id,
                        BackupRestoreItemEntity.STATUS_APPLIED,
                        providerId.takeIf { it != 0L },
                        1,
                        null,
                        now
                    )
                } else {
                    unresolved += 1
                    ledgerDao.updateItemStatus(
                        item.id,
                        BackupRestoreItemEntity.STATUS_UNRESOLVED,
                        providerId.takeIf { it != 0L },
                        1,
                        "Catalog identity is not available yet",
                        now
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += 1
                ledgerDao.updateItemStatus(
                    item.id,
                    BackupRestoreItemEntity.STATUS_FAILED_RETRYABLE,
                    providerId.takeIf { it != 0L },
                    1,
                    error.message ?: error::class.java.simpleName,
                    now
                )
            }
        }
        affectedJobs.forEach { ledgerDao.refreshJobCounts(it, System.currentTimeMillis()) }
        return RestoreResolutionSummary(applied, unresolved, failed)
    }

    private suspend fun applyItem(
        item: BackupRestoreItemEntity,
        providerId: Long,
        itemsAppliedByScopeMarker: MutableSet<Long> = mutableSetOf()
    ): Boolean = when (item.section) {
        SECTION_REPLACE_SCOPE -> applyReplaceScope(item, providerId, itemsAppliedByScopeMarker)
        SECTION_FAVORITES -> applyFavorite(item, providerId)
        SECTION_CUSTOM_GROUPS -> applyCustomGroup(item, providerId)
        SECTION_PLAYBACK_HISTORY -> applyPlaybackHistory(item, providerId)
        SECTION_PROTECTED_CONTENT -> applyProtectedContent(item, providerId)
        SECTION_HIDDEN_CONTENT -> applyHiddenContent(item, providerId)
        SECTION_HIDDEN_CATEGORIES -> applyHiddenCategory(item, providerId)
        SECTION_CONTENT_PREFERENCES -> applyContentPreference(item, providerId)
        SECTION_VARIANT_CHOICES -> applyVariantChoice(item, providerId)
        SECTION_MANUAL_EPG -> applyManualEpg(item, providerId)
        SECTION_MULTIVIEW -> applyMultiView(item)
        SECTION_RECORDING_SCHEDULES -> applyRecordingSchedule(item, providerId)
        SECTION_SEARCH_HISTORY -> applySearchHistory(item, providerId)
        else -> false
    }

    private suspend fun applyReplaceScope(
        marker: BackupRestoreItemEntity,
        providerId: Long,
        itemsAppliedByScopeMarker: MutableSet<Long>
    ): Boolean {
        val scope = gson.fromJson(marker.payloadJson, ReplaceScopePayload::class.java)
        val targets = ledgerDao.getItems(marker.jobId).filter { candidate ->
            candidate.providerIdentityKey == marker.providerIdentityKey &&
                candidate.section == scope.targetSection &&
                candidate.status != BackupRestoreItemEntity.STATUS_DISMISSED &&
                (scope.contentType == null || candidate.contentType == scope.contentType.name)
        }
        if (targets.any { !canResolve(it, providerId) }) return false
        clearScope(providerId, scope)
        val now = System.currentTimeMillis()
        targets.forEach { target ->
            check(applyItem(target, providerId)) { "Resolved restore item could not be applied" }
            ledgerDao.updateItemStatus(
                target.id,
                BackupRestoreItemEntity.STATUS_APPLIED,
                providerId.takeIf { it != 0L },
                1,
                null,
                now
            )
            itemsAppliedByScopeMarker += target.id
        }
        return true
    }

    private suspend fun canResolve(item: BackupRestoreItemEntity, providerId: Long): Boolean = when (item.section) {
        SECTION_FAVORITES -> resolveContent(providerId, gson.fromJson(item.payloadJson, PortableFavoriteBackup::class.java).content) != null
        SECTION_CUSTOM_GROUPS -> gson.fromJson(item.payloadJson, PortableCustomGroupBackup::class.java)
            .members.all { resolveContent(providerId, it.content) != null }
        SECTION_PLAYBACK_HISTORY -> {
            val backup = gson.fromJson(item.payloadJson, PortablePlaybackHistoryBackup::class.java)
            val parentRemoteId = backup.content.parentRemoteContentId
            resolveContent(providerId, backup.content) != null &&
                (parentRemoteId == null || resolveContent(
                    providerId,
                    backup.content.copy(contentType = ContentType.SERIES, remoteContentId = parentRemoteId)
                ) != null)
        }
        SECTION_PROTECTED_CONTENT -> resolveContent(providerId, gson.fromJson(item.payloadJson, PortableProtectedContentBackup::class.java).content) != null
        SECTION_HIDDEN_CONTENT -> resolveContent(providerId, gson.fromJson(item.payloadJson, PortableHiddenContentBackup::class.java).content) != null
        SECTION_HIDDEN_CATEGORIES -> resolveHiddenCategory(
            providerId,
            gson.fromJson(item.payloadJson, PortableCategoryReference::class.java)
        ) != null
        SECTION_CONTENT_PREFERENCES -> resolveContent(providerId, gson.fromJson(item.payloadJson, PortableContentPreferenceBackup::class.java).content) != null
        SECTION_VARIANT_CHOICES -> resolveContent(providerId, gson.fromJson(item.payloadJson, PortableVariantChoiceBackup::class.java).selectedContent) != null
        SECTION_MANUAL_EPG -> {
            val backup = gson.fromJson(item.payloadJson, PortableManualEpgMappingV14Backup::class.java)
            val sourceUrl = backup.sourceUrl
            resolveContent(providerId, backup.content) != null &&
                (sourceUrl == null || epgSourceDao.getByUrl(sourceUrl) != null)
        }
        SECTION_MULTIVIEW -> gson.fromJson(item.payloadJson, PortableMultiViewPresetV14Backup::class.java).channels.all { reference ->
            resolveProviderId(reference.provider)?.let { resolveContent(it, reference) } != null
        }
        SECTION_RECORDING_SCHEDULES -> gson.fromJson(item.payloadJson, ScheduledRecordingBackup::class.java).channel
            ?.let { resolveContent(providerId, it) } != null
        SECTION_SEARCH_HISTORY -> true
        else -> false
    }

    private suspend fun clearScope(providerId: Long, scope: ReplaceScopePayload) {
        when (scope.targetSection) {
            SECTION_FAVORITES -> scope.contentType?.let { favoriteDao.deleteGlobalByProviderAndType(providerId, it.name) }
            SECTION_CUSTOM_GROUPS -> scope.contentType?.let { virtualGroupDao.deleteByProviderAndType(providerId, it.name) }
            SECTION_PLAYBACK_HISTORY -> scope.contentType?.let { playbackHistoryDao.deleteByProviderAndType(providerId, it.name) }
            SECTION_PROTECTED_CONTENT -> when (scope.contentType) {
                ContentType.LIVE -> channelDao.resetProtectionToCategoryState(providerId)
                ContentType.MOVIE, ContentType.VOD -> movieDao.resetProtectionToCategoryState(providerId)
                ContentType.SERIES -> seriesDao.resetProtectionToCategoryState(providerId)
                ContentType.SERIES_EPISODE -> episodeDao.resetProtectionToCategoryState(providerId)
                null -> Unit
            }
            SECTION_HIDDEN_CONTENT -> preferencesRepository.setHiddenChannelIds(providerId, emptySet())
            SECTION_HIDDEN_CATEGORIES -> scope.contentType?.let { type ->
                preferencesRepository.setHiddenCategoryIds(providerId, type, emptySet())
            }
            SECTION_CONTENT_PREFERENCES -> channelPreferenceDao.deleteByProvider(providerId)
            SECTION_VARIANT_CHOICES -> {
                preferencesRepository.clearPreferredLiveVariants(providerId)
                preferencesRepository.clearPreferredVodVariants(providerId)
            }
            SECTION_MANUAL_EPG -> channelEpgMappingDao.deleteByProvider(providerId)
            SECTION_SEARCH_HISTORY -> searchHistoryDao.deleteByProvider(providerId)
            SECTION_MULTIVIEW -> repeat(3) { preferencesRepository.setMultiViewPreset(it, emptyList()) }
            SECTION_RECORDING_SCHEDULES -> recordingManager.observeRecordingItems().first()
                .filter { it.providerId == providerId && it.status == RecordingStatus.SCHEDULED }
                .forEach { recordingManager.cancelRecording(it.id) }
        }
    }

    private suspend fun applyFavorite(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableFavoriteBackup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        if (favoriteDao.get(providerId, resolved.localId, resolved.favoriteType.name, null) == null) {
            favoriteDao.insert(
                FavoriteEntity(
                    providerId = providerId,
                    contentId = resolved.localId,
                    contentType = resolved.favoriteType,
                    position = backup.position,
                    addedAt = backup.addedAt
                )
            )
        }
        return true
    }

    private suspend fun applyCustomGroup(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableCustomGroupBackup::class.java)
        val resolvedMembers = backup.members.map { member ->
            member to (resolveContent(providerId, member.content) ?: return false)
        }
        val matchingGroups = virtualGroupDao.getByType(providerId, backup.contentType.name).first()
            .filter { it.name.normalized() == backup.name.normalized() }
        val groupId = when (matchingGroups.size) {
            0 -> virtualGroupDao.insert(
                VirtualGroupEntity(
                    providerId = providerId,
                    name = backup.name,
                    iconEmoji = backup.icon,
                    position = backup.position,
                    createdAt = backup.createdAt,
                    contentType = backup.contentType
                )
            )
            1 -> matchingGroups.single().id
            else -> return false
        }
        resolvedMembers.forEach { (member, resolved) ->
            if (favoriteDao.get(providerId, resolved.localId, resolved.favoriteType.name, groupId) == null) {
                favoriteDao.insert(
                    FavoriteEntity(
                        providerId = providerId,
                        contentId = resolved.localId,
                        contentType = resolved.favoriteType,
                        position = member.position,
                        groupId = groupId,
                        groupKey = groupId,
                        addedAt = member.addedAt
                    )
                )
            }
        }
        return true
    }

    private suspend fun applyPlaybackHistory(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortablePlaybackHistoryBackup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        val parentSeriesId = backup.content.parentRemoteContentId?.let { remoteParent ->
            resolveContent(
                providerId,
                backup.content.copy(contentType = ContentType.SERIES, remoteContentId = remoteParent)
            )?.localId
        }
        playbackHistoryDao.insertOrUpdate(
            PlaybackHistoryEntity(
                contentId = resolved.localId,
                contentType = backup.content.contentType,
                providerId = providerId,
                title = backup.content.name.orEmpty(),
                posterUrl = backup.posterUrl,
                streamUrl = backup.content.urlFallback.orEmpty(),
                resumePositionMs = backup.resumePositionMs,
                totalDurationMs = backup.totalDurationMs,
                lastWatchedAt = backup.lastWatchedAt,
                watchCount = backup.watchCount.coerceAtLeast(1),
                watchedStatus = backup.watchedStatus,
                seriesId = parentSeriesId,
                seasonNumber = backup.seasonNumber,
                episodeNumber = backup.episodeNumber
            )
        )
        return true
    }

    private suspend fun applyProtectedContent(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableProtectedContentBackup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        return when (backup.content.contentType) {
            ContentType.LIVE -> channelDao.updateItemProtection(resolved.localId, providerId, true) > 0
            ContentType.VOD, ContentType.MOVIE -> movieDao.updateItemProtection(resolved.localId, providerId, true) > 0
            ContentType.SERIES -> seriesDao.updateItemProtection(resolved.localId, providerId, true) > 0
            ContentType.SERIES_EPISODE -> episodeDao.updateItemProtection(resolved.localId, providerId, true) > 0
        }
    }

    private suspend fun applyHiddenContent(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableHiddenContentBackup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        if (backup.content.contentType != ContentType.LIVE) return false
        preferencesRepository.setChannelHidden(providerId, resolved.localId, true)
        return true
    }

    private suspend fun applyHiddenCategory(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val reference = gson.fromJson(
            item.payloadJson,
            PortableCategoryReference::class.java
        )
        val category = resolveHiddenCategory(providerId, reference) ?: return false
        preferencesRepository.setCategoryHidden(providerId, reference.type, category.categoryId, true)
        return true
    }

    private suspend fun resolveHiddenCategory(
        providerId: Long,
        reference: PortableCategoryReference
    ): CategoryEntity? {
        val candidates = categoryDao?.getByProviderAndTypeSync(providerId, reference.type.name).orEmpty()
        reference.remoteCategoryId?.let { remoteId ->
            candidates.singleOrNull { it.categoryId == remoteId }?.let { return it }
        }
        val normalizedName = reference.name.trim().lowercase(Locale.ROOT)
        return candidates.filter { it.name.trim().lowercase(Locale.ROOT) == normalizedName }.singleOrNull()
    }

    private suspend fun applyContentPreference(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableContentPreferenceBackup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        channelPreferenceDao.upsert(
            ChannelPreferenceEntity(
                channelId = resolved.localId,
                aspectRatio = backup.aspectRatio,
                audioVideoOffsetMs = backup.audioVideoOffsetMs
            )
        )
        return true
    }

    private suspend fun applyVariantChoice(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableVariantChoiceBackup::class.java)
        val resolved = resolveContent(providerId, backup.selectedContent) ?: return false
        when (backup.selectedContent.contentType) {
            ContentType.LIVE -> preferencesRepository.setPreferredLiveVariant(providerId, backup.logicalGroupId, resolved.localId)
            ContentType.VOD, ContentType.MOVIE, ContentType.SERIES, ContentType.SERIES_EPISODE ->
                preferencesRepository.setPreferredVodVariant(providerId, backup.logicalGroupId, resolved.localId)
        }
        return true
    }

    private suspend fun applyManualEpg(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableManualEpgMappingV14Backup::class.java)
        val resolved = resolveContent(providerId, backup.content) ?: return false
        val sourceId = backup.sourceUrl?.let { epgSourceDao.getByUrl(it)?.id ?: return false }
        channelEpgMappingDao.upsert(
            ChannelEpgMappingEntity(
                providerChannelId = resolved.localId,
                providerId = providerId,
                sourceType = backup.sourceType,
                epgSourceId = sourceId,
                xmltvChannelId = backup.xmltvChannelId,
                matchType = backup.matchType,
                confidence = backup.confidence,
                source = backup.source,
                isManualOverride = true
            )
        )
        return true
    }

    private suspend fun applyMultiView(item: BackupRestoreItemEntity): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableMultiViewPresetV14Backup::class.java)
        val channelIds = backup.channels.map { reference ->
            val providerId = resolveProviderId(reference.provider) ?: return false
            resolveContent(providerId, reference)?.localId ?: return false
        }
        val index = backup.name.removePrefix("preset_").toIntOrNull()?.minus(1)
            ?.takeIf { it in 0..2 } ?: return false
        preferencesRepository.setMultiViewPreset(index, channelIds)
        return true
    }

    private suspend fun applyRecordingSchedule(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, ScheduledRecordingBackup::class.java)
        if (backup.scheduledEndMs <= System.currentTimeMillis() && backup.recurrence.name == "NONE") return true
        val reference = backup.channel ?: return false
        val resolved = resolveContent(providerId, reference) ?: return false
        val restored = backup.copy(channelId = resolved.localId)
        val duplicate = recordingManager.observeRecordingItems().first().any { existing ->
            existing.status == RecordingStatus.SCHEDULED &&
                existing.providerId == providerId &&
                existing.channelId == resolved.localId &&
                existing.scheduledStartMs == restored.scheduledStartMs &&
                existing.scheduledEndMs == restored.scheduledEndMs
        }
        if (duplicate) return true
        return when (recordingManager.scheduleRecording(restored.toRecordingRequest(providerId))) {
            is Result.Success -> true
            is Result.Error, Result.Loading -> false
        }
    }

    private suspend fun resolveProviderId(reference: BackupProviderReference): Long? {
        val matches = providerDao.getAllSync().mapNotNull { entity ->
            providerSnapshotRepository.getSnapshot(entity.id)?.toLegacyProvider()
        }.filter { provider ->
            normalizeUrl(provider.serverUrl) == normalizeUrl(reference.serverUrl) &&
                (reference.providerType == null || provider.type == reference.providerType) &&
                provider.username.trim() == reference.username.trim() &&
                provider.stalkerMacAddress.trim().uppercase(Locale.ROOT) ==
                    reference.stalkerMacAddress.orEmpty().trim().uppercase(Locale.ROOT)
        }
        return matches.singleOrNull()?.id
    }

    private suspend fun retryableItemsForProvider(providerId: Long): List<BackupRestoreItemEntity> {
        val byLocalId = ledgerDao.getRetryableItemsByLocalProviderId(providerId).orEmpty()
        val provider = providerSnapshotRepository.getSnapshot(providerId)?.toLegacyProvider()
            ?: return byLocalId
        val identityKey = BackupProviderReference(
            serverUrl = normalizeUrl(provider.serverUrl),
            username = provider.username.trim().takeUnless { provider.type == ProviderType.M3U }.orEmpty(),
            stalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() },
            providerType = provider.type
        ).stableIdentityKey()
        return (byLocalId + ledgerDao.getRetryableItems(identityKey).orEmpty()).distinctBy { it.id }
    }

    private fun BackupProviderReference.stableIdentityKey(): String = listOf(
        normalizeUrl(serverUrl),
        username.trim(),
        providerType?.name.orEmpty(),
        stalkerMacAddress.orEmpty().trim().uppercase(Locale.ROOT)
    ).joinToString("|")

    private fun normalizeUrl(value: String): String = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrElse { value.trim().trimEnd('/').lowercase(Locale.ROOT) }

    private suspend fun applySearchHistory(item: BackupRestoreItemEntity, providerId: Long): Boolean {
        val backup = gson.fromJson(item.payloadJson, PortableSearchHistoryBackup::class.java)
        if (searchHistoryDao.get(backup.query, backup.contentScope, providerId) == null) {
            searchHistoryDao.upsert(
                SearchHistoryEntity(
                    query = backup.query,
                    contentScope = backup.contentScope,
                    providerId = providerId,
                    usedAt = backup.usedAt,
                    useCount = backup.useCount.coerceAtLeast(1)
                )
            )
        }
        return true
    }

    private suspend fun resolveContent(providerId: Long, reference: PortableContentReference): ResolvedContent? {
        reference.remoteContentId.removePrefix(LEGACY_LOCAL_ID_PREFIX)
            .takeIf { reference.remoteContentId.startsWith(LEGACY_LOCAL_ID_PREFIX) }
            ?.toLongOrNull()
            ?.let { localId ->
                return when (reference.contentType) {
                    ContentType.LIVE -> channelDao.getById(localId)
                        ?.takeIf { it.providerId == providerId }
                        ?.let { ResolvedContent(it.id, ContentType.LIVE) }
                    ContentType.VOD, ContentType.MOVIE -> movieDao.getById(localId)
                        ?.takeIf { it.providerId == providerId }
                        ?.let { ResolvedContent(it.id, ContentType.MOVIE) }
                    ContentType.SERIES -> seriesDao.getById(localId)
                        ?.takeIf { it.providerId == providerId }
                        ?.let { ResolvedContent(it.id, ContentType.SERIES) }
                    ContentType.SERIES_EPISODE -> episodeDao.getById(localId)
                        ?.takeIf { it.providerId == providerId }
                        ?.let { ResolvedContent(it.id, ContentType.SERIES_EPISODE) }
                }
            }
        val remoteLong = reference.remoteContentId.toLongOrNull()
        return when (reference.contentType) {
            ContentType.LIVE -> {
                remoteLong?.let { channelDao.getByStreamId(providerId, it) }
                    ?.let { ResolvedContent(it.id, ContentType.LIVE) }
                    ?: channelDao.getByProviderSync(providerId)
                        .resolveUnique(reference, { it.name }, { it.streamUrl })
                        ?.let { ResolvedContent(it.id, ContentType.LIVE) }
            }
            ContentType.VOD, ContentType.MOVIE -> {
                remoteLong?.let { movieDao.getByStreamId(providerId, it) }
                    ?.let { ResolvedContent(it.id, ContentType.MOVIE) }
                    ?: movieDao.getByProviderSync(providerId)
                        .resolveUnique(reference, { it.name }, { it.streamUrl })
                        ?.let { ResolvedContent(it.id, ContentType.MOVIE) }
            }
            ContentType.SERIES -> {
                val rows = seriesDao.getByProviderSync(providerId)
                rows.filter {
                    it.providerSeriesId == reference.remoteContentId ||
                        (remoteLong != null && it.seriesId == remoteLong)
                }.singleOrNull()?.let { ResolvedContent(it.id, ContentType.SERIES) }
                    ?: rows.resolveUnique(reference, { it.name }, { null })
                        ?.let { ResolvedContent(it.id, ContentType.SERIES) }
            }
            ContentType.SERIES_EPISODE -> {
                val episode = remoteLong?.let { episodeDao.getByProviderAndEpisodeId(providerId, it) }
                if (episode != null) {
                    ResolvedContent(episode.id, ContentType.SERIES_EPISODE)
                } else {
                    episodeDao.getByProviderSync(providerId)
                        .resolveUnique(reference, { it.title }, { it.streamUrl })
                        ?.let { ResolvedContent(it.id, ContentType.SERIES_EPISODE) }
                }
            }
        }
    }

    private data class ResolvedContent(val localId: Long, val favoriteType: ContentType)

    private operator fun RestoreResolutionSummary.plus(other: RestoreResolutionSummary) = RestoreResolutionSummary(
        appliedCount = appliedCount + other.appliedCount,
        unresolvedCount = unresolvedCount + other.unresolvedCount,
        failedCount = failedCount + other.failedCount
    )

    private fun <T> List<T>.resolveUnique(
        reference: PortableContentReference,
        name: (T) -> String,
        url: (T) -> String?
    ): T? {
        reference.urlFallback?.takeIf { it.isNotBlank() }?.let { expected ->
            filter { url(it) == expected }.singleOrNull()?.let { return it }
        }
        val expectedName = reference.name?.normalized()?.takeIf { it.isNotBlank() } ?: return null
        return filter { name(it).normalized() == expectedName }.singleOrNull()
    }

    private fun String.normalized(): String = trim().lowercase(Locale.ROOT)

    private companion object {
        const val GLOBAL_PROVIDER_KEY = "__GLOBAL__"
        const val SECTION_FAVORITES = "FAVORITES"
        const val SECTION_CUSTOM_GROUPS = "CUSTOM_GROUPS"
        const val SECTION_PLAYBACK_HISTORY = "PLAYBACK_HISTORY"
        const val SECTION_PROTECTED_CONTENT = "PROTECTED_CONTENT"
        const val SECTION_HIDDEN_CONTENT = "HIDDEN_CONTENT"
        const val SECTION_HIDDEN_CATEGORIES = "HIDDEN_CATEGORIES"
        const val SECTION_CONTENT_PREFERENCES = "CONTENT_PREFERENCES"
        const val SECTION_VARIANT_CHOICES = "VARIANT_CHOICES"
        const val SECTION_MANUAL_EPG = "MANUAL_EPG"
        const val SECTION_MULTIVIEW = "MULTIVIEW"
        const val SECTION_RECORDING_SCHEDULES = "RECORDING_SCHEDULES"
        const val SECTION_SEARCH_HISTORY = "SEARCH_HISTORY"
        const val SECTION_REPLACE_SCOPE = "REPLACE_SCOPE"
        const val LEGACY_LOCAL_ID_PREFIX = "legacy-local-id:"
    }
}
