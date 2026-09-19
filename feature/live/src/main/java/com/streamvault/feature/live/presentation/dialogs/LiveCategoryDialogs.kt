package com.streamvault.feature.live.presentation.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton

data class LiveCategoryDialogOption(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val emphasized: Boolean = false
)

/** Shared category-options shell. Feature callers provide policy and actions as data. */
@Composable
fun LiveCategoryOptionsDialog(
    categoryName: String,
    options: List<LiveCategoryDialogOption>,
    onDismissRequest: () -> Unit,
    title: String = categoryName,
    subtitle: String? = null,
    cancelLabel: String = "Cancel"
) {
    PremiumDialog(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismissRequest,
        widthFraction = 0.42f,
        heightFraction = null,
        bodyHeightFraction = 0.72f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    PremiumDialogActionButton(
                        label = option.label,
                        onClick = { option.onClick(); onDismissRequest() },
                        enabled = option.enabled,
                        destructive = option.destructive,
                        emphasized = option.emphasized,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(label = cancelLabel, onClick = onDismissRequest)
        }
    )
}

@Composable
fun LiveCategoryRenameDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String = "Rename category",
    placeholder: String = "Category name",
    confirmLabel: String = "Save",
    cancelLabel: String = "Cancel"
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    PremiumDialog(
        title = title,
        onDismissRequest = onDismissRequest,
        heightFraction = null,
        bodyHeightFraction = 0.5f,
        content = {
            SearchInput(value = name, onValueChange = { name = it }, placeholder = placeholder, modifier = Modifier.fillMaxWidth())
        },
        footer = {
            PremiumDialogFooterButton(label = cancelLabel, onClick = onDismissRequest)
            PremiumDialogFooterButton(label = confirmLabel, onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank(), emphasized = true)
        }
    )
}

@Composable
fun LiveCategoryDeleteDialog(
    categoryName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    title: String = "Delete category",
    message: String = "This action cannot be undone.",
    cancelLabel: String = "Cancel",
    confirmLabel: String = "Delete"
) {
    PremiumDialog(
        title = title,
        subtitle = "$categoryName\n$message",
        onDismissRequest = onDismissRequest,
        heightFraction = null,
        bodyHeightFraction = 0.3f,
        content = {},
        footer = {
            PremiumDialogFooterButton(label = cancelLabel, onClick = onDismissRequest)
            PremiumDialogFooterButton(label = confirmLabel, onClick = onConfirm, destructive = true)
        }
    )
}
