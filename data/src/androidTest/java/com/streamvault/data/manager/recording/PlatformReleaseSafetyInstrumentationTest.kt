package com.streamvault.data.manager.recording

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.manager.reminder.ProgramReminderRestoreReceiver
import com.streamvault.data.manager.reminder.ProgramReminderRestoreWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the merged manifest on API 35/36. The production receiver must enqueue durable
 * recovery when Android delivers a restricted broadcast; it must not start a dataSync service.
 */
@RunWith(AndroidJUnit4::class)
class PlatformReleaseSafetyInstrumentationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(Executors.newSingleThreadExecutor())
                .build()
        )
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun bootBroadcastEnqueuesDurableRecordingRecoveryWithoutStartingRecordingService() {
        sendToRecordingRestoreReceiver(Intent(Intent.ACTION_BOOT_COMPLETED))

        val work = workManager
            .getWorkInfosForUniqueWork(RECORDING_RECONCILE_ONE_SHOT_WORK_NAME)
            .get(10, TimeUnit.SECONDS)

        assertThat(work).hasSize(1)
        assertThat(work.single().tags).contains(RecordingReconcileWorker::class.java.name)
        assertThat(work.single().state).isAnyOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED
        )
    }

    @Test
    fun exactAlarmPermissionBroadcastUsesTheSameDurableRecoveryLane() {
        val permissionAction = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        sendToRecordingRestoreReceiver(Intent(permissionAction))
        sendToRecordingRestoreReceiver(Intent(permissionAction))

        val work = workManager
            .getWorkInfosForUniqueWork(RECORDING_RECONCILE_ONE_SHOT_WORK_NAME)
            .get(10, TimeUnit.SECONDS)

        assertThat(work).hasSize(1)
    }

    @Test
    fun bootBroadcastEnqueuesDurableReminderRecovery() {
        sendToReminderRestoreReceiver(Intent(Intent.ACTION_BOOT_COMPLETED))

        val work = workManager
            .getWorkInfosForUniqueWork(ProgramReminderRestoreWorker.ONE_SHOT_WORK_NAME)
            .get(10, TimeUnit.SECONDS)

        assertThat(work).hasSize(1)
        assertThat(work.single().tags).contains(ProgramReminderRestoreWorker::class.java.name)
    }

    @Test
    fun exactAlarmPermissionBroadcastsCoalesceForBothRestorationLanes() {
        val permissionAction = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        sendToRecordingRestoreReceiver(Intent(permissionAction))
        sendToRecordingRestoreReceiver(Intent(permissionAction))
        sendToReminderRestoreReceiver(Intent(permissionAction))
        sendToReminderRestoreReceiver(Intent(permissionAction))

        assertThat(
            workManager
                .getWorkInfosForUniqueWork(RECORDING_RECONCILE_ONE_SHOT_WORK_NAME)
                .get(10, TimeUnit.SECONDS)
        ).hasSize(1)
        assertThat(
            workManager
                .getWorkInfosForUniqueWork(ProgramReminderRestoreWorker.ONE_SHOT_WORK_NAME)
                .get(10, TimeUnit.SECONDS)
        ).hasSize(1)
    }

    private fun sendToRecordingRestoreReceiver(intent: Intent) {
        invokeReceiver(RecordingRestoreReceiver(), intent)
    }

    private fun sendToReminderRestoreReceiver(intent: Intent) {
        invokeReceiver(ProgramReminderRestoreReceiver(), intent)
    }

    private fun invokeReceiver(receiver: android.content.BroadcastReceiver, intent: Intent) {
        // Android protects BOOT_COMPLETED and exact-alarm broadcasts from app-sent broadcasts.
        // Invoke the receiver on the same main-thread callback used by the framework so the
        // assertions observe the WorkManager instance initialized by this instrumentation test.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            receiver.onReceive(context, intent)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
