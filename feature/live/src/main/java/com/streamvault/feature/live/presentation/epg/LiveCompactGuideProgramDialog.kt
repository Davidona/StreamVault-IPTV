package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.AccentRed
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormat
import java.util.Date

data class LiveCompactGuideProgramLabels(
    val noInfo: String,
    val watchLive: String,
    val watchArchive: String,
    val scheduleRecording: String,
    val scheduleDailyRecording: String,
    val scheduleWeeklyRecording: String,
    val detailsShow: String,
    val detailsHide: String,
    val cancel: String
)

@Composable
fun LiveCompactGuideProgramDialog(
    channel: Channel,
    program: Program,
    providerLabel: String,
    now: Long,
    labels: LiveCompactGuideProgramLabels,
    onDismiss: () -> Unit,
    onWatchLive: () -> Unit,
    onWatchArchive: (() -> Unit)?,
    reminderButtonLabel: String?,
    onToggleReminder: (() -> Unit)?,
    onScheduleRecording: (() -> Unit)?,
    onScheduleDailyRecording: (() -> Unit)?,
    onScheduleWeeklyRecording: (() -> Unit)?
) {
    var showDetails by rememberSaveable(program.startTime, program.endTime, program.title) { mutableStateOf(false) }
    val appTimeFormat = LocalLiveTimeFormat.current
    val format = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormat() }
    val firstButtonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstButtonFocusRequester.requestFocus() }

    LiveCompactGuideModal(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 420.dp, max = 640.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (channel.number > 0) { append(channel.number); append(". ") }
                        append(channel.name)
                        append("  |  ")
                        append(format.format(Date(program.startTime)))
                        append(" - ")
                        append(format.format(Date(program.endTime)))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (providerLabel.isNotBlank()) {
                    Text(
                        text = providerLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                }
                if (now in program.startTime until program.endTime) {
                    LinearProgressIndicator(
                        progress = { ((now - program.startTime).toFloat() / (program.endTime - program.startTime).toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = Primary,
                        trackColor = SurfaceHighlight
                    )
                }
                if (showDetails) {
                    Text(
                        text = program.description.ifBlank { labels.noInfo },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        onClick = onWatchLive,
                        modifier = Modifier.fillMaxWidth().focusRequester(firstButtonFocusRequester),
                        scale = ButtonDefaults.scale(focusedScale = 1f)
                    ) { Text(labels.watchLive) }
                    if (onWatchArchive != null) {
                        TvButton(
                            onClick = onWatchArchive,
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = Primary, contentColor = Color.White)
                        ) { Text(labels.watchArchive) }
                    }
                    if (reminderButtonLabel != null && onToggleReminder != null) {
                        TvButton(
                            onClick = onToggleReminder,
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = SurfaceHighlight, contentColor = OnSurface)
                        ) { Text(reminderButtonLabel) }
                    }
                    if (onScheduleRecording != null) {
                        TvButton(
                            onClick = { onScheduleRecording(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = AccentRed, contentColor = Color.White)
                        ) { Text(labels.scheduleRecording) }
                    }
                    if (onScheduleDailyRecording != null) {
                        TvButton(
                            onClick = { onScheduleDailyRecording(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = SurfaceHighlight, contentColor = OnSurface)
                        ) { Text(labels.scheduleDailyRecording) }
                    }
                    if (onScheduleWeeklyRecording != null) {
                        TvButton(
                            onClick = { onScheduleWeeklyRecording(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(containerColor = SurfaceHighlight, contentColor = OnSurface)
                        ) { Text(labels.scheduleWeeklyRecording) }
                    }
                    TvButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.fillMaxWidth(),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(containerColor = SurfaceHighlight, contentColor = OnSurface)
                    ) { Text(if (showDetails) labels.detailsHide else labels.detailsShow) }
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = OnSurface)
                    ) { Text(labels.cancel) }
                }
            }
        }
    }
}

@Composable
private fun LiveCompactGuideModal(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
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
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
                    )
            )
            content()
        }
    }
}
