package com.streamvault.app.ui.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.core.ui.components.dialogs.PinEntryDialog

@Composable
fun PinDialog(
    onDismissRequest: () -> Unit,
    onPinEntered: (String) -> Unit,
    title: String? = null,
    error: String? = null
) {
    PinEntryDialog(
        title = title ?: stringResource(R.string.pin_dialog_title),
        cancelLabel = stringResource(R.string.pin_dialog_cancel),
        onDismissRequest = onDismissRequest,
        onPinEntered = onPinEntered,
        error = error
    )
}
