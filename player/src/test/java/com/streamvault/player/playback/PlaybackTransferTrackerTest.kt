@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackTransferTrackerTest {
    @Test
    fun `tracks only HTTP and HTTPS transfers from open until close`() {
        val delegates = mutableListOf<TestDataSource>()
        val tracker = PlaybackTransferTracker()
        val wrapped = tracker.wrap(DataSource.Factory {
            TestDataSource().also(delegates::add)
        })

        val http = wrapped.createDataSource()
        http.open(DataSpec(Uri.parse("http://fixture.test/live.m3u8")))
        assertThat(tracker.hasActiveTransfers).isTrue()
        http.close()
        assertThat(tracker.hasActiveTransfers).isFalse()

        val https = wrapped.createDataSource()
        https.open(DataSpec(Uri.parse("https://fixture.test/segment.ts")))
        assertThat(tracker.hasActiveTransfers).isTrue()
        https.close()
        assertThat(tracker.hasActiveTransfers).isFalse()

        val file = wrapped.createDataSource()
        file.open(DataSpec(Uri.parse("file:///tmp/local.ts")))
        assertThat(tracker.hasActiveTransfers).isFalse()
        file.close()
        assertThat(delegates).hasSize(3)
    }

    @Test
    fun `failed HTTP open preserves open error and suppressed close error`() {
        val delegate = TestDataSource(
            openFailure = IOException("fixture open failed"),
            failFirstClose = true
        )
        val tracker = PlaybackTransferTracker()
        val source = tracker.wrap(DataSource.Factory { delegate }).createDataSource()

        val openFailure = assertThrows(IOException::class.java) {
            source.open(DataSpec(Uri.parse("http://fixture.test/broken")))
        }

        assertThat(delegate.closeCalls).isEqualTo(1)
        assertThat(openFailure.message).isEqualTo("fixture open failed")
        assertThat(openFailure.suppressed.map { it.message }).containsExactly("delegate close failed")
        assertThat(tracker.hasActiveTransfers).isTrue()

        source.close()
        assertThat(tracker.hasActiveTransfers).isFalse()
    }

    @Test
    fun `transfer remains active until delegate close has actually returned`() {
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>(null)
        val delegate = TestDataSource(onClose = {
            closeEntered.countDown()
            check(allowClose.await(2, TimeUnit.SECONDS)) { "test did not release delegate close" }
        })
        val tracker = PlaybackTransferTracker()
        val source = tracker.wrap(DataSource.Factory { delegate }).createDataSource()
        source.open(DataSpec(Uri.parse("http://fixture.test/slow-close")))

        val closingThread = Thread {
            try {
                source.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            }
        }
        closingThread.start()
        try {
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(tracker.hasActiveTransfers).isTrue()
        } finally {
            allowClose.countDown()
            closingThread.join(2_000)
        }

        assertThat(closingThread.isAlive).isFalse()
        assertThat(closeFailure.get()).isNull()
        assertThat(tracker.hasActiveTransfers).isFalse()
    }

    @Test
    fun `failed delegate close stays tracked until a later close succeeds`() {
        val delegate = TestDataSource(failFirstClose = true)
        val tracker = PlaybackTransferTracker()
        val source = tracker.wrap(DataSource.Factory { delegate }).createDataSource()
        source.open(DataSpec(Uri.parse("https://fixture.test/slow-cancel")))

        assertThrows(IOException::class.java) { source.close() }
        assertThat(tracker.hasActiveTransfers).isTrue()

        source.close()
        assertThat(tracker.hasActiveTransfers).isFalse()
        assertThat(delegate.closeCalls).isEqualTo(2)
    }

    private class TestDataSource(
        private val openFailure: IOException? = null,
        private val onClose: () -> Unit = {},
        failFirstClose: Boolean = false
    ) : DataSource {
        var closeCalls = 0
            private set
        private var shouldFailFirstClose = failFirstClose

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openFailure?.let { throw it }
            return 0L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun getUri(): Uri? = null

        override fun close() {
            closeCalls++
            if (shouldFailFirstClose) {
                shouldFailFirstClose = false
                throw IOException("delegate close failed")
            }
            onClose()
        }
    }
}
