package com.streamvault.domain.manager

import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingReconciliationResult
import com.streamvault.domain.model.RecordingReconciliationSummary
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingSmbStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface RecordingManager {
    fun observeRecordingItems(): Flow<List<RecordingItem>>
    fun observeStorageState(): Flow<RecordingStorageState>
    fun observeActiveRecordingCount(): Flow<Int> = observeRecordingItems().map { items ->
        items.count { it.status == com.streamvault.domain.model.RecordingStatus.RECORDING }
    }

    suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem>
    suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem>
    suspend fun stopRecording(recordingId: String): Result<Unit>
    /** Stops a recording because Android exhausted the foreground-service time allowance. */
    suspend fun stopRecordingForForegroundServiceTimeout(recordingId: String): Result<Unit> =
        stopRecording(recordingId)
    suspend fun cancelRecording(recordingId: String): Result<Unit>
    suspend fun deleteRecording(recordingId: String): Result<Unit>
    suspend fun retryRecording(recordingId: String): Result<Unit> = Result.error("Retry is not supported by this recording manager.")
    suspend fun setScheduleEnabled(recordingId: String, enabled: Boolean): Result<Unit> =
        Result.error("Schedule enablement is not supported by this recording manager.")
    suspend fun updateStorageConfig(config: RecordingStorageConfig): Result<RecordingStorageState> =
        Result.error("Storage configuration is not supported by this recording manager.")
    suspend fun updateSmbStorageConfig(config: RecordingSmbStorageConfig): Result<RecordingStorageState> =
        Result.error("SMB recording storage is not supported by this recording manager.")
    suspend fun reconcileRecordingState(): RecordingReconciliationResult =
        RecordingReconciliationResult.Complete(RecordingReconciliationSummary())
    suspend fun promoteScheduledRecording(recordingId: String): Result<Unit> =
        Result.error("Scheduled promotion is not supported by this recording manager.")
    suspend fun skipOccurrence(recordingId: String): Result<Unit> =
        Result.error("Skip occurrence is not supported by this recording manager.")
    suspend fun forceScheduleRecording(request: RecordingRequest): Result<RecordingItem> =
        Result.error("Force scheduling is not supported by this recording manager.")
    suspend fun getConflictingRecordings(startMs: Long, endMs: Long, providerId: Long): List<RecordingItem> =
        emptyList()
}
