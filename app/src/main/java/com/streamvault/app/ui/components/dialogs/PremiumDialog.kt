package com.streamvault.app.ui.components.dialogs

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec

internal val LocalDialogCanInteract = compositionLocalOf { true }

@Composable
internal fun rememberDialogOpenGestureBlocker(canInteract: Boolean): (KeyEvent) -> Boolean = remember(canInteract) {
    { event ->
        !canInteract &&
            event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP &&
            when (event.nativeKeyEvent.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                AndroidKeyEvent.KEYCODE_BUTTON_A -> true
                else -> false
            }
    }
}

@Composable
fun PremiumDialog(
    title: String,
    subtitle: String? = null,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.42f,
    heightFraction: Float? = 0.88f,
    bodyHeightFraction: Float = 0.5f,
    bodyScrollHint: String? = null,
    initialBodyFocusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit = {}
) {
    var canInteract by remember { mutableStateOf(false) }
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val blockOpenGesture = rememberDialogOpenGestureBlocker(canInteract)
    LaunchedEffect(Unit) { delay(500); canInteract = true }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = canInteract,
            dismissOnClickOutside = canInteract,
            usePlatformDefaultWidth = false
        )
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDialogCanInteract provides canInteract) {
            if (isTelevisionDevice) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val maxDialogBodyHeight = maxHeight * bodyHeightFraction
                    val dialogModifier = modifier
                        .fillMaxWidth(widthFraction)
                        .then(
                            if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier
                        )
                        .onPreviewKeyEvent(blockOpenGesture)
                    Surface(
                        modifier = dialogModifier,
                        shape = RoundedCornerShape(28.dp),
                        colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            AppColors.BrandMuted.copy(alpha = 0.18f),
                                            AppColors.SurfaceElevated,
                                            AppColors.Surface
                                        )
                                    )
                                )
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = AppColors.TextPrimary
                                )
                                if (!subtitle.isNullOrBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }

                            PremiumDialogScrollableBody(
                                maxHeight = maxDialogBodyHeight,
                                scrollHint = bodyScrollHint,
                                initialFocusRequester = initialBodyFocusRequester,
                                content = content,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End),
                                content = footer
                            )
                        }
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val resolvedWidthFraction = when {
                        maxWidth < 700.dp -> 0.9f
                        maxWidth < 1000.dp -> maxOf(widthFraction, 0.62f)
                        else -> widthFraction
                    }
                    val maxDialogBodyHeight = maxHeight * bodyHeightFraction
                    val dialogModifier = modifier
                        .fillMaxWidth(resolvedWidthFraction)
                        .then(
                            if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier
                        )
                        .onPreviewKeyEvent(blockOpenGesture)

                    Surface(
                        modifier = dialogModifier,
                        shape = RoundedCornerShape(28.dp),
                        colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated)
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            AppColors.BrandMuted.copy(alpha = 0.18f),
                                            AppColors.SurfaceElevated,
                                            AppColors.Surface
                                        )
                                    )
                                )
                                .padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = AppColors.TextPrimary
                                )
                                if (!subtitle.isNullOrBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }

                            PremiumDialogScrollableBody(
                                maxHeight = maxDialogBodyHeight,
                                scrollHint = bodyScrollHint,
                                initialFocusRequester = initialBodyFocusRequester,
                                content = content,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, androidx.compose.ui.Alignment.End),
                                content = footer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PremiumDialogScrollableBody(
    maxHeight: androidx.compose.ui.unit.Dp,
    scrollHint: String?,
    initialFocusRequester: androidx.compose.ui.focus.FocusRequester?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val canScrollUp by remember { derivedStateOf { scrollState.value > 0 } }
    val canScrollDown by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }
    LaunchedEffect(initialFocusRequester) {
        initialFocusRequester?.requestFocusSafely(
            tag = "PremiumDialogScrollableBody",
            target = "Initial body focus"
        )
    }
    Box(
        modifier = Modifier
            .weight(1f, fill = false)
            .heightIn(max = maxHeight)
            .fillMaxWidth()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                val delta = when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -240
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 240
                    else -> 0
                }
                if (delta == 0 || scrollState.maxValue == 0) {
                    return@onPreviewKeyEvent false
                }
                val target = (scrollState.value + delta).coerceIn(0, scrollState.maxValue)
                if (target != scrollState.value) {
                    coroutineScope.launch {
                        scrollState.animateScrollTo(target)
                    }
                }
                // Keep the event available to focus navigation. This scrolls
                // the body while the focused control advances normally.
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }

        if (!scrollHint.isNullOrBlank() && (canScrollUp || canScrollDown)) {
            val arrow = when {
                canScrollUp && canScrollDown -> "↕"
                canScrollDown -> "↓"
                else -> "↑"
            }
            Text(
                text = "$arrow  $scrollHint",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(AppColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
fun PremiumDialogActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    emphasized: Boolean = false
) {
    val canInteract = LocalDialogCanInteract.current
    val containerColor = when {
        destructive -> AppColors.Live.copy(alpha = 0.22f)
        emphasized -> AppColors.Brand
        else -> Color.White.copy(alpha = 0.08f)
    }
    val contentColor = when {
        destructive -> AppColors.TextPrimary
        emphasized -> Color.Black
        else -> AppColors.TextPrimary
    }

    Button(
        onClick = { if (canInteract) onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().mouseClickable(enabled = enabled, onClick = { if (canInteract) onClick() }),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = if (destructive) AppColors.Live else AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary,
            disabledContainerColor = AppColors.Surface.copy(alpha = 0.85f),
            disabledContentColor = AppColors.TextDisabled
        ),
        border = ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus)
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun PremiumDialogFooterButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    emphasized: Boolean = false
) {
    val canInteract = LocalDialogCanInteract.current
    val containerColor = when {
        destructive -> AppColors.Live.copy(alpha = 0.18f)
        emphasized -> AppColors.Brand
        else -> Color.White.copy(alpha = 0.08f)
    }
    val contentColor = if (emphasized) Color.Black else AppColors.TextPrimary

    Button(
        onClick = { if (canInteract) onClick() },
        enabled = enabled,
        modifier = modifier.mouseClickable(enabled = enabled, onClick = { if (canInteract) onClick() }),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = if (destructive) AppColors.Live else AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary,
            disabledContainerColor = AppColors.Surface.copy(alpha = 0.85f),
            disabledContentColor = AppColors.TextDisabled
        ),
        border = ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus)
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
