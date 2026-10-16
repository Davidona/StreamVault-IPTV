package com.streamvault.player.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
internal class PlayerDataSourceReadStatsFactory(
    private val upstream: DataSource.Factory,
    private val resolvedStreamType: ResolvedStreamType,
    private val initialTargetUrl: String,
    private val sensitiveTargetOrigin: String? = null,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        PlayerDataSourceReadStatsDataSource(
            upstream = upstream.createDataSource(),
            resolvedStreamType = resolvedStreamType,
            initialTargetUrl = initialTargetUrl,
            sensitiveTargetOrigin = sensitiveTargetOrigin,
            clockMs = clockMs
        )
}

internal fun shouldWrapDataSourceReadStats(resolvedStreamType: ResolvedStreamType): Boolean =
    resolvedStreamType == ResolvedStreamType.HLS ||
        resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE

@UnstableApi
private class PlayerDataSourceReadStatsDataSource(
    private val upstream: DataSource,
    private val resolvedStreamType: ResolvedStreamType,
    initialTargetUrl: String,
    private val sensitiveTargetOrigin: String?,
    private val clockMs: () -> Long
) : DataSource {
    private val tracker = PlayerDataSourceReadStatsTracker()
    private var target = sanitizeTarget(initialTargetUrl)
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        target = sanitizeTarget(dataSpec.uri.toString())
        val length = upstream.open(dataSpec)
        tracker.open(clockMs())
        opened = true
        if (readStatsLoggingEnabled()) {
            Log.d(
                TAG,
                "read-open streamType=$resolvedStreamType position=${dataSpec.position} length=$length target=$target"
            )
        }
        return length
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0 && readStatsLoggingEnabled()) {
            tracker.recordRead(bytesRead, clockMs())?.let(::logProgress)
        }
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        try {
            upstream.close()
        } finally {
            if (opened && readStatsLoggingEnabled()) {
                logClose(tracker.snapshot(clockMs()))
            }
            opened = false
        }
    }

    private fun logProgress(snapshot: PlayerDataSourceReadStatsSnapshot) {
        Log.d(
            TAG,
            "read-progress streamType=$resolvedStreamType bytes=${snapshot.totalBytes} " +
                "deltaBytes=${snapshot.deltaBytes} elapsedMs=${snapshot.elapsedMs} " +
                "intervalMs=${snapshot.intervalMs} avgKbps=${snapshot.averageKbps} " +
                "intervalKbps=${snapshot.intervalKbps} target=$target"
        )
    }

    private fun logClose(snapshot: PlayerDataSourceReadStatsSnapshot) {
        Log.d(
            TAG,
            "read-close streamType=$resolvedStreamType bytes=${snapshot.totalBytes} " +
                "elapsedMs=${snapshot.elapsedMs} avgKbps=${snapshot.averageKbps} target=$target"
        )
    }

    private fun readStatsLoggingEnabled(): Boolean = Log.isLoggable(TAG, Log.DEBUG)

    private fun sanitizeTarget(url: String): String =
        PlaybackLogSanitizer.sanitizeUrl(sensitiveTargetOrigin ?: url)

    private companion object {
        const val TAG = "PlayerDataReadStats"
    }
}

internal class PlayerDataSourceReadStatsTracker(
    private val minLogIntervalMs: Long = MIN_LOG_INTERVAL_MS,
    private val minLogDeltaBytes: Long = MIN_LOG_DELTA_BYTES
) {
    private var openedAtMs: Long = 0L
    private var lastLogAtMs: Long = 0L
    private var lastLogBytes: Long = 0L
    private var totalBytes: Long = 0L

    fun open(nowMs: Long) {
        openedAtMs = nowMs
        lastLogAtMs = nowMs
        lastLogBytes = 0L
        totalBytes = 0L
    }

    fun recordRead(bytesRead: Int, nowMs: Long): PlayerDataSourceReadStatsSnapshot? {
        if (bytesRead <= 0) return null

        totalBytes += bytesRead.toLong()
        val intervalMs = nowMs - lastLogAtMs
        val deltaBytes = totalBytes - lastLogBytes
        if (intervalMs < minLogIntervalMs && deltaBytes < minLogDeltaBytes) {
            return null
        }

        return snapshot(nowMs).also {
            lastLogAtMs = nowMs
            lastLogBytes = totalBytes
        }
    }

    fun snapshot(nowMs: Long): PlayerDataSourceReadStatsSnapshot {
        val elapsedMs = (nowMs - openedAtMs).coerceAtLeast(0L)
        val intervalMs = (nowMs - lastLogAtMs).coerceAtLeast(0L)
        val deltaBytes = totalBytes - lastLogBytes
        return PlayerDataSourceReadStatsSnapshot(
            totalBytes = totalBytes,
            deltaBytes = deltaBytes,
            elapsedMs = elapsedMs,
            intervalMs = intervalMs,
            averageKbps = bytesToKbps(totalBytes, elapsedMs),
            intervalKbps = bytesToKbps(deltaBytes, intervalMs)
        )
    }

    private fun bytesToKbps(bytes: Long, elapsedMs: Long): Long {
        if (bytes <= 0L || elapsedMs <= 0L) return 0L
        return (bytes * 8_000L) / elapsedMs / 1_000L
    }

    companion object {
        const val MIN_LOG_INTERVAL_MS = 2_000L
        const val MIN_LOG_DELTA_BYTES = 512L * 1024L
    }
}

internal data class PlayerDataSourceReadStatsSnapshot(
    val totalBytes: Long,
    val deltaBytes: Long,
    val elapsedMs: Long,
    val intervalMs: Long,
    val averageKbps: Long,
    val intervalKbps: Long
)
