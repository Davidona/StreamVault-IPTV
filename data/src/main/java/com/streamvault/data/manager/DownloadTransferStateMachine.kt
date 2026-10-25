package com.streamvault.data.manager

import com.streamvault.domain.model.DownloadStatus
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Pure lifecycle decisions for a download transfer.
 *
 * Keeping these transitions independent from Room, OkHttp, SAF, and Android services makes
 * process-death, retry, cancellation, and quota behavior deterministic to test. The manager
 * remains responsible for applying the returned transition to durable state.
 */
internal class DownloadTransferStateMachine(
    private val maxRetries: Int = 5,
    private val retryDelayMillis: (retryCount: Int) -> Long = ::defaultDownloadRetryDelayMillis
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
    }

    /**
     * [canResumeOutput] reports that the partial output matches its checkpoint and the server
     * accepts byte ranges, so a scheduled retry can continue it instead of starting over.
     */
    fun failure(
        currentRetryCount: Int,
        kind: DownloadFailureKind,
        reason: String,
        canResumeOutput: Boolean = false
    ): DownloadFailureTransition {
        require(currentRetryCount >= 0) { "currentRetryCount must be non-negative" }
        if (kind == DownloadFailureKind.PERMANENT || currentRetryCount >= maxRetries) {
            return DownloadFailureTransition(
                status = DownloadStatus.FAILED,
                retryCount = 0,
                resetOutput = true,
                retryAfterMillis = null,
                reason = reason
            )
        }

        val nextRetryCount = currentRetryCount + 1
        return DownloadFailureTransition(
            status = DownloadStatus.PAUSED,
            retryCount = nextRetryCount,
            resetOutput = !canResumeOutput,
            retryAfterMillis = retryDelayMillis(nextRetryCount),
            reason = reason
        )
    }

    fun cancellation(kind: DownloadCancellationKind): DownloadCancellationTransition = when (kind) {
        DownloadCancellationKind.USER -> DownloadCancellationTransition(
            status = DownloadStatus.CANCELLED,
            resetOutput = false,
            reason = null
        )
        DownloadCancellationKind.PLAYBACK_STARTED -> DownloadCancellationTransition(
            status = DownloadStatus.PAUSED,
            resetOutput = true,
            reason = "Waiting for playback to stop"
        )
        DownloadCancellationKind.FOREGROUND_SERVICE_TIMEOUT -> DownloadCancellationTransition(
            status = DownloadStatus.PAUSED,
            resetOutput = false,
            reason = "Download paused because Android exhausted the foreground-service time allowance."
        )
    }
}

internal enum class DownloadFailureKind {
    TRANSIENT,
    PERMANENT
}

internal enum class DownloadCancellationKind {
    USER,
    PLAYBACK_STARTED,
    FOREGROUND_SERVICE_TIMEOUT
}

internal data class DownloadFailureTransition(
    val status: DownloadStatus,
    val retryCount: Int,
    val resetOutput: Boolean,
    val retryAfterMillis: Long?,
    val reason: String
)

internal data class DownloadCancellationTransition(
    val status: DownloadStatus,
    val resetOutput: Boolean,
    val reason: String?
)

internal fun defaultDownloadRetryDelayMillis(retryCount: Int): Long = when (retryCount) {
    1 -> 5_000L
    2 -> 15_000L
    3 -> 45_000L
    else -> 300_000L
}

/**
 * Cancellable, dispatcher-injected copy loop shared by the production transfer path and
 * deterministic tests. The callback is invoked for every read, and identifies durable
 * progress checkpoints separately from in-memory progress.
 */
internal class DownloadTransferCopier(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bufferSize: Int = 64 * 1024,
    private val checkpointBytes: Long = 512 * 1024
) {
    init {
        require(bufferSize > 0) { "bufferSize must be positive" }
        require(checkpointBytes > 0L) { "checkpointBytes must be positive" }
    }

    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        startingBytes: Long,
        onProgress: suspend (DownloadCopyProgress) -> Unit = {}
    ): Long = withContext(ioDispatcher) {
        require(startingBytes >= 0L) { "startingBytes must be non-negative" }
        var bytesWritten = startingBytes
        var lastCheckpoint = startingBytes
        val buffer = ByteArray(bufferSize)

        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read == -1) break

            try {
                output.write(buffer, 0, read)
            } catch (error: IOException) {
                throw DownloadStorageException(error)
            } catch (error: SecurityException) {
                throw DownloadStorageException(error)
            }
            bytesWritten += read.toLong()
            val shouldCheckpoint = bytesWritten - lastCheckpoint >= checkpointBytes
            if (shouldCheckpoint) lastCheckpoint = bytesWritten
            onProgress(DownloadCopyProgress(bytesWritten, shouldCheckpoint))
        }

        bytesWritten
    }
}

internal data class DownloadCopyProgress(
    val bytesWritten: Long,
    val shouldCheckpoint: Boolean
)

/** A local output/SAF failure must not be retried as if it were a network failure. */
internal class DownloadStorageException(cause: Throwable) : IOException(
    "Download storage operation failed",
    cause
)

internal fun isPermanentDownloadStorageFailure(error: Throwable): Boolean =
    error is DownloadStorageException ||
        error is FileNotFoundException ||
        error is SecurityException
