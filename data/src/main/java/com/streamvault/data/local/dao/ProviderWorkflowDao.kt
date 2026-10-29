package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.streamvault.data.local.entity.ProviderWorkflowEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowPhaseEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhaseState
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.ProviderWorkflowState
import kotlinx.coroutines.flow.Flow

data class ProviderWorkflowTicket(
    val providerId: Long,
    val generation: Long,
    val phase: ProviderWorkflowPhase,
    val admitted: Boolean = true
)

data class ProviderWorkflowLease(
    val providerId: Long,
    val generation: Long,
    val phase: ProviderWorkflowPhase,
    val token: String,
    val expiresAt: Long
)

/**
 * Persistent ownership boundary for provider work.
 *
 * All state-changing operations include both the generation and lease token. This is deliberate:
 * process-local cancellation cannot stop a worker that is already inside provider/network code,
 * while the database fencing check can stop that worker from committing stale state.
 */
@Dao
abstract class ProviderWorkflowDao {
    @Query("SELECT * FROM provider_workflows WHERE provider_id = :providerId")
    abstract suspend fun getWorkflow(providerId: Long): ProviderWorkflowEntity?

    @Query("SELECT * FROM provider_workflows WHERE provider_id = :providerId")
    abstract fun observeWorkflow(providerId: Long): Flow<ProviderWorkflowEntity?>

    @Query(
        """
        SELECT phase FROM provider_workflow_phases
        WHERE provider_id = :providerId
          AND generation = (
              SELECT generation FROM provider_workflows WHERE provider_id = :providerId
          )
          AND state IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE')
        """
    )
    abstract fun observeActivePhases(providerId: Long): Flow<List<ProviderWorkflowPhase>>

    @Query(
        """
        SELECT * FROM provider_workflow_phases
        WHERE provider_id = :providerId AND generation = :generation
        ORDER BY phase
        """
    )
    abstract suspend fun getPhases(
        providerId: Long,
        generation: Long
    ): List<ProviderWorkflowPhaseEntity>

    @Query(
        """
        SELECT checkpoint FROM provider_workflow_phases
        WHERE provider_id = :providerId AND generation = :generation AND phase = :phase
        """
    )
    abstract suspend fun getCheckpoint(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase
    ): String?

