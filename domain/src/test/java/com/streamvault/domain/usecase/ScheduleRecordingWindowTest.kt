package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScheduleRecordingWindowTest {

    @Test
    fun schedules_explicit_window_without_guide_program() = runTest {
        val manager = FakeRecordingManager()
        val useCase = ScheduleRecordingWindow(manager)

        val result = useCase(
            ScheduleRecordingWindowCommand(
                contentType = ContentType.LIVE,
                providerId = 7L,
                channel = channel(),
                streamUrl = "https://example.com/live.ts",
                startMs = 10_000L,
                endMs = 70_000L,
                recurrence = RecordingRecurrence.DAILY,
                title = "Channel 11",
                nowMs = 5_000L
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(manager.lastScheduledRequest?.scheduledStartMs).isEqualTo(10_000L)
        assertThat(manager.lastScheduledRequest?.scheduledEndMs).isEqualTo(70_000L)
        assertThat(manager.lastScheduledRequest?.recurrence).isEqualTo(RecordingRecurrence.DAILY)
        assertThat(manager.lastScheduledRequest?.programTitle).isEqualTo("Channel 11")
    }

    @Test
    fun clamps_start_to_now_when_window_is_in_the_past() = runTest {
        val manager = FakeRecordingManager()
        val useCase = ScheduleRecordingWindow(manager)

        useCase(
            ScheduleRecordingWindowCommand(
                contentType = ContentType.LIVE,
                providerId = 7L,
                channel = channel(),
                streamUrl = "https://example.com/live.ts",
                startMs = 1_000L,
                endMs = 9_000L,
                recurrence = RecordingRecurrence.NONE,
                nowMs = 5_000L
            )
        )

        assertThat(manager.lastScheduledRequest?.scheduledStartMs).isEqualTo(5_000L)
        assertThat(manager.lastScheduledRequest?.scheduledEndMs).isEqualTo(9_000L)
    }

    @Test
    fun rejects_invalid_window() = runTest {
        val manager = FakeRecordingManager()
        val useCase = ScheduleRecordingWindow(manager)

        val result = useCase(
            ScheduleRecordingWindowCommand(
                contentType = ContentType.LIVE,
                providerId = 7L,
                channel = channel(),
                streamUrl = "https://example.com/live.ts",
                startMs = 10_000L,
                endMs = 10_000L,
                recurrence = RecordingRecurrence.NONE,
                nowMs = 5_000L
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(manager.lastScheduledRequest).isNull()
    }

    @Test
    fun rejects_missing_live_context() = runTest {
        val manager = FakeRecordingManager()
        val useCase = ScheduleRecordingWindow(manager)

        val result = useCase(
            ScheduleRecordingWindowCommand(
                contentType = ContentType.MOVIE,
                providerId = 0L,
                channel = null,
                streamUrl = "",
                startMs = 10_000L,
                endMs = 20_000L,
                recurrence = RecordingRecurrence.NONE,
                nowMs = 5_000L
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(manager.lastScheduledRequest).isNull()
    }

    private fun channel() = Channel(
        id = 11L,
        name = "Channel 11",
        streamUrl = "https://example.com/live.ts",
        providerId = 7L
    )

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
