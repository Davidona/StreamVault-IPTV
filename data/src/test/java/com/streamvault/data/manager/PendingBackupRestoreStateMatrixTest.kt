package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.dao.*
import com.streamvault.data.local.entity.*
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.domain.manager.*
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.settings.VodTrackPreferences
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.*

/** Exercises the real coordinator, including its durable status transitions and target writes. */
@RunWith(Parameterized::class)
class PendingBackupRestoreStateMatrixTest(private val section: String, private val state: String) {
    companion object {
        private val sections = listOf(
            "FAVORITES", "CUSTOM_GROUPS", "PLAYBACK_HISTORY", "PROTECTED_CONTENT",
            "HIDDEN_CONTENT", "HIDDEN_CATEGORIES", "CONTENT_PREFERENCES", "VARIANT_CHOICES",
            "VOD_TRACK_PREFERENCES", "MANUAL_EPG", "MULTIVIEW", "RECORDING_SCHEDULES", "SEARCH_HISTORY"
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1}")
        fun cases(): List<Array<String>> = sections.flatMap { section ->
            listOf("available", "missing", "malformed", "write-failure").filterNot {
                section == "SEARCH_HISTORY" && it == "missing" // Search has no catalog dependency.
            }.map { arrayOf(section, it) }
        }
    }

