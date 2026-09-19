package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.design.AppColors

@Composable
internal fun SettingsPageCard(page: SettingsPage, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    SettingsActionSurface(onClick, modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = SettingsDesignTokens.explanatoryRowMinHeight)
            .padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space8),
            horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4)) {
                Text(stringResource(page.title), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium, color = colors.primaryText)
                Text(stringResource(page.description), style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}
