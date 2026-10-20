package com.streamvault.domain.usecase

import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.Result
import javax.inject.Inject

/**
 * Schedules a live recording for an explicit time window. Unlike [ScheduleRecording] this does not
 * need EPG data, which lets channels without guide information be recorded by time.
 */
data class ScheduleRecordingWindowCommand(
    val contentType: ContentType,
    val providerId: Long,
    val channel: Channel?,
    val streamUrl: String,
    val startMs: Long,
    val endMs: Long,
    val recurrence: RecordingRecurrence,
    val title: String? = null,
    val nowMs: Long = System.currentTimeMillis()
)

class ScheduleRecordingWindow @Inject constructor(
    private val recordingManager: RecordingManager
) {
    suspend operator fun invoke(command: ScheduleRecordingWindowCommand): Result<RecordingItem> {
        val channel = command.channel
        if (
            command.contentType != ContentType.LIVE ||
            channel == null ||
            command.providerId <= 0L ||
            command.streamUrl.isBlank()
        ) {
            return Result.error("Recording needs a valid live channel.")
        }

        val scheduledStartMs = maxOf(command.nowMs, command.startMs)
        if (command.endMs <= scheduledStartMs) {
            return Result.error("The selected recording time is invalid.")
        }

        return recordingManager.scheduleRecording(
            RecordingRequest(
                providerId = command.providerId,
                channelId = channel.id,
                channelName = channel.name,
                streamUrl = command.streamUrl,
                scheduledStartMs = scheduledStartMs,
                scheduledEndMs = command.endMs,
                programTitle = command.title ?: channel.name,
                recurrence = command.recurrence
            )
        )
    }
}
