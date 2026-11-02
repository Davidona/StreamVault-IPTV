package com.streamvault.app.tv

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.R
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O])
class WatchNextManagerTest {

    @Test
    fun selectWatchNextHistoryEntries_keepsOnlyActiveProviderRows() {
        val rows = selectWatchNextHistoryEntries(
            activeProviderId = 1L,
            historyEntries = listOf(
                history(contentId = 10L, providerId = 1L, lastWatchedAt = 300L),
                history(contentId = 11L, providerId = 2L, lastWatchedAt = 400L),
                history(contentId = 12L, providerId = 1L, lastWatchedAt = 200L)
            )
        )

        assertThat(rows.map { it.providerId }).containsExactly(1L, 1L)
        assertThat(rows.map { it.contentId }).containsExactly(10L, 12L).inOrder()
    }

    @Test
    fun selectWatchNextHistoryEntries_returnsEmptyWithoutActiveProvider() {
        val rows = selectWatchNextHistoryEntries(
            activeProviderId = null,
            historyEntries = listOf(history(contentId = 10L, providerId = 1L, lastWatchedAt = 300L))
        )

        assertThat(rows).isEmpty()
    }

    @Test
    fun `incremental progress updates the matching platform row only`() = runTest {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val packageManager = mock<PackageManager>()
        val cursor = mock<Cursor>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("com.streamvault.app")
        whenever(context.getString(R.string.saved_preset_watch_next)).thenReturn("Continue watching")
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(true)
        whenever(resolver.query(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(cursor)
        whenever(cursor.getColumnIndexOrThrow(BaseColumns._ID)).thenReturn(0)
        whenever(cursor.getColumnIndexOrThrow("internal_provider_id")).thenReturn(1)
        whenever(cursor.getColumnIndex("last_engagement_time_utc_millis")).thenReturn(2)
        whenever(cursor.moveToNext()).thenReturn(true, false)
        whenever(cursor.getString(1)).thenReturn("5:SERIES_EPISODE:10")
        whenever(cursor.getLong(0)).thenReturn(101L)
        whenever(cursor.getLong(2)).thenReturn(1_000L)

        val manager = WatchNextManager(
            context = context,
            playbackHistoryRepository = mock<PlaybackHistoryRepository>(),
            providerRepository = mock<ProviderRepository>(),
            stremioHistory = mock { whenever(it.observeHistory()).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
            stremioAddons = mock { whenever(it.observeAddons()).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
            preferences = mock { whenever(it.hideStremioAddons).thenReturn(kotlinx.coroutines.flow.flowOf(false)) },
            parental = com.streamvault.feature.stremio.presentation.NoopStremioCatalogParentalAccess
        )
        val history = history(contentId = 10L, providerId = 5L, lastWatchedAt = 2_000L)

        manager.updateWatchNextProgress(history)

        val uriCaptor = argumentCaptor<Uri>()
        verify(resolver).update(
            uriCaptor.capture(),
            any(),
            anyOrNull(),
            anyOrNull()
        )
        assertThat(uriCaptor.firstValue.lastPathSegment).isEqualTo("101")
    }

    @Test
    fun `two hours of thirty second progress stays within one row update per window`() = runTest {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val packageManager = mock<PackageManager>()
        val cursor = mock<Cursor>()
        var cursorRowsRead = 0
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("com.streamvault.app")
        whenever(context.getString(R.string.saved_preset_watch_next)).thenReturn("Continue watching")
        whenever(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)).thenReturn(true)
        whenever(resolver.query(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            cursorRowsRead = 0
            cursor
        }
        whenever(cursor.getColumnIndexOrThrow(BaseColumns._ID)).thenReturn(0)
        whenever(cursor.getColumnIndexOrThrow("internal_provider_id")).thenReturn(1)
        whenever(cursor.getColumnIndex("last_engagement_time_utc_millis")).thenReturn(2)
        whenever(cursor.moveToNext()).thenAnswer { cursorRowsRead++ == 0 }
        whenever(cursor.getString(1)).thenReturn("5:SERIES_EPISODE:10")
        whenever(cursor.getLong(0)).thenReturn(101L)
        whenever(cursor.getLong(2)).thenReturn(1_000L)

        val manager = WatchNextManager(
            context = context,
            playbackHistoryRepository = mock<PlaybackHistoryRepository>(),
            providerRepository = mock<ProviderRepository>(),
            stremioHistory = mock { whenever(it.observeHistory()).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
            stremioAddons = mock { whenever(it.observeAddons()).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
            preferences = mock { whenever(it.hideStremioAddons).thenReturn(kotlinx.coroutines.flow.flowOf(false)) },
            parental = com.streamvault.feature.stremio.presentation.NoopStremioCatalogParentalAccess
        )

        repeat(240) { index ->
            manager.updateWatchNextProgress(
                history(
                    contentId = 10L,
                    providerId = 5L,
                    lastWatchedAt = 2_000L + index * 30_000L
                )
            )
        }

        verify(resolver, times(240)).query(any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(resolver, times(240)).update(any(), any(), anyOrNull(), anyOrNull())
        verify(resolver, never()).insert(any(), any())
    }

    private fun history(contentId: Long, providerId: Long, lastWatchedAt: Long): PlaybackHistory = PlaybackHistory(
        contentId = contentId,
        contentType = ContentType.SERIES_EPISODE,
        providerId = providerId,
        title = "Episode $contentId",
        streamUrl = "https://example.com/$contentId.m3u8",
        resumePositionMs = 1_000L,
        totalDurationMs = 10_000L,
        lastWatchedAt = lastWatchedAt
    )
}
