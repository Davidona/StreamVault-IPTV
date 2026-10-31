package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class XtreamLivePlaybackYieldTest {
    @Test fun `manual idle and opt out remain eligible to continue`() {
        for (automatic in listOf(false, true)) for (defer in listOf(false, true)) for (active in listOf(false, true)) {
            assertThat(shouldYieldXtreamLiveBatch(automatic, defer, active)).isEqualTo(automatic && defer && active)
        }
        assertThat(CancellationException("external cancellation").isXtreamLivePlaybackDeferral()).isFalse()
        assertThat(IOException("source failure").isXtreamLivePlaybackDeferral()).isFalse()
    }

    @Test fun `pipeline drains children and preserves deferral identity without cancelling caller`() = runTest {
        var readerClosed = false
        var writes = 0
        val failure = runCatching {
            runXtreamLivePipeline<Int>(batchRows = 1, batchBytes = 100, queuedBatches = 1,
                estimatedBytes = { 1 }, prepare = {}, read = { emit ->
                    try { repeat(100) { emit(it) }; 100 } finally { readerClosed = true }
                }, consume = {
                    if (++writes == 2) throw XtreamLivePlaybackDeferredException()
                })
        }.exceptionOrNull()!!
        assertThat(failure.isXtreamLivePlaybackDeferral()).isTrue()
        assertThat(readerClosed).isTrue()
        assertThat(writes).isEqualTo(2)
        // A following child can run: cancellation was confined to the pipeline's scope.
        assertThat(async { 42 }.await()).isEqualTo(42)
    }
}
