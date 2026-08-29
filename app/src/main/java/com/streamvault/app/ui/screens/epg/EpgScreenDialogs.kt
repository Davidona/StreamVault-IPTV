package com.streamvault.app.ui.screens.epg

import com.streamvault.feature.live.presentation.epg.LiveGuideProgramSearchRow
import com.streamvault.feature.live.presentation.epg.LiveGuideSearchField
import com.streamvault.feature.live.presentation.epg.LiveGuideShortcutChip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.feature.live.presentation.epg.GuideChannelMode
import com.streamvault.feature.live.presentation.epg.GuideDensity
import com.streamvault.feature.live.presentation.epg.LiveGuideControlLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideDensityRow
import com.streamvault.feature.live.presentation.epg.LiveGuideDayRow
import com.streamvault.feature.live.presentation.epg.LiveGuideFavoritesRow
import com.streamvault.feature.live.presentation.epg.LiveGuideModeRow
import com.streamvault.feature.live.presentation.epg.LiveGuideTimeControlsRow
import com.streamvault.feature.live.presentation.epg.LiveGuideViewOptionsRow
import com.streamvault.feature.live.presentation.epg.startOfGuideDay
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgSourceType

@Composable
private fun GuideModalDialog(
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
internal fun GuideOptionsOverlay(
    uiState: EpgUiState,
    labels: LiveGuideControlLabels,
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
    GuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .fillMaxHeight(0.78f)
                .focusGroup(),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
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
                        text = stringResource(R.string.epg_options_short),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiveGuideShortcutChip(
                            label = stringResource(R.string.epg_show_app_navigation),
                            onClick = onShowAppNavigation
                        )
                        LiveGuideShortcutChip(
                            label = stringResource(R.string.settings_cancel),
                            onClick = onDismiss
                        )
                    }
                }
                LiveGuideTimeControlsRow(
                    labels = labels.time,
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
                    selectedDayStart = startOfGuideDay(uiState.guideWindowStart + EpgViewModel.LOOKBACK_MS),
                    labels = labels.day,
                    onDaySelected = onDaySelected
                )
                LiveGuideModeRow(
                    selectedMode = uiState.selectedChannelMode,
                    labels = labels.mode,
                    onModeSelected = onModeSelected
                )
                LiveGuideDensityRow(
                    selectedDensity = uiState.selectedDensity,
                    labels = labels.density,
                    onDensitySelected = onDensitySelected
                )
                LiveGuideViewOptionsRow(
                    showScheduledOnly = uiState.showScheduledOnly,
                    labels = labels.viewOptions,
                    onToggleScheduledOnly = onToggleScheduledOnly
                )
                LiveGuideFavoritesRow(
                    showFavoritesOnly = uiState.showFavoritesOnly,
                    labels = labels.favorites,
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
                            label = stringResource(R.string.epg_override_manage),
                            onClick = onManageEpgMatch
                        )
                    }
                    LiveGuideShortcutChip(
                        label = stringResource(R.string.epg_refresh_guide),
                        onClick = onRefresh
                    )
                }
            }
        }
    }
}

@Composable
internal fun EpgOverrideDialog(
    state: EpgOverrideUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCandidateSelected: (EpgOverrideCandidate) -> Unit,
    onClearOverride: () -> Unit
) {
    val channel = state.channel ?: return
    val unknownValue = stringResource(R.string.epg_program_unknown_value)
    val currentCandidate = remember(state.currentMapping, state.candidates) {
        state.candidates.firstOrNull {
            it.epgSourceId == state.currentMapping?.epgSourceId &&
                it.xmltvChannelId == state.currentMapping?.xmltvChannelId
        }
    }
    val currentDescriptor = currentCandidate?.let {
        "${it.displayName}  ג€¢  ${it.epgSourceName}  ג€¢  ${it.xmltvChannelId}"
    } ?: (state.currentMapping?.xmltvChannelId ?: unknownValue)
    val currentSummary = when {
        state.currentMapping == null || state.currentMapping.sourceType == EpgSourceType.NONE ->
            stringResource(R.string.epg_override_current_none)
        state.currentMapping.isManualOverride || state.currentMapping.matchType == EpgMatchType.MANUAL ->
            stringResource(R.string.epg_override_current_manual, currentDescriptor)
        state.currentMapping.sourceType == EpgSourceType.PROVIDER ->
            stringResource(R.string.epg_override_current_provider, currentDescriptor)
        else ->
            stringResource(R.string.epg_override_current_external, currentDescriptor)
    }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }

    GuideModalDialog(onDismiss = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 560.dp, max = 760.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.epg_override_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnSurface
                )
                Text(
                    text = if (channel.number > 0) "${channel.number}. ${channel.name}" else channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.epg_override_current_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                    Text(
                        text = currentSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface
                    )
                }
                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.isLoading || state.isSaving) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = Primary,
                        trackColor = SurfaceHighlight
                    )
                }
                LiveGuideSearchField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.epg_override_search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = searchFocusRequester,
                    onSearch = { onQueryChange(it) }
                )
                if (state.candidates.isEmpty()) {
                    Text(
                        text = if (state.searchQuery.isBlank()) {
                            stringResource(R.string.epg_override_no_candidates)
                        } else {
                            stringResource(R.string.epg_override_no_search_results)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = state.candidates,
                            key = { candidate -> "${candidate.epgSourceId}:${candidate.xmltvChannelId}" }
                        ) { candidate ->
                            val isCurrent = state.currentMapping?.epgSourceId == candidate.epgSourceId &&
                                state.currentMapping?.xmltvChannelId == candidate.xmltvChannelId
                            TvClickableSurface(
                                onClick = {
                                    if (!state.isSaving) {
                                        onCandidateSelected(candidate)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (isCurrent) SurfaceHighlight else SurfaceElevated,
                                    focusedContainerColor = SurfaceHighlight,
                                    contentColor = OnSurface,
                                    focusedContentColor = OnSurface
                                ),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                                border = ClickableSurfaceDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, FocusBorder),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = candidate.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = OnSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrent) {
                                            Text(
                                                text = stringResource(R.string.epg_override_selected_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${candidate.epgSourceName}  ג€¢  ${candidate.xmltvChannelId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnSurfaceDim,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.currentMapping?.isManualOverride == true) {
                        TvButton(
                            onClick = onClearOverride,
                            enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = SurfaceHighlight,
                                contentColor = OnSurface
                            )
                        ) {
                            Text(stringResource(R.string.epg_override_clear))
                        }
                    }
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = OnSurface
                        )
                    ) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            }
        }
    }
}


