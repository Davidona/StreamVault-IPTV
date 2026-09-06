package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStatus

@Composable
internal fun RecordingMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun StatusTonePill(
    label: String,
    accent: Color
) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

internal fun recordingDisplayTitle(item: RecordingItem): String {
    val title = item.programTitle?.trim().orEmpty()
    return if (title.isNotBlank()) title else item.channelName
}

internal fun recordingDisplaySubtitle(item: RecordingItem): String? {
    val title = item.programTitle?.trim().orEmpty()
    return if (title.isNotBlank() && title != item.channelName) item.channelName else null
}

@Composable
internal fun recordingListSecondaryLine(item: RecordingItem): String {
    val subtitle = recordingDisplaySubtitle(item)
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    return subtitle ?: stringResource(
        R.string.settings_recording_time_window,
        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
    )
}

@Composable
internal fun recordingStatusLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.SCHEDULED -> stringResource(R.string.settings_recording_status_scheduled)
    RecordingStatus.RECORDING -> stringResource(R.string.settings_recording_status_recording)
    RecordingStatus.COMPLETED -> stringResource(R.string.settings_recording_status_completed)
    RecordingStatus.FAILED -> stringResource(R.string.settings_recording_status_failed)
    RecordingStatus.CANCELLED -> stringResource(R.string.settings_recording_status_cancelled)
}

internal fun recordingStatusAccent(status: RecordingStatus): Color = when (status) {
    RecordingStatus.RECORDING -> Primary
    RecordingStatus.SCHEDULED -> Secondary
    RecordingStatus.COMPLETED -> Color(0xFF4FD1C5)
    RecordingStatus.FAILED -> ErrorColor
    RecordingStatus.CANCELLED -> OnSurfaceDim
}