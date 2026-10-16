package com.streamvault.data.sync

import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope

/** Only the consumer owns mapping, duplicate admission and staging state. */
internal suspend fun <T> runXtreamLivePipeline(
    batchRows: Int,
    batchBytes: Long,
    queuedBatches: Int,
    estimatedBytes: (T) -> Long,
    read: suspend (suspend (T) -> Unit) -> Int,
    prepare: suspend () -> Unit = {},
    consume: suspend (List<T>) -> Unit
): XtreamLivePipelineStats = coroutineScope {
    require(batchRows > 0 && batchBytes > 0 && queuedBatches > 0)
    val queue = Channel<BufferedLiveBatch<T>>(queuedBatches)
    val retainedBytes = AtomicLong()
    val highWaterBytes = AtomicLong()
    var sendWaitNanos = 0L
    var batches = 0
    val started = System.nanoTime()
    val writer = async(Dispatchers.Default) {
        try {
            prepare()
            for (batch in queue) {
                consume(batch.items)
                retainedBytes.addAndGet(-batch.bytes)
            }
        } catch (error: CancellationException) {
            // Child cancellation alone does not cancel its parent. Supersession
            // must also interrupt a reader blocked on HTTP or a full queue.
            this@coroutineScope.cancel(error)
            throw error
        }
    }
    try {
        var batch = ArrayList<T>(batchRows)
        var bytes = 0L
        suspend fun sendBatch() {
            if (batch.isEmpty()) return
            val sending = BufferedLiveBatch(batch, bytes)
            val outstanding = retainedBytes.addAndGet(bytes)
            highWaterBytes.updateAndGet { maxOf(it, outstanding) }
            val beforeSend = System.nanoTime()
            queue.send(sending)
            sendWaitNanos += System.nanoTime() - beforeSend
            batches++
            batch = ArrayList(batchRows)
            bytes = 0
        }
        val rows = read { row ->
            val size = estimatedBytes(row).coerceAtLeast(1)
            if (size > batchBytes) throw XtreamLiveBufferLimitException("Live row exceeds buffered batch budget")
            if (batch.isNotEmpty() && bytes + size > batchBytes) sendBatch()
            batch.add(row)
            bytes += size
            if (batch.size >= batchRows || bytes >= batchBytes) sendBatch()
        }
        // read only returns after the transport has validated END_DOCUMENT.
        sendBatch()
        val readerMs = (System.nanoTime() - started) / 1_000_000
        queue.close()
        val drainStarted = System.nanoTime()
        writer.await()
        XtreamLivePipelineStats(rows, batches, readerMs,
            (System.nanoTime() - drainStarted) / 1_000_000,
            sendWaitNanos / 1_000_000, highWaterBytes.get())
    } finally {
        queue.cancel()
        writer.cancel()
        // coroutineScope joins the writer before attempt cleanup/retry outside.
    }
}

internal data class XtreamLivePipelineStats(
    val rows: Int,
    val batches: Int,
    val readerMs: Long,
    val drainMs: Long,
    val sendWaitMs: Long,
    // Includes the writer batch and a producer suspended in send, not just queue.
    val outstandingBatchBytes: Long
)

internal class XtreamLiveBufferLimitException(message: String) : IllegalStateException(message)

private data class BufferedLiveBatch<T>(val items: List<T>, val bytes: Long)

private fun retainedTextBytes(fields: List<String?>, ids: List<String>?): Long =
    256L + fields.sumOf { 40L + 2L * (it?.length ?: 0) } +
        ids.orEmpty().sumOf { 48L + 2L * it.length }

internal fun XtreamLiveStreamRow.estimatedBufferBytes(): Long = retainedTextBytes(
    listOf(name, streamIcon, epgChannelId, categoryId, categoryName, containerExtension), categoryIds)

internal fun XtreamStream.estimatedBufferBytes(): Long = retainedTextBytes(
    listOf(name, streamType, streamIcon, coverBig, epgChannelId, added, categoryId,
        categoryName, customSid, directSource, containerExtension, rating, rating5based,
        tmdb, trailer, youtubeTrailer), categoryIds)
