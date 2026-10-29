@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Collections
import java.util.IdentityHashMap

/** Tracks actual HTTP data-source ownership through open/close, including cancelled reads. */
class PlaybackTransferTracker {
    private val active = Collections.newSetFromMap(IdentityHashMap<DataSource, Boolean>())

    val hasActiveTransfers: Boolean
        get() = synchronized(active) { active.isNotEmpty() }

    fun wrap(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        TrackedDataSource(upstream.createDataSource())
    }

    private inner class TrackedDataSource(private val upstream: DataSource) : DataSource {
        private var tracked = false

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.uri.scheme.equals("http", true) || dataSpec.uri.scheme.equals("https", true)) {
                synchronized(active) { active.add(this) }
                tracked = true
            }
            try {
                return upstream.open(dataSpec)
            } catch (failure: Throwable) {
                try { close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
                throw failure
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
        override fun getUri(): Uri? = upstream.uri
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            // Do not advertise a drained source before its upstream close has completed.
            // A failed close remains conservatively active; the refresh gate can give up.
            upstream.close()
            if (tracked) {
                synchronized(active) { active.remove(this) }
                tracked = false
            }
        }
    }
}
