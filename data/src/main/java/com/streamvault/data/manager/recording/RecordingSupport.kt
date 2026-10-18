package com.streamvault.data.manager.recording

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.streamvault.data.local.entity.RecordingRunEntity
import com.streamvault.data.local.entity.RecordingRunWithSchedule
import com.streamvault.data.local.entity.RecordingStorageEntity
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.util.LivePlaybackCaptureBackpressureException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private const val DEFAULT_RECORDINGS_DIR_NAME = "recordings"

internal fun RecordingRunWithSchedule.toDomain(): RecordingItem = RecordingItem(
    id = id,
    scheduleId = scheduleId,
    providerId = providerId,
    channelId = channelId,
    channelName = channelName,
    streamUrl = streamUrl,
    scheduledStartMs = scheduledStartMs,
    scheduledEndMs = scheduledEndMs,
    programTitle = programTitle,
    outputPath = outputDisplayPath,
    outputUri = outputUri,
    outputDisplayPath = outputDisplayPath,
    recurrence = recurrence,
    recurringRuleId = recurringRuleId,
    status = status,
    sourceType = sourceType,
    bytesWritten = bytesWritten,
    averageThroughputBytesPerSecond = averageThroughputBytesPerSecond,
    retryCount = retryCount,
    lastProgressAtMs = lastProgressAtMs,
    failureCategory = failureCategory,
    scheduleEnabled = scheduleEnabled,
    exactAlarmArmed = exactAlarmArmed,
    priority = priority,
    failureReason = failureReason,
    terminalAtMs = terminalAtMs
)

internal fun RecordingStorageEntity.toDomain(credentialCrypto: CredentialCrypto): RecordingStorageState {
    val smbCredentials = runCatching {
        decodeSmbCredentials(smbCredentialsEncrypted, credentialCrypto)
    }.getOrNull()
    return RecordingStorageState(
        treeUri = treeUri,
        displayName = displayName,
        // When no SAF tree is selected, outputDirectory holds the resolved local path (default
        // internal dir or a chosen USB folder), so it doubles as the active localDirectory.
        localDirectory = if (treeUri.isNullOrBlank() && smbUrl.isNullOrBlank()) outputDirectory else null,
        smbUrl = smbUrl,
        smbUsername = smbCredentials?.username,
        outputDirectory = outputDirectory,
        availableBytes = availableBytes,
        isWritable = isWritable,
        fileNamePattern = fileNamePattern,
        retentionDays = retentionDays,
        maxSimultaneousRecordings = maxSimultaneousRecordings
    )
}

internal fun RecordingStorageConfig.toEntity(
    existing: RecordingStorageEntity?,
    outputDirectory: String?,
    availableBytes: Long?,
    isWritable: Boolean
): RecordingStorageEntity =
    RecordingStorageEntity(
        id = existing?.id ?: 1L,
        treeUri = treeUri,
        smbUrl = smbUrl,
        smbCredentialsEncrypted = existing
            ?.takeIf { !smbUrl.isNullOrBlank() && it.smbUrl == smbUrl }
            ?.smbCredentialsEncrypted,
        displayName = displayName,
        outputDirectory = outputDirectory,
        availableBytes = availableBytes,
        isWritable = isWritable,
        fileNamePattern = fileNamePattern,
        retentionDays = retentionDays,
        maxSimultaneousRecordings = maxSimultaneousRecordings,
        updatedAt = System.currentTimeMillis()
    )

internal fun headersToJson(headers: Map<String, String>): String {
    val obj = JSONObject()
    headers.forEach { (k, v) -> obj.put(k, v) }
    return obj.toString() ?: "{}"
}

internal fun headersFromJson(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(raw)
        buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
    }.getOrDefault(emptyMap())
}

internal fun inferFailureCategory(error: Throwable?, fallback: RecordingFailureCategory = RecordingFailureCategory.UNKNOWN): RecordingFailureCategory {
    if (error is UnsupportedRecordingException) return error.category
    if (error is LivePlaybackCaptureBackpressureException) return RecordingFailureCategory.STORAGE
    val rootCause = error?.cause ?: error
    when (rootCause) {
        is java.net.SocketTimeoutException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.net.ConnectException -> return RecordingFailureCategory.NETWORK
        is javax.net.ssl.SSLException -> return RecordingFailureCategory.NETWORK
    }
    val normalized = error?.message.orEmpty().lowercase(Locale.ROOT)
    return when {
        normalized.isBlank() -> fallback
        "drm" in normalized -> RecordingFailureCategory.DRM_UNSUPPORTED
        "storage" in normalized || "space" in normalized || "writable" in normalized -> RecordingFailureCategory.STORAGE
        "conflict" in normalized -> RecordingFailureCategory.SCHEDULE_CONFLICT
        "connection" in normalized || "http 401" in normalized || "http 403" in normalized || "forbidden" in normalized -> RecordingFailureCategory.AUTH
        "expired" in normalized || "token" in normalized -> RecordingFailureCategory.TOKEN_EXPIRED
        "unsupported" in normalized || "dash" in normalized -> RecordingFailureCategory.FORMAT_UNSUPPORTED
        "network" in normalized || "timeout" in normalized || "http" in normalized -> RecordingFailureCategory.NETWORK
        else -> fallback
    }
}

internal fun sanitizeRecordingFileName(
    channelName: String,
    programTitle: String?,
    startMs: Long,
    pattern: String
): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
    val startLabel = formatter.format(Date(startMs))
    val safeChannel = channelName.replace(Regex("[^a-zA-Z0-9_ -]"), "_").trim().ifBlank { "Channel" }
    val safeProgram = programTitle
        ?.replace(Regex("[^a-zA-Z0-9_ -]"), "_")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "Program"
    val rendered = pattern
        .replace("ChannelName", safeChannel)
        .replace("ProgramTitle", safeProgram)
        .replace("yyyy-MM-dd_HH-mm", startLabel)
    return if (rendered.endsWith(".ts", ignoreCase = true)) rendered else "$rendered.ts"
}

