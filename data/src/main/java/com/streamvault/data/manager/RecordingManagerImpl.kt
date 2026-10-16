package com.streamvault.data.manager

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.RecordingStorageDao
import com.streamvault.data.local.entity.RecordingRunEntity
import com.streamvault.data.local.entity.RecordingScheduleEntity
import com.streamvault.data.local.entity.RecordingStorageEntity
import com.streamvault.data.manager.recording.CaptureProgress
import com.streamvault.data.manager.recording.HlsLiveCaptureEngine
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.recording.RecordingForegroundService
import com.streamvault.data.manager.recording.RecordingOutputTarget
import com.streamvault.data.manager.recording.RecordingServiceLauncher
import com.streamvault.data.manager.recording.RecordingSourceResolver
import com.streamvault.data.manager.recording.ResolvedRecordingSource
import com.streamvault.data.manager.recording.SmbRecordingCredentials
import com.streamvault.data.manager.recording.SmbRecordingStorage
import com.streamvault.data.manager.recording.TsPassThroughCaptureEngine
import com.streamvault.data.manager.recording.UnsupportedRecordingException
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.data.manager.recording.asPersistenceValues
import com.streamvault.data.manager.recording.createOutputTarget
import com.streamvault.data.manager.recording.decodeSmbCredentials
import com.streamvault.data.manager.recording.deleteOutputTarget
import com.streamvault.data.manager.recording.encodeEncrypted
import com.streamvault.data.manager.recording.encryptedSmbCredentials
import com.streamvault.data.manager.recording.headersFromJson
import com.streamvault.data.manager.recording.headersToJson
import com.streamvault.data.manager.recording.inferFailureCategory
import com.streamvault.data.manager.recording.resolveStorageDetails
import com.streamvault.data.manager.recording.sanitizeRecordingFileName
import com.streamvault.data.manager.recording.toEntity
import com.streamvault.data.manager.recording.toDomain
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingReconciliationResult
import com.streamvault.domain.model.RecordingReconciliationRowFailure
import com.streamvault.domain.model.RecordingReconciliationSummary
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingSmbStorageConfig
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "RecordingManager"
private const val MIN_FREE_SPACE_BYTES = 512L * 1024L * 1024L // 512 MB

internal fun isTransientRecordingReconciliationFailure(error: Throwable): Boolean = when (error) {
    is IOException -> true
    is SQLiteException -> {
        val message = error.message.orEmpty()
        message.contains("locked", ignoreCase = true) ||
            message.contains("busy", ignoreCase = true)
    }
    else -> false
}
private const val FAILURE_NOTIFICATION_CHANNEL_ID = "streamvault_recording_failure"
private const val FAILURE_NOTIFICATION_ID_BASE = 5000
private const val FOREGROUND_SERVICE_TIMEOUT_REASON =
    "Recording stopped because Android exhausted the foreground-service time allowance."

internal class ActiveCapture(private val job: Job) {
    private val cancellationRequested = AtomicBoolean(false)

    val isActive: Boolean
        get() = job.isActive

    suspend fun cancelAndJoin() {
        if (cancellationRequested.compareAndSet(false, true)) {
            job.cancel()
        }
        job.join()
    }
}

