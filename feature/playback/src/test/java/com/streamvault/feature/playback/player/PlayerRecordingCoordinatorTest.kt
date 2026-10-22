package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.domain.usecase.ScheduleRecordingWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerRecordingCoordinatorTest {

    @Test
    fun `scheduleRecording delegates to the use case instead of recursing`() = runTest {
        val manager = FakeRecordingManager()
        val coordinator = PlayerRecordingCoordinator(
            recordingManager = manager,
            scheduleRecording = ScheduleRecording(manager),
            scheduleRecordingWindow = ScheduleRecordingWindow(manager)
        )

        val result = coordinator.scheduleRecording(
            ScheduleRecordingCommand(
                contentType = ContentType.LIVE,
                providerId = 7L,
                channel = Channel(
                    id = 11L,
                    name = "Channel 11",
                    streamUrl = "https://example.com/live.ts",
                    providerId = 7L
                ),
                streamUrl = "https://example.com/live.ts",
                currentProgram = Program(
                    id = 1L,
                    channelId = "11",
                    title = "Current",
                    startTime = 1_000L,
                    endTime = 4_000L,
                    providerId = 7L
                ),
                nextProgram = null,
                recurrence = RecordingRecurrence.DAILY,
                nowMs = 1_500L
            )
        )

        assertThat((result as Result.Success).data.programTitle).isEqualTo("Current")
        assertThat(manager.lastScheduledRequest?.programTitle).isEqualTo("Current")
        assertThat(manager.lastScheduledRequest?.recurrence).isEqualTo(RecordingRecurrence.DAILY)
    }

    private class FakeRecordingManager : RecordingManager {
        var lastScheduledRequest: RecordingRequest? = null

        override fun observeRecordingItems(): Flow<List<RecordingItem>> = flowOf(emptyList())
        override fun observeStorageState(): Flow<RecordingStorageState> = flowOf(RecordingStorageState())
        override suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> =
            error("Not used in test")

        override suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem> {
            lastScheduledRequest = request
            return Result.success(
                RecordingItem(
                    id = "scheduled-1",
                    providerId = request.providerId,
                    channelId = request.channelId,
                    channelName = request.channelName,
                    streamUrl = request.streamUrl,
                    scheduledStartMs = request.scheduledStartMs,
                    scheduledEndMs = request.scheduledEndMs,
                    programTitle = request.programTitle,
                    recurrence = request.recurrence
                )
            )
        }

        override suspend fun stopRecording(recordingId: String): Result<Unit> = error("Not used in test")
        override suspend fun cancelRecording(recordingId: String): Result<Unit> = error("Not used in test")
        override suspend fun deleteRecording(recordingId: String): Result<Unit> = error("Not used in test")
    }
}
