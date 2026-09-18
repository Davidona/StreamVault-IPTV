package com.streamvault.app.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DownloadForegroundServiceTimeoutHandlerTest {

    @Test
    fun timeoutReleasesQuotaBeforeCleanup() {
        val events = mutableListOf<String>()
        val handler = DownloadForegroundServiceTimeoutHandler(
            releaseQuotaLease = { events += "release" },
            pauseDownload = { events += "pause" },
            stopForeground = { events += "stop_foreground" },
            stopSelf = { startId -> events += "stop_self_$startId" }
        )

        handler.handle(
            downloadId = "probe",
            startId = 7,
            launch = { block -> runBlocking { block() } }
        )

        assertThat(events).containsExactly(
            "release",
            "pause",
            "stop_foreground",
            "stop_self_7"
        ).inOrder()
    }
}