internal fun resolveStorageDetails(
    context: Context,
    treeUriString: String?,
    localDirectory: String? = null
): Triple<String?, Long?, Boolean> {
    if (treeUriString.isNullOrBlank()) {
        val recordingsDir = localDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.also { runCatching { it.mkdirs() } }
            ?: defaultRecordingDirectory(context)
        val available = runCatching { StatFs(recordingsDir.absolutePath).availableBytes }.getOrNull()
            ?.takeIf { it > 0L }
            ?: recordingsDir.usableSpace.takeIf { it > 0L }
        return Triple(recordingsDir.absolutePath, available, recordingsDir.canWrite())
    }

    val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return Triple(null, null, false)
    val documentTree = DocumentFile.fromTreeUri(context, treeUri)
    val isWritable = documentTree?.canWrite() == true
    return Triple(documentTree?.name ?: treeUriString, null, isWritable)
}

sealed interface RecordingOutputTarget {
    data class FileTarget(val file: File) : RecordingOutputTarget
    data class DocumentTarget(val uri: Uri, val displayPath: String?) : RecordingOutputTarget
    data class SmbTarget(
        val url: String,
        val credentials: SmbRecordingCredentials,
        val displayPath: String?
    ) : RecordingOutputTarget
}

internal fun createOutputTarget(
    context: Context,
    storage: RecordingStorageEntity,
    fileName: String,
    credentialCrypto: CredentialCrypto
): RecordingOutputTarget {
    storage.smbUrl?.takeIf { it.isNotBlank() }?.let { smbUrl ->
        val credentials = decodeSmbCredentials(storage.smbCredentialsEncrypted, credentialCrypto)
            ?: throw IllegalStateException("SMB credentials are unavailable. Reconfigure the SMB recording folder.")
        val outputUrl = SmbRecordingStorage.findAvailableFileUrl(smbUrl, fileName, credentials)
        return RecordingOutputTarget.SmbTarget(
            url = outputUrl,
            credentials = credentials,
            displayPath = outputUrl
        )
    }

    val treeUri = storage.treeUri
    if (treeUri.isNullOrBlank()) {
        val baseDirectory = storage.outputDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: defaultRecordingDirectory(context)
        val outputFile = File(baseDirectory, fileName)
        outputFile.parentFile?.mkdirs()
        return RecordingOutputTarget.FileTarget(outputFile)
    }

    val uri = Uri.parse(treeUri)
    val documentTree = DocumentFile.fromTreeUri(context, uri)
        ?: throw IllegalStateException("Recording folder is unavailable.")
    val baseName = fileName.removeSuffix(".ts")
    var candidateName = fileName
    var counter = 1
    while (documentTree.findFile(candidateName) != null) {
        candidateName = "${baseName}_$counter.ts"
        counter++
    }
    val document = documentTree.createFile("video/mp2t", candidateName.removeSuffix(".ts"))
    requireNotNull(document?.uri) { "Failed to create recording file." }
    return RecordingOutputTarget.DocumentTarget(document.uri, "${documentTree.name ?: "Recordings"}/$candidateName")
}

internal fun deleteOutputTarget(
    context: Context,
    outputUri: String?,
    outputPath: String?,
    outputCredentialsEncrypted: String?,
    credentialCrypto: CredentialCrypto
) {
    if (outputUri?.startsWith("smb://", ignoreCase = true) == true) {
        runCatching {
            val credentials = decodeSmbCredentials(outputCredentialsEncrypted, credentialCrypto)
                ?: throw IllegalStateException("SMB credentials are unavailable.")
            SmbRecordingStorage.deleteFile(outputUri, credentials)
        }
        return
    }

    outputUri?.let { rawUri ->
        runCatching {
            val document = DocumentFile.fromSingleUri(context, Uri.parse(rawUri))
            if (document?.exists() == true) {
                document.delete()
            }
        }
    }
    outputPath?.takeIf { it.isNotBlank() }?.let { path ->
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

internal fun RecordingOutputTarget.asPersistenceValues(): Pair<String?, String?> = when (this) {
    is RecordingOutputTarget.FileTarget -> null to file.absolutePath
    is RecordingOutputTarget.DocumentTarget -> uri.toString() to displayPath
    is RecordingOutputTarget.SmbTarget -> url to displayPath
}

internal fun RecordingOutputTarget.encryptedSmbCredentials(credentialCrypto: CredentialCrypto): String? =
    (this as? RecordingOutputTarget.SmbTarget)?.credentials?.encodeEncrypted(credentialCrypto)

internal fun RecordingOutputTarget.openOutputStream(contentResolver: ContentResolver, append: Boolean = false) = when (this) {
    is RecordingOutputTarget.FileTarget -> FileOutputStream(file, append).buffered()
    is RecordingOutputTarget.DocumentTarget -> contentResolver.openOutputStream(uri, if (append) "wa" else "w")
    is RecordingOutputTarget.SmbTarget -> SmbRecordingStorage.openOutputStream(url, credentials, append)
}

private fun defaultRecordingDirectory(context: Context): File {
    val externalAppMoviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?.let { File(it, DEFAULT_RECORDINGS_DIR_NAME) }
    val targetDir = externalAppMoviesDir ?: File(context.filesDir, DEFAULT_RECORDINGS_DIR_NAME)
    if (!targetDir.exists()) {
        targetDir.mkdirs()
    }
    return targetDir
}
