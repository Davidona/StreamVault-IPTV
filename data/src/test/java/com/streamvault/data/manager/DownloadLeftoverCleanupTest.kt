package com.streamvault.data.manager

import android.os.Environment
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Deleting a download frees the storage its earlier attempts left behind in app storage: every failed attempt
 * started a new "name (n)" partial file that no download row refers to any more.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadLeftoverCleanupTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: StreamVaultDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManagerImpl
    private lateinit var downloadDir: File

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "StreamVault")
            .apply { mkdirs() }
        val preferences = mock<PreferencesRepository> {
            on { downloadTreeUri } doReturn flowOf(null)
        }
        manager = DownloadManagerImpl(
            context = context,
            downloadDao = database.downloadDao(),
            preferencesRepository = preferences,
            okHttpClient = OkHttpClient(),
            xtreamStreamUrlResolver = mock<XtreamStreamUrlResolver>(),
            torrentDownloadStreamResolver = { it },
            applicationScope = scope
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        downloadDir.deleteRecursively()
    }

    @Test
    fun `delete removes partial files left by earlier attempts`() = runBlocking {
        val leftovers = listOf("ALB BROTHER - 2026.mkv", "ALB BROTHER - 2026 (2).mkv", "ALB BROTHER - 2026 (5).mkv")
            .map(::appStorageFile)
        // A download stuck after failed attempts: its row no longer points at any of them.
        database.downloadDao().insert(download(DOWNLOAD_ID, "ALB| BROTHER - 2026"))

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(leftovers.filter { it.exists() }).isEmpty()
        assertThat(database.downloadDao().getByIdOnce(DOWNLOAD_ID)).isNull()
    }

    @Test
    fun `delete keeps files of other downloads and unrelated files`() = runBlocking {
        val leftover = appStorageFile("Movie (2).mp4")
        val otherDownloadsFile = appStorageFile("Movie.mp4")
        val otherTitle = appStorageFile("Movie Night.mp4")
        val unrelated = appStorageFile("notes.txt")
        database.downloadDao().insert(download(DOWNLOAD_ID, "Movie"))
        database.downloadDao().insert(
            download(OTHER_ID, "Movie").copy(
                status = DownloadStatus.COMPLETED,
                outputDisplayPath = otherDownloadsFile.absolutePath
            )
        )

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(leftover.exists()).isFalse()
        assertThat(otherDownloadsFile.exists()).isTrue()
        assertThat(otherTitle.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
    }

    private fun appStorageFile(name: String): File =
        File(downloadDir, name).apply { writeBytes(ByteArray(16)) }

    private fun download(id: String, name: String) = DownloadEntity(
        id = id,
        providerId = 1L,
        contentType = DownloadContentType.MOVIE,
        contentId = 2L,
        contentName = name,
        streamUrl = "http://example.test/movie/1/2/$id.mkv",
        status = DownloadStatus.PENDING,
        retryCount = 5
    )

    private companion object {
        const val DOWNLOAD_ID = "download-1"
        const val OTHER_ID = "download-2"
    }
}
