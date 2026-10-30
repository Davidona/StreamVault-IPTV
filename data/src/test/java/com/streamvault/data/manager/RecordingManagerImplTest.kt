package com.streamvault.data.manager

import android.app.NotificationManager
import android.content.ContentResolver
import android.database.sqlite.SQLiteException
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.RecordingScheduleDao
import com.streamvault.data.local.dao.RecordingStorageDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.RecordingRunEntity
import com.streamvault.data.local.entity.RecordingStorageEntity
import com.streamvault.data.manager.recording.HlsLiveCaptureEngine
import com.streamvault.data.manager.recording.RecordingAlarmScheduler
import com.streamvault.data.manager.recording.RecordingServiceLauncher
import com.streamvault.data.manager.recording.RecordingSourceResolver
import com.streamvault.data.manager.recording.ResolvedRecordingSource
import com.streamvault.data.manager.recording.TsPassThroughCaptureEngine
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.ExactRecordingAlarmAccessException
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingReconciliationResult
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.Result
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RecordingManagerImplTest {

    private val context: Context = mock()
    private val providerDao: ProviderDao = mock()
    private val recordingScheduleDao: RecordingScheduleDao = mock()
    private val recordingRunDao: RecordingRunDao = mock()
    private val recordingStorageDao: RecordingStorageDao = mock()
    private val recordingSourceResolver: RecordingSourceResolver = mock()
    private val tsEngine: TsPassThroughCaptureEngine = mock()
    private val hlsEngine: HlsLiveCaptureEngine = mock()
    private val alarmScheduler: RecordingAlarmScheduler = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val recordingServiceLauncher: RecordingServiceLauncher = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private val tempRoot = File("build/tmp/recording-manager-tests/${UUID.randomUUID()}").apply { mkdirs() }

    init {
        whenever(context.filesDir).thenReturn(tempRoot)
        whenever(context.getExternalFilesDir(any())).thenReturn(null)
        whenever(context.contentResolver).thenReturn(mock<ContentResolver>())
        whenever(context.getSystemService(NotificationManager::class.java)).thenReturn(null)
        whenever(preferencesRepository.recordingWifiOnly).thenReturn(flowOf(false))
        whenever(preferencesRepository.recordingPaddingBeforeMinutes).thenReturn(flowOf(0))
        whenever(preferencesRepository.recordingPaddingAfterMinutes).thenReturn(flowOf(0))
        whenever(preferencesRepository.playerWifiMaxVideoHeight).thenReturn(flowOf(null))
        whenever(preferencesRepository.playerEthernetMaxVideoHeight).thenReturn(flowOf(null))
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(true)
        whenever(alarmScheduler.scheduleStart(any(), any())).thenReturn(Result.success(Unit))
        whenever(alarmScheduler.scheduleStop(any(), any())).thenReturn(Result.success(Unit))
        runBlocking {
            whenever(recordingStorageDao.get()).thenReturn(defaultStorage())
            whenever(recordingRunDao.getAlarmManagedScheduledRuns()).thenReturn(emptyList())
            whenever(recordingRunDao.getRecordingRuns()).thenReturn(emptyList())
            whenever(recordingRunDao.getOverlapping(any(), any())).thenReturn(emptyList())
            whenever(providerDao.getById(any())).thenReturn(providerEntity())
        }
    }

    @Test
    fun `scheduleRecording validates before inserting schedule`() = runBlocking {
        val existing = scheduledRun(id = "existing")
        runBlocking {
            whenever(recordingStorageDao.get()).thenReturn(defaultStorage(maxSimultaneousRecordings = 1))
            whenever(recordingRunDao.getOverlapping(any(), any())).thenReturn(listOf(existing))
        }

        val manager = createManager()
        val result = manager.scheduleRecording(recordingRequest())

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Error::class.java)
        verify(recordingScheduleDao, never()).insert(any())
        verify(recordingRunDao, never()).insert(any())
    }

    @Test
    fun `scheduleRecording fails when exact alarms are unavailable`() = runBlocking {
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)

        val manager = createManager()
        val result = manager.scheduleRecording(recordingRequest())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        verify(recordingScheduleDao, never()).insert(any())
        verify(recordingRunDao, never()).insert(any())
    }

    @Test
    fun `manual recording rejects missing exact alarm access before resolving or starting capture`() = runBlocking {
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)

        val result = createManager().startManualRecording(recordingRequest())

        assertThat((result as Result.Error).message)
            .isEqualTo(RecordingAlarmScheduler.EXACT_ALARM_PERMISSION_MESSAGE)
        assertThat(result.exception).isInstanceOf(ExactRecordingAlarmAccessException::class.java)
        verify(recordingSourceResolver, never()).resolveLiveSource(any(), any(), any())
        verify(recordingRunDao, never()).insert(any())
        verify(recordingServiceLauncher, never()).startCapture(any(), any())
    }

    @Test
    fun `enabling a schedule without exact alarm access returns a typed error`() = runBlocking {
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)

        val result = createManager().setScheduleEnabled("recording-1", enabled = true)

        assertThat((result as Result.Error).message)
            .isEqualTo(RecordingAlarmScheduler.EXACT_ALARM_PERMISSION_MESSAGE)
        assertThat(result.exception).isInstanceOf(ExactRecordingAlarmAccessException::class.java)
        verify(recordingRunDao, never()).getById(any())
        Unit
    }

    @Test
    fun `startManualRecording routes capture through foreground service`() = runBlocking {
        val request = recordingRequest()
        whenever(recordingSourceResolver.resolveLiveSource(any(), any(), any())).thenReturn(resolvedSource())
        runBlocking {
            whenever(recordingScheduleDao.insert(any())).thenReturn(17L)
        }

        val manager = createManager()
        val result = manager.startManualRecording(request)

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        verify(recordingRunDao).insert(argThat { status == RecordingStatus.RECORDING })
        verify(alarmScheduler).scheduleStop(any(), eq(request.scheduledEndMs))
        verify(recordingServiceLauncher).startCapture(eq(context), any())
    }

    @Test
    fun `promoteScheduledRecording completes for one-shot schedule without deadlock`() {
        runBlocking {
            val run = scheduledRun(id = "scheduled-one-shot")
            whenever(recordingRunDao.getById(run.id)).thenReturn(run)
            whenever(recordingSourceResolver.resolveLiveSource(any(), any(), any())).thenReturn(resolvedSource())

            val manager = createManager()

            val result = withTimeout(1_000L) {
                manager.promoteScheduledRecording(run.id)
            }

            assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
            verify(recordingRunDao).update(argThat { id == run.id && status == RecordingStatus.RECORDING })
            verify(alarmScheduler).scheduleStop(run.id, run.scheduledEndMs)
        }
    }

    @Test
    fun `promoteScheduledRecording spawns next recurring run without deadlock`() {
        runBlocking {
            val run = scheduledRun(
                id = "scheduled-recurring",
                recurrence = RecordingRecurrence.DAILY,
                recurringRuleId = "rule-1"
            )
            whenever(recordingRunDao.getById(run.id)).thenReturn(run)
            whenever(recordingRunDao.getByStatus(RecordingStatus.SCHEDULED)).thenReturn(emptyList())
            whenever(recordingRunDao.getRecordingRuns()).thenReturn(emptyList())
            whenever(recordingSourceResolver.resolveLiveSource(any(), any(), any())).thenReturn(resolvedSource())

            val manager = createManager()

            val result = withTimeout(1_000L) {
                manager.promoteScheduledRecording(run.id)
            }

            assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
            verify(recordingRunDao).insert(any())
            verify(alarmScheduler, times(1)).scheduleStart(any(), any())
        }
    }

    @Test
    fun `reconcileRecordingState marks stale recording failed instead of restarting capture`() = runBlocking {
        val staleRun = scheduledRun(id = "stale-recording", status = RecordingStatus.RECORDING)
        runBlocking {
            whenever(recordingRunDao.getRecordingRuns()).thenReturn(listOf(staleRun))
            whenever(recordingRunDao.getById(staleRun.id)).thenReturn(staleRun)
        }

        val manager = createManager()
        val result = manager.reconcileRecordingState()

        assertThat(result).isInstanceOf(RecordingReconciliationResult.Complete::class.java)
        verify(recordingRunDao, atLeastOnce()).update(
            argThat {
                id == staleRun.id &&
                    status == RecordingStatus.FAILED &&
                    failureCategory == RecordingFailureCategory.UNKNOWN
            }
        )
        verify(recordingServiceLauncher, never()).startCapture(any(), any())
    }

    @Test
    fun `reconcileRecordingState persists unarmed schedules while exact alarms are unavailable`(): Unit = runBlocking {
        val scheduled = scheduledRun(id = "unarmed")
        whenever(alarmScheduler.canScheduleExactAlarms()).thenReturn(false)
        whenever(recordingRunDao.getAlarmManagedScheduledRuns()).thenReturn(listOf(scheduled))

        val result = createManager().reconcileRecordingState()

        assertThat(result).isInstanceOf(RecordingReconciliationResult.Complete::class.java)
        verify(recordingRunDao, atLeastOnce()).setExactAlarmArmed(eq(scheduled.id), eq(false), any())
        verify(alarmScheduler, never()).scheduleStart(any(), any())
    }

    @Test
    fun `reconcileRecordingState quarantines malformed row and reports partial outcome`(): Unit = runBlocking {
        val malformed = scheduledRun(id = "malformed-row")
        whenever(recordingRunDao.getAlarmManagedScheduledRuns()).thenReturn(listOf(malformed))
        whenever(recordingRunDao.getById(malformed.id)).thenReturn(malformed)
        whenever(alarmScheduler.scheduleStart(malformed.id, malformed.scheduledStartMs)).thenReturn(
            Result.error("Malformed legacy schedule", IllegalArgumentException("bad timestamp"))
        )

        val result = createManager().reconcileRecordingState()

        assertThat(result).isInstanceOf(RecordingReconciliationResult.Partial::class.java)
        val partial = result as RecordingReconciliationResult.Partial
        assertThat(partial.summary.rowsInspected).isEqualTo(1)
        assertThat(partial.summary.rowsQuarantined).isEqualTo(1)
        assertThat(partial.rowFailures.single().recordingId).isEqualTo(malformed.id)
        verify(recordingRunDao, atLeastOnce()).update(
            argThat {
                id == malformed.id &&
                    status == RecordingStatus.FAILED &&
                    failureReason?.contains("Malformed legacy schedule") == true
            }
        )
    }

    @Test
    fun `reconciliation failure classification separates transient infrastructure from permanent data`() {
        val lockedDatabase: SQLiteException = mock()
        val constraintFailure: SQLiteException = mock()
        whenever(lockedDatabase.message).thenReturn("database is locked")
        whenever(constraintFailure.message).thenReturn("constraint failed")

        assertThat(isTransientRecordingReconciliationFailure(IOException("offline"))).isTrue()
        assertThat(isTransientRecordingReconciliationFailure(lockedDatabase)).isTrue()
        assertThat(isTransientRecordingReconciliationFailure(constraintFailure)).isFalse()
        assertThat(isTransientRecordingReconciliationFailure(IllegalArgumentException("bad row"))).isFalse()
    }

    @Test
    fun `reconcileRecordingState propagates cancellation without terminal classification`() = runBlocking {
        val manager = createManager()
        whenever(recordingRunDao.getAlarmManagedScheduledRuns())
            .thenThrow(CancellationException("test cancellation"))

        var cancellation: CancellationException? = null
        try {
            manager.reconcileRecordingState()
        } catch (caught: CancellationException) {
            cancellation = caught
        }

        assertThat(cancellation).isNotNull()
        assertThat(cancellation?.message).isEqualTo("test cancellation")
    }

    @Test
    fun `foreground service timeout joins capture and persists a terminal reason`() = runBlocking {
        val activeRun = scheduledRun(id = "quota-timeout", status = RecordingStatus.RECORDING)
        whenever(recordingRunDao.getById(activeRun.id)).thenReturn(activeRun)

        val manager = createManager()
        val result = manager.stopRecordingForForegroundServiceTimeout(activeRun.id)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        verify(alarmScheduler).cancel(activeRun.id)
        verify(recordingRunDao).update(
            argThat {
                id == activeRun.id &&
                    status == RecordingStatus.FAILED &&
                    failureReason?.contains("foreground-service time allowance") == true &&
                    terminalAtMs != null
            }
        )
    }

    private fun createManager() = RecordingManagerImpl(
        context = context,
        gson = Gson(),
        transactionRunner = transactionRunner,
        providerDao = providerDao,
        recordingScheduleDao = recordingScheduleDao,
        recordingRunDao = recordingRunDao,
        recordingStorageDao = recordingStorageDao,
        recordingSourceResolver = recordingSourceResolver,
        tsPassThroughCaptureEngine = tsEngine,
        hlsLiveCaptureEngine = hlsEngine,
        alarmScheduler = alarmScheduler,
        preferencesRepository = preferencesRepository,
        recordingServiceLauncher = recordingServiceLauncher
    )

    private fun defaultStorage(maxSimultaneousRecordings: Int = 2) = RecordingStorageEntity(
        outputDirectory = tempRoot.absolutePath,
        availableBytes = 2_000_000_000L,
        isWritable = true,
        maxSimultaneousRecordings = maxSimultaneousRecordings
    )

    private fun recordingRequest() = RecordingRequest(
        providerId = 7L,
        channelId = 100L,
        channelName = "BBC One",
        streamUrl = "https://example.com/live.ts",
        scheduledStartMs = System.currentTimeMillis() + 60_000L,
        scheduledEndMs = System.currentTimeMillis() + 120_000L,
        programTitle = "World News"
    )

    private fun scheduledRun(
        id: String,
        status: RecordingStatus = RecordingStatus.SCHEDULED,
        recurrence: RecordingRecurrence = RecordingRecurrence.NONE,
        recurringRuleId: String? = null
    ) = RecordingRunEntity(
        id = id,
        scheduleId = 11L,
        providerId = 7L,
        channelId = 100L,
        channelName = "BBC One",
        streamUrl = "https://example.com/live.ts",
        programTitle = "World News",
        scheduledStartMs = System.currentTimeMillis() + 60_000L,
        scheduledEndMs = System.currentTimeMillis() + 120_000L,
        recurrence = recurrence,
        recurringRuleId = recurringRuleId,
        status = status,
        sourceType = RecordingSourceType.UNKNOWN,
        headersJson = "{}",
        failureCategory = RecordingFailureCategory.NONE,
        scheduleEnabled = true,
        alarmStartAtMs = System.currentTimeMillis() + 60_000L
    )

    private fun resolvedSource() = ResolvedRecordingSource(
        url = "https://example.com/live.ts",
        sourceType = RecordingSourceType.TS,
        headers = emptyMap(),
        userAgent = null,
        expirationTime = null,
        providerLabel = "Provider"
    )

    private fun providerEntity() = ProviderEntity(
        id = 7L,
        name = "Provider",
        type = ProviderType.M3U,
        serverUrl = "https://example.com",
        username = "user",
        password = "pass",
        maxConnections = 3
    )
}
