package com.streamvault.feature.settings.presentation

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.items
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderType
import com.streamvault.feature.settings.R

fun LazyListScope.epgSourcesSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    firstFocusModifier: Modifier = Modifier,
    targetItemId: String? = null,
    targetFocusModifier: Modifier = Modifier,
) {
    val epgSources = uiState.epgSources
    val providers = uiState.providers

    item {
        Text(
            text = stringResource(R.string.settings_epg_sources_title),
            style = MaterialTheme.typography.titleMedium,
            color = Primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_epg_sources_description),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceDim,
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }

    item {
        AddEpgSourceCard(
            viewModel = viewModel,
            firstFocusModifier = if (targetItemId == "guide.source.add") {
                targetFocusModifier
            } else firstFocusModifier,
        )
    }

    if (epgSources.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.settings_epg_sources_empty),
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
                ,
                modifier = if (
                    targetItemId in setOf(
                        "guide.source.enabled",
                        "guide.source.refresh",
                        "guide.source.timezone",
                        "guide.source.delete",
                    ) && source.id == epgSources.first().id
                ) targetFocusModifier else Modifier,
            )
        }
    }

    if (providers.isNotEmpty() && epgSources.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.settings_epg_assignments_title),
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.settings_epg_assignments_description),
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
                onAssign = { epgSourceId -> viewModel.assignEpgSourceToProvider(provider.id, epgSourceId) },
                modifier = if (
                    targetItemId == "guide.assignment" && provider.id == providers.first().id
                ) targetFocusModifier else Modifier,
            )
            if (supportsGuideAndLogoPolicy(provider.type)) {
                ProviderGuideAndLogoPolicyCard(
                    providerName = provider.name,
                    guideSourcePolicy = provider.guideSourcePolicy,
                    channelLogoSourcePolicy = provider.channelLogoSourcePolicy,
                    onGuideSourceSelected = { policy -> viewModel.setGuideSourcePolicy(provider.id, policy) },
                    onLogoSourceSelected = { policy -> viewModel.setChannelLogoSourcePolicy(provider.id, policy) },
                    modifier = if (
                        targetItemId in setOf("guide.policy", "guide.logo_policy") &&
                            provider.id == providers.first().id
                    ) targetFocusModifier else Modifier,
                )
            }
        }
    }

    if (providers.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.settings_epg_time_shift_title),
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.settings_epg_time_shift_description),
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
                onReset = { viewModel.resetEpgTimeShift(provider.id) },
                modifier = if (
                    targetItemId == "guide.time_shift" && provider.id == providers.first().id
                ) targetFocusModifier else Modifier,
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
    onLogoSourceSelected: (ChannelLogoSourcePolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceElevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_epg_provider_controls, providerName),
            style = MaterialTheme.typography.titleSmall,
            color = OnSurface,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.settings_epg_provider_controls_description),
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
            containerColor = if (selected) Primary.copy(alpha = 0.22f) else SurfaceElevated,
            focusedContainerColor = if (selected) Primary.copy(alpha = 0.32f) else SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Primary else OnSurface.copy(alpha = 0.16f))),
            focusedBorder = Border(BorderStroke(2.dp, Primary))
        ),
        glow = ClickableSurfaceDefaults.glow(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurface
        )
    }
}

@Composable
private fun guideSourceLabel(policy: GuideSourcePolicy): String = stringResource(when (policy) {
    GuideSourcePolicy.AUTO -> R.string.settings_epg_policy_auto
    GuideSourcePolicy.EXTERNAL_ONLY -> R.string.settings_epg_policy_external_only
    GuideSourcePolicy.PROVIDER_ONLY -> R.string.settings_epg_policy_provider_only
    GuideSourcePolicy.DISABLED -> R.string.settings_epg_policy_disabled
})

@Composable
private fun logoSourceLabel(policy: ChannelLogoSourcePolicy): String = stringResource(when (policy) {
    ChannelLogoSourcePolicy.SUPPLIER_PREFERRED -> R.string.settings_epg_logo_supplier_preferred
    ChannelLogoSourcePolicy.EPG_PREFERRED -> R.string.settings_epg_logo_epg_preferred
    ChannelLogoSourcePolicy.SUPPLIER_ONLY -> R.string.settings_epg_logo_supplier_only
    ChannelLogoSourcePolicy.EPG_ONLY -> R.string.settings_epg_logo_epg_only
})

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
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsColors = SettingsDesignTokens.colors(com.streamvault.core.ui.design.AppColors.current)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
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
                    color = settingsColors.primaryText,
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
                ShiftAdjustButton(stringResource(R.string.settings_epg_reset_shift), onClick = onReset, enabled = shiftMinutes != 0)
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
    val settingsColors = SettingsDesignTokens.colors(com.streamvault.core.ui.design.AppColors.current)
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = settingsColors.searchSurface,
            contentColor = settingsColors.primaryText,
            focusedContainerColor = settingsColors.focusedSurface,
            focusedContentColor = settingsColors.primaryText,
            disabledContainerColor = settingsColors.groupSurface,
            disabledContentColor = settingsColors.disabledText,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, settingsColors.divider)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(SettingsDesignTokens.focusStroke, settingsColors.focusOutline)
            ),
        )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) settingsColors.primaryText else settingsColors.disabledText
        )
    }
}

@Composable
private fun formatShiftLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.settings_epg_no_shift)
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

