package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.RecordingItem

@Composable
public fun recordingListSecondaryLine(
    item: RecordingItem,
    appTimeFormat: AppTimeFormat
): String {
    val subtitle = recordingDisplaySubtitle(item)
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createSettingsDateTimeFormat() }
    return subtitle ?: stringResource(
        R.string.settings_recording_time_window,
        formatTimestamp(item.scheduledStartMs, dateTimeFormat),
        formatTimestamp(item.scheduledEndMs, dateTimeFormat)
    )
}
