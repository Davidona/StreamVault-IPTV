package com.streamvault.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.DownloadDao
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadStatus
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Durable restart fixture for the service's orphan-finalization path. */
@RunWith(AndroidJUnit4::class)
class DownloadForegroundServiceRecoveryInstrumentationTest {

    private lateinit var context: Context
    private lateinit var downloadDao: DownloadDao
    private var outputFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        downloadDao = EntryPointAccessors.fromApplication(
            context,
            DownloadForegroundService.DownloadServiceEntryPoint::class.java
        ).downloadDao()
        runBlocking { downloadDao.deleteById(DOWNLOAD_ID) }
    }

    @After
    fun tearDown() {
        context.stopService(Intent(context, DownloadForegroundService::class.java))
        runBlocking { downloadDao.deleteById(DOWNLOAD_ID) }
        outputFile?.delete()
    }

    @Test
    fun stickyRestartFinalizesFullyWrittenOrphanWithoutStartingNetworkTransfer() = runBlocking {
        val bytes = "durable-final-bytes".toByteArray()
        outputFile = File(context.cacheDir, "wp7-$DOWNLOAD_ID.bin").apply { writeBytes(bytes) }
        downloadDao.insert(
            DownloadEntity(
                id = DOWNLOAD_ID,
                providerId = 1L,
                contentType = DownloadContentType.MOVIE,
                contentId = 2L,
                contentName = "Restart fixture",
                streamUrl = "https://127.0.0.1:1/should-not-be-requested",
                outputDisplayPath = outputFile!!.absolutePath,
                status = DownloadStatus.DOWNLOADING,
                bytesWritten = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                supportsResume = true,
                ownerId = "dead-process",
                ownerEpoch = 4L,
                heartbeatAt = 1L
            )
        )

        ContextCompat.startForegroundService(
            context,
            Intent(context, DownloadForegroundService::class.java)
        )

        withTimeout(10_000L) {
            while (downloadDao.getByIdOnce(DOWNLOAD_ID)?.status != DownloadStatus.COMPLETED) {
                delay(100L)
            }
        }

        val recovered = downloadDao.getByIdOnce(DOWNLOAD_ID)!!
        assertThat(recovered.status).isEqualTo(DownloadStatus.COMPLETED)
        assertThat(recovered.bytesWritten).isEqualTo(bytes.size.toLong())
        assertThat(recovered.ownerId).isNull()
        assertThat(recovered.heartbeatAt).isNull()
    }

    private companion object {
        const val DOWNLOAD_ID = "wp7-restart-finalization"
    }
}
