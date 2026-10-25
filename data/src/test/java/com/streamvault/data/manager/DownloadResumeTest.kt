package com.streamvault.data.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadSourceType
import com.streamvault.domain.model.DownloadStatus
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end transfer behaviour after a transient interruption. Output goes to a user-selected
 * folder backed by [TreeProvider] (a minimal documents provider over a temp directory), because
 * the default destination needs the app module's FileProvider manifest entry.
 *
 * The retry delay is skipped by moving the PAUSED row to PENDING, which is exactly what
 * `handleDownloadFailure` does once the delay elapses.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadResumeTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: StreamVaultDatabase
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManagerImpl
    private val payload = Random(7).nextBytes(TOTAL)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        TreeProvider.root = File(context.cacheDir, "tree").apply { deleteRecursively(); mkdirs() }
        Robolectric.buildContentProvider(TreeProvider::class.java)
            .create(ProviderInfo().apply { authority = TreeProvider.AUTHORITY })
        val treeUri = DocumentsContract.buildTreeDocumentUri(TreeProvider.AUTHORITY, "root")
        val preferences = mock<PreferencesRepository> {
            on { downloadTreeUri } doReturn flowOf(treeUri.toString())
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
        server.shutdown()
        database.close()
    }

    @Test
    fun `complete response is downloaded and verified`() = runBlocking {
        server.enqueue(full())

        start()

        val row = awaitStatus(DownloadStatus.COMPLETED)
        assertThat(row.bytesWritten).isEqualTo(TOTAL.toLong())
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `interrupted transfer keeps its partial output for the retry`() = runBlocking {
        server.enqueue(interrupted())

        start()

        val row = awaitStatus(DownloadStatus.PAUSED)
        assertThat(row.retryCount).isEqualTo(1)
        assertThat(row.bytesWritten).isEqualTo(HALF.toLong())
        assertThat(row.outputUri).isNotNull()
        assertThat(outputFiles().single().length()).isEqualTo(HALF.toLong())
    }

    @Test
    fun `retry resumes with a range request and completes the original bytes`() = runBlocking {
        server.enqueue(interrupted())
        server.enqueue(range(start = HALF, end = TOTAL - 1, body = payload.copyOfRange(HALF, TOTAL)))

        start()
        awaitStatus(DownloadStatus.PAUSED)
        retryNow()

        val row = awaitStatus(DownloadStatus.COMPLETED)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=$HALF-")
        assertThat(row.bytesWritten).isEqualTo(TOTAL.toLong())
        assertThat(row.totalBytes).isEqualTo(TOTAL.toLong())
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `server ignoring the range restarts from zero without duplicating bytes`() = runBlocking {
        server.enqueue(interrupted())
        server.enqueue(full())

        start()
        awaitStatus(DownloadStatus.PAUSED)
        retryNow()

        awaitStatus(DownloadStatus.COMPLETED)
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `range starting at the wrong offset is not appended`() = runBlocking {
        server.enqueue(interrupted())
        server.enqueue(range(start = HALF - 100, end = TOTAL - 1, body = payload.copyOfRange(HALF - 100, TOTAL)))
        server.enqueue(full())

        start()
        awaitStatus(DownloadStatus.PAUSED)
        retryNow()

        awaitStatus(DownloadStatus.COMPLETED)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `range reporting a different total size restarts from zero`() = runBlocking {
        server.enqueue(interrupted())
        server.enqueue(range(start = HALF, end = TOTAL, body = ByteArray(TOTAL - HALF + 1), total = TOTAL + 1))
        server.enqueue(full())

        start()
        awaitStatus(DownloadStatus.PAUSED)
        retryNow()

        awaitStatus(DownloadStatus.COMPLETED)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `range body longer than the declared range is not appended`() = runBlocking {
        server.enqueue(interrupted())
        server.enqueue(range(start = HALF, end = TOTAL - 1, body = payload.copyOfRange(HALF, TOTAL) + ByteArray(100)))
        server.enqueue(full())

        start()
        awaitStatus(DownloadStatus.PAUSED)
        retryNow()

        awaitStatus(DownloadStatus.COMPLETED)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(outputFiles().single().readBytes().sha256()).isEqualTo(payload.sha256())
    }

    @Test
    fun `response shorter than the known total is not marked completed`() = runBlocking {
        server.enqueue(range(start = 0, end = HALF - 1, body = payload.copyOfRange(0, HALF)))

        start()

        val row = awaitStatus(DownloadStatus.PAUSED)
        assertThat(row.status).isNotEqualTo(DownloadStatus.COMPLETED)
        assertThat(row.retryCount).isEqualTo(1)
    }

    private suspend fun start() {
        database.downloadDao().insert(
            DownloadEntity(
                id = DOWNLOAD_ID,
                providerId = 1L,
                contentType = DownloadContentType.MOVIE,
                contentId = 2L,
                contentName = "Movie",
                streamUrl = server.url("/movie.mp4").toString(),
                sourceType = DownloadSourceType.DIRECT_URL.name,
                containerExtension = "mp4"
            )
        )
        manager.recoverInterruptedDownloads()
    }

    private suspend fun retryNow() {
        val dao = database.downloadDao()
        dao.update(dao.getByIdOnce(DOWNLOAD_ID)!!.copy(status = DownloadStatus.PENDING))
        manager.recoverInterruptedDownloads()
    }

    private suspend fun awaitStatus(expected: DownloadStatus): DownloadEntity {
        val dao = database.downloadDao()
        val deadline = System.currentTimeMillis() + 5_000
        var row = dao.getByIdOnce(DOWNLOAD_ID)!!
        while (row.status != expected && System.currentTimeMillis() < deadline) {
            delay(25)
            row = dao.getByIdOnce(DOWNLOAD_ID)!!
        }
        assertThat(row.status).isEqualTo(expected)
        return row
    }

    private fun outputFiles(): List<File> = TreeProvider.root.listFiles().orEmpty().filter { it.isFile }

    private fun full() = MockResponse()
        .setHeader("Content-Type", "video/mp4")
        .setHeader("Accept-Ranges", "bytes")
        .setBody(Buffer().write(payload))

    private fun interrupted() = full().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    private fun range(start: Int, end: Int, body: ByteArray, total: Int = TOTAL) = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Type", "video/mp4")
        .setHeader("Accept-Ranges", "bytes")
        .setHeader("Content-Range", "bytes $start-$end/$total")
        .setBody(Buffer().write(body))

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    /** Minimal tree documents provider: one flat directory, documents keyed by file name. */
    class TreeProvider : ContentProvider() {
        override fun onCreate() = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            @Suppress("DEPRECATION")
            val uri = extras?.getParcelable<Uri>(EXTRA_URI) ?: return null
            when (method) {
                METHOD_CREATE_DOCUMENT -> {
                    val name = uniqueName(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)
                    File(root, name).createNewFile()
                    return Bundle().apply {
                        putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(uri, name))
                    }
                }
                METHOD_DELETE_DOCUMENT -> File(root, DocumentsContract.getDocumentId(uri)).delete()
            }
            return null
        }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            val file = File(root, DocumentsContract.getDocumentId(uri))
            val columns = p ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS
            )
            return MatrixCursor(columns).apply {
                if (file.exists()) {
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                            DocumentsContract.Document.COLUMN_SIZE -> file.length()
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> "video/mp4"
                            DocumentsContract.Document.COLUMN_FLAGS ->
                                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                            else -> null
                        }
                    })
                }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                File(root, DocumentsContract.getDocumentId(uri)),
                ParcelFileDescriptor.parseMode(mode)
            )

        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        private fun uniqueName(name: String): String {
            if (!File(root, name).exists()) return name
            val base = name.substringBeforeLast('.')
            val extension = name.substringAfterLast('.', "")
            return generateSequence(1) { it + 1 }
                .map { "$base ($it).$extension" }
                .first { !File(root, it).exists() }
        }

        companion object {
            const val AUTHORITY = "com.streamvault.test.tree"
            // Hidden DocumentsContract constants used by createDocument()/deleteDocument().
            const val METHOD_CREATE_DOCUMENT = "android:createDocument"
            const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
            const val EXTRA_URI = "uri"
            lateinit var root: File
        }
    }

    private companion object {
        const val DOWNLOAD_ID = "download-1"
        const val TOTAL = 64 * 1024
        const val HALF = TOTAL / 2
    }
}
