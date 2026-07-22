package com.streamvault.data.manager.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.streamvault.data.manager.ProgramReminderManagerImpl
import com.streamvault.data.util.isUserUnlockedForWork
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProgramReminderRestoreReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProgramReminderRestoreReceiverEntryPoint {
        fun reminderManager(): ProgramReminderManagerImpl
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!context.isUserUnlockedForWork()) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val entryPoint = EntryPointAccessors.fromApplication(
                            appContext,
                            ProgramReminderRestoreReceiverEntryPoint::class.java
                        )
                        entryPoint.reminderManager().restoreScheduledReminders()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
