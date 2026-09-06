package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated

@Composable
fun RenameGroupDialog(
    initialName: String,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var canInteract by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTelevisionDevice = rememberIsTelevisionDevice()

    LaunchedEffect(Unit) {
        focusRequester.requestFocusSafely(tag = "RenameGroupDialog", target = "Rename group field")
        keyboardController?.show()
        delay(500)
        canInteract = true
    }

    Dialog(
        onDismissRequest = {
            if (canInteract) {
                keyboardController?.hide()
                onDismissRequest()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            Surface(
                modifier = resolvedModifier,
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Primary.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                Text(
                    text = stringResource(R.string.category_options_rename),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnSurface
                )
                Text(
                    text = stringResource(R.string.library_saved_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.add_group_name_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = OnSurface,
                        unfocusedTextColor = OnSurface,
                        cursorColor = Primary,
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Primary.copy(alpha = 0.55f),
                        focusedLabelColor = Primary,
                        unfocusedLabelColor = OnSurfaceDim,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val normalized = value.trim()
                            if (canInteract && normalized.isNotEmpty()) {
                                keyboardController?.hide()
                                onConfirm(normalized)
                            }
                        }
                    ),
                    supportingText = errorMessage?.let { message ->
                        { Text(message) }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val cancelHandler = {
                        if (canInteract) {
                            keyboardController?.hide()
                            onDismissRequest()
                        }
                    }
                    val renameHandler = {
                        val normalized = value.trim()
                        if (canInteract && normalized.isNotEmpty()) {
                            keyboardController?.hide()
                            onConfirm(normalized)
                        }
                    }
                    Button(
                        onClick = cancelHandler,
                        modifier = Modifier.mouseClickable(onClick = cancelHandler),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = OnSurface,
                            focusedContainerColor = Color.White.copy(alpha = 0.16f),
                            focusedContentColor = OnSurface
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                            )
                        ),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Text(stringResource(R.string.add_group_cancel))
                    }
                    Button(
                        onClick = renameHandler,
                        enabled = canInteract && value.trim().isNotEmpty(),
                        modifier = Modifier.mouseClickable(
                            enabled = canInteract && value.trim().isNotEmpty(),
                            onClick = renameHandler
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.Black,
                            focusedContainerColor = Primary.copy(alpha = 0.84f),
                            focusedContentColor = Color.Black
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                            )
                        ),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Text(stringResource(R.string.add_group_rename))
                    }
                }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.fillMaxWidth(0.42f))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogModifier = when {
                    maxWidth < 700.dp -> Modifier.fillMaxWidth(0.9f)
                    maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.58f)
                    else -> Modifier.fillMaxWidth(0.42f)
                }
                dialogContent(dialogModifier)
            }
        }
    }
}
