package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StalkerLivePipelineTest {
    @Test fun fullQueueBackpressuresReaderAndDrainsInOrder() = runTest {
        val enteredWriter = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val thirdSend = CompletableDeferred<Unit>()
        val emitted = AtomicInteger()
        val localWork = AtomicInteger()
        val rows = mutableListOf<String>()
        val task = async {
            runStalkerLivePipeline(1, 16_384L, { if (it) localWork.incrementAndGet() else localWork.decrementAndGet() },
                read = { emit ->
                    repeat(4) { index ->
                        if (index == 2) thirdSend.complete(Unit)
                        emit(StalkerItemRecord(index.toString(), "Channel"))
                        emitted.incrementAndGet()
                    }
                    Result.success(4)
                },
                consume = { batch ->
                    if (batch.first().id == "0") { enteredWriter.complete(Unit); releaseWriter.await() }
                    rows += batch.map { it.id }
                }
            )
        }
        enteredWriter.await()
        thirdSend.await()
        assertThat(emitted.get()).isEqualTo(2)
        releaseWriter.complete(Unit)
        assertThat(task.await()).isInstanceOf(Result.Success::class.java)
        assertThat(rows).containsExactly("0", "1", "2", "3").inOrder()
        assertThat(localWork.get()).isEqualTo(0)
    }

    @Test fun readerErrorJoinsWriterBeforeReturningFailedAttempt() = runTest {
        val enteredWriter = CompletableDeferred<Unit>()
        val stoppedWriter = CompletableDeferred<Unit>()
        val result = runStalkerLivePipeline(1, 16_384L, {},
            read = { emit ->
                emit(StalkerItemRecord("1", "Channel"))
                enteredWriter.await()
                Result.error("expired", StalkerApiError.SessionExpired("expired"))
            },
            consume = {
                enteredWriter.complete(Unit)
                try { awaitCancellation() } finally { stoppedWriter.complete(Unit) }
            }
        )
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(stoppedWriter.isCompleted).isTrue()
    }

    @Test fun cancellationWhileQueueIsFullStopsReaderAndWriter() = runTest {
        val enteredWriter = CompletableDeferred<Unit>()
        val thirdSend = CompletableDeferred<Unit>()
        val stoppedReader = CompletableDeferred<Unit>()
        val stoppedWriter = CompletableDeferred<Unit>()
        val task = async {
            runStalkerLivePipeline(1, 16_384L, {},
                read = { emit ->
                    try {
                        repeat(4) { index ->
                            if (index == 2) thirdSend.complete(Unit)
                            emit(StalkerItemRecord(index.toString(), "Channel"))
                        }
                        Result.success(4)
                    } finally { stoppedReader.complete(Unit) }
                },
                consume = {
                    enteredWriter.complete(Unit)
                    try { awaitCancellation() } finally { stoppedWriter.complete(Unit) }
                }
            )
        }
        enteredWriter.await()
        thirdSend.await()
        task.cancelAndJoin()
        assertThat(stoppedReader.isCompleted).isTrue()
        assertThat(stoppedWriter.isCompleted).isTrue()
    }

    @Test fun writerFailureInterruptsReaderAndRejectsOversizedRows() = runTest {
        supervisorScope {
            val stoppedReader = CompletableDeferred<Unit>()
            val task = async {
                runStalkerLivePipeline(1, 16_384L, {},
                    read = { emit ->
                        try { emit(StalkerItemRecord("1", "Channel")); awaitCancellation() }
                        finally { stoppedReader.complete(Unit) }
                    }, consume = { error("disk failure") }
                )
            }
            assertThat(runCatching { task.await() }.exceptionOrNull()?.message).isEqualTo("disk failure")
            assertThat(stoppedReader.isCompleted).isTrue()
        }
        assertThat(runCatching {
            runStalkerLivePipeline(1, 1024L, {},
                read = { emit -> emit(StalkerItemRecord("1", "x".repeat(2048))); Result.success(1) },
                consume = { error("must not write") }
            )
        }.exceptionOrNull()).isInstanceOf(StalkerApiError.ResponseTooLarge::class.java)
    }
}
