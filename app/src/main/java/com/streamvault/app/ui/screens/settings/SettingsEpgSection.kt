package com.streamvault.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.items
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderType

internal fun LazyListScope.epgSourcesSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val epgSources = uiState.epgSources
    val providers = uiState.providers

    item {
        Text(
            text = "EPG Sources",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF66BB6A),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Add external XMLTV EPG sources and assign them to providers. External sources are matched to channels by ID or name and override provider-native EPG data.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    item {
        AddEpgSourceCard(viewModel = viewModel)
    }

    if (epgSources.isEmpty()) {
        item {
            Text(
                text = "No external EPG sources configured. Add a source above to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    } else {
        items(epgSources, key = { source -> "epg-source-${source.id}" }) { source ->
            EpgSourceCard(
                source = source,
                isRefreshing = source.id in uiState.refreshingEpgSourceIds,
                pendingDelete = uiState.epgPendingDeleteSourceId == source.id,
                onToggleEnabled = { enabled -> viewModel.toggleEpgSourceEnabled(source.id, enabled) },
                onRefresh = { viewModel.refreshEpgSource(source.id) },
                onUpdateTimezone = { timezoneId, onSuccess, onError ->
                    viewModel.updateEpgSourceTimezone(source, timezoneId, onSuccess, onError)
                },
                onSetPendingDelete = { pending ->
                    viewModel.setPendingDeleteEpgSource(if (pending) source.id else null)
                },
                onDelete = { viewModel.deleteEpgSource(source.id) }
            )
        }
    }

    if (providers.isNotEmpty() && epgSources.isNotEmpty()) {
        item {
            Text(
                text = "Provider Assignments",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF66BB6A),
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Text(
                text = "Assign EPG sources to providers. Channels will be matched automatically by ID or name.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(providers, key = { provider -> "epg-provider-${provider.id}" }) { provider ->
            val assignments = uiState.epgSourceAssignments[provider.id].orEmpty()
            val resolutionSummary = uiState.epgResolutionSummaries[provider.id]
            val assignedSourceIds = assignments.map { it.epgSourceId }.toSet()
            val unassignedSources = epgSources.filter { it.id !in assignedSourceIds }

            LaunchedEffect(provider.id) {
                viewModel.loadEpgAssignments(provider.id)
            }

            ProviderEpgAssignmentsCard(
                providerName = provider.name,
                assignments = assignments,
                resolutionSummary = resolutionSummary,
                unassignedSources = unassignedSources,
                onMoveUp = { epgSourceId -> viewModel.moveEpgSourceAssignmentUp(provider.id, epgSourceId) },
                onMoveDown = { epgSourceId -> viewModel.moveEpgSourceAssignmentDown(provider.id, epgSourceId) },
                onRemove = { epgSourceId -> viewModel.unassignEpgSourceFromProvider(provider.id, epgSourceId) },
                onAssign = { epgSourceId -> viewModel.assignEpgSourceToProvider(provider.id, epgSourceId) }
            )
            if (supportsGuideAndLogoPolicy(provider.type)) {
                ProviderGuideAndLogoPolicyCard(
                    providerName = provider.name,
                    guideSourcePolicy = provider.guideSourcePolicy,
                    channelLogoSourcePolicy = provider.channelLogoSourcePolicy,
                    onGuideSourceSelected = { policy -> viewModel.setGuideSourcePolicy(provider.id, policy) },
                    onLogoSourceSelected = { policy -> viewModel.setChannelLogoSourcePolicy(provider.id, policy) }
                )
            }
        }
    }

    if (providers.isNotEmpty()) {
        item {
            Text(
                text = "EPG Time Shift",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF66BB6A),
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Text(
                text = "Adjust EPG times if they're consistently off from broadcast time (e.g., wrong timezone in the provider's data). Negative values shift programs earlier; positive shift them later.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(providers, key = { provider -> "epg-shift-${provider.id}" }) { provider ->
            val shiftMinutes = uiState.epgTimeShiftMinutesByProvider[provider.id] ?: 0
            EpgTimeShiftCard(
                providerName = provider.name,
                shiftMinutes = shiftMinutes,
                onAdjust = { delta -> viewModel.adjustEpgTimeShift(provider.id, delta) },
                onReset = { viewModel.resetEpgTimeShift(provider.id) }
            )
        }
    }
}

@Composable
private fun ProviderGuideAndLogoPolicyCard(
    providerName: String,
    guideSourcePolicy: GuideSourcePolicy,
    channelLogoSourcePolicy: ChannelLogoSourcePolicy,
    onGuideSourceSelected: (GuideSourcePolicy) -> Unit,
    onLogoSourceSelected: (ChannelLogoSourcePolicy) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.SurfaceElevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "$providerName Guide Controls",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Choose whether guide data comes from external XMLTV, supplier data, or stays disabled, then decide which logo source wins.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideSourcePolicy.entries.forEach { policy ->
                PolicyChip(
                    text = guideSourceLabel(policy),
                    selected = guideSourcePolicy == policy,
                    onClick = { onGuideSourceSelected(policy) }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogoSourcePolicy.entries.forEach { policy ->
                PolicyChip(
                    text = logoSourceLabel(policy),
                    selected = channelLogoSourcePolicy == policy,
                    onClick = { onLogoSourceSelected(policy) }
                )
            }
        }
    }
}

@Composable
private fun PolicyChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Primary.copy(alpha = 0.22f) else AppColors.Surface,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.32f) else AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Primary else AppColors.SurfaceAccent)),
            focusedBorder = Border(BorderStroke(2.dp, Primary))
        ),
        glow = ClickableSurfaceDefaults.glow(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

private fun guideSourceLabel(policy: GuideSourcePolicy): String = when (policy) {
    GuideSourcePolicy.AUTO -> "Auto"
    GuideSourcePolicy.EXTERNAL_ONLY -> "Use external EPG only"
    GuideSourcePolicy.PROVIDER_ONLY -> "Use supplier EPG only"
    GuideSourcePolicy.DISABLED -> "Disable guide data"
}

private fun logoSourceLabel(policy: ChannelLogoSourcePolicy): String = when (policy) {
    ChannelLogoSourcePolicy.SUPPLIER_PREFERRED -> "Supplier logos first"
    ChannelLogoSourcePolicy.EPG_PREFERRED -> "Prefer EPG logos"
    ChannelLogoSourcePolicy.SUPPLIER_ONLY -> "Supplier logos only"
    ChannelLogoSourcePolicy.EPG_ONLY -> "EPG logos only"
}

private fun supportsGuideAndLogoPolicy(providerType: ProviderType): Boolean = when (providerType) {
    ProviderType.XTREAM_CODES,
    ProviderType.STALKER_PORTAL,
    ProviderType.M3U -> true
    ProviderType.JELLYFIN -> false
}

@Composable
private fun EpgTimeShiftCard(
    providerName: String,
    shiftMinutes: Int,
    onAdjust: (Int) -> Unit,
    onReset: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatShiftLabel(shiftMinutes),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (shiftMinutes == 0) OnSurfaceDim else Primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShiftAdjustButton("−1h", onClick = { onAdjust(-60) })
                ShiftAdjustButton("−30m", onClick = { onAdjust(-30) })
                ShiftAdjustButton("−15m", onClick = { onAdjust(-15) })
                ShiftAdjustButton("−5m", onClick = { onAdjust(-5) })
                ShiftAdjustButton("Reset", onClick = onReset, enabled = shiftMinutes != 0)
                ShiftAdjustButton("+5m", onClick = { onAdjust(5) })
                ShiftAdjustButton("+15m", onClick = { onAdjust(15) })
                ShiftAdjustButton("+30m", onClick = { onAdjust(30) })
                ShiftAdjustButton("+1h", onClick = { onAdjust(60) })
            }
        }
    }
}

@Composable
private fun ShiftAdjustButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF262626),
            focusedContainerColor = Primary,
            disabledContainerColor = Color(0xFF1A1A1A)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Primary)
            )
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.White else OnSurfaceDim
        )
    }
}

private fun formatShiftLabel(minutes: Int): String {
    if (minutes == 0) return "No shift"
    val sign = if (minutes < 0) "−" else "+"
    val abs = kotlin.math.abs(minutes)
    val hours = abs / 60
    val mins = abs % 60
    return when {
        hours > 0 && mins > 0 -> "$sign${hours}h ${mins}m"
        hours > 0 -> "$sign${hours}h"
        else -> "$sign${mins}m"
    }
}

