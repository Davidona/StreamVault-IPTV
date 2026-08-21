package com.streamvault.app.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.data.platform.DataSyncQuotaOwner
import com.streamvault.data.platform.DataSyncServiceOwner
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs only with the API 35/36 reduced-timeout harness from release CI.
 *
 * The missing download id deliberately leaves the service observing a live Room flow, so the
 * Android timeout callback—not normal download completion—must release the shared lease.
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
        context.stopService(probeIntent())
        context.getSharedPreferences(QUOTA_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun reducedDataSyncTimeoutReleasesDownloadLease() {
        val intent = probeIntent()
        androidx.core.content.ContextCompat.startForegroundService(context, intent)

        await("download service acquires shared quota lease") {
            quotaOwner.snapshot().activeOwners.contains(DataSyncServiceOwner.DOWNLOAD)
        }
        await("Android timeout callback releases shared quota lease") {
            !quotaOwner.snapshot().activeOwners.contains(DataSyncServiceOwner.DOWNLOAD)
        }
    }

    private fun probeIntent(): Intent = Intent(context, DownloadForegroundService::class.java)
        .putExtra("download_id", PROBE_DOWNLOAD_ID)

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_WAIT_MS
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        if (!predicate()) throw AssertionError(description)
    }

    private companion object {
        const val QUOTA_PREFERENCES = "foreground_service_quota"
        const val PROBE_DOWNLOAD_ID = "wp0-reduced-timeout-probe"
        const val POLL_INTERVAL_MS = 250L
        const val TIMEOUT_WAIT_MS = 20_000L
    }
}
