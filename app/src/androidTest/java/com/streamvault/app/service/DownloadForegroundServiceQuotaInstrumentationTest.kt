package com.streamvault.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.data.platform.DataSyncQuotaAcquireResult
import com.streamvault.data.platform.DataSyncQuotaOwner
import com.streamvault.data.platform.DataSyncServiceOwner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same timeout handler used by the service without relying on an emulator timer.
 *
 * The handler is exercised with a probe id so cleanup remains representative of a real download;
 * the shared lease must be released before asynchronous cleanup.
 */
@RunWith(AndroidJUnit4::class)
class DownloadForegroundServiceQuotaInstrumentationTest {

    private lateinit var context: Context
    private lateinit var quotaOwner: DataSyncQuotaOwner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(QUOTA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        quotaOwner = EntryPointAccessors.fromApplication(
            context,
            DownloadForegroundService.DownloadServiceEntryPoint::class.java
        ).dataSyncQuotaOwner()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(QUOTA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun timeoutHandlerReleasesDownloadLease() {
        val acquired = quotaOwner.acquire(DataSyncServiceOwner.DOWNLOAD)
            as DataSyncQuotaAcquireResult.Granted

        DownloadForegroundServiceTimeoutHandler(
            releaseQuotaLease = { quotaOwner.release(acquired.lease) },
            pauseDownload = {},
            stopForeground = {},
            stopSelf = {}
        ).handle(
            downloadId = PROBE_DOWNLOAD_ID,
            startId = 1,
            launch = { block -> runBlocking { block() } }
        )

        check(
            !quotaOwner.snapshot().activeOwners.contains(DataSyncServiceOwner.DOWNLOAD)
        ) {
            "timeout handler did not release the shared download lease"
        }
    }

    private companion object {
        const val QUOTA_PREFERENCES = "foreground_service_quota"
        const val PROBE_DOWNLOAD_ID = "wp0-reduced-timeout-probe"
    }
}
