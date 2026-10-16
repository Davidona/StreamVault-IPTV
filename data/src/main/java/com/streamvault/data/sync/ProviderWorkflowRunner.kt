package com.streamvault.data.sync

import android.database.sqlite.SQLiteException
import com.streamvault.data.local.dao.ProviderWorkflowDao
import com.streamvault.data.local.dao.ProviderWorkflowLease
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinItemLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException
import com.streamvault.data.remote.jellyfin.JellyfinResponseTooLargeException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ProviderWorkflowOutcome {
    data class Success(
        val checkpoint: String? = null,
        val partial: Boolean = false
    ) : ProviderWorkflowOutcome

    data class Failure(
        val code: String,
        val message: String?,
        val cause: Throwable? = null,
        val retryable: Boolean = ProviderWorkFailureClassifier.isRetryable(cause)
    ) : ProviderWorkflowOutcome
}

enum class ProviderWorkflowDisposition {
    SUCCEEDED,
    RETRY,
    FAILED,
    BUSY,
    SUPERSEDED
}

object ProviderWorkFailureClassifier {
    fun isRetryable(error: Throwable?): Boolean = when (error) {
        is JellyfinPaginationException,
        is JellyfinCatalogLimitException,
        is JellyfinResponseTooLargeException,
        is JellyfinItemLimitException -> false
        is IOException -> true
        is SQLiteException -> error.message.orEmpty().contains("locked", ignoreCase = true) ||
            error.message.orEmpty().contains("busy", ignoreCase = true)
        else -> false
    }
}

/**
 * Executes one provider phase while maintaining its persisted lease.
 *
 * The Room generation/token checks are the authority. WorkManager uniqueness and the in-process
 * mutex remain useful admission controls, but neither is relied on to prove ownership.
 */
@Singleton
class ProviderWorkflowRunner @Inject constructor(
    private val workflowDao: ProviderWorkflowDao
) {
    suspend fun execute(
        providerId: Long,
        phase: ProviderWorkflowPhase,
        reason: ProviderWorkflowReason,
        force: Boolean = false,
        supersede: Boolean = false,
        priority: Int = 0,
        block: suspend () -> ProviderWorkflowOutcome
    ): ProviderWorkflowDisposition {
        val requestedAt = System.currentTimeMillis()
        val ticket = workflowDao.request(
            providerId = providerId,
            phase = phase,
            reason = reason,
            now = requestedAt,
            supersede = supersede,
            priority = priority,
            force = force
        )
        val lease = workflowDao.claim(
            ticket = ticket,
            token = UUID.randomUUID().toString(),
            now = requestedAt,
            leaseDurationMs = LEASE_DURATION_MILLIS,
            staleHeartbeatBefore = requestedAt - STALE_HEARTBEAT_MILLIS
        ) ?: return ProviderWorkflowDisposition.BUSY

        return try {
            val outcome = withContext(ProviderWorkflowExecutionContext(lease)) {
                runWithHeartbeat(lease, block)
            }
            publishOutcome(lease, outcome)
        } catch (lost: ProviderWorkflowLeaseLostException) {
            ProviderWorkflowDisposition.SUPERSEDED
        } catch (superseded: ProviderWorkflowSupersededException) {
            ProviderWorkflowDisposition.SUPERSEDED
        } catch (cancelled: CancellationException) {
            // Cancellation is owner control flow, not a provider failure. Leave the durable
            // lease running so the normal stale-lease recovery path can reclaim it without
            // publishing a retry/error/status update after the worker has stopped.
            throw cancelled
        } catch (error: Exception) {
            publishOutcome(
                lease,
                ProviderWorkflowOutcome.Failure(
                    code = error::class.java.simpleName.ifBlank { "UNEXPECTED" },
                    message = error.message,
                    cause = error
                )
            )
        }
    }

    private suspend fun runWithHeartbeat(
        lease: ProviderWorkflowLease,
        block: suspend () -> ProviderWorkflowOutcome
    ): ProviderWorkflowOutcome = coroutineScope {
        val heartbeat = launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                val now = System.currentTimeMillis()
                if (
                    workflowDao.renewLease(
                        providerId = lease.providerId,
                        generation = lease.generation,
                        phase = lease.phase,
                        token = lease.token,
                        now = now,
                        expiresAt = now + LEASE_DURATION_MILLIS
                    ) != 1
                ) {
                    throw ProviderWorkflowLeaseLostException()
                }
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private suspend fun publishOutcome(
        lease: ProviderWorkflowLease,
        outcome: ProviderWorkflowOutcome
    ): ProviderWorkflowDisposition {
        val now = System.currentTimeMillis()
        return when (outcome) {
            is ProviderWorkflowOutcome.Success -> {
                if (
                    workflowDao.complete(
                        lease = lease,
                        now = now,
                        checkpoint = outcome.checkpoint,
                        partial = outcome.partial
                    )
                ) {
                    ProviderWorkflowDisposition.SUCCEEDED
                } else {
                    ProviderWorkflowDisposition.SUPERSEDED
                }
            }

            is ProviderWorkflowOutcome.Failure -> {
                val published = workflowDao.fail(
                    lease = lease,
                    now = now,
                    errorCode = outcome.code,
                    errorMessage = outcome.message,
                    retryable = outcome.retryable
                )
                when {
                    !published -> ProviderWorkflowDisposition.SUPERSEDED
                    outcome.retryable -> ProviderWorkflowDisposition.RETRY
                    else -> ProviderWorkflowDisposition.FAILED
                }
            }
        }
    }

    private class ProviderWorkflowLeaseLostException : IllegalStateException(
        "Provider workflow lease was superseded or reclaimed."
    )

    private companion object {
        const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val LEASE_DURATION_MILLIS = 2 * 60_000L
        const val STALE_HEARTBEAT_MILLIS = 2 * 60_000L
    }
}
