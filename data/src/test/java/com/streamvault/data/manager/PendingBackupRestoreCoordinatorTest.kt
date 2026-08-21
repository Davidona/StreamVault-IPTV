package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.dao.BackupRestoreLedgerDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
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
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.manager.PortableContentReference
import com.streamvault.domain.manager.PortableCategoryReference
import com.streamvault.domain.manager.PortableCustomGroupBackup
import com.streamvault.domain.manager.PortableFavoriteBackup
import com.streamvault.domain.manager.PortableMultiViewPresetV14Backup
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.manager.RecordingManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.whenever
import org.mockito.kotlin.never

class PendingBackupRestoreCoordinatorTest {
    @Test
    fun `cancellation leaves restore instruction retryable`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val channelDao: ChannelDao = mock()
        val gson = Gson()
        val reference = PortableContentReference(
            BackupProviderReference("https://example.com", "user", providerType = ProviderType.XTREAM_CODES),
            ContentType.LIVE,
            "42"
        )
        val item = BackupRestoreItemEntity(
            id = 10,
            jobId = "job",
            providerIdentityKey = "provider",
            localProviderId = 77,
            section = "FAVORITES",
            contentType = "LIVE",
            stableReferenceKey = "LIVE:42",
            referenceJson = gson.toJson(reference),
            payloadJson = gson.toJson(PortableFavoriteBackup(reference)),
            createdAt = 1,
            updatedAt = 1
        )
        whenever(providerDao.getById(77)).thenReturn(
            ProviderEntity(id = 77, name = "Provider", type = ProviderType.XTREAM_CODES)
        )
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(listOf(item))
        whenever(channelDao.getByStreamId(77, 42)).thenThrow(CancellationException("cancelled"))

