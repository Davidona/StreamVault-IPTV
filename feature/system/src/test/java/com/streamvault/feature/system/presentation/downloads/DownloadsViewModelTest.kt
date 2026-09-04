package com.streamvault.feature.system.presentation.downloads

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.R
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun confirmDeleteClearsDialogAndDeletesSelectedItem() = runTest {
        val item = downloadFixture("download-7")
        val manager = RecordingDownloadManager()
        val viewModel = createViewModel(manager)

        viewModel.showDeleteConfirm(item)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.deleteConfirmItem).isNull()
        assertThat(manager.deletedIds).containsExactly("download-7")
    }

    @Test
    fun missingOutputUriReturnsNullWithoutStoppingPlayback() {
        val manager = RecordingDownloadManager()
        val viewModel = createViewModel(manager)

        val result = viewModel.playDownload(downloadFixture("missing-output"))

        assertThat(result).isNull()
        assertThat(manager.playbackStoppedCount).isEqualTo(0)
    }

    @Test
    fun resolvablePlaybackStopsManagerBeforeResolvingIntent() {
        val manager = RecordingDownloadManager()
        val application = mock<Context>()
        val packageManager = mock<PackageManager>()
        whenever(application.packageManager).thenReturn(packageManager)
        whenever(
            packageManager.resolveActivity(any(), eq(PackageManager.MATCH_DEFAULT_ONLY))
        ).thenAnswer {
            manager.events += "resolve"
            ResolveInfo()
        }
        val viewModel = createViewModel(manager, application)

        val intent = viewModel.playDownload(
            downloadFixture("playable").copy(outputUri = "content://downloads/movie")
        )

        assertThat(intent?.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent?.type).isEqualTo("video/*")
        assertThat(intent?.flags).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(manager.events).containsExactly("stopped", "resolve").inOrder()
    }

    @Test
    fun resumeUsesSelectedIdAndPublishesMessage() = runTest {
        val manager = RecordingDownloadManager()
        val viewModel = createViewModel(manager)

        viewModel.resumeDownload(downloadFixture("download-8"))
        advanceUntilIdle()

        assertThat(manager.resumedIds).containsExactly("download-8")
        assertThat(viewModel.uiState.value.userMessage).isEqualTo("Downloads resumed")
    }

    @Test
    fun folderSelectionPersistsPermissionAndDisplayName() = runTest {
        val manager = RecordingDownloadManager()
        val application = mock<Context>()
        val resolver = mock<android.content.ContentResolver>()
        val cursor = mock<Cursor>()
        val treeUri = Uri.parse("content://downloads/tree/movies")
        whenever(application.contentResolver).thenReturn(resolver)
        whenever(resolver.query(any(), any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex("DISPLAY_NAME")).thenReturn(0)
        whenever(cursor.getString(0)).thenReturn("Movies")
        val viewModel = createViewModel(manager, application)

        viewModel.onFolderSelected(treeUri)
        advanceUntilIdle()

        verify(resolver).takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        assertThat(manager.storageUpdates).containsExactly(treeUri.toString() to "Movies")
    }

    @Test
    fun changeDownloadFolderCreatesDocumentTreeIntent() {
        val viewModel = createViewModel(RecordingDownloadManager())

        assertThat(viewModel.changeDownloadFolder().action)
            .isEqualTo("android.intent.action.OPEN_DOCUMENT_TREE")
    }

    private fun createViewModel(
        manager: RecordingDownloadManager,
        application: Context = mock()
    ): DownloadsViewModel {
        whenever(application.getString(R.string.downloads_deleted)).thenReturn("Downloads deleted")
        whenever(application.getString(R.string.downloads_resumed)).thenReturn("Downloads resumed")
        return DownloadsViewModel(
            downloadManager = manager,
            application = application
        )
    }

    private fun downloadFixture(id: String) = DownloadItem(
        id = id,
        providerId = 7L,
        contentType = DownloadContentType.MOVIE,
        contentId = 42L,
        contentName = "Test movie",
        streamUrl = "https://example.test/movie.mp4"
    )

    private class RecordingDownloadManager : DownloadManager {
        private val downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
        private val storage = MutableStateFlow(DownloadStorageConfig())
        val deletedIds = mutableListOf<String>()
        var playbackStoppedCount = 0
        val events = mutableListOf<String>()
        val resumedIds = mutableListOf<String>()
        val storageUpdates = mutableListOf<Pair<String?, String?>>()

        override fun observeAllDownloads(): Flow<List<DownloadItem>> = downloads

        override fun observeDownload(id: String): Flow<DownloadItem?> = flowOf(null)

        override fun observeStorageState(): Flow<DownloadStorageConfig> = storage

        override suspend fun enqueueDownload(request: DownloadRequest): Result<DownloadItem> =
            Result.error("not used")

        override suspend fun resumeDownload(id: String): Result<Unit> {
            resumedIds += id
            return Result.Success(Unit)
        }

        override suspend fun recoverInterruptedDownloads(): Result<Int> = Result.Success(0)

        override suspend fun cancelDownload(id: String): Result<Unit> = Result.Success(Unit)

        override fun onPlaybackStarted() = Unit

        override fun onPlaybackStopped() {
            playbackStoppedCount++
            events += "stopped"
        }

        override suspend fun deleteDownload(id: String): Result<Unit> {
            deletedIds += id
            return Result.Success(Unit)
        }

        override suspend fun updateStorageConfig(
            treeUri: String?,
            displayName: String?
        ): Result<DownloadStorageConfig> {
            storageUpdates += treeUri to displayName
            return Result.Success(storage.value)
        }
    }

    @Test
    fun fileSizeFormattingPreservesBinaryThresholds() {
        assertThat(formatDownloadFileSize(1023)).isEqualTo("1023 B")
        assertThat(formatDownloadFileSize(1024)).isEqualTo("1 KB")
        assertThat(formatDownloadFileSize(1024L * 1024)).isEqualTo("1 MB")
        assertThat(formatDownloadFileSize(1024L * 1024 * 1024)).isEqualTo("1 GB")
    }
}
