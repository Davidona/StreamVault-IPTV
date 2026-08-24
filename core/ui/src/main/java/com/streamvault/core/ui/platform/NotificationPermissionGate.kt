package com.streamvault.core.ui.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class NotificationPermissionGate(
    private val runReminderActionImpl: (() -> Unit) -> Unit,
    private val runRecordingActionImpl: (() -> Unit) -> Unit,
) {
    fun runReminderAction(action: () -> Unit) {
        runReminderActionImpl(action)
    }

    fun runRecordingAction(action: () -> Unit) {
        runRecordingActionImpl(action)
    }
}

private enum class NotificationPermissionRequestType {
    REMINDER,
    RECORDING_ALERTS,
}

@Composable
fun rememberNotificationPermissionGate(
    onNotificationsBlocked: (String) -> Unit,
    reminderBlockedMessage: String,
    recordingBlockedMessage: String,
): NotificationPermissionGate {
    val context = LocalContext.current
    var pendingReminderAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingRequestType by remember { mutableStateOf<NotificationPermissionRequestType?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val requestType = pendingRequestType
        val reminderAction = pendingReminderAction
        pendingRequestType = null
        pendingReminderAction = null
        if (granted && notificationsEnabled(context)) {
            reminderAction?.invoke()
            return@rememberLauncherForActivityResult
        }
        when (requestType) {
            NotificationPermissionRequestType.REMINDER -> onNotificationsBlocked(reminderBlockedMessage)
            NotificationPermissionRequestType.RECORDING_ALERTS -> onNotificationsBlocked(recordingBlockedMessage)
            null -> Unit
        }
    }

    return NotificationPermissionGate(
        runReminderActionImpl = { action ->
            when {
                notificationsEnabled(context) -> action()
                needsNotificationRuntimePermission(context) -> {
                    pendingReminderAction = action
                    pendingRequestType = NotificationPermissionRequestType.REMINDER
                    permissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                }
                else -> {
                    pendingReminderAction = null
                    pendingRequestType = null
                    onNotificationsBlocked(reminderBlockedMessage)
                }
            }
        },
        runRecordingActionImpl = { action ->
            when {
                needsNotificationRuntimePermission(context) -> {
                    pendingReminderAction = null
                    pendingRequestType = NotificationPermissionRequestType.RECORDING_ALERTS
                    permissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                }
                !notificationsEnabled(context) -> onNotificationsBlocked(recordingBlockedMessage)
            }
            action()
        }
    )
}

private fun needsNotificationRuntimePermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return false
    }
    return ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED
}

private fun notificationsEnabled(context: Context): Boolean {
    return !needsNotificationRuntimePermission(context) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
