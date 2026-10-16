package com.streamvault.domain.model

enum class RecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class RecordingSourceType {
    TS,
    HLS,
    DASH,
    UNKNOWN
}

enum class RecordingFailureCategory {
    NONE,
    NETWORK,
    STORAGE,
    AUTH,
    TOKEN_EXPIRED,
    DRM_UNSUPPORTED,
    FORMAT_UNSUPPORTED,
    SCHEDULE_CONFLICT,
    PROVIDER_LIMIT,
    UNKNOWN
}

/** Stable persisted code for a localized recording failure detail. */
object RecordingFailureReasonCodes {
    const val LIVE_CAPTURE_OUTPUT_TOO_SLOW = "streamvault:live_capture_output_too_slow"
}

enum class RecordingRecurrence {
    NONE,
    DAILY,
    WEEKLY
}

data class RecordingReconciliationSummary(
    val rowsInspected: Int = 0,
    val rowsRepaired: Int = 0,
    val rowsQuarantined: Int = 0
)

data class RecordingReconciliationRowFailure(
    val recordingId: String,
    val reason: String
)

sealed interface RecordingReconciliationResult {
    data class Complete(
        val summary: RecordingReconciliationSummary
    ) : RecordingReconciliationResult

    data class Partial(
        val summary: RecordingReconciliationSummary,
        val rowFailures: List<RecordingReconciliationRowFailure>
    ) : RecordingReconciliationResult

    data class TransientFailure(
        val message: String
    ) : RecordingReconciliationResult

    data class PermanentFailure(
        val message: String
    ) : RecordingReconciliationResult
}

data class RecordingRequest(
    val providerId: Long,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val programTitle: String? = null,
    val outputPath: String? = null,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    val recurringRuleId: String? = null,
    val recordNextProgram: Boolean = false,
    val priority: Int = 0,
    val paddingBeforeMs: Long = 0L,
    val paddingAfterMs: Long = 0L
) {
    init {
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        require(scheduledStartMs > 0) { "scheduledStartMs must be positive" }
        require(scheduledEndMs > scheduledStartMs) { "scheduledEndMs must be after scheduledStartMs" }
    }
}

data class RecordingItem(
    val id: String,
    val scheduleId: Long? = null,
    val providerId: Long,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val programTitle: String? = null,
    val outputPath: String? = null,
    val outputUri: String? = null,
    val outputDisplayPath: String? = null,
    val recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
    val recurringRuleId: String? = null,
    val status: RecordingStatus = RecordingStatus.SCHEDULED,
    val sourceType: RecordingSourceType = RecordingSourceType.UNKNOWN,
    val bytesWritten: Long = 0L,
    val averageThroughputBytesPerSecond: Long = 0L,
    val retryCount: Int = 0,
    val lastProgressAtMs: Long? = null,
    val failureCategory: RecordingFailureCategory = RecordingFailureCategory.NONE,
    val scheduleEnabled: Boolean = true,
    val priority: Int = 0,
    val failureReason: String? = null,
    val terminalAtMs: Long? = null,
    val exactAlarmArmed: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(streamUrl.isNotBlank()) { "streamUrl must not be blank" }
        require(scheduledEndMs > scheduledStartMs) { "scheduledEndMs must be after scheduledStartMs" }
        require(bytesWritten >= 0L) { "bytesWritten must not be negative" }
        require(averageThroughputBytesPerSecond >= 0L) { "averageThroughputBytesPerSecond must not be negative" }
        require(retryCount >= 0) { "retryCount must not be negative" }
    }
}

data class RecordingStorageConfig(
    val treeUri: String? = null,
    val displayName: String? = null,
    // Absolute filesystem path to write recordings to when [treeUri] is unset, e.g. an app-private
    // folder on a USB drive (`/storage/<id>/Android/data/<pkg>/files/...`). Null falls back to the
    // default internal recordings directory.
    val localDirectory: String? = null,
    /** SMB destination without credentials, for settings updates that preserve the active target. */
    val smbUrl: String? = null,
    /** SMB account name shown in settings. The password is never included in storage state. */
    val smbUsername: String? = null,
    val fileNamePattern: String = "ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts",
    val retentionDays: Int? = null,
    val maxSimultaneousRecordings: Int = 2
) {
    init {
        require(fileNamePattern.isNotBlank()) { "fileNamePattern must not be blank" }
        require(maxSimultaneousRecordings >= 1) { "maxSimultaneousRecordings must be at least 1" }
        retentionDays?.let { require(it >= 1) { "retentionDays must be at least 1 when set" } }
    }
}

data class RecordingSmbStorageConfig(
    val url: String,
    val username: String = "",
    /** Null keeps the saved password only when the SMB destination URL is unchanged. */
    val password: String? = null
) {
    init {
        require(url.isNotBlank()) { "SMB storage URL must not be blank" }
    }
}

data class RecordingStorageState(
    val treeUri: String? = null,
    val displayName: String? = null,
    // The explicit local path currently in effect (null when using a SAF tree). Lets the UI tell
    // whether recordings are going to internal storage vs. a USB drive without re-deriving it.
    val localDirectory: String? = null,
    val smbUrl: String? = null,
    val smbUsername: String? = null,
    val outputDirectory: String? = null,
    val availableBytes: Long? = null,
    val isWritable: Boolean = false,
    val fileNamePattern: String = "ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts",
    val retentionDays: Int? = null,
    val maxSimultaneousRecordings: Int = 2
) {
    init {
        availableBytes?.let { require(it >= 0) { "availableBytes must be non-negative" } }
        require(maxSimultaneousRecordings >= 1) { "maxSimultaneousRecordings must be at least 1" }
        retentionDays?.let { require(it >= 1) { "retentionDays must be at least 1 when set" } }
    }
}
