package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton

data class LiveAddQuickFilterDialogLabels(
    val title: String,
    val subtitle: String,
    val placeholder: String,
    val action: String,
    val cancel: String
)

@Composable
fun LiveAddQuickFilterDialog(
    savedFilters: List<String>,
    labels: LiveAddQuickFilterDialogLabels,
    onAddFilter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingFilter by rememberSaveable { mutableStateOf("") }
    PremiumDialog(
        title = labels.title,
        subtitle = labels.subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.42f,
        content = {
            SearchInput(
                value = pendingFilter,
                onValueChange = { pendingFilter = it },
                placeholder = labels.placeholder,
                modifier = Modifier.fillMaxWidth()
            )
            PremiumDialogActionButton(
                label = labels.action,
                enabled = pendingFilter.isNotBlank(),
                onClick = {
                    val normalized = pendingFilter.trim()
                    val isDuplicate = savedFilters.any {
                        it.equals(normalized, ignoreCase = true)
                    }
                    onAddFilter(pendingFilter)
                    if (normalized.isNotBlank() && !isDuplicate) {
                        onDismiss()
                    }
                }
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = labels.cancel,
                onClick = onDismiss
            )
        }
    )
}
