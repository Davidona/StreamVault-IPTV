package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgSourceType

data class LiveGuideEpgOverrideLabels(
    val title: String,
    val currentLabel: String,
    val unknownValue: String,
    val currentNone: String,
    val currentManualFormat: String,
    val currentProviderFormat: String,
    val currentExternalFormat: String,
    val searchPlaceholder: String,
    val noCandidates: String,
    val noSearchResults: String,
    val selectedBadge: String,
    val clear: String,
    val cancel: String
)

private const val DESCRIPTOR_SEPARATOR = " • "

internal fun liveGuideOverrideSummary(
    mapping: ChannelEpgMapping?,
    currentCandidate: EpgOverrideCandidate?,
    unknownValue: String,
    currentNone: String,
    currentManualFormat: String,
    currentProviderFormat: String,
    currentExternalFormat: String
): String {
    val currentDescriptor = currentCandidate?.let {
        "${it.displayName}$DESCRIPTOR_SEPARATOR${it.epgSourceName}$DESCRIPTOR_SEPARATOR${it.xmltvChannelId}"
    } ?: (mapping?.xmltvChannelId ?: unknownValue)
    return when {
        mapping == null || mapping.sourceType == EpgSourceType.NONE -> currentNone
        mapping.isManualOverride || mapping.matchType == EpgMatchType.MANUAL -> currentManualFormat.format(currentDescriptor)
        mapping.sourceType == EpgSourceType.PROVIDER -> currentProviderFormat.format(currentDescriptor)
        else -> currentExternalFormat.format(currentDescriptor)
    }
}

@Composable
fun LiveGuideEpgOverrideDialog(
    channel: Channel,
    currentMapping: ChannelEpgMapping?,
    searchQuery: String,
    candidates: List<EpgOverrideCandidate>,
    isLoading: Boolean,
    isSaving: Boolean,
    error: String?,
    labels: LiveGuideEpgOverrideLabels,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCandidateSelected: (EpgOverrideCandidate) -> Unit,
    onClearOverride: () -> Unit
) {
    val currentCandidate = remember(currentMapping, candidates) {
        candidates.firstOrNull {
            it.epgSourceId == currentMapping?.epgSourceId &&
                it.xmltvChannelId == currentMapping.xmltvChannelId
        }
    }
    val currentSummary = liveGuideOverrideSummary(
        mapping = currentMapping,
        currentCandidate = currentCandidate,
        unknownValue = labels.unknownValue,
        currentNone = labels.currentNone,
        currentManualFormat = labels.currentManualFormat,
        currentProviderFormat = labels.currentProviderFormat,
        currentExternalFormat = labels.currentExternalFormat
    )

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }

    LiveGuideModalDialog(onDismiss = onDismiss) {
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
                    text = labels.title,
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
                        text = labels.currentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                    Text(
                        text = currentSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (isLoading || isSaving) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = Primary,
                        trackColor = SurfaceHighlight
                    )
                }
                LiveGuideSearchField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = labels.searchPlaceholder,
                    modifier = Modifier.fillMaxWidth(),
                    focusRequester = searchFocusRequester,
                    onSearch = onQueryChange
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) labels.noCandidates else labels.noSearchResults,
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
                            items = candidates,
                            key = { candidate -> "${candidate.epgSourceId}:${candidate.xmltvChannelId}" }
                        ) { candidate ->
                            val isCurrent = currentMapping?.epgSourceId == candidate.epgSourceId &&
                                currentMapping?.xmltvChannelId == candidate.xmltvChannelId
                            TvClickableSurface(
                                onClick = {
                                    if (!isSaving) onCandidateSelected(candidate)
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
                                                text = labels.selectedBadge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${candidate.epgSourceName}$DESCRIPTOR_SEPARATOR${candidate.xmltvChannelId}",
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
                    if (currentMapping?.isManualOverride == true) {
                        TvButton(
                            onClick = onClearOverride,
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth(),
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = SurfaceHighlight,
                                contentColor = OnSurface
                            )
                        ) {
                            Text(labels.clear)
                        }
                    }
                    TvButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        colors = ButtonDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = OnSurface
                        )
                    ) {
                        Text(labels.cancel)
                    }
                }
            }
        }
    }
}
