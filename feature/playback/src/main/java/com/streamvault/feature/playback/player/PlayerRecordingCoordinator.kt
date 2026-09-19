package com.streamvault.feature.playback.player

import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.Result
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Owns recording observation and player-originated recording commands. */
class PlayerRecordingCoordinator @Inject constructor(
    private val recordingManager: RecordingManager,
    private val scheduleRecording: ScheduleRecording
) {
    internal fun observeRecordingItems(): Flow<List<RecordingItem>> =
        recordingManager.observeRecordingItems()

    internal suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> =
        recordingManager.startManualRecording(request)

    internal suspend fun scheduleRecording(command: ScheduleRecordingCommand): Result<RecordingItem> =
        scheduleRecording(command)

    internal suspend fun stopRecording(recordingId: String): Result<Unit> =
        recordingManager.stopRecording(recordingId)
}
