package com.streamvault.app.service

/**
 * Coordinates the work triggered by Android's foreground-service timeout callback.
 *
 * Quota release is deliberately synchronous and happens before the asynchronous cleanup so a
 * replacement dataSync service can acquire the shared lease during the platform grace period.
 */
internal class DownloadForegroundServiceTimeoutHandler(
    private val releaseQuotaLease: () -> Unit,
    private val pauseDownload: suspend () -> Unit,
    private val stopForeground: () -> Unit,
    private val stopSelf: (Int) -> Unit
) {

    fun handle(
        downloadId: String?,
        startId: Int,
        launch: ((suspend () -> Unit) -> Unit)
    ) {
        releaseQuotaLease()
        launch {
            try {
                if (!downloadId.isNullOrBlank()) pauseDownload()
            } finally {
                stopForeground()
                stopSelf(startId)
            }
        }
    }
}
