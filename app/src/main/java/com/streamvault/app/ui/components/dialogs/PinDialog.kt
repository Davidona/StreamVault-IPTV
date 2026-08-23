package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.design.requestFocusSafely
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.core.ui.design.FocusSpec
import androidx.tv.material3.Border

/**
 * PIN entry dialog with a D-pad-navigable numeric keypad ג€” no hidden text field.
 * Each digit button is a full TV-focusable surface, so users can navigate with
 * the remote directional pad even without a software keyboard.
 */
@Composable
fun PinDialog(
    onDismissRequest: () -> Unit,
    onPinEntered: (String) -> Unit,
    title: String? = null,
    error: String? = null
) {
    var pin by remember { mutableStateOf("") }
    var canInteract by remember { mutableStateOf(false) }
    val firstKeyFocusRequester = remember { FocusRequester() }
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val blockOpenGesture = rememberDialogOpenGestureBlocker(canInteract)

    LaunchedEffect(Unit) {
        firstKeyFocusRequester.requestFocusSafely(tag = "PinDialog", target = "PIN keypad")
        delay(500)
        canInteract = true
    }

    // Auto-submit when all 4 digits entered
    LaunchedEffect(pin) {
        if (pin.length == 4) {
            val submittedPin = pin
            delay(200)
            if (pin == submittedPin) {
                onPinEntered(submittedPin)
                pin = ""
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = canInteract,
            dismissOnClickOutside = canInteract,
            usePlatformDefaultWidth = false
        )
    ) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            Box(
                modifier = resolvedModifier
                    .onPreviewKeyEvent(blockOpenGesture)
                    .background(SurfaceElevated, RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = title ?: stringResource(R.string.pin_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) { index ->
                            val isFilled = index < pin.length
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isFilled) Primary else Color.White.copy(alpha = 0.1f))
                                    .border(1.dp, if (isFilled) Primary else Color.White.copy(alpha = 0.3f), CircleShape)
                            )
                        }
                    }

                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorColor
                        )
                    }

                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "ג«")
                    val handleKeyPress: (String) -> Unit = { key ->
                        if (canInteract) {
                            if (key == "ג«") {
                                if (pin.isNotEmpty()) pin = pin.dropLast(1)
                            } else if (pin.length < 4) {
                                pin += key
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.chunked(3).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                row.forEach { key ->
                                    val isFirst = key == "1"
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (key.isEmpty()) {
                                            Spacer(modifier = Modifier.fillMaxWidth().height(48.dp))
                                        } else {
                                            Button(
                                                onClick = { handleKeyPress(key) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .then(if (isFirst) Modifier.focusRequester(firstKeyFocusRequester) else Modifier)
                                                    .mouseClickable(onClick = { handleKeyPress(key) }),
                                                colors = ButtonDefaults.colors(
                                                    containerColor = Color.White.copy(alpha = 0.08f),
                                                    contentColor = Color.White,
                                                    focusedContainerColor = Primary.copy(alpha = 0.35f),
                                                    focusedContentColor = Color.White
                                                )
                                            ) {
                                                Text(
                                                    text = key,
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val dismissHandler = { if (canInteract) onDismissRequest() }
                    Button(
                        onClick = dismissHandler,
                        modifier = Modifier.mouseClickable(onClick = dismissHandler),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White.copy(alpha = 0.7f),
                            focusedContainerColor = Color.White.copy(alpha = 0.12f),
                            focusedContentColor = Color.White
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = androidx.compose.foundation.BorderStroke(FocusSpec.BorderWidth, FocusBorder)
                            )
                        ),
                        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                    ) {
                        Text(stringResource(R.string.pin_dialog_cancel))
                    }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.width(360.dp))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogModifier = when {
                    maxWidth < 700.dp -> Modifier.fillMaxWidth(0.9f)
                    maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.54f)
                    else -> Modifier.width(360.dp)
                }
                dialogContent(dialogModifier)
            }
        }
    }
}
