package com.streamvault.core.ui.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity

/** Applies the selected text scale inside Material dialog content slots. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
) {
    val density = LocalDensity.current
    fun scaled(slot: (@Composable () -> Unit)?): (@Composable () -> Unit)? = slot?.let {
        { CompositionLocalProvider(LocalDensity provides density, content = it) }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { CompositionLocalProvider(LocalDensity provides density, content = confirmButton) },
        dismissButton = scaled(dismissButton),
        title = scaled(title),
        text = scaled(text),
        containerColor = containerColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
    )
}
