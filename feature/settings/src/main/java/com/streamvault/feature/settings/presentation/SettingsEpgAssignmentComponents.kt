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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.EpgSource
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
    onAssign: (Long) -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerName, style = MaterialTheme.typography.titleSmall, color = Color.White)
            if (resolutionSummary != null) {
                val matchedChannels = (resolutionSummary.totalChannels - resolutionSummary.unresolvedChannels).coerceAtLeast(0)
                val summaryParts = buildList {
                    add("Matched $matchedChannels/${resolutionSummary.totalChannels} channels")
                    if (resolutionSummary.exactIdMatches > 0) add("${resolutionSummary.exactIdMatches} exact")
                    if (resolutionSummary.normalizedNameMatches > 0) add("${resolutionSummary.normalizedNameMatches} name")
                    if (resolutionSummary.providerNativeMatches > 0) add("${resolutionSummary.providerNativeMatches} provider")
                    if (resolutionSummary.manualMatches > 0) add("${resolutionSummary.manualMatches} manual")
                    if (resolutionSummary.unresolvedChannels > 0) add("${resolutionSummary.unresolvedChannels} without EPG")
                    if (resolutionSummary.lowConfidenceChannels > 0) add("${resolutionSummary.lowConfidenceChannels} weak")
                    if (resolutionSummary.rematchCandidateChannels > 0) add("${resolutionSummary.rematchCandidateChannels} need review")
                }
                Text(
                    text = summaryParts.joinToString(" ג€¢ "),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }

            if (assignments.isEmpty()) {
                Text("No EPG sources assigned", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
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
                                containerColor = Color(0xFF66BB6A).copy(alpha = 0.12f),
                                focusedContainerColor = Color(0xFF66BB6A).copy(alpha = 0.25f)
                            ),
                            border = epgActionBorder(assignActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(
                                "+ ${source.name}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF66BB6A)
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
            "${assignment.epgSourceName} (priority: ${assignment.priority})",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        val priorityActionShape = RoundedCornerShape(6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderEpgAssignmentButton(
                label = "Up",
                enabled = canMoveUp,
                shape = priorityActionShape,
                onClick = onMoveUp
            )
            ProviderEpgAssignmentButton(
                label = "Down",
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
                containerColor = Color(0xFFEF5350).copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.25f)
            ),
            border = epgActionBorder(priorityActionShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Text("Remove", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
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
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            disabledContainerColor = Color.White.copy(alpha = 0.04f)
        ),
        border = epgActionBorder(shape, enabled = enabled),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
