package com.streamvault.core.ui.components.dialogs

import androidx.compose.runtime.Composable
import com.streamvault.core.ui.components.dialogs.PinEntryDialog

@Composable
fun PinDialog(
    onDismissRequest: () -> Unit,
    onPinEntered: (String) -> Unit,
    title: String,
    cancelLabel: String,
    error: String? = null
) {
    PinEntryDialog(
        title = title,
        cancelLabel = cancelLabel,
        onDismissRequest = onDismissRequest,
        onPinEntered = onPinEntered,
        error = error
    )
}
