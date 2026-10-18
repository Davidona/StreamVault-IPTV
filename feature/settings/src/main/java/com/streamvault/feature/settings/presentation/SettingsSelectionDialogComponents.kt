package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvClickableSurface

/** A compact, shared shell for settings dialogs that choose one value from a list. */
@Composable
internal fun SettingsSingleChoiceDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    widthFraction: Float = 0.52f,
    bodyHeightFraction: Float = 0.62f,
    content: @Composable ColumnScope.() -> Unit,
) {
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = widthFraction,
        heightFraction = null,
        bodyHeightFraction = bodyHeightFraction,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = androidx.compose.ui.res.stringResource(R.string.settings_cancel),
                onClick = onDismiss,
            )
        },
    )
}

/** Focus is shown with a steady outline; selection is shown with a quiet theme tint and check. */
@Composable
internal fun SettingsChoiceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = AppColors.current
    val shape = RoundedCornerShape(14.dp)
    val container = if (selected) lerp(palette.surfaceElevated, palette.brand, 0.14f)
        else palette.surfaceElevated

    TvClickableSurface(
        onClick = onSelect,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = container,
            contentColor = palette.textPrimary,
            focusedContentColor = palette.textPrimary,
            disabledContainerColor = palette.surface.copy(alpha = 0.7f),
            disabledContentColor = palette.textDisabled,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, palette.outline.copy(alpha = 0.72f)),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, palette.focus),
                shape = shape,
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (description.isNullOrBlank()) 56.dp else 68.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = label,
                    style = if (description.isNullOrBlank()) {
                        MaterialTheme.typography.bodyLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    color = if (enabled) palette.textPrimary else palette.textDisabled,
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) palette.textSecondary else palette.textDisabled,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = palette.brand,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Spacer(Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun PremiumSelectionDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsSingleChoiceDialog(
        title = title,
        onDismiss = onDismiss,
        widthFraction = 0.62f,
        bodyHeightFraction = 0.66f,
        content = content,
    )
}

@Composable
fun LevelOption(
    level: Int,
    text: String,
    currentLevel: Int,
    subtitle: String? = null,
    onSelect: () -> Unit,
) {
    SettingsChoiceOption(
        label = text,
        description = subtitle,
        selected = level == currentLevel,
        onSelect = onSelect,
    )
}