        var cancelled = false
        try {
            coordinator(ledger, providerDao, mock(), channelDao, gson).applyForProvider(77)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
        verify(ledger, never()).updateItemStatus(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `replace scope clears existing favorites only after every backup reference resolves`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val channelDao: ChannelDao = mock()
        val gson = Gson()
        val provider = ProviderEntity(id = 77, name = "Provider", type = ProviderType.XTREAM_CODES)
        val reference = PortableContentReference(
            provider = BackupProviderReference("https://example.com", "user", providerType = ProviderType.XTREAM_CODES),
            contentType = ContentType.LIVE,
            remoteContentId = "42"
        )
        val marker = BackupRestoreItemEntity(
            id = 1, jobId = "job", providerIdentityKey = "provider", localProviderId = 77,
            section = "REPLACE_SCOPE", contentType = "LIVE", stableReferenceKey = "scope:FAVORITES:LIVE",
            referenceJson = "{}",
            payloadJson = """{"provider":null,"targetSection":"FAVORITES","contentType":"LIVE"}""",
            createdAt = 1, updatedAt = 1
        )
        val favorite = BackupRestoreItemEntity(
            id = 2, jobId = "job", providerIdentityKey = "provider", localProviderId = 77,
            section = "FAVORITES", contentType = "LIVE", stableReferenceKey = "LIVE:42",
            referenceJson = gson.toJson(reference), payloadJson = gson.toJson(PortableFavoriteBackup(reference)),
            createdAt = 1, updatedAt = 1
        )
        val otherProviderFavorite = favorite.copy(
            id = 3,
            providerIdentityKey = "other-provider",
            stableReferenceKey = "LIVE:99",
            referenceJson = gson.toJson(reference.copy(remoteContentId = "99")),
            payloadJson = gson.toJson(PortableFavoriteBackup(reference.copy(remoteContentId = "99")))
        )
        whenever(providerDao.getById(77)).thenReturn(provider)
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(listOf(marker, favorite))
        whenever(ledger.getRetryableItems("__GLOBAL__")).thenReturn(emptyList())
        whenever(ledger.getItems("job")).thenReturn(listOf(marker, favorite, otherProviderFavorite))
        whenever(channelDao.getByStreamId(77, 42)).thenReturn(ChannelEntity(id = 900, streamId = 42, name = "News", providerId = 77))
        whenever(favoriteDao.get(77, 900, "LIVE", null)).thenReturn(null)

        coordinator(ledger, providerDao, favoriteDao, channelDao, gson).applyForProvider(77)

        inOrder(favoriteDao) {
            verify(favoriteDao).deleteGlobalByProviderAndType(77, "LIVE")
            verify(favoriteDao, atLeastOnce()).insert(any())
        }
    }

    @Test
    fun `provider pass rebinds pending items when local provider id changes again`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val channelDao: ChannelDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val snapshots: ProviderSnapshotRepository = mock()
        val gson = Gson()
        val provider = LegacyProvider(
            id = 88,
            name = "Recreated",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val reference = PortableContentReference(
            provider = BackupProviderReference(
                serverUrl = "https://example.com",
                username = "user",
                providerType = ProviderType.XTREAM_CODES
            ),
            contentType = ContentType.LIVE,
            remoteContentId = "42"
        )
        val item = BackupRestoreItemEntity(
            id = 7,
            jobId = "job",
            providerIdentityKey = "https://example.com|user|XTREAM_CODES|",
            localProviderId = 77,
            section = "FAVORITES",
            contentType = "LIVE",
            stableReferenceKey = "LIVE:42",
            referenceJson = gson.toJson(reference),
            payloadJson = gson.toJson(PortableFavoriteBackup(reference)),
            createdAt = 1,
            updatedAt = 1
        )
        whenever(providerDao.getById(88)).thenReturn(provider.copy().let {
            ProviderEntity(id = it.id, name = it.name, type = it.type, serverUrl = it.serverUrl, username = it.username)
        })
        whenever(snapshots.getSnapshot(88)).thenReturn(provider.toProviderSnapshot())
        whenever(ledger.getRetryableItemsByLocalProviderId(88)).thenReturn(emptyList())
        whenever(ledger.getRetryableItems(item.providerIdentityKey)).thenReturn(listOf(item))
        whenever(channelDao.getByStreamId(88, 42)).thenReturn(
            ChannelEntity(id = 901, streamId = 42, name = "News", providerId = 88)
        )
        whenever(favoriteDao.get(88, 901, "LIVE", null)).thenReturn(null)

        val coordinator = PendingBackupRestoreCoordinator(
            ledger, providerDao, favoriteDao, mock(), mock(), channelDao, mock(), mock(), mock(),
            mock(), mock(), mock(), mock(), mock(), snapshots, mock(), gson
        )

        assertThat(coordinator.applyForProvider(88).appliedCount).isEqualTo(1)
        verify(ledger).updateItemStatus(eq(7), eq(BackupRestoreItemEntity.STATUS_APPLIED), eq(88), eq(1), isNull(), any())
    }

    @Test
    fun `hidden category fetch requirements include only categories referenced by pending restore items`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val preferences: PreferencesRepository = mock()
        val gson = Gson()
        val reference = PortableContentReference(
            provider = BackupProviderReference("https://example.com", "user", providerType = ProviderType.XTREAM_CODES),
            contentType = ContentType.LIVE,
            remoteContentId = "42",
            remoteCategoryId = "9",
            name = "News"
        )
        whenever(preferences.getHiddenCategoryIds(77, ContentType.LIVE)).thenReturn(flowOf(setOf(9, 10)))
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(
            listOf(
                BackupRestoreItemEntity(
                    id = 1,
                    jobId = "job",
                    providerIdentityKey = "provider",
                    localProviderId = 77,
                    section = "FAVORITES",
                    contentType = "LIVE",
                    stableReferenceKey = "LIVE:42",
                    referenceJson = gson.toJson(reference),
                    payloadJson = gson.toJson(PortableFavoriteBackup(reference)),
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        )
        whenever(ledger.getRetryableItems("__GLOBAL__")).thenReturn(emptyList())

        val coordinator = PendingBackupRestoreCoordinator(
            ledgerDao = ledger,
            providerDao = mock(),
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = mock(),
            channelDao = mock(),
            movieDao = mock(),
            seriesDao = mock(),
            episodeDao = mock(),
            searchHistoryDao = mock(),
            channelPreferenceDao = mock(),
            channelEpgMappingDao = mock(),
            epgSourceDao = mock(),
            preferencesRepository = preferences,
            providerSnapshotRepository = mock(),
            recordingManager = mock(),
            gson = gson
        )

        assertThat(coordinator.requiredHiddenCategoryIds(77, ContentType.LIVE)).containsExactly(9L)
        Unit
    }

    @Test
    fun `hidden category requirements inspect nested provider and global restore payloads`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val preferences: PreferencesRepository = mock()
        val snapshots: ProviderSnapshotRepository = mock()
        val gson = Gson()
        val provider = LegacyProvider(
            id = 77,
            name = "Provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com",
            username = "user"
        )
        val providerReference = BackupProviderReference(
            provider.serverUrl,
            provider.username,
            providerType = provider.type
        )
        val groupMember = PortableContentReference(
            providerReference,
            ContentType.LIVE,
            remoteContentId = "42",
            remoteCategoryId = "9"
        )
        val multiviewChannel = groupMember.copy(remoteContentId = "43", remoteCategoryId = "10")
        val customGroup = PortableCustomGroupBackup(
            provider = providerReference,
            contentType = ContentType.LIVE,
            name = "News",
            members = listOf(PortableFavoriteBackup(groupMember))
        )
        val multiview = PortableMultiViewPresetV14Backup("preset_1", listOf(multiviewChannel))
        whenever(preferences.getHiddenCategoryIds(77, ContentType.LIVE)).thenReturn(flowOf(setOf(9, 10, 11)))
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(
            listOf(restoreItem(1, "CUSTOM_GROUPS", gson.toJson(customGroup)))
        )
        whenever(ledger.getRetryableItems("__GLOBAL__")).thenReturn(
            listOf(restoreItem(2, "MULTIVIEW", gson.toJson(multiview), providerKey = "__GLOBAL__"))
        )
        whenever(ledger.getRetryableItems("https://example.com|user|XTREAM_CODES|"))
            .thenReturn(emptyList())
        whenever(providerDao.getAllSync()).thenReturn(
            listOf(ProviderEntity(id = 77, name = provider.name, type = provider.type))
        )
        whenever(snapshots.getSnapshot(77)).thenReturn(provider.toProviderSnapshot())

        val coordinator = PendingBackupRestoreCoordinator(
            ledger, providerDao, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(),
            mock(), mock(), mock(), preferences, snapshots, mock(), gson
        )

        assertThat(coordinator.requiredHiddenCategoryIds(77, ContentType.LIVE))
            .containsExactly(9L, 10L)
        Unit
    }

    private fun restoreItem(
        id: Long,
        section: String,
        payloadJson: String,
        providerKey: String = "provider"
    ) = BackupRestoreItemEntity(
        id = id,
        jobId = "job",
        providerIdentityKey = providerKey,
        localProviderId = if (providerKey == "__GLOBAL__") null else 77,
        section = section,
        contentType = "LIVE",
        stableReferenceKey = "$section:$id",
        referenceJson = "{}",
        payloadJson = payloadJson,
        createdAt = 1,
        updatedAt = 1
    )

    @Test
    fun `provider pass resolves favorite by remote id after local ids changed`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val channelDao: ChannelDao = mock()
        val favoriteDao: FavoriteDao = mock()
        val gson = Gson()
        val provider = ProviderEntity(
            id = 77,
            name = "Provider",
            type = ProviderType.XTREAM_CODES
        )
        val reference = PortableContentReference(
            provider = BackupProviderReference(
                serverUrl = "https://example.com/",
                username = "user",
                providerType = provider.type
            ),
            contentType = ContentType.LIVE,
            remoteContentId = "42",
            name = "News"
        )
        val payload = PortableFavoriteBackup(reference, position = 4, addedAt = 123)
        val item = BackupRestoreItemEntity(
            id = 5,
            jobId = "job",
            providerIdentityKey = "https://example.com|user|XTREAM_CODES|",
            section = "FAVORITES",
            contentType = "LIVE",
            stableReferenceKey = "provider|LIVE:42",
            referenceJson = gson.toJson(reference),
            payloadJson = gson.toJson(payload),
            createdAt = 1,
            updatedAt = 1
        )
        whenever(providerDao.getById(77)).thenReturn(provider)
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(listOf(item))
        whenever(ledger.getRetryableItems("__GLOBAL__")).thenReturn(emptyList())
        whenever(channelDao.getByStreamId(77, 42)).thenReturn(
            ChannelEntity(id = 900, streamId = 42, name = "News", providerId = 77)
        )
        whenever(favoriteDao.get(77, 900, "LIVE", null)).thenReturn(null)

        val result = PendingBackupRestoreCoordinator(
            ledgerDao = ledger,
            providerDao = providerDao,
            favoriteDao = favoriteDao,
            virtualGroupDao = mock<VirtualGroupDao>(),
            playbackHistoryDao = mock<PlaybackHistoryDao>(),
            channelDao = channelDao,
            movieDao = mock<MovieDao>(),
            seriesDao = mock<SeriesDao>(),
            episodeDao = mock<EpisodeDao>(),
            searchHistoryDao = mock<SearchHistoryDao>(),
            channelPreferenceDao = mock<ChannelPreferenceDao>(),
            channelEpgMappingDao = mock<ChannelEpgMappingDao>(),
            epgSourceDao = mock<EpgSourceDao>(),
            preferencesRepository = mock<PreferencesRepository>(),
            providerSnapshotRepository = mock<ProviderSnapshotRepository>(),
            recordingManager = mock<RecordingManager>(),
            gson = gson
        ).applyForProvider(77)

        assertThat(result.appliedCount).isEqualTo(1)
        assertThat(result.unresolvedCount).isEqualTo(0)
        verify(favoriteDao).insert(
            argThat<FavoriteEntity> {
                providerId == 77L && contentId == 900L && contentType == ContentType.LIVE && position == 4
            }
        )
        verify(ledger).updateItemStatus(
            itemId = eq(5),
            status = eq(BackupRestoreItemEntity.STATUS_APPLIED),
            localProviderId = eq(77),
            attemptIncrement = eq(1),
            lastError = isNull(),
            updatedAt = any()
        )
        verify(ledger).refreshJobCounts(eq("job"), any())
        Unit
    }

