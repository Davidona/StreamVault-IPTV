package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.feature.settings.R

@Composable
internal fun SettingsLocalHeader(
    title: String,
    description: String,
    parentTitle: String?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)? = null,
    searchModifier: Modifier = Modifier,
    backModifier: Modifier = Modifier,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDesignTokens.headerMinHeight),
        horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null && parentTitle != null) {
            TvClickableSurface(
                onClick = onBack,
                modifier = backModifier,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(SettingsDesignTokens.groupRadius)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.groupSurface,
                    focusedContainerColor = colors.focusedSurface,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        BorderStroke(SettingsDesignTokens.focusStroke, colors.focusOutline),
                        shape = RoundedCornerShape(SettingsDesignTokens.groupRadius),
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = SettingsDesignTokens.space12,
                        vertical = SettingsDesignTokens.space8,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back_to_category, parentTitle),
                        tint = colors.primaryText,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = parentTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primaryText,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.primaryText,
                modifier = Modifier.semantics { heading() },
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondaryText,
                )
            }
        }
        if (onSearch != null) {
            TvIconButton(onClick = onSearch, modifier = searchModifier) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.settings_search_action),
                    tint = colors.primaryText,
                )
            }
        }
    }
}
