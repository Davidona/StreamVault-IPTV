package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType

@Composable
internal fun QualityCapSelectionDialog(
    title: String,
    currentValue: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit
) {
    val context = LocalContext.current
    val options = remember(context) {
        listOf<Int?>(null, 2160, 1080, 720, 480)
    }
    PremiumSelectionDialog(
        title = title,
        onDismiss = onDismiss
    ) {
        options.forEachIndexed { index, option ->
            LevelOption(
                level = index,
                text = formatQualityCapLabel(option, context.getString(R.string.settings_quality_cap_auto)),
                currentLevel = if (option == currentValue) index else -1,
                onSelect = { onSelect(option) }
            )
        }
    }
}

@Composable
internal fun CategorySortModeDialog(
    type: ContentType,
    currentMode: CategorySortMode,
    onDismiss: () -> Unit,
    onModeSelected: (CategorySortMode) -> Unit
) {
    val context = LocalContext.current
    PremiumDialog(
        title = categoryTypeLabel(type, context),
        subtitle = categoryTypeDescription(type, context),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CategorySortMode.entries.forEach { mode ->
                    TvClickableSurface(
                        onClick = { onModeSelected(mode) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (mode == currentMode) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatCategorySortModeLabel(mode, context),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (mode == currentMode) Primary else OnBackground
                            )
                            Text(
                                text = sortModeLabel(mode, context),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim
                            )
                        }
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )
}