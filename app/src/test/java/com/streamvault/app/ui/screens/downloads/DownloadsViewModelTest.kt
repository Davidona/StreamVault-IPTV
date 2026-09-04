package com.streamvault.app.ui.screens.downloads

import android.content.Context
import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.R
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
import org.mockito.kotlin.mock
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
    fun changeDownloadFolderCreatesDocumentTreeIntent() {
        val viewModel = createViewModel(RecordingDownloadManager())

        assertThat(viewModel.changeDownloadFolder().action)
            .isEqualTo("android.intent.action.OPEN_DOCUMENT_TREE")
    }

    private fun createViewModel(manager: RecordingDownloadManager): DownloadsViewModel {
        val application = mock<Context>()
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

        override fun observeAllDownloads(): Flow<List<DownloadItem>> = downloads

        override fun observeDownload(id: String): Flow<DownloadItem?> = flowOf(null)

        override fun observeStorageState(): Flow<DownloadStorageConfig> = storage

        override suspend fun enqueueDownload(request: DownloadRequest): Result<DownloadItem> =
            Result.error("not used")

        override suspend fun resumeDownload(id: String): Result<Unit> = Result.Success(Unit)

        override suspend fun recoverInterruptedDownloads(): Result<Int> = Result.Success(0)

        override suspend fun cancelDownload(id: String): Result<Unit> = Result.Success(Unit)

        override fun onPlaybackStarted() = Unit

        override fun onPlaybackStopped() {
            playbackStoppedCount++
        }

        override suspend fun deleteDownload(id: String): Result<Unit> {
            deletedIds += id
            return Result.Success(Unit)
        }

        override suspend fun updateStorageConfig(
            treeUri: String?,
            displayName: String?
        ): Result<DownloadStorageConfig> = Result.Success(storage.value)
    }
}
