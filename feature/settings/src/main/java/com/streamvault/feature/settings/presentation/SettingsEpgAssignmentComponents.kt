package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.EpgSource
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.ProviderEpgSourceAssignment

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderEpgAssignmentsCard(
    providerName: String,
    assignments: List<ProviderEpgSourceAssignment>,
    resolutionSummary: EpgResolutionSummary?,
    unassignedSources: List<EpgSource>,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAssign: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceElevated, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerName, style = MaterialTheme.typography.titleSmall, color = OnSurface)
            if (resolutionSummary != null) {
                val matchedChannels = (resolutionSummary.totalChannels - resolutionSummary.unresolvedChannels).coerceAtLeast(0)
                val summaryParts = buildList {
                    add(stringResource(R.string.settings_epg_matches_summary, matchedChannels, resolutionSummary.totalChannels))
                    if (resolutionSummary.exactIdMatches > 0) add(stringResource(R.string.settings_epg_matches_exact, resolutionSummary.exactIdMatches))
                    if (resolutionSummary.normalizedNameMatches > 0) add(stringResource(R.string.settings_epg_matches_name, resolutionSummary.normalizedNameMatches))
                    if (resolutionSummary.providerNativeMatches > 0) add(stringResource(R.string.settings_epg_matches_provider, resolutionSummary.providerNativeMatches))
                    if (resolutionSummary.manualMatches > 0) add(stringResource(R.string.settings_epg_matches_manual, resolutionSummary.manualMatches))
                    if (resolutionSummary.unresolvedChannels > 0) add(stringResource(R.string.settings_epg_matches_unresolved, resolutionSummary.unresolvedChannels))
                    if (resolutionSummary.lowConfidenceChannels > 0) add(stringResource(R.string.settings_epg_matches_weak, resolutionSummary.lowConfidenceChannels))
                    if (resolutionSummary.rematchCandidateChannels > 0) add(stringResource(R.string.settings_epg_matches_review, resolutionSummary.rematchCandidateChannels))
                }
                Text(
                    text = summaryParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }

            if (assignments.isEmpty()) {
                Text(stringResource(R.string.settings_epg_no_sources_assigned), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            } else {
                assignments.sortedBy { it.priority }.forEachIndexed { assignmentIndex, assignment ->
                    ProviderEpgAssignmentRow(
                        assignment = assignment,
                        canMoveUp = assignmentIndex > 0,
                        canMoveDown = assignmentIndex < assignments.lastIndex,
                        onMoveUp = { onMoveUp(assignment.epgSourceId) },
                        onMoveDown = { onMoveDown(assignment.epgSourceId) },
                        onRemove = { onRemove(assignment.epgSourceId) }
                    )
                }
            }

            if (unassignedSources.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    unassignedSources.forEach { source ->
                        val assignActionShape = RoundedCornerShape(8.dp)
                        TvClickableSurface(
                            onClick = { onAssign(source.id) },
                            shape = ClickableSurfaceDefaults.shape(assignActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Primary.copy(alpha = 0.12f),
                                focusedContainerColor = Primary.copy(alpha = 0.25f)
                            ),
                            border = epgActionBorder(assignActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(
                                "+ ${source.name}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEpgAssignmentRow(
    assignment: ProviderEpgSourceAssignment,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.settings_epg_assignment_priority, assignment.epgSourceName, assignment.priority),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        val priorityActionShape = RoundedCornerShape(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderEpgAssignmentButton(
                label = stringResource(R.string.settings_epg_move_up),
                enabled = canMoveUp,
                shape = priorityActionShape,
                onClick = onMoveUp
            )
            ProviderEpgAssignmentButton(
                label = stringResource(R.string.settings_epg_move_down),
                enabled = canMoveDown,
                shape = priorityActionShape,
                onClick = onMoveDown
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        TvClickableSurface(
            onClick = onRemove,
            shape = ClickableSurfaceDefaults.shape(priorityActionShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = ErrorColor.copy(alpha = 0.12f),
                focusedContainerColor = ErrorColor.copy(alpha = 0.25f)
            ),
            border = epgActionBorder(priorityActionShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Text(stringResource(R.string.settings_epg_remove_assignment), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = ErrorColor)
        }
    }
}

@Composable
private fun ProviderEpgAssignmentButton(
    label: String,
    enabled: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight,
            disabledContainerColor = SurfaceElevated.copy(alpha = 0.55f)
        ),
        border = epgActionBorder(shape, enabled = enabled),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = OnSurface)
    }
}