    @Test
    fun `provider pass applies hidden category after category catalog is available`() = runBlocking {
        val ledger: BackupRestoreLedgerDao = mock()
        val providerDao: ProviderDao = mock()
        val preferences: PreferencesRepository = mock()
        val categoryDao: CategoryDao = mock()
        val gson = Gson()
        val provider = ProviderEntity(id = 77, name = "Provider", type = ProviderType.XTREAM_CODES)
        val reference = PortableCategoryReference(
            provider = BackupProviderReference(
                serverUrl = "https://example.com",
                username = "user",
                providerType = provider.type
            ),
            name = "News",
            type = ContentType.LIVE,
            remoteCategoryId = 55
        )
        val item = BackupRestoreItemEntity(
            id = 12,
            jobId = "job",
            providerIdentityKey = "https://example.com|user|XTREAM_CODES|",
            localProviderId = 77,
            section = "HIDDEN_CATEGORIES",
            contentType = "LIVE",
            stableReferenceKey = "provider|LIVE:CATEGORY:55",
            referenceJson = gson.toJson(reference),
            payloadJson = gson.toJson(reference),
            createdAt = 1,
            updatedAt = 1
        )
        whenever(providerDao.getById(77)).thenReturn(provider)
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(listOf(item))
        whenever(ledger.getRetryableItems("__GLOBAL__")).thenReturn(emptyList())
        whenever(categoryDao.getByProviderAndTypeSync(77, ContentType.LIVE.name)).thenReturn(
            listOf(CategoryEntity(categoryId = 55, name = "News", type = ContentType.LIVE, providerId = 77))
        )

        val coordinator = PendingBackupRestoreCoordinator(
            ledgerDao = ledger,
            providerDao = providerDao,
            favoriteDao = mock(),
            virtualGroupDao = mock(),
            playbackHistoryDao = mock(),
            channelDao = mock(),
            movieDao = mock(),
            seriesDao = mock(),
            episodeDao = mock(),
            searchHistoryDao = mock(),
            channelPreferenceDao = mock(),
            channelEpgMappingDao = mock(),
            epgSourceDao = mock(),
            categoryDao = categoryDao,
            preferencesRepository = preferences,
            providerSnapshotRepository = mock(),
            recordingManager = mock(),
            gson = gson
        )

        val result = coordinator.applyForProvider(77)

        assertThat(result.appliedCount).isEqualTo(1)
        verify(preferences).setCategoryHidden(77, ContentType.LIVE, 55L, true)
        verify(ledger).updateItemStatus(
            itemId = eq(12),
            status = eq(BackupRestoreItemEntity.STATUS_APPLIED),
            localProviderId = eq(77),
            attemptIncrement = eq(1),
            lastError = isNull(),
            updatedAt = any()
        )
        Unit
    }

    private fun coordinator(
        ledger: BackupRestoreLedgerDao,
        providerDao: ProviderDao,
        favoriteDao: FavoriteDao,
        channelDao: ChannelDao,
        gson: Gson
    ) = PendingBackupRestoreCoordinator(
        ledgerDao = ledger,
        providerDao = providerDao,
        favoriteDao = favoriteDao,
        virtualGroupDao = mock(),
        playbackHistoryDao = mock(),
        channelDao = channelDao,
        movieDao = mock(),
        seriesDao = mock(),
        episodeDao = mock(),
        searchHistoryDao = mock(),
        channelPreferenceDao = mock(),
        channelEpgMappingDao = mock(),
        epgSourceDao = mock(),
        preferencesRepository = mock(),
        providerSnapshotRepository = mock(),
        recordingManager = mock(),
        gson = gson
    )
}
