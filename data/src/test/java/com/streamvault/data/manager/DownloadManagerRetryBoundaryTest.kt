package com.streamvault.data.manager

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadSourceType
import com.streamvault.domain.model.DownloadStatus
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Scheduler-level retry boundary: `DownloadTransferStateMachine` schedules a retry for the failure
 * that brings retryCount to the ceiling, and only fails terminally on the attempt after it. The
 * rows below are seeded in the exact state `handleDownloadFailure` writes once the retry delay ends
 * (PENDING with the incremented retryCount), so no real-time backoff is needed.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadManagerRetryBoundaryTest {

    private lateinit var database: StreamVaultDatabase
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManagerImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = mock<PreferencesRepository> {
            on { downloadTreeUri } doReturn flowOf(null)
        }
        manager = DownloadManagerImpl(
            context = RuntimeEnvironment.getApplication(),
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
    fun `retry due at the ceiling runs its final attempt and fails terminally`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        database.downloadDao().insert(download(retryCount = MAX_RETRIES))

        manager.recoverInterruptedDownloads()

        val settled = awaitStatus { it != DownloadStatus.PENDING && it != DownloadStatus.DOWNLOADING }
        assertThat(settled.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(settled.retryCount).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `terminally failed download is not scheduled again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        database.downloadDao().insert(download(retryCount = MAX_RETRIES))
        manager.recoverInterruptedDownloads()
        awaitStatus { it == DownloadStatus.FAILED }

        manager.recoverInterruptedDownloads()
        delay(500)

        assertThat(database.downloadDao().getByIdOnce(DOWNLOAD_ID)!!.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `retry below the ceiling keeps the existing backoff`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        database.downloadDao().insert(download(retryCount = MAX_RETRIES - 1))

        manager.recoverInterruptedDownloads()

        val settled = awaitStatus { it == DownloadStatus.PAUSED }
        assertThat(settled.retryCount).isEqualTo(MAX_RETRIES)
        assertThat(server.requestCount).isEqualTo(1)
    }

    private suspend fun awaitStatus(predicate: (DownloadStatus) -> Boolean): DownloadEntity {
        val dao = database.downloadDao()
        val deadline = System.currentTimeMillis() + 5_000
        var row = dao.getByIdOnce(DOWNLOAD_ID)!!
        while (!predicate(row.status) && System.currentTimeMillis() < deadline) {
            delay(25)
            row = dao.getByIdOnce(DOWNLOAD_ID)!!
        }
        return row
    }

    private fun download(retryCount: Int) = DownloadEntity(
        id = DOWNLOAD_ID,
        providerId = 1L,
        contentType = DownloadContentType.MOVIE,
        contentId = 2L,
        contentName = "Movie",
        streamUrl = server.url("/movie.mp4").toString(),
        sourceType = DownloadSourceType.DIRECT_URL.name,
        status = DownloadStatus.PENDING,
        retryCount = retryCount
    )

    private companion object {
        const val DOWNLOAD_ID = "download-1"
        const val MAX_RETRIES = 5
    }
}
