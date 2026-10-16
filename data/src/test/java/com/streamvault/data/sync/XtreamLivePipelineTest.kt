package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class XtreamLivePipelineTest {
    @Test
    fun `provider stress reduces metadata and bulk concurrency`() {
        val policy = XtreamAdaptiveSyncPolicy()
        assertThat(policy.liveRequestConcurrencyFor(1)).isEqualTo(2)
        policy.recordFailure(1, IOException("connection reset"))
        assertThat(policy.liveRequestConcurrencyFor(1)).isEqualTo(1)
        assertThat(policy.liveRequestConcurrencyFor(2)).isEqualTo(2)
        policy.forgetProvider(1)
        assertThat(policy.liveRequestConcurrencyFor(1)).isEqualTo(2)
    }

    @Test
    fun `different providers share one bulk pipeline permit`() = runBlocking {
        withTimeout(5_000) {
            val policy = XtreamAdaptiveSyncPolicy()
            val firstEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondAttempting = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = async { policy.withLivePipelinePermit { firstEntered.complete(Unit); release.await() } }
            firstEntered.await()
            val second = async {
                secondAttempting.complete(Unit)
                policy.withLivePipelinePermit { secondEntered.complete(Unit) }
            }
            secondAttempting.await()
            assertThat(secondEntered.isCompleted).isFalse()
            release.complete(Unit)
            first.await(); second.await()
            assertThat(secondEntered.isCompleted).isTrue()
        }
    }

    @Test
    fun `reader overlaps writer but cannot run beyond queue capacity`() = runBlocking {
        withTimeout(5_000) {
            val writing = CompletableDeferred<Unit>()
            val thirdRead = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val emitted = AtomicInteger()
            val finished = AtomicInteger()
            val result = async {
                runXtreamLivePipeline(1, 10, 1, { 10L }, read = { emit ->
                    repeat(6) { index ->
                        emitted.incrementAndGet()
                        if (index == 2) thirdRead.complete(Unit)
                        emit(index)
                        finished.incrementAndGet()
                    }; 6
                }, consume = { writing.complete(Unit); release.await() })
            }
            writing.await()
            thirdRead.await()
            assertThat(emitted.get()).isAtMost(4) // writer + queue + suspended send + next row
            assertThat(finished.get()).isAtMost(2)
            release.complete(Unit)
            val stats = result.await()
            assertThat(stats.rows).isEqualTo(6)
            assertThat(stats.outstandingBatchBytes).isAtMost(30)
        }
    }

    @Test
    fun `metadata wait fills bounded queue and parent cancellation stops reader`() = runBlocking {
        withTimeout(5_000) {
            val thirdRead = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val context = CompletableDeferred<Unit>()
            val finished = AtomicInteger()
            val result = async {
                runXtreamLivePipeline(1, 10, 1, { 10L }, prepare = { context.await() }, read = { emit ->
                    try {
                        repeat(10) { index ->
                            if (index == 2) thirdRead.complete(Unit)
                            emit(index); finished.incrementAndGet()
                        }; 10
                    } finally { stopped.complete(Unit) }
                }, consume = { error("mapping before metadata") })
            }
            // With preparation blocked, even the second send must wait.
            while (finished.get() == 0) kotlinx.coroutines.yield()
            assertThat(finished.get()).isEqualTo(1)
            assertThat(thirdRead.isCompleted).isFalse()
            result.cancelAndJoin()
            stopped.await()
        }
    }

    @Test
    fun `completion waits for final batch writer drain`() = runBlocking {
        withTimeout(5_000) {
            val eof = CompletableDeferred<Unit>()
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val result = async {
                runXtreamLivePipeline(10, 100, 1, { 1L }, read = { emit ->
                    emit(1); eof.complete(Unit); 1
                }, consume = { writing.complete(Unit); release.await() })
            }
            eof.await(); writing.await()
            assertThat(result.isCompleted).isFalse()
            release.complete(Unit)
            assertThat(result.await().rows).isEqualTo(1)
        }
    }

    @Test
    fun `reader failure joins cancelled writer before returning for retry`() = runBlocking {
        withTimeout(5_000) {
            val writing = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val result = async {
                runCatching {
                    runXtreamLivePipeline(1, 100, 1, { 1L }, read = { emit ->
                        emit(1); writing.await(); throw IOException("truncated")
                    }, consume = {
                        writing.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { cancelling.complete(Unit); releaseCleanup.await() }
                        }
                    })
                }
            }
            cancelling.await()
            assertThat(result.isCompleted).isFalse()
            releaseCleanup.complete(Unit)
            assertThat(result.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `superseded writer cancels reader even when reader is waiting on transport`() = runBlocking {
        withTimeout(5_000) {
            val reading = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val failure = runCatching {
                runXtreamLivePipeline(1, 100, 1, { 1L }, read = { emit ->
                    try { emit(1); reading.complete(Unit); awaitCancellation() }
                    finally { stopped.complete(Unit) }
                }, consume = { reading.await(); throw ProviderWorkflowSupersededException(1, 1) })
            }.exceptionOrNull()
            stopped.await()
            assertThat(failure).isInstanceOf(ProviderWorkflowSupersededException::class.java)
        }
    }

    @Test
    fun `wide rows flush by bytes and preserve order`() = runBlocking {
        val batches = mutableListOf<List<Int>>()
        val stats = runXtreamLivePipeline(100, 100, 2, { row: Int -> row.toLong() }, read = { emit ->
            listOf(30, 60, 70, 20, 10).forEach { emit(it) }; 5
        }, consume = { batches.add(it) })
        assertThat(batches).containsExactly(listOf(30, 60), listOf(70, 20, 10)).inOrder()
        assertThat(stats.batches).isEqualTo(2)
        assertThat(stats.outstandingBatchBytes).isAtMost(400)
    }

    @Test
    fun `oversized row is rejected without silent trimming`() = runBlocking {
        val failure = runCatching {
            runXtreamLivePipeline(100, 100, 1, { row: Int -> row.toLong() },
                read = { emit -> emit(101); 1 }, consume = { error("oversized write") })
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(XtreamLiveBufferLimitException::class.java)
    }

    @Test
    fun `ordinary writer failure stops producer`() = runBlocking {
        withTimeout(5_000) {
            val stopped = CompletableDeferred<Unit>()
            val failure = runCatching {
                runXtreamLivePipeline(1, 100, 1, { 1L }, read = { emit ->
                    try { emit(1); awaitCancellation() } finally { stopped.complete(Unit) }
                }, consume = { throw IllegalStateException("write failed") })
            }.exceptionOrNull()
            stopped.await()
            assertThat(failure).hasMessageThat().isEqualTo("write failed")
        }
    }
}
