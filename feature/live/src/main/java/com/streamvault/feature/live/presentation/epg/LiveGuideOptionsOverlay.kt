package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

data class LiveGuideOptionsLabels(
    val title: String,
    val showAppNavigation: String,
    val cancel: String,
    val manageEpgMatch: String,
    val refreshGuide: String
)

fun liveGuideOptionsDayStart(
    guideWindowStart: Long,
    lookbackMs: Long,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
): Long = startOfGuideDay(guideWindowStart + lookbackMs, zoneId)

@Composable
fun LiveGuideModalDialog(
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
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
            contentAlignment = contentAlignment
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        onClick = onDismiss,
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    )
            )
            content()
        }
    }
}

@Composable
fun LiveGuideOptionsOverlay(
    selectedDayStart: Long,
    selectedMode: GuideChannelMode,
    selectedDensity: GuideDensity,
    showScheduledOnly: Boolean,
    showFavoritesOnly: Boolean,
    controlLabels: LiveGuideControlLabels,
    labels: LiveGuideOptionsLabels,
    onDismiss: () -> Unit,
    onShowAppNavigation: () -> Unit,
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit,
    onDaySelected: (Long) -> Unit,
    onModeSelected: (GuideChannelMode) -> Unit,
    onDensitySelected: (GuideDensity) -> Unit,
    onToggleScheduledOnly: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onRefresh: () -> Unit,
    onManageEpgMatch: (() -> Unit)? = null
) {
    val optionsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        optionsFocusRequester.requestFocus()
    }
    LiveGuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .fillMaxHeight(0.78f)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = com.streamvault.core.ui.theme.SurfaceElevated),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labels.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = com.streamvault.core.ui.theme.OnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveGuideShortcutChip(
                            label = labels.showAppNavigation,
                            onClick = onShowAppNavigation
                        )
                        LiveGuideShortcutChip(
                            label = labels.cancel,
                            onClick = onDismiss
                        )
                    }
                }
                LiveGuideTimeControlsRow(
                    labels = controlLabels.time,
                    onJumpToPreviousDay = onJumpToPreviousDay,
                    onPageBackward = onPageBackward,
                    onJumpBackwardHalfHour = onJumpBackwardHalfHour,
                    onJumpBackward = onJumpBackward,
                    onJumpToNow = onJumpToNow,
                    onJumpForwardHalfHour = onJumpForwardHalfHour,
                    onJumpForward = onJumpForward,
                    onPageForward = onPageForward,
                    onJumpToPrimeTime = onJumpToPrimeTime,
                    onJumpToTomorrow = onJumpToTomorrow,
                    onJumpToNextDay = onJumpToNextDay,
                    firstChipFocusRequester = optionsFocusRequester
                )
                LiveGuideDayRow(
                    selectedDayStart = selectedDayStart,
                    labels = controlLabels.day,
                    onDaySelected = onDaySelected
                )
                LiveGuideModeRow(
                    selectedMode = selectedMode,
                    labels = controlLabels.mode,
                    onModeSelected = onModeSelected
                )
                LiveGuideDensityRow(
                    selectedDensity = selectedDensity,
                    labels = controlLabels.density,
                    onDensitySelected = onDensitySelected
                )
                LiveGuideViewOptionsRow(
                    showScheduledOnly = showScheduledOnly,
                    labels = controlLabels.viewOptions,
                    onToggleScheduledOnly = onToggleScheduledOnly
                )
                LiveGuideFavoritesRow(
                    showFavoritesOnly = showFavoritesOnly,
                    labels = controlLabels.favorites,
                    onToggleFavoritesOnly = onToggleFavoritesOnly
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (onManageEpgMatch != null) {
                        LiveGuideShortcutChip(
                            label = labels.manageEpgMatch,
                            onClick = onManageEpgMatch
                        )
                    }
                    LiveGuideShortcutChip(
                        label = labels.refreshGuide,
                        onClick = onRefresh
                    )
                }
            }
        }
    }
}
