package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.formatTimestamp
import com.streamvault.feature.settings.presentation.recordingDisplaySubtitle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createDateTimeFormat
import com.streamvault.domain.model.RecordingItem

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
