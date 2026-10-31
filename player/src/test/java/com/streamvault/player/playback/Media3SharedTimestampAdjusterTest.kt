@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeoutException
import org.robolectric.shadows.ShadowSystemClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Deterministic checks of Media3's real shared HLS timestamp-adjuster wait contract. */
@RunWith(RobolectricTestRunner::class)
class Media3SharedTimestampAdjusterTest {

    @Test
    fun `non primary waits without primary and times out when configured`() {
        val adjuster = TimestampAdjuster(TimestampAdjuster.MODE_SHARED)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val timeoutMs = 180L

        val waiter = Thread {
            try {
                adjuster.sharedInitializeOrWait(
                    /* canInitialize= */ false,
                    /* nextSampleTimestampUs= */ C.TIME_UNSET,
                    timeoutMs
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }.apply {
            name = "timestamp-adjuster-timeout-test"
            isDaemon = true
        }

        waiter.start()
        try {
            awaitWaitingState(waiter, allowTimedWait = true)
            // Media3 uses Android elapsedRealtime() in the timeout loop. Advance Robolectric's
            // clock after the worker enters Object.wait(); the real wait then wakes normally.
            ShadowSystemClock.advanceBy(timeoutMs + 1, TimeUnit.MILLISECONDS)
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue()
            waiter.join(1_000)

            assertThat(failure.get()).isInstanceOf(TimeoutException::class.java)
            assertThat(failure.get()?.message).contains("$timeoutMs milliseconds")
            assertThat(isHlsTimestampInitializationTimeout(IOException(failure.get()))).isTrue()
            assertThat(adjuster.isInitialized).isFalse()
            assertThat(adjuster.timestampOffsetUs).isEqualTo(C.TIME_UNSET)
        } finally {
            waiter.interrupt()
            waiter.join(1_000)
        }
    }

    @Test
    fun `delayed primary sample initializes and releases an already waiting secondary`() {
        val adjuster = TimestampAdjuster(TimestampAdjuster.MODE_SHARED)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val timeoutMs = 2_000L

        val secondary = Thread {
            entered.countDown()
            try {
                adjuster.sharedInitializeOrWait(
                    /* canInitialize= */ false,
                    /* nextSampleTimestampUs= */ C.TIME_UNSET,
                    timeoutMs
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }.apply {
            name = "timestamp-adjuster-secondary-test"
            isDaemon = true
        }

        secondary.start()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            awaitWaitingState(secondary, allowTimedWait = true)
            assertThat(finished.count).isEqualTo(1L)
            assertThat(adjuster.isInitialized).isFalse()

            val desiredFirstSampleUs = 4_000_000L
            val rawFirstSampleUs = 19_000_000L
            adjuster.sharedInitializeOrWait(
                /* canInitialize= */ true,
                desiredFirstSampleUs,
                /* timeoutMs= */ 0L
            )
            assertThat(adjuster.adjustSampleTimestamp(rawFirstSampleUs)).isEqualTo(desiredFirstSampleUs)

            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
            secondary.join(1_000)
            assertThat(failure.get()).isNull()
            assertThat(adjuster.isInitialized).isTrue()
            assertThat(adjuster.timestampOffsetUs).isEqualTo(desiredFirstSampleUs - rawFirstSampleUs)
        } finally {
            secondary.interrupt()
            secondary.join(1_000)
        }
    }

    @Test
    fun `interrupt releases an unbounded non primary wait`() {
        val adjuster = TimestampAdjuster(TimestampAdjuster.MODE_SHARED)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val secondary = Thread {
            entered.countDown()
            try {
                adjuster.sharedInitializeOrWait(
                    /* canInitialize= */ false,
                    /* nextSampleTimestampUs= */ C.TIME_UNSET,
                    /* timeoutMs= */ 0L
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }.apply {
            name = "timestamp-adjuster-cancel-test"
            isDaemon = true
        }

        secondary.start()
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            awaitWaitingState(secondary)
            secondary.interrupt()

            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue()
            secondary.join(1_000)
            assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(adjuster.isInitialized).isFalse()
        } finally {
            secondary.interrupt()
            secondary.join(1_000)
        }
    }

    private fun awaitWaitingState(thread: Thread, allowTimedWait: Boolean = false) {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        fun waiting() = thread.state == Thread.State.WAITING ||
            allowTimedWait && thread.state == Thread.State.TIMED_WAITING
        while (!waiting() && System.nanoTime() < deadlineNs) {
            Thread.yield()
        }
        assertThat(waiting()).isTrue()
    }
}
