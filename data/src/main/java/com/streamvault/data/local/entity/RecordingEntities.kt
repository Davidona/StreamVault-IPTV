package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.ProgramReminderDeliveryState

@Entity(
    tableName = "recording_schedules",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["enabled", "requested_start_ms"]),
        Index(value = ["recurring_rule_id"])
    ]
)
data class RecordingScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "program_title") val programTitle: String? = null,
    @ColumnInfo(name = "requested_start_ms") val requestedStartMs: Long,
    @ColumnInfo(name = "requested_end_ms") val requestedEndMs: Long,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: String? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "is_manual") val isManual: Boolean = false,
    val priority: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "recording_runs",
    foreignKeys = [
        ForeignKey(
            entity = RecordingScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["schedule_id"]),
        Index(value = ["provider_id"]),
        Index(value = ["status", "scheduled_start_ms"]),
        Index(value = ["alarm_start_at_ms"]),
        Index(value = ["alarm_stop_at_ms"])
    ]
)
data class RecordingRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "program_title") val programTitle: String? = null,
    @ColumnInfo(name = "scheduled_start_ms") val scheduledStartMs: Long,
    @ColumnInfo(name = "scheduled_end_ms") val scheduledEndMs: Long,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: String? = null,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    @ColumnInfo(name = "source_type") val sourceType: RecordingSourceType = RecordingSourceType.UNKNOWN,
    @ColumnInfo(name = "resolved_url") val resolvedUrl: String? = null,
    @ColumnInfo(name = "headers_json") val headersJson: String = "{}",
    @ColumnInfo(name = "user_agent") val userAgent: String? = null,
    @ColumnInfo(name = "expiration_time") val expirationTime: Long? = null,
    @ColumnInfo(name = "provider_label") val providerLabel: String? = null,
    @ColumnInfo(name = "output_uri") val outputUri: String? = null,
    @ColumnInfo(name = "output_display_path") val outputDisplayPath: String? = null,
    @ColumnInfo(name = "output_credentials_encrypted") val outputCredentialsEncrypted: String? = null,
    @ColumnInfo(name = "bytes_written") val bytesWritten: Long = 0L,
    @ColumnInfo(name = "average_throughput_bps") val averageThroughputBytesPerSecond: Long = 0L,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "last_progress_at_ms") val lastProgressAtMs: Long? = null,
    @ColumnInfo(name = "failure_category") val failureCategory: RecordingFailureCategory = RecordingFailureCategory.NONE,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    @ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long? = null,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long? = null,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long? = null,
    @ColumnInfo(name = "schedule_enabled") val scheduleEnabled: Boolean = true,
    val priority: Int = 0,
    @ColumnInfo(name = "alarm_start_at_ms") val alarmStartAtMs: Long? = null,
    @ColumnInfo(name = "alarm_stop_at_ms") val alarmStopAtMs: Long? = null,
    @ColumnInfo(name = "exact_alarm_armed") val exactAlarmArmed: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recording_storage")
data class RecordingStorageEntity(
    @PrimaryKey
    val id: Long = 1L,
    @ColumnInfo(name = "tree_uri") val treeUri: String? = null,
    @ColumnInfo(name = "smb_url") val smbUrl: String? = null,
    @ColumnInfo(name = "smb_credentials_encrypted") val smbCredentialsEncrypted: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "output_directory") val outputDirectory: String? = null,
    @ColumnInfo(name = "available_bytes") val availableBytes: Long? = null,
    @ColumnInfo(name = "is_writable") val isWritable: Boolean = false,
    @ColumnInfo(name = "file_name_pattern") val fileNamePattern: String = "ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts",
    @ColumnInfo(name = "retention_days") val retentionDays: Int? = null,
    @ColumnInfo(name = "max_simultaneous_recordings") val maxSimultaneousRecordings: Int = 2,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "program_reminders",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider_id", "remind_at"]),
        Index(value = ["is_dismissed", "notified_at", "remind_at"]),
        Index(value = ["provider_id", "channel_id", "program_start_time"]),
        Index(
            value = ["provider_id", "channel_id", "program_title", "program_start_time"],
            unique = true
        )
    ]
)
data class ProgramReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "channel_id") val channelId: String,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "program_title") val programTitle: String,
    @ColumnInfo(name = "program_start_time") val programStartTime: Long,
    @ColumnInfo(name = "remind_at") val remindAt: Long,
    @ColumnInfo(name = "lead_time_minutes") val leadTimeMinutes: Int = 5,
    @ColumnInfo(name = "is_dismissed") val isDismissed: Boolean = false,
    @ColumnInfo(name = "notified_at") val notifiedAt: Long? = null,
    @ColumnInfo(name = "exact_alarm_armed") val exactAlarmArmed: Boolean = true,
    @ColumnInfo(name = "delivery_state")
    val deliveryState: ProgramReminderDeliveryState = ProgramReminderDeliveryState.PENDING,
    @ColumnInfo(name = "delivery_attempt_token") val deliveryAttemptToken: String? = null,
    @ColumnInfo(name = "delivery_attempted_at") val deliveryAttemptedAt: Long? = null,
    @ColumnInfo(name = "delivery_attempt_count") val deliveryAttemptCount: Int = 0,
    @ColumnInfo(name = "delivery_failure_reason") val deliveryFailureReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

data class RecordingRunWithSchedule(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "channel_name") val channelName: String,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "program_title") val programTitle: String? = null,
    @ColumnInfo(name = "scheduled_start_ms") val scheduledStartMs: Long,
    @ColumnInfo(name = "scheduled_end_ms") val scheduledEndMs: Long,
    val recurrence: RecordingRecurrence,
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: String? = null,
    val status: RecordingStatus,
    @ColumnInfo(name = "source_type") val sourceType: RecordingSourceType,
    @ColumnInfo(name = "output_uri") val outputUri: String? = null,
    @ColumnInfo(name = "output_display_path") val outputDisplayPath: String? = null,
    @ColumnInfo(name = "bytes_written") val bytesWritten: Long,
    @ColumnInfo(name = "average_throughput_bps") val averageThroughputBytesPerSecond: Long,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    @ColumnInfo(name = "last_progress_at_ms") val lastProgressAtMs: Long? = null,
    @ColumnInfo(name = "failure_category") val failureCategory: RecordingFailureCategory,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null,
    @ColumnInfo(name = "terminal_at_ms") val terminalAtMs: Long? = null,
    @ColumnInfo(name = "schedule_enabled") val scheduleEnabled: Boolean,
    @ColumnInfo(name = "exact_alarm_armed") val exactAlarmArmed: Boolean,
    val priority: Int
)
