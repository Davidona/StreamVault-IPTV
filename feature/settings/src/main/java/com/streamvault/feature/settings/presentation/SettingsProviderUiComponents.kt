package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.presentation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.Secondary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
public fun ProviderSelectorTab(
    provider: Provider,
    isSelected: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .mouseClickable(
                focusRequester = focusRequester,
                onClick = onClick
            ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
            focusedContainerColor = Primary.copy(alpha = 0.34f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) Primary.copy(alpha = 0.45f) else com.streamvault.core.ui.design.AppColors.Divider
                )
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    width = 2.dp,
                    color = Primary
                )
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = provider.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
            if (isActive) {
                Text(
                    text = stringResource(R.string.settings_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
public fun ProviderCompactStat(
    title: String,
    count: Int
) {
    ProviderCompactStat(
        title = title,
        value = ProviderCatalogCountUiModel(count, ProviderCatalogCountStatus.READY)
    )
}

@Composable
public fun ProviderCompactStat(
    title: String,
    value: ProviderCatalogCountUiModel,
    syncingLabel: String = stringResource(R.string.settings_catalog_count_syncing)
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = value.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = OnBackground
            )
            providerCatalogStatusTagText(value, syncingLabel)?.let { tag ->
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = Secondary
                )
            }
        }
    }
}

@Composable
private fun providerCatalogStatusTagText(
    value: ProviderCatalogCountUiModel,
    syncingLabel: String
): String? = when (value.status) {
    ProviderCatalogCountStatus.QUEUED -> stringResource(R.string.settings_catalog_count_queued)
    ProviderCatalogCountStatus.SYNCING -> syncingLabel
    ProviderCatalogCountStatus.PARTIAL -> stringResource(R.string.settings_catalog_count_partial)
    ProviderCatalogCountStatus.FAILED -> stringResource(R.string.settings_catalog_count_failed)
    ProviderCatalogCountStatus.PENDING,
    ProviderCatalogCountStatus.READY -> null
}

public fun ProviderCatalogCountUiModel.shouldShowCatalogStatusTag(): Boolean =
    status != ProviderCatalogCountStatus.PENDING && status != ProviderCatalogCountStatus.READY

@Composable
public fun ProviderStatusBadge(
    status: ProviderStatus,
    requiresAttention: Boolean = false
) {
    val (label, color) = when (status) {
        ProviderStatus.ACTIVE -> stringResource(R.string.settings_status_active) to Primary
        ProviderStatus.PARTIAL -> stringResource(
            if (requiresAttention) {
                R.string.settings_status_requires_attention
            } else {
                R.string.settings_status_partial
            }
        ) to Secondary
        ProviderStatus.ERROR -> stringResource(R.string.settings_status_error) to ErrorColor
        ProviderStatus.EXPIRED -> stringResource(R.string.settings_status_expired) to ErrorColor
        ProviderStatus.DISABLED -> stringResource(R.string.settings_status_disabled) to OnSurfaceDim
        ProviderStatus.UNKNOWN -> stringResource(R.string.settings_status_unknown) to OnSurfaceDim
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
