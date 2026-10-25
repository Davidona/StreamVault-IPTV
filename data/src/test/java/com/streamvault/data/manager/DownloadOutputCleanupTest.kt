package com.streamvault.data.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

/**
 * Output cleanup for the two download destinations:
 * - default app storage, addressed through a FileProvider URI plus an absolute path. Like
 *   `androidx.core.content.FileProvider`, the provider implements `delete()` but not `call()`, so
 *   `DocumentFile.delete()` (which calls `DocumentsContract.deleteDocument`) reports success
 *   without removing anything;
 * - a user-selected SAF folder, addressed through a document URI plus a display name.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadOutputCleanupTest {

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
        Robolectric.buildContentProvider(FileBackedProvider::class.java)
            .create(ProviderInfo().apply { authority = FILE_PROVIDER_AUTHORITY })
        Robolectric.buildContentProvider(DocumentsLikeProvider::class.java)
            .create(ProviderInfo().apply { authority = DOCUMENTS_AUTHORITY })
        DocumentsLikeProvider.reset()
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
        downloadDir.setWritable(true)
        scope.cancel()
        database.close()
    }

    @Test
    fun `delete removes app storage output behind a FileProvider uri`() = runBlocking {
        val partial = appStorageFile("Movie.mp4")
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, partial))

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(partial.exists()).isFalse()
        assertThat(database.downloadDao().getByIdOnce(DOWNLOAD_ID)).isNull()
    }

    @Test
    fun `delete leaves other files in the download folder untouched`() = runBlocking {
        val partial = appStorageFile("Movie (2).mp4")
        val completed = appStorageFile("Movie.mp4")
        val unrelated = appStorageFile("notes.txt")
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, partial))
        database.downloadDao().insert(
            appStorageDownload(OTHER_ID, completed).copy(status = DownloadStatus.COMPLETED)
        )

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(partial.exists()).isFalse()
        assertThat(completed.exists()).isTrue()
        assertThat(unrelated.exists()).isTrue()
        assertThat(database.downloadDao().getByIdOnce(OTHER_ID)!!.status).isEqualTo(DownloadStatus.COMPLETED)
    }

    @Test
    fun `delete never removes an absolute path outside the download folder`() = runBlocking {
        val outside = File(context.filesDir, "Movie.mp4").apply { writeText("not a download") }
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, outside))

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(outside.exists()).isTrue()
    }

    @Test
    fun `delete is safe when the output is already gone and when repeated`() = runBlocking {
        val partial = File(downloadDir, "Missing.mp4")
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, partial))

        manager.deleteDownload(DOWNLOAD_ID)
        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(database.downloadDao().getByIdOnce(DOWNLOAD_ID)).isNull()
        assertThat(ShadowLog.getLogsForTag(TAG)).isEmpty()
    }

    @Test
    fun `undeletable app storage output is reported instead of treated as removed`() = runBlocking {
        val partial = appStorageFile("Locked.mp4")
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, partial))
        downloadDir.setWritable(false)

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(partial.exists()).isTrue()
        assertThat(ShadowLog.getLogsForTag(TAG).map { it.msg })
            .contains("Download output could not be deleted")
    }

    @Test
    fun `delete still removes SAF outputs through the documents provider`() = runBlocking<Unit> {
        val documentUri = DocumentsContract.buildDocumentUri(DOCUMENTS_AUTHORITY, "doc-1")
        database.downloadDao().insert(
            download(DOWNLOAD_ID).copy(outputUri = documentUri.toString(), outputDisplayPath = "Movie.mp4")
        )

        manager.deleteDownload(DOWNLOAD_ID)

        assertThat(DocumentsLikeProvider.deletedDocuments).containsExactly("doc-1")
    }

    @Test
    fun `cancel keeps the partial output`() = runBlocking {
        val partial = appStorageFile("Movie.mp4")
        database.downloadDao().insert(appStorageDownload(DOWNLOAD_ID, partial))

        manager.cancelDownload(DOWNLOAD_ID)

        assertThat(partial.exists()).isTrue()
        assertThat(database.downloadDao().getByIdOnce(DOWNLOAD_ID)!!.status).isEqualTo(DownloadStatus.CANCELLED)
    }

    private fun appStorageFile(name: String) = File(downloadDir, name).apply { writeText("partial bytes") }

    private fun appStorageDownload(id: String, file: File) = download(id).copy(
        outputUri = Uri.parse("content://$FILE_PROVIDER_AUTHORITY/apk_downloads/StreamVault/${file.name}").toString(),
        outputDisplayPath = file.absolutePath
    )

    private fun download(id: String) = DownloadEntity(
        id = id,
        providerId = 1L,
        contentType = DownloadContentType.MOVIE,
        contentId = 2L,
        contentName = "Movie",
        streamUrl = "https://example.com/movie.mp4",
        status = DownloadStatus.FAILED
    )

    /** Mirrors FileProvider: deletes the backing file in `delete()`, has no `call()` support. */
    class FileBackedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val file = File(
                File(context!!.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "StreamVault"),
                uri.lastPathSegment.orEmpty()
            )
            return if (file.delete()) 1 else 0
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    /** Minimal documents provider: deletes through `DocumentsContract`'s `call()` protocol. */
    class DocumentsLikeProvider : ContentProvider() {
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == METHOD_DELETE_DOCUMENT) {
                @Suppress("DEPRECATION")
                val uri = extras?.getParcelable<Uri>(EXTRA_URI)
                deletedDocuments += DocumentsContract.getDocumentId(uri)
            }
            return null
        }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            // Hidden DocumentsContract constants used by deleteDocument().
            const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
            const val EXTRA_URI = "uri"
            val deletedDocuments = mutableListOf<String>()
            fun reset() = deletedDocuments.clear()
        }
    }

    private companion object {
        const val DOWNLOAD_ID = "download-1"
        const val OTHER_ID = "download-2"
        const val TAG = "DownloadManager"
        val FILE_PROVIDER_AUTHORITY = "${RuntimeEnvironment.getApplication().packageName}.fileprovider"
        const val DOCUMENTS_AUTHORITY = "com.streamvault.test.documents"
    }
}
