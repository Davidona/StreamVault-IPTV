package com.streamvault.feature.provider.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text
import com.streamvault.feature.provider.R
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.manager.DriveBackupSnapshot

@Composable
internal fun ImportOptionsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }

    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .height(if (compact) 38.dp else 44.dp)
            .onFocusEvent { isFocused = it.hasFocus }
            .semantics { contentDescription = text },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Surface.copy(alpha = 0.9f),
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (isFocused) PrimaryLight else SurfaceHighlight)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SettingsBackupRestore,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp),
                tint = if (isFocused) TextPrimary else OnSurface
            )
            Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            Text(
                text = text,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = if (isFocused) TextPrimary else OnSurface
            )
        }
    }
}

@Composable
internal fun ImportOptionsDialog(
    isImportingBackup: Boolean,
    driveSignedIn: Boolean,
    onDismiss: () -> Unit,
    onImportBackup: () -> Unit,
    onImportFromDrive: () -> Unit,
    onDriveSignIn: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.settings_backup_restore),
        subtitle = stringResource(R.string.settings_restore_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.34f,
        heightFraction = null,
        bodyHeightFraction = 0.28f,
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                ImportDialogActionButton(
                    text = stringResource(R.string.setup_import_backup),
                    isLoading = isImportingBackup,
                    onClick = onImportBackup
                )
                if (driveSignedIn) {
                    ImportDialogActionButton(
                        text = stringResource(R.string.settings_drive_pull),
                        isLoading = isImportingBackup,
                        onClick = onImportFromDrive
                    )
                } else {
                    ImportDialogActionButton(
                        text = stringResource(R.string.settings_drive_signin),
                        isLoading = false,
                        onClick = onDriveSignIn
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.add_group_cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
internal fun DriveBackupSnapshotChoiceDialog(
    snapshots: List<DriveBackupSnapshot>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PremiumDialog(
        title = stringResource(R.string.settings_drive_choose_backup_title),
        subtitle = stringResource(R.string.settings_drive_choose_backup_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.52f,
        heightFraction = 0.82f,
        bodyHeightFraction = 0.64f,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                snapshots.forEach { snapshot ->
                    SmallActionButton(
                        text = snapshot.fileName,
                        isLoading = false,
                        onClick = { onSelect(snapshot.id) },
                    )
                    Text(
                        text = formatDriveSnapshotDetails(snapshot),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.72f),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_drive_cancel_backup_choice),
                onClick = onDismiss,
            )
        },
    )
}

private fun formatDriveSnapshotDetails(snapshot: DriveBackupSnapshot): String {
    val date = snapshot.modifiedAtMs?.let {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(it))
    } ?: "Date unavailable"
    val size = if (snapshot.sizeBytes > 0L) "${snapshot.sizeBytes / 1024L} KB" else "Size unavailable"
    return "$date ֲ· $size"
}

@Composable
private fun ImportDialogActionButton(
    text: String,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(0.72f)) {
        SmallActionButton(
            text = text,
            isLoading = isLoading,
            onClick = onClick
        )
    }
}

// ??? FileSelectorCard ?????????????????????????????????????????????????????????

@Composable
internal fun FileSelectorCard(
    fileName: String?,
    fileSelectedHint: String,
    emptySelectionTitle: String,
    emptySelectionHint: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) Primary else SurfaceHighlight
    val bgColor     = if (isFocused) Surface  else SurfaceElevated

    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(80.dp).onFocusEvent { isFocused = it.hasFocus }.mouseClickable(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, borderColor)),
            focusedBorder = Border(BorderStroke(2.dp, FocusBorder))
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = bgColor, focusedContainerColor = bgColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (fileName != null) {
                Text(text = fileName, style = MaterialTheme.typography.bodyLarge, color = OnBackground, textAlign = TextAlign.Center)
                Text(text = fileSelectedHint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            } else {
                Text(text = emptySelectionTitle, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                Text(text = emptySelectionHint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
            }
        }
    }
}
