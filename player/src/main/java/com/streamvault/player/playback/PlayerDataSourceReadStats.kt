package com.streamvault.player.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.streamvault.domain.model.LivePlaybackIdentity
import com.streamvault.domain.util.LivePlaybackByteTap
import java.io.IOException

@UnstableApi
internal class PlayerDataSourceReadStatsFactory(
    private val upstream: DataSource.Factory,
    private val resolvedStreamType: ResolvedStreamType,
    private val initialTargetUrl: String,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val livePlaybackByteTap: LivePlaybackByteTap? = null,
    private val livePlaybackIdentity: LivePlaybackIdentity? = null,
    private val sensitiveTargetOrigin: String? = null,
    private val livePlaybackRequestFingerprint: String? = null
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        PlayerDataSourceReadStatsDataSource(
            upstream = upstream.createDataSource(),
            resolvedStreamType = resolvedStreamType,
            initialTargetUrl = initialTargetUrl,
            clockMs = clockMs,
            livePlaybackByteTap = livePlaybackByteTap,
            livePlaybackIdentity = livePlaybackIdentity,
            sensitiveTargetOrigin = sensitiveTargetOrigin,
            livePlaybackRequestFingerprint = livePlaybackRequestFingerprint
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
    private val clockMs: () -> Long,
    private val livePlaybackByteTap: LivePlaybackByteTap?,
    private val livePlaybackIdentity: LivePlaybackIdentity?,
    private val sensitiveTargetOrigin: String?,
    private val livePlaybackRequestFingerprint: String?
) : DataSource {
    private val tracker = PlayerDataSourceReadStatsTracker()
    private var target = sanitizeTarget(initialTargetUrl)
    private var opened = false
    private var livePlaybackPublisher: LivePlaybackByteTap.Publisher? = null
    private var livePlaybackHlsPublisher: LivePlaybackByteTap.HlsPublisher? = null
    private var livePlaybackHlsExpectedLength: Long? = null
    private var livePlaybackHlsBytesRead = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        livePlaybackPublisher?.close()
        livePlaybackPublisher = null
        livePlaybackHlsPublisher?.close()
        livePlaybackHlsPublisher = null
        livePlaybackHlsExpectedLength = null
        livePlaybackHlsBytesRead = 0L
        target = sanitizeTarget(dataSpec.uri.toString())
        val length = upstream.open(dataSpec)
        tracker.open(clockMs())
        opened = true
        if ((resolvedStreamType == ResolvedStreamType.HLS ||
                (resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE && dataSpec.position == 0L)) &&
            (dataSpec.uri.scheme.equals("http", ignoreCase = true) ||
                dataSpec.uri.scheme.equals("https", ignoreCase = true))
        ) {
            val identity = livePlaybackIdentity
            val fingerprint = livePlaybackRequestFingerprint
            if (identity != null && fingerprint != null) {
                if (resolvedStreamType == ResolvedStreamType.MPEG_TS_LIVE) {
                    livePlaybackPublisher = livePlaybackByteTap?.begin(identity, fingerprint)
                } else {
                    val requestedUrl = dataSpec.uri.toString()
                    val resolvedUrl = upstream.uri?.toString() ?: requestedUrl
                    livePlaybackHlsPublisher = livePlaybackByteTap?.beginHlsResource(
                        identity = identity,
                        requestFingerprint = fingerprint,
                        requestedUrl = requestedUrl,
                        resolvedUrl = resolvedUrl,
                        isPlaylist = isHlsPlaylist(dataSpec),
                        rangeStart = dataSpec.position,
                        rangeLength = dataSpec.length.takeIf { it >= 0L }
                            ?: length.takeIf { dataSpec.position > 0L && it >= 0L }
                            ?: -1L
                    )
                    livePlaybackHlsExpectedLength = dataSpec.length.takeIf { it >= 0L }
                        ?: length.takeIf { it >= 0L }
                }
            }
        }
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
        if (bytesRead > 0) {
            livePlaybackPublisher?.publish(buffer, offset, bytesRead)
            livePlaybackHlsPublisher?.publish(buffer, offset, bytesRead)
            if (livePlaybackHlsPublisher != null) {
                livePlaybackHlsBytesRead += bytesRead
                livePlaybackHlsExpectedLength?.let { expectedLength ->
                    when {
                        livePlaybackHlsBytesRead == expectedLength -> livePlaybackHlsPublisher?.complete()
                        livePlaybackHlsBytesRead > expectedLength -> livePlaybackHlsPublisher?.close()
                    }
                }
            }
        } else if (bytesRead == C.RESULT_END_OF_INPUT) {
            val expectedLength = livePlaybackHlsExpectedLength
            if (expectedLength == null || livePlaybackHlsBytesRead == expectedLength) {
                livePlaybackHlsPublisher?.complete()
            } else {
                livePlaybackHlsPublisher?.close()
            }
        }
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
            livePlaybackPublisher?.close()
            livePlaybackPublisher = null
            livePlaybackHlsPublisher?.close()
            livePlaybackHlsPublisher = null
            livePlaybackHlsExpectedLength = null
            livePlaybackHlsBytesRead = 0L
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

    private fun isHlsPlaylist(dataSpec: DataSpec): Boolean {
        val contentType = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            .orEmpty()
            .joinToString(" ")
            .lowercase()
        val path = dataSpec.uri.path.orEmpty().lowercase()
        return "mpegurl" in contentType || path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

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
