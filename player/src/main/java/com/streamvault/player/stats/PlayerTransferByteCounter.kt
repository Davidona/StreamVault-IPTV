package com.streamvault.player.stats

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts every network byte ExoPlayer pulls through the playback data sources so
 * [PlayerStatsCollector] can compute a measured stream bitrate. Loader threads write, the
 * Main thread reads, hence the [AtomicLong].
 */
@UnstableApi
class PlayerTransferByteCounter : TransferListener {
    private val networkBytes = AtomicLong(0L)

    /** Cumulative network bytes since the counter was created. Never reset; consumers diff it. */
    val totalNetworkBytes: Long get() = networkBytes.get()

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork && bytesTransferred > 0) {
            networkBytes.addAndGet(bytesTransferred.toLong())
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}