    @Query(
        """
        UPDATE provider_workflow_phases
        SET checkpoint = :checkpoint, updated_at = :now
        WHERE provider_id = :providerId
          AND generation = :generation
          AND phase = :phase
          AND state = 'RUNNING'
          AND EXISTS (
              SELECT 1 FROM provider_workflows workflow
              WHERE workflow.provider_id = :providerId
                AND workflow.generation = :generation
                AND workflow.current_phase = :phase
                AND workflow.lease_token = :token
          )
        """
    )
    abstract suspend fun updateRunningCheckpoint(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        token: String,
        checkpoint: String?,
        now: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertWorkflow(workflow: ProviderWorkflowEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPhase(phase: ProviderWorkflowPhaseEntity): Long

    @Query(
        """
        UPDATE provider_workflows
        SET generation = generation + 1,
            state = 'PENDING',
            reason = :reason,
            priority = :priority,
            force = :force,
            current_phase = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            heartbeat_at = NULL,
            progress_message = NULL,
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = :now,
            completed_at = NULL
        WHERE provider_id = :providerId
          AND (
              :priority >= priority
              OR state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
          )
        """
    )
    protected abstract suspend fun supersedeWorkflow(
        providerId: Long,
        reason: ProviderWorkflowReason,
        priority: Int,
        force: Boolean,
        now: Long
    ): Int

    @Query(
        """
        UPDATE provider_workflow_phases
        SET state = 'SUPERSEDED', updated_at = :now, completed_at = :now
        WHERE provider_id = :providerId
          AND generation < :generation
          AND state NOT IN ('SUCCEEDED', 'PARTIAL', 'FAILED_PERMANENT', 'SUPERSEDED')
        """
    )
    protected abstract suspend fun supersedeOlderPhases(
        providerId: Long,
        generation: Long,
        now: Long
    )

    @Query(
        """
        UPDATE provider_workflows
        SET state = CASE
                WHEN state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN 'PENDING'
                ELSE state
            END,
            reason = :reason,
            priority = CASE
                WHEN state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN :priority
                ELSE MAX(priority, :priority)
            END,
            force = CASE
                WHEN state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN :force
                WHEN force = 1 OR :force = 1 THEN 1
                ELSE 0
            END,
            updated_at = :now,
            completed_at = CASE
                WHEN state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN NULL
                ELSE completed_at
            END
        WHERE provider_id = :providerId
          AND (
              :priority >= priority
              OR state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
          )
        """
    )
    protected abstract suspend fun joinWorkflow(
        providerId: Long,
        reason: ProviderWorkflowReason,
        priority: Int,
        force: Boolean,
        now: Long
    ): Int

    @Query(
        """
        UPDATE provider_workflow_phases
        SET state = 'PENDING',
            checkpoint = CASE WHEN :force = 1 THEN NULL ELSE checkpoint END,
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = :now,
            completed_at = NULL
        WHERE provider_id = :providerId
          AND generation = :generation
          AND phase = :phase
          AND state != 'RUNNING'
        """
    )
    protected abstract suspend fun requeuePhase(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        force: Boolean,
        now: Long
    ): Int

    @Query(
        """
        UPDATE provider_workflow_phases
        SET state = 'FAILED_RETRYABLE',
            last_error_code = 'LEASE_RECOVERY',
            last_error_message = 'The prior worker lease expired or stopped heartbeating.',
            updated_at = :now,
            completed_at = NULL
        WHERE provider_id = :providerId
          AND generation = :generation
          AND phase = :phase
          AND state = 'RUNNING'
          AND EXISTS (
              SELECT 1 FROM provider_workflows workflow
              WHERE workflow.provider_id = :providerId
                AND workflow.generation = :generation
                AND workflow.current_phase = :phase
                AND (
                    workflow.lease_token IS NULL
                    OR workflow.lease_expires_at IS NULL
                    OR workflow.lease_expires_at <= :now
                    OR workflow.heartbeat_at IS NULL
                    OR workflow.heartbeat_at <= :staleHeartbeatBefore
                    OR workflow.heartbeat_at > :now
                )
          )
        """
    )
    protected abstract suspend fun recoverStaleRunningPhase(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        now: Long,
        staleHeartbeatBefore: Long
    ): Int

    @Transaction
    open suspend fun request(
        providerId: Long,
        phase: ProviderWorkflowPhase,
        reason: ProviderWorkflowReason,
        now: Long,
        supersede: Boolean = false,
        priority: Int = 0,
        force: Boolean = false,
        staleHeartbeatBefore: Long = now - DEFAULT_STALE_HEARTBEAT_MILLIS
    ): ProviderWorkflowTicket {
        val inserted = insertWorkflow(
            ProviderWorkflowEntity(
                providerId = providerId,
                generation = 1L,
                state = ProviderWorkflowState.PENDING,
                reason = reason,
                priority = priority,
                force = force,
                createdAt = now,
                updatedAt = now
            )
        )

        var admitted = true
        if (inserted == -1L) {
            if (supersede) {
                admitted = supersedeWorkflow(providerId, reason, priority, force, now) == 1
            } else {
                admitted = joinWorkflow(providerId, reason, priority, force, now) == 1
            }
        }

        val workflow = checkNotNull(getWorkflow(providerId))
        if (!admitted) {
            return ProviderWorkflowTicket(
                providerId = providerId,
                generation = workflow.generation,
                phase = phase,
                admitted = false
            )
        }
        if (supersede && inserted == -1L) {
            supersedeOlderPhases(providerId, workflow.generation, now)
        }
        val phaseEntity = ProviderWorkflowPhaseEntity(
            providerId = providerId,
            generation = workflow.generation,
            phase = phase,
            state = ProviderWorkflowPhaseState.PENDING,
            createdAt = now,
            updatedAt = now
        )
        if (insertPhase(phaseEntity) == -1L) {
            if (requeuePhase(providerId, workflow.generation, phase, force, now) == 0) {
                recoverStaleRunningPhase(
                    providerId,
                    workflow.generation,
                    phase,
                    now,
                    staleHeartbeatBefore
                )
            }
        }
        return ProviderWorkflowTicket(providerId, workflow.generation, phase)
    }

    @Query(
        """
        UPDATE provider_workflows
        SET state = 'RUNNING',
            current_phase = :phase,
            lease_token = :token,
            lease_expires_at = :expiresAt,
            heartbeat_at = :now,
            progress_message = NULL,
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = :now,
            completed_at = NULL
        WHERE provider_id = :providerId
          AND generation = :generation
          AND EXISTS (
              SELECT 1 FROM provider_workflow_phases phase_row
              WHERE phase_row.provider_id = :providerId
                AND phase_row.generation = :generation
                AND phase_row.phase = :phase
                AND phase_row.state IN ('PENDING', 'FAILED_RETRYABLE')
          )
          AND (
              lease_token IS NULL
              OR lease_expires_at IS NULL
              OR lease_expires_at <= :now
              OR heartbeat_at IS NULL
              OR heartbeat_at <= :staleHeartbeatBefore
              OR heartbeat_at > :now
          )
        """
    )
    protected abstract suspend fun claimWorkflow(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        token: String,
        now: Long,
        expiresAt: Long,
        staleHeartbeatBefore: Long
    ): Int

    @Query(
        """
        UPDATE provider_workflow_phases
        SET state = 'RUNNING',
            attempt_count = attempt_count + 1,
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = :now,
            completed_at = NULL
        WHERE provider_id = :providerId
          AND generation = :generation
          AND phase = :phase
          AND state IN ('PENDING', 'FAILED_RETRYABLE')
        """
    )
    protected abstract suspend fun markPhaseRunning(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        now: Long
    ): Int

    @Transaction
    open suspend fun claim(
        ticket: ProviderWorkflowTicket,
        token: String,
        now: Long,
        leaseDurationMs: Long,
        staleHeartbeatBefore: Long
    ): ProviderWorkflowLease? {
        require(leaseDurationMs > 0L)
        if (!ticket.admitted) return null
        val expiresAt = now + leaseDurationMs
        if (
            claimWorkflow(
                providerId = ticket.providerId,
                generation = ticket.generation,
                phase = ticket.phase,
                token = token,
                now = now,
                expiresAt = expiresAt,
                staleHeartbeatBefore = staleHeartbeatBefore
            ) != 1
        ) {
            return null
        }
        check(
            markPhaseRunning(
                ticket.providerId,
                ticket.generation,
                ticket.phase,
                now
            ) == 1
        )
        return ProviderWorkflowLease(
            providerId = ticket.providerId,
            generation = ticket.generation,
            phase = ticket.phase,
            token = token,
            expiresAt = expiresAt
        )
    }

    @Query(
        """
        UPDATE provider_workflows
        SET heartbeat_at = :now,
            lease_expires_at = :expiresAt,
            progress_message = :progressMessage,
            updated_at = :now
        WHERE provider_id = :providerId
          AND generation = :generation
          AND state = 'RUNNING'
          AND current_phase = :phase
          AND lease_token = :token
        """
    )
    abstract suspend fun renewLease(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        token: String,
        now: Long,
        expiresAt: Long,
        progressMessage: String? = null
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM provider_workflows
        WHERE provider_id = :providerId
          AND generation = :generation
          AND state = 'RUNNING'
          AND current_phase = :phase
          AND lease_token = :token
        """
    )
    protected abstract suspend fun ownsLease(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        token: String
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM provider_workflows
        WHERE provider_id = :providerId
          AND generation = :generation
          AND state = 'RUNNING'
          AND current_phase = :phase
          AND lease_token = :token
        """
    )
    abstract suspend fun isCurrentOwner(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        token: String
    ): Int

    @Query(
        """
        UPDATE provider_workflow_phases
        SET state = :state,
            checkpoint = :checkpoint,
            last_error_code = :errorCode,
            last_error_message = :errorMessage,
            updated_at = :now,
            completed_at = CASE
                WHEN :state IN ('SUCCEEDED', 'PARTIAL', 'FAILED_PERMANENT') THEN :now
                ELSE NULL
            END
        WHERE provider_id = :providerId
          AND generation = :generation
          AND phase = :phase
          AND state = 'RUNNING'
        """
    )
    protected abstract suspend fun finishPhase(
        providerId: Long,
        generation: Long,
        phase: ProviderWorkflowPhase,
        state: ProviderWorkflowPhaseState,
        checkpoint: String?,
        errorCode: String?,
        errorMessage: String?,
        now: Long
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM provider_workflow_phases
        WHERE provider_id = :providerId
          AND generation = :generation
          AND state IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE')
        """
    )
    protected abstract suspend fun unfinishedPhaseCount(
        providerId: Long,
        generation: Long
    ): Int

    @Query(
        """
        UPDATE provider_workflows
        SET state = :state,
            current_phase = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            heartbeat_at = NULL,
            progress_message = NULL,
            last_error_code = :errorCode,
            last_error_message = :errorMessage,
            updated_at = :now,
            completed_at = CASE
                WHEN :state IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED') THEN :now
                ELSE NULL
            END
        WHERE provider_id = :providerId
          AND generation = :generation
          AND lease_token = :token
        """
    )
    protected abstract suspend fun finishWorkflowOwnership(
        providerId: Long,
        generation: Long,
        token: String,
        state: ProviderWorkflowState,
        errorCode: String?,
        errorMessage: String?,
        now: Long
    ): Int

    @Transaction
    open suspend fun complete(
        lease: ProviderWorkflowLease,
        now: Long,
        checkpoint: String? = null,
        partial: Boolean = false
    ): Boolean {
        if (ownsLease(lease.providerId, lease.generation, lease.phase, lease.token) != 1) {
            return false
        }
        val phaseState = if (partial) {
            ProviderWorkflowPhaseState.PARTIAL
        } else {
            ProviderWorkflowPhaseState.SUCCEEDED
        }
        check(
            finishPhase(
                lease.providerId,
                lease.generation,
                lease.phase,
                phaseState,
                checkpoint,
                null,
                null,
                now
            ) == 1
        )
        val hasMoreWork = unfinishedPhaseCount(lease.providerId, lease.generation) > 0
        val workflowState = when {
            hasMoreWork -> ProviderWorkflowState.PENDING
            partial -> ProviderWorkflowState.PARTIAL
            else -> ProviderWorkflowState.SUCCEEDED
        }
        return finishWorkflowOwnership(
            lease.providerId,
            lease.generation,
            lease.token,
            workflowState,
            null,
            null,
            now
        ) == 1
    }

    @Transaction
    open suspend fun defer(lease: ProviderWorkflowLease, now: Long): Boolean {
        if (ownsLease(lease.providerId, lease.generation, lease.phase, lease.token) != 1) return false
        check(finishPhase(
            lease.providerId, lease.generation, lease.phase, ProviderWorkflowPhaseState.PENDING,
            getCheckpoint(lease.providerId, lease.generation, lease.phase), null, null, now
        ) == 1)
        return finishWorkflowOwnership(
            lease.providerId, lease.generation, lease.token, ProviderWorkflowState.PENDING, null, null, now
        ) == 1
    }

    @Transaction
    open suspend fun fail(
        lease: ProviderWorkflowLease,
        now: Long,
        errorCode: String,
        errorMessage: String?,
        retryable: Boolean,
        checkpoint: String? = null
    ): Boolean {
        if (ownsLease(lease.providerId, lease.generation, lease.phase, lease.token) != 1) {
            return false
        }
        val phaseState = if (retryable) {
            ProviderWorkflowPhaseState.FAILED_RETRYABLE
        } else {
            ProviderWorkflowPhaseState.FAILED_PERMANENT
        }
        // A retryable failure represents an interrupted attempt, not abandoned work. Preserve
        // the last atomically recorded page checkpoint when the caller has no newer one.
        val retainedCheckpoint = if (retryable) {
            checkpoint ?: getCheckpoint(lease.providerId, lease.generation, lease.phase)
        } else {
            checkpoint
        }
        check(
            finishPhase(
                lease.providerId,
                lease.generation,
                lease.phase,
                phaseState,
                retainedCheckpoint,
                errorCode,
                errorMessage,
                now
            ) == 1
        )
        return finishWorkflowOwnership(
            lease.providerId,
            lease.generation,
            lease.token,
            if (retryable) ProviderWorkflowState.PENDING else ProviderWorkflowState.FAILED,
            errorCode,
            errorMessage,
            now
        ) == 1
    }

    @Query(
        """
        SELECT * FROM provider_workflows
        WHERE state = 'RUNNING'
          AND (
              lease_token IS NULL
              OR lease_expires_at IS NULL
              OR lease_expires_at <= :now
              OR heartbeat_at IS NULL
              OR heartbeat_at <= :staleHeartbeatBefore
              OR heartbeat_at > :now
          )
        ORDER BY updated_at
        """
    )
    abstract suspend fun getRecoveryCandidates(
        now: Long,
        staleHeartbeatBefore: Long
    ): List<ProviderWorkflowEntity>

    private companion object {
        const val DEFAULT_STALE_HEARTBEAT_MILLIS = 2 * 60_000L
    }
}