    @Test
    fun restorePersistsExpectedStateAndCanRetry() = runBlocking {
        val gson = Gson()
        val ledger: BackupRestoreLedgerDao = mock()
        val providers: ProviderDao = mock()
        val favorites: FavoriteDao = mock()
        val groups: VirtualGroupDao = mock()
        val history: PlaybackHistoryDao = mock()
        val channels: ChannelDao = mock()
        val movies: MovieDao = mock()
        val categories: CategoryDao = mock()
        val searches: SearchHistoryDao = mock()
        val channelPreferences: ChannelPreferenceDao = mock()
        val mappings: ChannelEpgMappingDao = mock()
        val preferences: PreferencesRepository = mock()
        val snapshots: ProviderSnapshotRepository = mock()
        val recordings: RecordingManager = mock()
        val provider = LegacyProvider(
            id = 77, name = "Provider", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user"
        )
        val providerRef = BackupProviderReference(provider.serverUrl, provider.username, providerType = provider.type)
        val reference = PortableContentReference(providerRef, ContentType.LIVE, "42", name = "News")
        val movieReference = reference.copy(contentType = ContentType.MOVIE)
        val category = PortableCategoryReference(providerRef, "News", ContentType.LIVE, 9)
        val end = System.currentTimeMillis() + 3_600_000
        val payload: Any = when (section) {
            "FAVORITES" -> PortableFavoriteBackup(reference, position = 3, addedAt = 123)
            "CUSTOM_GROUPS" -> PortableCustomGroupBackup(providerRef, ContentType.LIVE, "My group",
                members = listOf(PortableFavoriteBackup(reference, position = 3, addedAt = 123)))
            "PLAYBACK_HISTORY" -> PortablePlaybackHistoryBackup(reference, resumePositionMs = 1234, totalDurationMs = 9000)
            "PROTECTED_CONTENT" -> PortableProtectedContentBackup(reference)
            "HIDDEN_CONTENT" -> PortableHiddenContentBackup(reference)
            "HIDDEN_CATEGORIES" -> category
            "CONTENT_PREFERENCES" -> PortableContentPreferenceBackup(reference, "16:9", 150)
            "VARIANT_CHOICES" -> PortableVariantChoiceBackup("news", reference)
            "VOD_TRACK_PREFERENCES" -> PortableVodTrackPreferenceBackup(movieReference, VodTrackPreferences())
            "MANUAL_EPG" -> PortableManualEpgMappingV14Backup(reference, xmltvChannelId = "news", sourceType = "XMLTV")
            "MULTIVIEW" -> PortableMultiViewPresetV14Backup("preset_1", listOf(reference))
            "RECORDING_SCHEDULES" -> ScheduledRecordingBackup(provider.serverUrl, provider.username,
                channelId = 1, channelName = "News", streamUrl = "https://example.com/live",
                scheduledStartMs = end - 60_000, scheduledEndMs = end, channel = reference)
            "SEARCH_HISTORY" -> PortableSearchHistoryBackup("news", "LIVE", providerRef, usedAt = 123)
            else -> error(section)
        }
        val item = BackupRestoreItemEntity(
            id = 10, jobId = "job", providerIdentityKey = "provider", localProviderId = 77,
            section = section, contentType = "LIVE", stableReferenceKey = "stable",
            referenceJson = gson.toJson(reference),
            payloadJson = if (state == "malformed") "{" else gson.toJson(payload),
            createdAt = 1, updatedAt = 1
        )
        whenever(providers.getById(77)).thenReturn(ProviderEntity(id = 77, name = provider.name, type = provider.type))
        whenever(providers.getAllSync()).thenReturn(listOf(ProviderEntity(id = 77, name = provider.name, type = provider.type)))
        whenever(snapshots.getSnapshot(77)).thenReturn(provider.toProviderSnapshot())
        whenever(ledger.getRetryableItemsByLocalProviderId(77)).thenReturn(listOf(item))
        whenever(channels.getByProviderSync(77)).thenReturn(emptyList())
        whenever(movies.getByProviderSync(77)).thenReturn(emptyList())
        whenever(groups.getByType(77, "LIVE")).thenReturn(flowOf(emptyList()))
        whenever(groups.insert(any())).thenReturn(500)
        whenever(recordings.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(recordings.scheduleRecording(any())).thenReturn(Result.Success(mock<com.streamvault.domain.model.RecordingItem>()))
        whenever(channels.updateItemProtection(900, 77, true)).thenReturn(1)

        suspend fun catalogAvailable() {
            whenever(channels.getByStreamId(77, 42)).thenReturn(ChannelEntity(id = 900, providerId = 77, streamId = 42, name = "News"))
            whenever(movies.getByStreamId(77, 42)).thenReturn(MovieEntity(id = 901, providerId = 77, streamId = 42, name = "Movie"))
            whenever(categories.getByProviderAndTypeSync(77, "LIVE")).thenReturn(listOf(
                CategoryEntity(categoryId = 9, name = "News", type = ContentType.LIVE, providerId = 77)
            ))
        }
        if (state != "missing") catalogAvailable()

        // Throw at the actual destination write, then allow it on retry.
        if (state == "write-failure") {
            val failure = IllegalStateException("destination unavailable")
            when (section) {
                "FAVORITES", "CUSTOM_GROUPS" -> whenever(favorites.insert(any())).thenThrow(failure).thenReturn(Unit)
                "PLAYBACK_HISTORY" -> whenever(history.insertOrUpdate(any())).thenThrow(failure).thenReturn(Unit)
                "PROTECTED_CONTENT" -> whenever(channels.updateItemProtection(900, 77, true)).thenThrow(failure).thenReturn(1)
                "HIDDEN_CONTENT" -> whenever(preferences.setChannelHidden(77, 900, true)).thenThrow(failure).thenReturn(Unit)
                "HIDDEN_CATEGORIES" -> whenever(preferences.setCategoryHidden(77, ContentType.LIVE, 9, true)).thenThrow(failure).thenReturn(Unit)
                "CONTENT_PREFERENCES" -> whenever(channelPreferences.upsert(any())).thenThrow(failure).thenReturn(Unit)
                "VARIANT_CHOICES" -> whenever(preferences.setPreferredLiveVariant(77, "news", 900)).thenThrow(failure).thenReturn(Unit)
                "VOD_TRACK_PREFERENCES" -> whenever(preferences.setVodTrackPreferences(any(), any())).thenThrow(failure).thenReturn(Unit)
                "MANUAL_EPG" -> whenever(mappings.upsert(any())).thenThrow(failure).thenReturn(Unit)
                "MULTIVIEW" -> whenever(preferences.setMultiViewPreset(0, listOf(900L))).thenThrow(failure).thenReturn(Unit)
                "RECORDING_SCHEDULES" -> whenever(recordings.scheduleRecording(any())).thenThrow(failure).thenReturn(Result.Success(mock<com.streamvault.domain.model.RecordingItem>()))
                "SEARCH_HISTORY" -> whenever(searches.upsert(any())).thenThrow(failure).thenReturn(1L)
            }
        }
        val coordinator = PendingBackupRestoreCoordinator(
            ledger, providers, favorites, groups, history, channels, movies, mock(), mock(),
            searches, channelPreferences, mappings, mock(), preferences, snapshots, recordings, gson, categories
        )
        val result = coordinator.applyForProvider(77)
        val expected = when (state) {
            "available" -> RestoreResolutionSummary(appliedCount = 1)
            "missing" -> RestoreResolutionSummary(unresolvedCount = 1)
            else -> RestoreResolutionSummary(failedCount = 1)
        }
        assertThat(result).isEqualTo(expected)
        val status = when (state) {
            "available" -> BackupRestoreItemEntity.STATUS_APPLIED
            "missing" -> BackupRestoreItemEntity.STATUS_UNRESOLVED
            else -> BackupRestoreItemEntity.STATUS_FAILED_RETRYABLE
        }
        verify(ledger).updateItemStatus(eq(10), eq(status), eq(77), eq(1), anyOrNull(), any())
        verify(ledger).refreshJobCounts(eq("job"), any())

        if (state == "missing" || state == "malformed") {
            val destinations = listOf(favorites, groups, history, channels, movies, searches,
                categories, channelPreferences, mappings, preferences, recordings)
            val writes = destinations.flatMap { org.mockito.Mockito.mockingDetails(it).invocations }
                .filter { it.method.name in setOf("insert", "insertOrUpdate", "updateItemProtection",
                    "setChannelHidden", "setCategoryHidden", "upsert", "setPreferredLiveVariant",
                    "setVodTrackPreferences", "setMultiViewPreset", "scheduleRecording") }
            assertThat(writes).isEmpty()
        }
        if (state == "missing" || state == "write-failure") {
            catalogAvailable()
            assertThat(coordinator.applyForProvider(77)).isEqualTo(RestoreResolutionSummary(appliedCount = 1))
            verify(ledger).updateItemStatus(eq(10), eq(BackupRestoreItemEntity.STATUS_APPLIED), eq(77), eq(1), isNull(), any())
        }
        if (state != "malformed") {
            val writes = if (state == "write-failure") times(2) else times(1)
            when (section) {
                "FAVORITES", "CUSTOM_GROUPS" -> verify(favorites, writes).insert(argThat {
                    providerId == 77L && contentId == 900L && position == 3 && addedAt == 123L &&
                        groupId == (if (section == "CUSTOM_GROUPS") 500L else null)
                })
                "PLAYBACK_HISTORY" -> verify(history, writes).insertOrUpdate(argThat { contentId == 900L && providerId == 77L && resumePositionMs == 1234L })
                "PROTECTED_CONTENT" -> verify(channels, writes).updateItemProtection(900, 77, true)
                "HIDDEN_CONTENT" -> verify(preferences, writes).setChannelHidden(77, 900, true)
                "HIDDEN_CATEGORIES" -> verify(preferences, writes).setCategoryHidden(77, ContentType.LIVE, 9, true)
                "CONTENT_PREFERENCES" -> verify(channelPreferences, writes).upsert(argThat { channelId == 900L && aspectRatio == "16:9" && audioVideoOffsetMs == 150 })
                "VARIANT_CHOICES" -> verify(preferences, writes).setPreferredLiveVariant(77, "news", 900)
                "VOD_TRACK_PREFERENCES" -> verify(preferences, writes).setVodTrackPreferences(argThat { providerId == 77L && contentId == 901L }, eq(VodTrackPreferences()))
                "MANUAL_EPG" -> verify(mappings, writes).upsert(argThat { providerChannelId == 900L && providerId == 77L && xmltvChannelId == "news" && isManualOverride })
                "MULTIVIEW" -> verify(preferences, writes).setMultiViewPreset(0, listOf(900L))
                "RECORDING_SCHEDULES" -> verify(recordings, writes).scheduleRecording(argThat { providerId == 77L && channelId == 900L && scheduledEndMs == end })
                "SEARCH_HISTORY" -> verify(searches, writes).upsert(argThat { query == "news" && providerId == 77L && usedAt == 123L })
            }
        }
    }
}

