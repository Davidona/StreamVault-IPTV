package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.Result
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** One bounded reader and one owner of identity mapping and staging callbacks per attempt. */
internal suspend fun runStalkerLivePipeline(
    batchRows: Int,
    batchBytes: Long,
    localWork: (Boolean) -> Unit,
    read: suspend (suspend (StalkerItemRecord) -> Unit) -> Result<Int>,
    consume: suspend (List<StalkerItemRecord>) -> Unit
): Result<Int> = coroutineScope {
    require(batchRows > 0 && batchBytes > 0)
    val queue = Channel<List<StalkerItemRecord>>(1)
    val cleaningUp = AtomicBoolean()
    val writer = async(Dispatchers.Default) {
        try {
            for (batch in queue) {
                localWork(true)
                try { consume(batch) } finally { localWork(false) }
            }
        } catch (cancelled: CancellationException) {
            if (!cleaningUp.get()) this@coroutineScope.cancel(cancelled)
            throw cancelled
        }
    }
    try {
        var batch = ArrayList<StalkerItemRecord>(batchRows)
        var bytes = 0L
        suspend fun flush() {
            if (batch.isEmpty()) return
            localWork(true)
            try { queue.send(batch) } finally { localWork(false) }
            batch = ArrayList(batchRows)
            bytes = 0L
        }
        val result = read { row ->
            val size = row.estimatedLiveBufferBytes()
            if (size > batchBytes) throw StalkerApiError.ResponseTooLarge("Live row exceeds the buffered batch budget.")
            if (batch.isNotEmpty() && bytes + size > batchBytes) flush()
            batch += row
            bytes += size
            if (batch.size >= batchRows || bytes >= batchBytes) flush()
        }
        if (result is Result.Success) {
            flush()
            queue.close()
            writer.await()
        }
        result
    } finally {
        cleaningUp.set(true)
        queue.cancel()
        // Retry and staging cleanup must wait for callbacks to stop, including parent cancel.
        withContext(NonCancellable) { writer.cancelAndJoin() }
    }
}

private fun StalkerItemRecord.estimatedLiveBufferBytes(): Long =
    768L + listOf(id, name, categoryId, categoryName, logoUrl, epgChannelId, cmd, streamUrl,
        plot, cast, director, genre, releaseDate, youtubeTrailer, backdropUrl, containerExtension, mcCmd)
        .sumOf { 40L + 2L * (it?.length ?: 0) } +
        commandVariants.sumOf { 128L + 2L * (it.cmd.length + it.sourceKey.length) }
