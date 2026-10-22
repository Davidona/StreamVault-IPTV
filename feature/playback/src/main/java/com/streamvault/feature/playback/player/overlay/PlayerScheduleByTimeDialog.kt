package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.browse.OnSurface
import com.streamvault.core.ui.design.browse.OnSurfaceDim
import com.streamvault.core.ui.design.browse.Primary
import com.streamvault.core.ui.design.browse.SurfaceElevated
import com.streamvault.core.ui.design.browse.SurfaceHighlight
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.core.ui.time.createTimeFormat
import com.streamvault.domain.model.RecordingRecurrence
import java.util.Date
import java.util.Locale

data class PlayerScheduleByTimeLabels(
    val title: String,
    val start: String,
    val duration: String,
    val repeat: String,
    val once: String,
    val daily: String,
    val weekly: String,
    val confirm: String,
    val cancel: String,
    val windowFormat: String,
    val durationHoursMinutes: String,
    val durationHours: String,
    val durationMinutes: String
)

private const val START_STEP_MINUTES = 30
private const val START_MAX_MINUTES = 24 * 60
private const val DURATION_STEP_MINUTES = 30
private const val DURATION_MIN_MINUTES = 30
private const val DURATION_MAX_MINUTES = 8 * 60

@Composable
fun PlayerScheduleByTimeDialog(
    channelName: String,
    now: Long,
    labels: PlayerScheduleByTimeLabels,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long, recurrence: RecordingRecurrence) -> Unit
) {
    var startOffsetMinutes by remember(now) { mutableIntStateOf(0) }
    var durationMinutes by remember(now) { mutableIntStateOf(60) }
    var recurrence by remember(now) { mutableStateOf(RecordingRecurrence.NONE) }

    val uiTimeFormat = LocalUiTimeFormat.current
    val timeFormat = remember(uiTimeFormat) { uiTimeFormat.createTimeFormat() }
    val startMs = now + startOffsetMinutes * 60_000L
    val endMs = startMs + durationMinutes * 60_000L
    val confirmFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { confirmFocusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.68f))
            )
            Surface(
                modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = labels.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface
                    )
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim
                    )
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            labels.windowFormat,
                            timeFormat.format(Date(startMs)),
                            timeFormat.format(Date(endMs))
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = Primary
                    )
                    StepperRow(
                        label = labels.start,
                        value = timeFormat.format(Date(startMs)),
                        onDecrease = {
                            startOffsetMinutes =
                                (startOffsetMinutes - START_STEP_MINUTES).coerceAtLeast(0)
                        },
                        onIncrease = {
                            startOffsetMinutes =
                                (startOffsetMinutes + START_STEP_MINUTES).coerceAtMost(START_MAX_MINUTES)
                        },
                        decreaseEnabled = startOffsetMinutes > 0,
                        increaseEnabled = startOffsetMinutes < START_MAX_MINUTES
                    )
                    StepperRow(
                        label = labels.duration,
                        value = formatDuration(durationMinutes, labels),
                        onDecrease = {
                            durationMinutes =
                                (durationMinutes - DURATION_STEP_MINUTES).coerceAtLeast(DURATION_MIN_MINUTES)
                        },
                        onIncrease = {
                            durationMinutes =
                                (durationMinutes + DURATION_STEP_MINUTES).coerceAtMost(DURATION_MAX_MINUTES)
                        },
                        decreaseEnabled = durationMinutes > DURATION_MIN_MINUTES,
                        increaseEnabled = durationMinutes < DURATION_MAX_MINUTES
                    )
                    StepperRow(
                        label = labels.repeat,
                        value = when (recurrence) {
                            RecordingRecurrence.NONE -> labels.once
                            RecordingRecurrence.DAILY -> labels.daily
                            RecordingRecurrence.WEEKLY -> labels.weekly
                        },
                        onDecrease = { recurrence = recurrence.previous() },
                        onIncrease = { recurrence = recurrence.next() },
                        decreaseEnabled = true,
                        increaseEnabled = true
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            onClick = { onConfirm(startMs, endMs, recurrence) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(confirmFocusRequester),
                            scale = ButtonDefaults.scale(focusedScale = 1f)
                        ) { Text(labels.confirm) }
                        TvButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = SurfaceHighlight,
                                contentColor = OnSurface
                            )
                        ) { Text(labels.cancel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            modifier = Modifier.widthIn(min = 120.dp)
        )
        TvButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            scale = ButtonDefaults.scale(focusedScale = 1f)
        ) { Text("\u2212") }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.widthIn(min = 120.dp)
        )
        TvButton(
            onClick = onIncrease,
            enabled = increaseEnabled,
            scale = ButtonDefaults.scale(focusedScale = 1f)
        ) { Text("+") }
    }
}

private fun formatDuration(minutes: Int, labels: PlayerScheduleByTimeLabels): String {
    val locale = Locale.getDefault()
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours <= 0 -> String.format(locale, labels.durationMinutes, remainingMinutes)
        remainingMinutes <= 0 -> String.format(locale, labels.durationHours, hours)
        else -> String.format(locale, labels.durationHoursMinutes, hours, remainingMinutes)
    }
}

private fun RecordingRecurrence.next(): RecordingRecurrence = when (this) {
    RecordingRecurrence.NONE -> RecordingRecurrence.DAILY
    RecordingRecurrence.DAILY -> RecordingRecurrence.WEEKLY
    RecordingRecurrence.WEEKLY -> RecordingRecurrence.NONE
}

private fun RecordingRecurrence.previous(): RecordingRecurrence = when (this) {
    RecordingRecurrence.NONE -> RecordingRecurrence.WEEKLY
    RecordingRecurrence.WEEKLY -> RecordingRecurrence.DAILY
    RecordingRecurrence.DAILY -> RecordingRecurrence.NONE
}
