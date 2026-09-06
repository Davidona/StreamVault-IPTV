package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary
import com.streamvault.app.ui.theme.Surface
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.ProviderType

@Composable
internal fun ProviderM3uOptionsPanel(
    m3uVodClassificationEnabled: Boolean,
    isSyncing: Boolean,
    onToggleM3uVodClassification: (Boolean) -> Unit,
    onRefreshM3uClassification: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(10.dp))
            .border(1.dp, SurfaceHighlight, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_m3u_vod_classification_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnBackground
                )
                Text(
                    text = stringResource(R.string.settings_m3u_vod_classification_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
            Switch(
                checked = m3uVodClassificationEnabled,
                onCheckedChange = onToggleM3uVodClassification
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvClickableSurface(
                onClick = onRefreshM3uClassification,
                enabled = !isSyncing,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.15f),
                    focusedContainerColor = Primary.copy(alpha = 0.3f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_m3u_vod_classification_refresh),
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
internal fun ProviderSyncWarningsPanel(
    providerType: ProviderType,
    syncWarnings: List<String>,
    isSyncing: Boolean,
    onRetryWarningAction: (ProviderWarningAction) -> Unit
) {
    val hasEpgWarning = syncWarnings.any { it.contains("EPG", ignoreCase = true) }
    val hasMoviesWarning = syncWarnings.any { it.contains("Movies", ignoreCase = true) }
    val hasSeriesWarning = syncWarnings.any { it.contains("Series", ignoreCase = true) }

    Text(
        text = stringResource(R.string.settings_provider_warnings, syncWarnings.take(3).joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = Secondary
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasEpgWarning) {
            ProviderWarningRetryButton(
                label = stringResource(R.string.settings_retry_epg),
                isSyncing = isSyncing,
                onClick = { onRetryWarningAction(ProviderWarningAction.EPG) }
            )
        }
        if (hasMoviesWarning) {
            ProviderWarningRetryButton(
                label = stringResource(R.string.settings_retry_movies),
                isSyncing = isSyncing,
                onClick = { onRetryWarningAction(ProviderWarningAction.MOVIES) }
            )
        }
        if (hasSeriesWarning && providerType == ProviderType.XTREAM_CODES) {
            ProviderWarningRetryButton(
                label = stringResource(R.string.settings_retry_series),
                isSyncing = isSyncing,
                onClick = { onRetryWarningAction(ProviderWarningAction.SERIES) }
            )
        }
    }
}

@Composable
private fun ProviderWarningRetryButton(
    label: String,
    isSyncing: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = !isSyncing,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Secondary.copy(alpha = 0.16f),
            focusedContainerColor = Secondary.copy(alpha = 0.35f)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Secondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun ProviderActionButtons(
    isActive: Boolean,
    isSyncing: Boolean,
    liveOnboardingIncomplete: Boolean,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onParentalControl: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!isActive) {
            ProviderActionButton(
                label = when {
                    liveOnboardingIncomplete && isSyncing -> stringResource(R.string.settings_syncing_btn)
                    liveOnboardingIncomplete -> stringResource(R.string.settings_sync_btn)
                    else -> stringResource(R.string.settings_connect)
                },
                accent = Primary,
                filled = true,
                contentColor = Color.White,
                onClick = if (liveOnboardingIncomplete) onRefresh else onConnect
            )
        } else {
            ProviderActionButton(
                label = if (isSyncing) stringResource(R.string.settings_syncing_btn) else stringResource(R.string.settings_sync_btn),
                accent = Primary,
                onClick = onRefresh
            )
        }

        ProviderActionButton(
            label = stringResource(R.string.settings_edit),
            accent = Secondary,
            onClick = onEdit
        )

        ProviderActionButton(
            label = stringResource(R.string.settings_delete),
            accent = ErrorColor,
            onClick = onDelete
        )

        if (isActive) {
            ProviderActionButton(
                label = stringResource(R.string.settings_provider_category_controls_action),
                accent = Primary,
                onClick = onParentalControl
            )
        }
    }
}

@Composable
private fun ProviderActionButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    filled: Boolean = false,
    contentColor: Color = accent
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (filled) accent else accent.copy(alpha = 0.2f),
            focusedContainerColor = if (filled) accent.copy(alpha = 0.8f) else accent.copy(alpha = 0.5f),
            contentColor = contentColor,
            focusedContentColor = contentColor
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = RoundedCornerShape(6.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier
                .widthIn(min = 0.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}