@Singleton
class RecordingManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val transactionRunner: DatabaseTransactionRunner,
    private val providerDao: ProviderDao,
    private val recordingScheduleDao: RecordingScheduleDao,
    private val recordingRunDao: RecordingRunDao,
    private val recordingStorageDao: RecordingStorageDao,
    private val recordingSourceResolver: RecordingSourceResolver,
    private val tsPassThroughCaptureEngine: TsPassThroughCaptureEngine,
    private val hlsLiveCaptureEngine: HlsLiveCaptureEngine,
    private val alarmScheduler: RecordingAlarmScheduler,
    private val preferencesRepository: PreferencesRepository,
    private val recordingServiceLauncher: RecordingServiceLauncher,
    private val providerSnapshotDao: ProviderSnapshotDao? = null,
    private val credentialCrypto: CredentialCrypto = AndroidKeystoreCredentialCrypto(context)
) : RecordingManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, ActiveCapture>()
    private val activeJobsMutex = Mutex()
    private val foregroundServiceTimeoutRecordingIds = ConcurrentHashMap.newKeySet<String>()
    private val recordingMutex = Mutex()
    private val legacyStateFile by lazy { File(File(context.filesDir, "recordings"), "recordings_state.json") }

    init {
        scope.launch {
            migrateLegacyStateIfNeeded()
            ensureStorageState()
            reconcileRecordingState()
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun resolveMaxVideoHeightForCurrentNetwork(): Int? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                preferencesRepository.playerEthernetMaxVideoHeight.first()
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                preferencesRepository.playerWifiMaxVideoHeight.first()
            else -> null
        }
    }

    override fun observeRecordingItems(): Flow<List<RecordingItem>> =
        recordingRunDao.observeAll().map { runs -> runs.map { it.toDomain() } }

    override fun observeStorageState(): Flow<RecordingStorageState> =
        recordingStorageDao.observe().map { entity ->
            (entity ?: ensureStorageStateSync()).toDomain(credentialCrypto)
        }

    override suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val storage = ensureStorageStateSync()
            if (!storage.isWritable) {
                return@withContext Result.error("Recording storage is not writable.")
            }
            ensureExactRecordingAlarmsAvailableOrThrow()
            validateRecordingWindowOrThrow(request.scheduledStartMs, request.scheduledEndMs, request.providerId)
            val source = resolveRecordableSource(request.providerId, request.channelId, request.streamUrl)
            val outputTarget = createOutputTarget(
                context = context,
                storage = storage,
                fileName = sanitizeRecordingFileName(
                    channelName = request.channelName,
                    programTitle = request.programTitle,
                    startMs = request.scheduledStartMs,
                    pattern = storage.fileNamePattern
                ),
                credentialCrypto = credentialCrypto
            )
            val run = try {
                persistManualRun(request, source, outputTarget)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val (outputUri, outputDisplayPath) = outputTarget.asPersistenceValues()
                deleteOutputTarget(
                    context,
                    outputUri,
                    outputDisplayPath,
                    outputTarget.encryptedSmbCredentials(credentialCrypto),
                    credentialCrypto
                )
                throw error
            }

            scheduleStopAlarmOrFail(run.id, request.scheduledEndMs)
            try {
                recordingServiceLauncher.startCapture(context, run.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                markRunFailed(run.id, error.message ?: "Capture failed to start.", inferFailureCategory(error))
                throw IllegalStateException(error.message ?: "Capture failed to start.", error)
            }
            run.toStandaloneDomain()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error -> Result.error(error.message ?: "Failed to start recording", error) }
        )
    }

    override suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val storage = ensureStorageStateSync()
            if (!storage.isWritable) {
                return@withContext Result.error("Recording storage is not writable.")
            }
            ensureExactRecordingAlarmsAvailableOrThrow()

            val recurringRuleId = request.recurringRuleId
                ?: request.recurrence.takeIf { it != RecordingRecurrence.NONE }?.let { UUID.randomUUID().toString() }
            val run = persistScheduledRun(request, recurringRuleId)

            scheduleStartAlarmOrFail(run.id, run.scheduledStartMs)
            run.toStandaloneDomain()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error -> Result.error(error.message ?: "Failed to schedule recording", error) }
        )
    }

    override suspend fun stopRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        cancelActiveJob(recordingId)
        alarmScheduler.cancel(recordingId)
        val now = System.currentTimeMillis()
        val newStatus = when {
            run.status == RecordingStatus.RECORDING -> RecordingStatus.COMPLETED
            run.status == RecordingStatus.SCHEDULED -> RecordingStatus.CANCELLED
            else -> RecordingStatus.CANCELLED
        }
        recordingRunDao.update(
            run.copy(
                status = newStatus,
                endedAtMs = now,
                terminalAtMs = now,
                updatedAt = now
            )
        )
        Result.success(Unit)
    }

    override suspend fun stopRecordingForForegroundServiceTimeout(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        foregroundServiceTimeoutRecordingIds.add(recordingId)
        try {
            cancelActiveJob(recordingId)
            alarmScheduler.cancel(recordingId)
            val now = System.currentTimeMillis()
            recordingRunDao.update(
                run.copy(
                    status = RecordingStatus.FAILED,
                    failureCategory = RecordingFailureCategory.UNKNOWN,
                    failureReason = FOREGROUND_SERVICE_TIMEOUT_REASON,
                    endedAtMs = now,
                    terminalAtMs = now,
                    updatedAt = now
                )
            )
        } finally {
            foregroundServiceTimeoutRecordingIds.remove(recordingId)
        }
        Result.success(Unit)
    }

    override suspend fun cancelRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        cancelActiveJob(recordingId)
        alarmScheduler.cancel(recordingId)
        val now = System.currentTimeMillis()
        recordingRunDao.update(
            run.copy(
                status = RecordingStatus.CANCELLED,
                terminalAtMs = now,
                endedAtMs = now,
                scheduleEnabled = false,
                updatedAt = now
            )
        )
        recordingScheduleDao.getById(run.scheduleId)?.let { schedule ->
            recordingScheduleDao.update(schedule.copy(enabled = false, updatedAt = now))
        }
        run.recurringRuleId?.let { ruleId ->
            recordingRunDao.getScheduledByRecurringRuleId(ruleId).forEach { pendingRun ->
                if (pendingRun.id != recordingId) {
                    alarmScheduler.cancel(pendingRun.id)
                    recordingRunDao.update(
                        pendingRun.copy(
                            status = RecordingStatus.CANCELLED,
                            terminalAtMs = now,
                            endedAtMs = now,
                            scheduleEnabled = false,
                            updatedAt = now
                        )
                    )
                }
            }
        }
        Result.success(Unit)
    }

    override suspend fun skipOccurrence(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        if (run.status != RecordingStatus.SCHEDULED) {
            return@withContext Result.error("Only scheduled recordings can be skipped.")
        }
        cancelActiveJob(recordingId)
        alarmScheduler.cancel(recordingId)
        val now = System.currentTimeMillis()
        recordingRunDao.update(
            run.copy(
                status = RecordingStatus.CANCELLED,
                failureReason = "Skipped by user",
                terminalAtMs = now,
                endedAtMs = now,
                updatedAt = now
            )
        )
        Result.success(Unit)
    }

    override suspend fun getConflictingRecordings(startMs: Long, endMs: Long, providerId: Long): List<RecordingItem> = withContext(Dispatchers.IO) {
        recordingRunDao.getOverlapping(startMs, endMs)
            .filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
            .filter { it.scheduleEnabled }
            .map { it.toStandaloneDomain() }
    }

    override suspend fun forceScheduleRecording(request: RecordingRequest): Result<RecordingItem> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val storage = ensureStorageStateSync()
            if (!storage.isWritable) {
                return@withContext Result.error("Recording storage is not writable.")
            }
            ensureExactRecordingAlarmsAvailableOrThrow()

            val recurringRuleId = request.recurringRuleId
                ?: request.recurrence.takeIf { it != RecordingRecurrence.NONE }?.let { UUID.randomUUID().toString() }
            val scheduleId = recordingScheduleDao.insert(
                RecordingScheduleEntity(
                    providerId = request.providerId,
                    channelId = request.channelId,
                    channelName = request.channelName,
                    streamUrl = request.streamUrl,
                    programTitle = request.programTitle,
                    requestedStartMs = request.scheduledStartMs,
                    requestedEndMs = request.scheduledEndMs,
                    recurrence = request.recurrence,
                    recurringRuleId = recurringRuleId,
                    enabled = true,
                    isManual = false,
                    priority = request.priority
                )
            )
            val run = createPendingRun(
                scheduleId = scheduleId,
                request = request.copy(recurringRuleId = recurringRuleId)
            )

            recordingMutex.withLock {
                val overlapping = recordingRunDao.getOverlapping(request.scheduledStartMs, request.scheduledEndMs)
                    .filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
                    .filter { it.scheduleEnabled }
                for (conflict in overlapping) {
                    cancelActiveJob(conflict.id)
                    alarmScheduler.cancel(conflict.id)
                    val now = System.currentTimeMillis()
                    recordingRunDao.update(
                        conflict.copy(
                            status = RecordingStatus.CANCELLED,
                            failureReason = "Replaced by ${request.programTitle ?: request.channelName}",
                            terminalAtMs = now,
                            endedAtMs = now,
                            updatedAt = now
                        )
                    )
                }
                recordingRunDao.insert(run)
            }

            scheduleStartAlarmOrFail(run.id, run.scheduledStartMs)
            run.toStandaloneDomain()
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error -> Result.error(error.message ?: "Failed to force-schedule recording", error) }
        )
    }

    override suspend fun deleteRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        if (run.status == RecordingStatus.SCHEDULED || run.status == RecordingStatus.RECORDING) {
            return@withContext Result.error("Only finished recordings can be deleted.")
        }
        deleteOutputTarget(
            context,
            run.outputUri,
            run.outputDisplayPath,
            run.outputCredentialsEncrypted,
            credentialCrypto
        )
        recordingRunDao.delete(recordingId)
        Result.success(Unit)
    }

    override suspend fun retryRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            ensureExactRecordingAlarmsAvailableOrThrow()
            val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
            val schedule = recordingScheduleDao.getById(run.scheduleId) ?: return@withContext Result.error("Recording schedule not found")
            val now = System.currentTimeMillis()
            val retryStartMs = maxOf(now + 2_000L, schedule.requestedStartMs)
            val retryEndMs = maxOf(schedule.requestedEndMs, retryStartMs + 60_000L)
            val retryRecurrence = if (schedule.enabled) schedule.recurrence else RecordingRecurrence.NONE
            val retryRuleId = if (schedule.enabled) schedule.recurringRuleId else null
            if (schedule.requestedEndMs <= now) {
                val remainingDuration = schedule.requestedEndMs - schedule.requestedStartMs
                val newEndMs = retryStartMs + remainingDuration.coerceAtLeast(60_000L)
                val retriedRun = createPendingRun(
                    scheduleId = schedule.id,
                    request = RecordingRequest(
                        providerId = schedule.providerId,
                        channelId = schedule.channelId,
                        channelName = schedule.channelName,
                        streamUrl = schedule.streamUrl,
                        scheduledStartMs = retryStartMs,
                        scheduledEndMs = newEndMs,
                        programTitle = schedule.programTitle,
                        recurrence = retryRecurrence,
                        recurringRuleId = retryRuleId,
                        priority = schedule.priority
                    )
                )
                recordingRunDao.insert(retriedRun)
                scheduleStartAlarmOrFail(retriedRun.id, retriedRun.scheduledStartMs)
            } else {
                val retriedRun = createPendingRun(
                    scheduleId = schedule.id,
                    request = RecordingRequest(
                        providerId = schedule.providerId,
                        channelId = schedule.channelId,
                        channelName = schedule.channelName,
                        streamUrl = schedule.streamUrl,
                        scheduledStartMs = retryStartMs,
                        scheduledEndMs = retryEndMs,
                        programTitle = schedule.programTitle,
                        recurrence = retryRecurrence,
                        recurringRuleId = retryRuleId,
                        priority = schedule.priority
                    )
                )
                recordingRunDao.insert(retriedRun)
                scheduleStartAlarmOrFail(retriedRun.id, retriedRun.scheduledStartMs)
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error -> Result.error(error.message ?: "Failed to retry recording", error) }
        )
    }

    override suspend fun setScheduleEnabled(recordingId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (enabled && !alarmScheduler.canScheduleExactAlarms()) {
            return@withContext Result.error(RecordingAlarmScheduler.EXACT_ALARM_PERMISSION_MESSAGE)
        }
        val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
        val schedule = recordingScheduleDao.getById(run.scheduleId) ?: return@withContext Result.error("Recording schedule not found")
        val now = System.currentTimeMillis()
        recordingScheduleDao.update(schedule.copy(enabled = enabled, updatedAt = now))
        recordingRunDao.update(run.copy(scheduleEnabled = enabled, updatedAt = now))
        if (enabled && run.status == RecordingStatus.SCHEDULED) {
            scheduleStartAlarmOrFail(run.id, run.scheduledStartMs)
        } else {
            alarmScheduler.cancel(run.id)
        }
        if (!enabled) {
            run.recurringRuleId?.let { ruleId ->
                recordingRunDao.getScheduledByRecurringRuleId(ruleId).forEach { pendingRun ->
                    if (pendingRun.id != recordingId) {
                        alarmScheduler.cancel(pendingRun.id)
                        recordingRunDao.update(
                            pendingRun.copy(scheduleEnabled = false, updatedAt = now)
                        )
                    }
                }
            }
        }
        Result.success(Unit)
    }

    override suspend fun updateStorageConfig(config: RecordingStorageConfig): Result<RecordingStorageState> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val existing = recordingStorageDao.get()
            val (outputDirectory, availableBytes, isWritable) = if (!config.smbUrl.isNullOrBlank()) {
                Triple(
                    config.smbUrl,
                    null,
                    existing?.takeIf { it.smbUrl == config.smbUrl }?.isWritable == true
                )
            } else {
                resolveStorageDetails(context, config.treeUri, config.localDirectory)
            }
            val entity = config.toEntity(existing, outputDirectory, availableBytes, isWritable)
            recordingStorageDao.upsert(entity)
            reconcileRecordingState()
            entity.toDomain(credentialCrypto)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error -> Result.error(error.message ?: "Failed to update recording storage", error) }
        )
    }

    override suspend fun updateSmbStorageConfig(config: RecordingSmbStorageConfig): Result<RecordingStorageState> =
        withContext(Dispatchers.IO) {
            runSuspendCatching {
                val url = SmbRecordingStorage.normalizeDirectoryUrl(config.url)
                val existing = recordingStorageDao.get()
                val previousCredentials = existing
                    ?.takeIf { it.smbUrl == url }
                    ?.let { decodeSmbCredentials(it.smbCredentialsEncrypted, credentialCrypto) }
                val credentials = SmbRecordingCredentials(
                    username = config.username.trim(),
                    password = config.password ?: previousCredentials
                        ?.takeIf { it.username == config.username.trim() }
                        ?.password
                        .orEmpty()
                )
                SmbRecordingStorage.validateWritable(url, credentials)

                val storage = RecordingStorageEntity(
                    id = existing?.id ?: 1L,
                    treeUri = null,
                    smbUrl = url,
                    smbCredentialsEncrypted = credentials.encodeEncrypted(credentialCrypto),
                    displayName = "SMB · ${android.net.Uri.parse(url).host.orEmpty()}",
                    outputDirectory = url,
                    availableBytes = null,
                    isWritable = true,
                    fileNamePattern = existing?.fileNamePattern ?: "ChannelName_yyyy-MM-dd_HH-mm_ProgramTitle.ts",
                    retentionDays = existing?.retentionDays,
                    maxSimultaneousRecordings = existing?.maxSimultaneousRecordings ?: 2,
                    updatedAt = System.currentTimeMillis()
                )
                recordingStorageDao.upsert(storage)
                reconcileRecordingState()
                storage.toDomain(credentialCrypto)
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { error -> Result.error(error.message ?: "Failed to connect to the SMB recording folder", error) }
            )
        }

    override suspend fun reconcileRecordingState(): RecordingReconciliationResult =
        withContext(Dispatchers.IO) {
        val rowFailures = mutableListOf<RecordingReconciliationRowFailure>()
        var rowsInspected = 0
        var rowsRepaired = 0
        try {
            ensureStorageState()
            pruneExpiredRecordings()
            val scheduledRuns = recordingRunDao.getAlarmManagedScheduledRuns()
                .filter { it.scheduleEnabled && it.status == RecordingStatus.SCHEDULED }
            val canScheduleExactAlarms = alarmScheduler.canScheduleExactAlarms()
            scheduledRuns.forEach { run ->
                rowsInspected++
                try {
                    when {
                        !canScheduleExactAlarms -> {
                            recordingRunDao.setExactAlarmArmed(run.id, false)
                            rowsRepaired++
                        }
                        run.scheduledEndMs <= System.currentTimeMillis() -> {
                            quarantineReconciliationRow(
                                run,
                                "Recording window expired before capture started."
                            )
                            rowsRepaired++
                        }
                        else -> when (val result = alarmScheduler.scheduleStart(run.id, run.scheduledStartMs)) {
                            is Result.Success -> {
                                recordingRunDao.setExactAlarmArmed(run.id, true)
                                rowsRepaired++
                            }
                            is Result.Error -> {
                                result.exception
                                    ?.takeIf(::isTransientRecordingReconciliationFailure)
                                    ?.let { throw it }
                                val reason = result.message.ifBlank {
                                    "Recording alarm could not be restored."
                                }
                                quarantineReconciliationRow(run, reason)
                                rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                                rowsRepaired++
                            }
                            Result.Loading -> {
                                val reason = "Recording alarm restore returned a non-terminal result."
                                quarantineReconciliationRow(run, reason)
                                rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                                rowsRepaired++
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isTransientRecordingReconciliationFailure(error)) throw error
                    val reason = error.message?.takeIf(String::isNotBlank)
                        ?: "Recording row could not be reconciled."
                    quarantineReconciliationRow(run, reason)
                    rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                    rowsRepaired++
                }
            }
            recordingRunDao.getRecordingRuns().forEach { run ->
                rowsInspected++
                try {
                    if (run.scheduledEndMs <= System.currentTimeMillis()) {
                        when (val result = stopRecording(run.id)) {
                            is Result.Success -> rowsRepaired++
                            is Result.Error -> {
                                result.exception
                                    ?.takeIf(::isTransientRecordingReconciliationFailure)
                                    ?.let { throw it }
                                val reason = result.message.ifBlank {
                                    "Expired recording could not be stopped."
                                }
                                quarantineReconciliationRow(run, reason)
                                rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                                rowsRepaired++
                            }
                            Result.Loading -> {
                                val reason = "Recording stop returned a non-terminal result."
                                quarantineReconciliationRow(run, reason)
                                rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                                rowsRepaired++
                            }
                        }
                    } else if (!isActiveJob(run.id)) {
                        markInterruptedRun(run)
                        rowsRepaired++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isTransientRecordingReconciliationFailure(error)) throw error
                    val reason = error.message?.takeIf(String::isNotBlank)
                        ?: "Recording row could not be reconciled."
                    quarantineReconciliationRow(run, reason)
                    rowFailures += RecordingReconciliationRowFailure(run.id, reason)
                    rowsRepaired++
                }
            }
            val summary = RecordingReconciliationSummary(
                rowsInspected = rowsInspected,
                rowsRepaired = rowsRepaired,
                rowsQuarantined = rowFailures.size
            )
            if (rowFailures.isEmpty()) {
                RecordingReconciliationResult.Complete(summary)
            } else {
                RecordingReconciliationResult.Partial(summary, rowFailures)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.takeIf(String::isNotBlank)
                ?: "Failed to reconcile recording state."
            if (isTransientRecordingReconciliationFailure(error)) {
                RecordingReconciliationResult.TransientFailure(message)
            } else {
                RecordingReconciliationResult.PermanentFailure(message)
            }
        }
    }

    override suspend fun promoteScheduledRecording(recordingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val runToStart = recordingMutex.withLock {
            val run = recordingRunDao.getById(recordingId) ?: return@withContext Result.error("Recording not found")
            when (run.status) {
                RecordingStatus.RECORDING -> run
                RecordingStatus.SCHEDULED -> {
                    val source = resolveRecordableSource(run.providerId, run.channelId, run.streamUrl)
                    val storage = ensureStorageStateSync()
                    if (!storage.isWritable) {
                        return@withContext Result.error("Recording storage is not writable.")
                    }
                    val availableBytes = storage.availableBytes
                    if (availableBytes != null && availableBytes < MIN_FREE_SPACE_BYTES) {
                        markRunFailed(run.id, "Insufficient disk space (${availableBytes / 1_048_576} MB free, ${MIN_FREE_SPACE_BYTES / 1_048_576} MB required).", RecordingFailureCategory.STORAGE)
                        return@withContext Result.error("Not enough disk space to start recording.")
                    }
                    if (preferencesRepository.recordingWifiOnly.first() && !isOnWifi()) {
                        markRunFailed(run.id, "Recording requires Wi-Fi but device is not on Wi-Fi.", RecordingFailureCategory.NETWORK)
                        return@withContext Result.error("Recording requires Wi-Fi connection.")
                    }
                    val outputTarget = if (!run.outputUri.isNullOrBlank() || !run.outputDisplayPath.isNullOrBlank()) {
                        existingOutputTarget(run)
                    } else {
                        createOutputTarget(
                            context = context,
                            storage = storage,
                            fileName = sanitizeRecordingFileName(run.channelName, run.programTitle, run.scheduledStartMs, storage.fileNamePattern),
                            credentialCrypto = credentialCrypto
                        )
                    }
                    val (outputUri, outputDisplayPath) = outputTarget.asPersistenceValues()
                    val now = System.currentTimeMillis()
                    val updatedRun = run.copy(
                        status = RecordingStatus.RECORDING,
                        sourceType = source.sourceType,
                        resolvedUrl = source.url,
                        headersJson = headersToJson(source.headers),
                        userAgent = source.userAgent,
                        expirationTime = source.expirationTime,
                        providerLabel = source.providerLabel,
                        outputUri = outputUri ?: run.outputUri,
                        outputDisplayPath = outputDisplayPath ?: run.outputDisplayPath,
                        outputCredentialsEncrypted = outputTarget.encryptedSmbCredentials(credentialCrypto)
                            ?: run.outputCredentialsEncrypted,
                        startedAtMs = now,
                        updatedAt = now,
                        alarmStopAtMs = run.scheduledEndMs
                    )
                    recordingRunDao.update(updatedRun)
                    when (val alarmResult = alarmScheduler.scheduleStop(updatedRun.id, updatedRun.scheduledEndMs)) {
                        is Result.Error -> {
                            markRunFailed(updatedRun.id, alarmResult.message, RecordingFailureCategory.UNKNOWN)
                            return@withContext Result.error(alarmResult.message, alarmResult.exception)
                        }
                        else -> Unit
                    }
                    spawnNextRecurringRunIfNeeded(updatedRun)
                    updatedRun
                }
                else -> return@withContext Result.error("Recording is no longer active.")
            }
        }
        startCaptureOrFail(runToStart)
    }

    internal suspend fun onCaptureFinished(recordingId: String) {
        val remaining = observeActiveRecordingCountSync().coerceAtLeast(0)
        if (remaining == 0 && recordingId !in foregroundServiceTimeoutRecordingIds) {
            recordingServiceLauncher.stopIfIdle(context)
        }
    }

    private suspend fun startCapture(run: RecordingRunEntity): Result<Unit> {
        if (isActiveJob(run.id)) return Result.success(Unit)
        val target = existingOutputTarget(run)
        val source = ResolvedRecordingSource(
            url = run.resolvedUrl ?: return Result.error("Recording stream URL could not be resolved."),
            sourceType = run.sourceType,
            headers = headersFromJson(run.headersJson),
            userAgent = run.userAgent,
            expirationTime = run.expirationTime,
            providerLabel = run.providerLabel
        )
        val engine = when (source.sourceType) {
            RecordingSourceType.HLS -> hlsLiveCaptureEngine
            RecordingSourceType.TS,
            RecordingSourceType.UNKNOWN -> tsPassThroughCaptureEngine
            RecordingSourceType.DASH -> return Result.error("DASH live recording is not supported yet.")
        }
        val maxVideoHeight = resolveMaxVideoHeightForCurrentNetwork()
        lateinit var activeCapture: ActiveCapture
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.capture(
                    source = source,
                    outputTarget = target,
                    contentResolver = context.contentResolver,
                    scheduledEndMs = run.scheduledEndMs,
                    onProgress = { progress -> updateRunProgress(run.id, progress) },
                    maxVideoHeight = maxVideoHeight
                )
                completeRun(run.id)
            } catch (cancelled: CancellationException) {
                Log.i("RecordingManager", "Capture cancelled for ${run.id}")
                throw cancelled
            } catch (unsupported: UnsupportedRecordingException) {
                markRunFailed(run.id, unsupported.message ?: "Recording format is unsupported.", unsupported.category)
            } catch (error: Throwable) {
                markRunFailed(run.id, error.message ?: "Recording failed.", inferFailureCategory(error))
            } finally {
                removeActiveJob(run.id, activeCapture)
                onCaptureFinished(run.id)
            }
        }
        activeCapture = ActiveCapture(job)
        registerActiveJob(run.id, activeCapture)
        job.start()
        return Result.success(Unit)
    }

    private suspend fun migrateLegacyStateIfNeeded() {
        if (!legacyStateFile.exists()) return
        val hasExistingRuns = runSuspendCatching {
            recordingRunDao.getByStatus(RecordingStatus.SCHEDULED).isNotEmpty() || recordingRunDao.getRecordingRuns().isNotEmpty()
        }.getOrDefault(false)
        if (hasExistingRuns) return
        val listType = object : TypeToken<List<RecordingItem>>() {}.type
        val legacyItems = runCatching {
            FileInputStream(legacyStateFile).bufferedReader().use { reader ->
                gson.fromJson<List<RecordingItem>>(reader, listType).orEmpty()
            }
        }.getOrDefault(emptyList())
        legacyItems.forEach { item ->
            val scheduleId = recordingScheduleDao.insert(
                RecordingScheduleEntity(
                    providerId = item.providerId,
                    channelId = item.channelId,
                    channelName = item.channelName,
                    streamUrl = item.streamUrl,
                    programTitle = item.programTitle,
                    requestedStartMs = item.scheduledStartMs,
                    requestedEndMs = item.scheduledEndMs,
                    recurrence = item.recurrence,
                    recurringRuleId = item.recurringRuleId,
                    enabled = item.scheduleEnabled,
                    isManual = item.recurrence == RecordingRecurrence.NONE && item.status == RecordingStatus.RECORDING,
                    priority = item.priority
                )
            )
            recordingRunDao.insert(
                RecordingRunEntity(
                    id = item.id,
                    scheduleId = scheduleId,
                    providerId = item.providerId,
                    channelId = item.channelId,
                    channelName = item.channelName,
                    streamUrl = item.streamUrl,
                    programTitle = item.programTitle,
                    scheduledStartMs = item.scheduledStartMs,
                    scheduledEndMs = item.scheduledEndMs,
                    recurrence = item.recurrence,
                    recurringRuleId = item.recurringRuleId,
                    status = if (item.status == RecordingStatus.RECORDING) RecordingStatus.FAILED else item.status,
                    sourceType = item.sourceType,
                    outputUri = item.outputUri,
                    outputDisplayPath = item.outputDisplayPath ?: item.outputPath,
                    bytesWritten = item.bytesWritten,
                    averageThroughputBytesPerSecond = item.averageThroughputBytesPerSecond,
                    retryCount = item.retryCount,
                    lastProgressAtMs = item.lastProgressAtMs,
                    failureCategory = if (item.status == RecordingStatus.RECORDING) RecordingFailureCategory.UNKNOWN else item.failureCategory,
                    failureReason = if (item.status == RecordingStatus.RECORDING) "Recording was interrupted during migration." else item.failureReason,
                    terminalAtMs = if (item.status == RecordingStatus.RECORDING) System.currentTimeMillis() else item.terminalAtMs,
                    startedAtMs = item.scheduledStartMs,
                    endedAtMs = if (item.status == RecordingStatus.RECORDING) System.currentTimeMillis() else item.terminalAtMs,
                    scheduleEnabled = item.scheduleEnabled,
                    priority = item.priority
                )
            )
        }
        legacyStateFile.delete()
    }

    private suspend fun ensureStorageState() {
        ensureStorageStateSync()
    }

    private suspend fun ensureStorageStateSync(): RecordingStorageEntity {
        val existing = recordingStorageDao.get()
        if (existing != null) {
            // Preserve an explicitly chosen local path (e.g. a USB folder) across refreshes; without
            // passing it back in, a null treeUri would reset the destination to internal default.
            val (outputDirectory, availableBytes, isWritable) = if (!existing.smbUrl.isNullOrBlank()) {
                Triple(existing.smbUrl, null, existing.isWritable)
            } else {
                val localDirectory = existing.outputDirectory?.takeIf { existing.treeUri.isNullOrBlank() }
                resolveStorageDetails(context, existing.treeUri, localDirectory)
            }
            val refreshed = existing.copy(
                outputDirectory = outputDirectory,
                availableBytes = availableBytes,
                isWritable = isWritable,
                updatedAt = System.currentTimeMillis()
            )
            recordingStorageDao.upsert(refreshed)
            return refreshed
        }
        val (outputDirectory, availableBytes, isWritable) = resolveStorageDetails(context, null)
        return RecordingStorageEntity(
            outputDirectory = outputDirectory,
            availableBytes = availableBytes,
            isWritable = isWritable
        ).also { recordingStorageDao.upsert(it) }
    }

    private suspend fun pruneExpiredRecordings() {
        val storage = recordingStorageDao.get() ?: return
        val retentionDays = storage.retentionDays ?: return
        if (retentionDays <= 0) return
        val thresholdMs = System.currentTimeMillis() - retentionDays.toLong() * 86_400_000L
        val expired = recordingRunDao.getExpiredRuns(thresholdMs)
        expired.forEach { run ->
            deleteOutputTarget(
                context,
                run.outputUri,
                run.outputDisplayPath,
                run.outputCredentialsEncrypted,
                credentialCrypto
            )
            recordingRunDao.delete(run.id)
        }
        if (expired.isNotEmpty()) {
            Log.i(TAG, "Pruned ${expired.size} recording(s) older than $retentionDays days")
        }
    }

    private suspend fun resolveRecordableSource(providerId: Long, channelId: Long, streamUrl: String): ResolvedRecordingSource {
        val source = recordingSourceResolver.resolveLiveSource(providerId, channelId, streamUrl)
        return when (source.sourceType) {
            RecordingSourceType.DASH -> throw UnsupportedRecordingException("DASH live recording is not supported yet.", RecordingFailureCategory.FORMAT_UNSUPPORTED)
            else -> source
        }
    }

    private suspend fun updateRunProgress(recordingId: String, progress: CaptureProgress) {
        recordingRunDao.updateProgress(
            id = recordingId,
            bytesWritten = progress.bytesWritten,
            averageThroughputBytesPerSecond = progress.averageThroughputBytesPerSecond,
            retryCount = progress.retryCount,
            lastProgressAtMs = progress.lastProgressAtMs,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun completeRun(recordingId: String) {
        val run = recordingRunDao.getById(recordingId) ?: return
        val now = System.currentTimeMillis()
        recordingRunDao.update(
            run.copy(
                status = RecordingStatus.COMPLETED,
                terminalAtMs = now,
                endedAtMs = now,
                updatedAt = now
            )
        )
        alarmScheduler.cancel(recordingId)
    }

    private suspend fun startCaptureOrFail(run: RecordingRunEntity): Result<Unit> =
        when (val startResult = startCapture(run)) {
            is Result.Success -> Result.success(Unit)
            is Result.Error -> {
                markRunFailed(
                    run.id,
                    startResult.message ?: "Capture failed to start.",
                    inferFailureCategory(startResult.exception)
                )
                Result.error(startResult.message ?: "Capture failed to start.", startResult.exception)
            }
            Result.Loading -> Result.success(Unit)
        }

    private suspend fun markRunFailed(recordingId: String, reason: String, category: RecordingFailureCategory) {
        val run = recordingRunDao.getById(recordingId) ?: return
        if (run.bytesWritten == 0L) {
            deleteOutputTarget(
                context,
                run.outputUri,
                run.outputDisplayPath,
                run.outputCredentialsEncrypted,
                credentialCrypto
            )
        }
        val now = System.currentTimeMillis()
        recordingRunDao.update(
            run.copy(
                status = RecordingStatus.FAILED,
                failureReason = reason,
                failureCategory = category,
                terminalAtMs = now,
                endedAtMs = now,
                updatedAt = now
            )
        )
        alarmScheduler.cancel(recordingId)
        postFailureNotification(run.channelName, run.programTitle, reason)
    }

    private fun postFailureNotification(channelName: String, programTitle: String?, reason: String) {
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                FAILURE_NOTIFICATION_CHANNEL_ID,
                "Recording Failures",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts when a scheduled recording fails" }
            notificationManager.createNotificationChannel(channel)
        }
        val title = programTitle?.let { "Recording failed: $it" } ?: "Recording failed: $channelName"
        val notification = androidx.core.app.NotificationCompat.Builder(context, FAILURE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(FAILURE_NOTIFICATION_ID_BASE + channelName.hashCode(), notification)
    }

    private suspend fun markInterruptedRun(run: RecordingRunEntity) {
        markRunFailed(
            recordingId = run.id,
            reason = "Recording was interrupted after the app process stopped. Capture was not resumed to avoid overwriting the existing recording.",
            category = RecordingFailureCategory.UNKNOWN
        )
    }

    private suspend fun quarantineReconciliationRow(run: RecordingRunEntity, reason: String) {
        markRunFailed(
            recordingId = run.id,
            reason = "Recording reconciliation quarantined this row: $reason",
            category = RecordingFailureCategory.UNKNOWN
        )
    }

    private suspend fun persistManualRun(
        request: RecordingRequest,
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget
    ): RecordingRunEntity = recordingMutex.withLock {
        validateRecordingWindowOrThrow(request.scheduledStartMs, request.scheduledEndMs, request.providerId)
        transactionRunner.inTransaction {
            val scheduleId = recordingScheduleDao.insert(
                buildScheduleEntity(
                    request = request,
                    recurrence = RecordingRecurrence.NONE,
                    recurringRuleId = null,
                    isManual = true
                )
            )
            val run = createManualRun(scheduleId, request, source, outputTarget)
            recordingRunDao.insert(run)
            run
        }
    }

    private suspend fun persistScheduledRun(
        request: RecordingRequest,
        recurringRuleId: String?
    ): RecordingRunEntity = recordingMutex.withLock {
        validateRecordingWindowOrThrow(request.scheduledStartMs, request.scheduledEndMs, request.providerId)
        transactionRunner.inTransaction {
            val scheduleId = recordingScheduleDao.insert(
                buildScheduleEntity(
                    request = request,
                    recurrence = request.recurrence,
                    recurringRuleId = recurringRuleId,
                    isManual = false
                )
            )
            val run = createPendingRun(
                scheduleId = scheduleId,
                request = request.copy(recurringRuleId = recurringRuleId)
            )
            recordingRunDao.insert(run)
            run
        }
    }

    private fun buildScheduleEntity(
        request: RecordingRequest,
        recurrence: RecordingRecurrence,
        recurringRuleId: String?,
        isManual: Boolean
    ): RecordingScheduleEntity = RecordingScheduleEntity(
        providerId = request.providerId,
        channelId = request.channelId,
        channelName = request.channelName,
        streamUrl = request.streamUrl,
        programTitle = request.programTitle,
        requestedStartMs = request.scheduledStartMs,
        requestedEndMs = request.scheduledEndMs,
        recurrence = recurrence,
        recurringRuleId = recurringRuleId,
        enabled = true,
        isManual = isManual,
        priority = request.priority
    )

    private fun createManualRun(
        scheduleId: Long,
        request: RecordingRequest,
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget
    ): RecordingRunEntity {
        val (outputUri, outputDisplayPath) = outputTarget.asPersistenceValues()
        val outputCredentialsEncrypted = outputTarget.encryptedSmbCredentials(credentialCrypto)
        val now = System.currentTimeMillis()
        return RecordingRunEntity(
            id = UUID.randomUUID().toString(),
            scheduleId = scheduleId,
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            programTitle = request.programTitle,
            scheduledStartMs = request.scheduledStartMs,
            scheduledEndMs = request.scheduledEndMs,
            recurrence = RecordingRecurrence.NONE,
            status = RecordingStatus.RECORDING,
            sourceType = source.sourceType,
            resolvedUrl = source.url,
            headersJson = headersToJson(source.headers),
            userAgent = source.userAgent,
            expirationTime = source.expirationTime,
            providerLabel = source.providerLabel,
            outputUri = outputUri,
            outputDisplayPath = outputDisplayPath,
            outputCredentialsEncrypted = outputCredentialsEncrypted,
            startedAtMs = now,
            scheduleEnabled = true,
            priority = request.priority,
            alarmStopAtMs = request.scheduledEndMs,
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun createPendingRun(scheduleId: Long, request: RecordingRequest): RecordingRunEntity {
        val now = System.currentTimeMillis()
        val paddingBeforeMs = if (request.paddingBeforeMs > 0) {
            request.paddingBeforeMs
        } else {
            preferencesRepository.recordingPaddingBeforeMinutes.first().toLong() * 60_000L
        }
        val paddingAfterMs = if (request.paddingAfterMs > 0) {
            request.paddingAfterMs
        } else {
            preferencesRepository.recordingPaddingAfterMinutes.first().toLong() * 60_000L
        }
        val effectiveStart = (request.scheduledStartMs - paddingBeforeMs).coerceAtLeast(0L)
        val effectiveEnd = request.scheduledEndMs + paddingAfterMs
        return RecordingRunEntity(
            id = UUID.randomUUID().toString(),
            scheduleId = scheduleId,
            providerId = request.providerId,
            channelId = request.channelId,
            channelName = request.channelName,
            streamUrl = request.streamUrl,
            programTitle = request.programTitle,
            scheduledStartMs = effectiveStart,
            scheduledEndMs = effectiveEnd,
            recurrence = request.recurrence,
            recurringRuleId = request.recurringRuleId,
            status = RecordingStatus.SCHEDULED,
            scheduleEnabled = true,
            priority = request.priority,
            alarmStartAtMs = effectiveStart,
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun spawnNextRecurringRunIfNeeded(run: RecordingRunEntity) {
        if (run.recurrence == RecordingRecurrence.NONE || run.recurringRuleId.isNullOrBlank()) return
        val scheduledRuns = recordingRunDao.getByStatus(RecordingStatus.SCHEDULED)
        val activeRuns = (scheduledRuns + recordingRunDao.getRecordingRuns()).distinctBy { it.id }
        val lookAheadLimit = when (run.recurrence) {
            RecordingRecurrence.DAILY -> 366
            RecordingRecurrence.WEEKLY -> 104
            RecordingRecurrence.NONE -> 0
        }

        for (occurrenceIndex in 1..lookAheadLimit) {
            val nextWindow = nextRecurringWindow(run, occurrenceIndex) ?: return
            val nextStart = nextWindow.first
            val nextEnd = nextWindow.second
            val overlappingPending = scheduledRuns.any {
                it.recurringRuleId == run.recurringRuleId && it.scheduledStartMs == nextStart
            }
            if (overlappingPending) return

            val conflict = activeRuns.findRecordingRunConflict(
                candidateStartMs = nextStart,
                candidateEndMs = nextEnd,
                statuses = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING),
                ignoredRunId = run.id
            )
            if (conflict != null) {
                val conflictTitle = conflict.programTitle ?: conflict.channelName
                val skippedRun = run.toConflictFailure(
                    conflictStartMs = nextStart,
                    conflictEndMs = nextEnd,
                    reason = "Skipped recurring occurrence because it conflicts with '$conflictTitle'."
                )
                recordingRunDao.insert(skippedRun)
                continue
            }

            val now = System.currentTimeMillis()
            val nextRun = run.copy(
                id = UUID.randomUUID().toString(),
                status = RecordingStatus.SCHEDULED,
                scheduledStartMs = nextStart,
                scheduledEndMs = nextEnd,
                sourceType = RecordingSourceType.UNKNOWN,
                resolvedUrl = null,
                headersJson = "{}",
                userAgent = null,
                expirationTime = null,
                providerLabel = null,
                outputUri = null,
                outputDisplayPath = null,
                outputCredentialsEncrypted = null,
                bytesWritten = 0L,
                averageThroughputBytesPerSecond = 0L,
                retryCount = 0,
                lastProgressAtMs = null,
                failureCategory = RecordingFailureCategory.NONE,
                failureReason = null,
                terminalAtMs = null,
                startedAtMs = null,
                endedAtMs = null,
                alarmStartAtMs = nextStart,
                alarmStopAtMs = null,
                createdAt = now,
                updatedAt = now
            )
            recordingRunDao.insert(nextRun)
            when (val alarmResult = alarmScheduler.scheduleStart(nextRun.id, nextRun.scheduledStartMs)) {
                is Result.Error -> markRunFailed(nextRun.id, alarmResult.message, RecordingFailureCategory.UNKNOWN)
                else -> Unit
            }
            return
        }

        Log.w(TAG, "Unable to find a non-conflicting future slot for recurring recording ${run.id}")
    }

    private fun nextRecurringWindow(run: RecordingRunEntity, occurrencesAhead: Int = 1): Pair<Long, Long>? {
        if (run.scheduledEndMs <= run.scheduledStartMs) return null

        val zoneId = ZoneId.systemDefault()
        val startZoned = Instant.ofEpochMilli(run.scheduledStartMs).atZone(zoneId)
        val nextStartZoned = when (run.recurrence) {
            RecordingRecurrence.NONE -> return null
            RecordingRecurrence.DAILY -> startZoned.plusDays(occurrencesAhead.toLong())
            RecordingRecurrence.WEEKLY -> startZoned.plusWeeks(occurrencesAhead.toLong())
        }
        val recordingDuration = Duration.ofMillis(run.scheduledEndMs - run.scheduledStartMs)
        val nextStartMs = nextStartZoned.toInstant().toEpochMilli()
        val nextEndMs = nextStartZoned.plus(recordingDuration).toInstant().toEpochMilli()
        return nextStartMs to nextEndMs
    }

    private suspend fun validateRecordingWindow(startMs: Long, endMs: Long, providerId: Long): String? {
        val storage = ensureStorageStateSync()
        val overlapping = recordingRunDao.getOverlapping(startMs, endMs)
            .filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
            .filter { it.scheduleEnabled }
        if (overlapping.size >= storage.maxSimultaneousRecordings) {
            val title = overlapping.firstOrNull()?.programTitle ?: overlapping.firstOrNull()?.channelName.orEmpty()
            return "Recording conflicts with an existing active recording for $title."
        }
        val provider = providerDao.getById(providerId)
            ?: return "Recording provider no longer exists."
        val providerMaxConnections = providerSnapshotDao?.getRuntime(providerId)?.maxConnections ?: 1
        if (overlapping.count { it.providerId == providerId } >= providerMaxConnections) {
            return "Recording exceeds the provider connection limit for this account."
        }
        return null
    }

    private suspend fun validateRecordingWindowOrThrow(startMs: Long, endMs: Long, providerId: Long) {
        validateRecordingWindow(startMs, endMs, providerId)?.let { message ->
            throw IllegalStateException(message)
        }
    }

    private fun ensureExactRecordingAlarmsAvailableOrThrow() {
        if (!alarmScheduler.canScheduleExactAlarms()) {
            throw IllegalStateException(RecordingAlarmScheduler.EXACT_ALARM_PERMISSION_MESSAGE)
        }
    }

    private suspend fun scheduleStartAlarmOrFail(recordingId: String, whenMs: Long) {
        when (val result = alarmScheduler.scheduleStart(recordingId, whenMs)) {
            is Result.Success -> Unit
            is Result.Error -> {
                markRunFailed(recordingId, result.message, RecordingFailureCategory.UNKNOWN)
                throw IllegalStateException(result.message, result.exception)
            }
            Result.Loading -> Unit
        }
    }

    private suspend fun scheduleStopAlarmOrFail(recordingId: String, whenMs: Long) {
        when (val result = alarmScheduler.scheduleStop(recordingId, whenMs)) {
            is Result.Success -> Unit
            is Result.Error -> {
                markRunFailed(recordingId, result.message, RecordingFailureCategory.UNKNOWN)
                throw IllegalStateException(result.message, result.exception)
            }
            Result.Loading -> Unit
        }
    }

    private suspend fun registerActiveJob(id: String, capture: ActiveCapture) {
        activeJobsMutex.withLock { activeJobs[id] = capture }
    }

    private suspend fun removeActiveJob(id: String, expected: ActiveCapture? = null): ActiveCapture? =
        activeJobsMutex.withLock {
            val current = activeJobs[id] ?: return@withLock null
            if (expected != null && current !== expected) return@withLock null
            activeJobs.remove(id)
        }

    private suspend fun cancelActiveJob(id: String) {
        val capture = activeJobsMutex.withLock { activeJobs[id] }
        capture?.let {
            it.cancelAndJoin()
            removeActiveJob(id, it)
        }
    }

    private suspend fun isActiveJob(id: String): Boolean =
        activeJobsMutex.withLock { activeJobs[id]?.isActive == true }

    private suspend fun observeActiveRecordingCountSync(): Int =
        activeJobsMutex.withLock { activeJobs.values.count { it.isActive } }

    private suspend fun existingOutputTarget(run: RecordingRunEntity): RecordingOutputTarget = when {
        run.outputUri?.startsWith("smb://", ignoreCase = true) == true -> {
            val credentials = decodeSmbCredentials(run.outputCredentialsEncrypted, credentialCrypto)
                ?: recordingStorageDao.get()?.let {
                    decodeSmbCredentials(it.smbCredentialsEncrypted, credentialCrypto)
                }
                ?: throw IllegalStateException("SMB credentials are unavailable. Reconfigure the SMB recording folder.")
            RecordingOutputTarget.SmbTarget(run.outputUri, credentials, run.outputDisplayPath)
        }
        !run.outputUri.isNullOrBlank() -> RecordingOutputTarget.DocumentTarget(
            uri = android.net.Uri.parse(run.outputUri),
            displayPath = run.outputDisplayPath
        )
        !run.outputDisplayPath.isNullOrBlank() -> RecordingOutputTarget.FileTarget(File(run.outputDisplayPath))
        else -> throw IllegalStateException("Recording output target is unavailable.")
    }

    private fun RecordingRunEntity.toStandaloneDomain(): RecordingItem = RecordingItem(
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
        priority = priority,
        failureReason = failureReason,
        terminalAtMs = terminalAtMs,
        exactAlarmArmed = exactAlarmArmed
    )

}
