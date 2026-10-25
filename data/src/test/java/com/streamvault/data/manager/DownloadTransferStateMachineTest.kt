package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DownloadStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTransferStateMachineTest {

    @Test
    fun `transient failures use bounded retry schedule`() {
        val machine = DownloadTransferStateMachine(maxRetries = 5)

        val transitions = (0 until 5).map { retryCount ->
            machine.failure(retryCount, DownloadFailureKind.TRANSIENT, "network")
        }

        assertThat(transitions.map { it.status }).containsExactlyElementsIn(
            listOf(
                DownloadStatus.PAUSED,
                DownloadStatus.PAUSED,
                DownloadStatus.PAUSED,
                DownloadStatus.PAUSED,
                DownloadStatus.PAUSED
            )
        ).inOrder()
        assertThat(transitions.map { it.retryCount }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(transitions.map { it.retryAfterMillis })
            .containsExactly(5_000L, 15_000L, 45_000L, 300_000L, 300_000L)
            .inOrder()
    }

    @Test
    fun `failure at retry ceiling becomes terminal and resets output`() {
        val transition = DownloadTransferStateMachine(maxRetries = 5)
            .failure(5, DownloadFailureKind.TRANSIENT, "timeout")

        assertThat(transition).isEqualTo(
            DownloadFailureTransition(
                status = DownloadStatus.FAILED,
                retryCount = 0,
                resetOutput = true,
                retryAfterMillis = null,
                reason = "timeout"
            )
        )
    }

    @Test
    fun `permanent failures never consume a retry`() {
        val transition = DownloadTransferStateMachine()
            .failure(2, DownloadFailureKind.PERMANENT, "disk full")

        assertThat(transition.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(transition.retryCount).isEqualTo(0)
        assertThat(transition.retryAfterMillis).isNull()
        assertThat(transition.resetOutput).isTrue()
    }

    @Test
    fun `cancellation transitions preserve or discard output according to owner`() {
        val machine = DownloadTransferStateMachine()

        assertThat(machine.cancellation(DownloadCancellationKind.USER))
            .isEqualTo(DownloadCancellationTransition(DownloadStatus.CANCELLED, false, null))
        assertThat(machine.cancellation(DownloadCancellationKind.PLAYBACK_STARTED))
            .isEqualTo(
                DownloadCancellationTransition(
                    DownloadStatus.PAUSED,
                    true,
                    "Waiting for playback to stop"
                )
            )
        assertThat(machine.cancellation(DownloadCancellationKind.FOREGROUND_SERVICE_TIMEOUT))
            .isEqualTo(
                DownloadCancellationTransition(
                    DownloadStatus.PAUSED,
                    false,
                    "Download paused because Android exhausted the foreground-service time allowance."
                )
            )
    }

    @Test
    fun `copy loop reports checkpoints and supports unknown length`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val copier = DownloadTransferCopier(
            ioDispatcher = dispatcher,
            bufferSize = 3,
            checkpointBytes = 5
        )
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<DownloadCopyProgress>()

        val bytes = copier.copy(
            input = ByteArrayInputStream("abcdefgh".toByteArray()),
            output = output,
            startingBytes = 0L,
            onProgress = { progress += it }
        )

        assertThat(bytes).isEqualTo(8L)
        assertThat(output.toString(Charsets.UTF_8.name())).isEqualTo("abcdefgh")
        assertThat(progress.map { it.bytesWritten }).containsExactly(3L, 6L, 8L).inOrder()
        assertThat(progress.map { it.shouldCheckpoint }).containsExactly(false, true, false).inOrder()
    }

    @Test(expected = CancellationException::class)
    fun `copy loop propagates cancellation from an output checkpoint`() = runTest {
        val copier = DownloadTransferCopier(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            bufferSize = 2,
            checkpointBytes = 2
        )

        copier.copy(
            input = ByteArrayInputStream("abcd".toByteArray()),
            output = ByteArrayOutputStream(),
            startingBytes = 0L,
            onProgress = { throw CancellationException("test cancellation") }
        )
    }

    @Test
    fun `copy loop turns disk full output failure into a storage failure`() = runTest {
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                throw IOException("No space left on device")
            }
        }

        val error = runCatching {
            DownloadTransferCopier(
                ioDispatcher = StandardTestDispatcher(testScheduler),
                bufferSize = 4
            ).copy(
                input = ByteArrayInputStream("abcd".toByteArray()),
                output = output,
                startingBytes = 0L
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(DownloadStorageException::class.java)
        assertThat(isPermanentDownloadStorageFailure(error!!)).isTrue()
    }

    @Test
    fun `permission loss is permanent even when the network is unavailable`() {
        assertThat(isPermanentDownloadStorageFailure(FileNotFoundException("Permission denied"))).isTrue()
        assertThat(isPermanentDownloadStorageFailure(SecurityException("tree revoked"))).isTrue()
        assertThat(
            isPermanentDownloadStorageFailure(
                DownloadStorageException(FileNotFoundException("selected folder revoked"))
            )
        ).isTrue()
    }

    @Test
    fun `resumable transient failures keep output on the same retry schedule`() {
        val machine = DownloadTransferStateMachine(maxRetries = 5)

        val transitions = (0..4).map { retryCount ->
            machine.failure(retryCount, DownloadFailureKind.TRANSIENT, "reset", canResumeOutput = true)
        }

        assertThat(transitions.map { it.status }).containsExactly(*Array(5) { DownloadStatus.PAUSED })
        assertThat(transitions.map { it.resetOutput }).containsExactly(false, false, false, false, false)
        assertThat(transitions.map { it.retryAfterMillis })
            .containsExactly(5_000L, 15_000L, 45_000L, 300_000L, 300_000L).inOrder()
    }

    @Test
    fun `resumable output is still reset when retries are exhausted or the failure is permanent`() {
        val machine = DownloadTransferStateMachine(maxRetries = 5)

        val exhausted = machine.failure(5, DownloadFailureKind.TRANSIENT, "reset", canResumeOutput = true)
        val permanent = machine.failure(0, DownloadFailureKind.PERMANENT, "403", canResumeOutput = true)

        assertThat(exhausted.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(exhausted.resetOutput).isTrue()
        assertThat(permanent.status).isEqualTo(DownloadStatus.FAILED)
        assertThat(permanent.resetOutput).isTrue()
    }
}
