package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated

public data class BackupDialogItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
)

@Composable
public fun BackupSelectionDialog(
    title: String,
    subtitle: String,
    items: List<BackupDialogItem>,
    emptyMessage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.62f,
        heightFraction = 0.78f,
        bodyHeightFraction = 0.58f,
        bodyScrollHint = stringResource(R.string.settings_backup_preview_scroll_hint),
        initialBodyFocusRequester = firstItemFocusRequester.takeIf { items.isNotEmpty() },
        content = {
            if (items.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                )
            } else {
                items.forEachIndexed { index, item ->
                    BackupDialogItemSurface(
                        item = item,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                        onClick = { onSelect(item.id) },
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
public fun BackupManagementDialog(
    title: String,
    subtitle: String,
    items: List<BackupDialogItem>,
    emptyMessage: String,
    isBusy: Boolean = false,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<BackupDialogItem?>(null) }
    val firstItemFocusRequester = remember { FocusRequester() }

    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.62f,
        heightFraction = 0.78f,
        bodyHeightFraction = 0.58f,
        bodyScrollHint = stringResource(R.string.settings_backup_preview_scroll_hint),
        initialBodyFocusRequester = firstItemFocusRequester.takeIf { items.isNotEmpty() },
        content = {
            if (items.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                )
            } else {
                items.forEachIndexed { index, item ->
                    BackupDialogManagementRow(
                        item = item,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                        enabled = !isBusy,
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                enabled = !isBusy,
            )
        },
    )

    deleteTarget?.let { item ->
        PremiumDialog(
            title = stringResource(R.string.settings_backup_delete_title),
            onDismissRequest = { deleteTarget = null },
            widthFraction = 0.44f,
            heightFraction = null,
            bodyHeightFraction = 0.22f,
            content = {
                Text(
                    text = stringResource(R.string.settings_backup_delete_body, item.title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                )
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = { deleteTarget = null },
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_delete),
                    onClick = {
                        deleteTarget = null
                        onDelete(item.id)
                    },
                    destructive = true,
                    emphasized = true,
                )
            },
        )
    }
}

@Composable
private fun BackupDialogItemSurface(
    item: BackupDialogItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated.copy(alpha = 0.72f),
            focusedContainerColor = Primary.copy(alpha = 0.28f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim,
                    )
                }
            }
            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
            )
        }
    }
}

@Composable
private fun BackupDialogManagementRow(
    item: BackupDialogItem,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                )
            }
        }
        TvClickableSurface(
            onClick = onDelete,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = SurfaceElevated,
                focusedContainerColor = com.streamvault.core.ui.theme.SurfaceHighlight,
                disabledContainerColor = SurfaceElevated.copy(alpha = 0.55f),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Text(
                text = stringResource(R.string.settings_delete),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
            )
        }
    }
}
