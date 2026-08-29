package com.streamvault.feature.live.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import kotlinx.coroutines.delay

data class LiveDeleteGroupDialogLabels(
    val title: String,
    val body: String,
    val cancel: String,
    val confirm: String
)

@Composable
fun LiveDeleteGroupDialog(
    labels: LiveDeleteGroupDialogLabels,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var canInteract by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(500)
        canInteract = true
    }

    val safeDismiss = {
        if (canInteract) onDismiss()
    }

    PremiumDialog(
        title = labels.title,
        subtitle = labels.body,
        onDismissRequest = safeDismiss,
        widthFraction = 0.36f,
        content = {},
        footer = {
            PremiumDialogFooterButton(
                label = labels.cancel,
                onClick = safeDismiss
            )
            PremiumDialogFooterButton(
                label = labels.confirm,
                onClick = { if (canInteract) onConfirm() },
                destructive = true
            )
        }
    )
}
