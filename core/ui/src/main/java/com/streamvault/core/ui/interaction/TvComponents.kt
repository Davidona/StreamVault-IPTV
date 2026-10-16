package com.streamvault.core.ui.interaction

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonGlow
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Surface
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.contentOnColor
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.LocalBrowseVisuals
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for TV Material3 Surface(onClick) that automatically adds
 * [mouseClickable] to the modifier so the first finger-tap fires onClick on phones/tablets,
 * while D-pad and mouse navigation on TV remain unchanged.
 */
@Composable
fun TvClickableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: ClickableSurfaceShape = if (LocalBrowseVisuals.current) ClickableSurfaceDefaults.shape(BrowseShapes.Control) else ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = if (LocalBrowseVisuals.current) ClickableSurfaceDefaults.colors(
        containerColor = BrowseColors.SurfaceElevated,
        focusedContainerColor = BrowseColors.SurfaceEmphasis,
        contentColor = BrowseColors.TextPrimary,
        focusedContentColor = BrowseColors.TextPrimary,
    ) else ClickableSurfaceDefaults.colors(),
    border: ClickableSurfaceBorder = if (LocalBrowseVisuals.current) ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, BrowseColors.Outline), shape = BrowseShapes.Control),
        focusedBorder = Border(BorderStroke(2.dp, BrowseColors.Focus), shape = BrowseShapes.Control),
    ) else ClickableSurfaceDefaults.border(),
    scale: ClickableSurfaceScale = if (LocalBrowseVisuals.current) ClickableSurfaceDefaults.scale(focusedScale = 1.035f) else ClickableSurfaceDefaults.scale(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val activationTracker = remember { RemoteKeyActivationTracker() }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        // When a long-click handler is provided, we cannot consume the
        // activation key events ourselves: the underlying TV Surface needs to
        // observe the full DOWN → (hold) → UP sequence to recognise a
        // long-press. Forwarding only onClick on ACTION_UP (as activateOnRemoteKey
        // does) collapses every key press into a click and silently breaks
        // onLongClick on the D-pad / Enter / Space / Button A.
        modifier = modifier
            .then(
                if (onLongClick != null) Modifier
                else Modifier.activateOnRemoteKey(
                    tracker = activationTracker,
                    enabled = enabled,
                    onClick = onClick
                )
            )
            .mouseClickable(onClick = onClick, enabled = enabled, onLongClick = onLongClick),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        scale = scale,
        glow = glow,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for TV Material3 Button(onClick) that automatically adds
 * [mouseClickable] so the first finger-tap fires onClick on phones/tablets.
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: ButtonScale = if (LocalBrowseVisuals.current) ButtonDefaults.scale(focusedScale = 1.035f) else ButtonDefaults.scale(),
    glow: ButtonGlow = ButtonDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    shape: ButtonShape = if (LocalBrowseVisuals.current) ButtonDefaults.shape(BrowseShapes.Control) else ButtonDefaults.shape(),
    colors: ButtonColors = if (LocalBrowseVisuals.current) ButtonDefaults.colors(
        containerColor = BrowseColors.Brand.copy(alpha = 0.14f),
        contentColor = BrowseColors.TextPrimary,
        focusedContainerColor = BrowseColors.BrandStrong,
        focusedContentColor = contentOnColor(BrowseColors.BrandStrong),
    ) else ButtonDefaults.colors(),
    border: ButtonBorder = if (LocalBrowseVisuals.current) ButtonDefaults.border(
        border = Border(BorderStroke(1.dp, BrowseColors.Outline), shape = BrowseShapes.Control),
        focusedBorder = Border(BorderStroke(2.dp, BrowseColors.Focus), shape = BrowseShapes.Control),
    ) else ButtonDefaults.border(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val activationTracker = remember { RemoteKeyActivationTracker() }
    Button(
        onClick = onClick,
        modifier = modifier
            .activateOnRemoteKey(tracker = activationTracker, enabled = enabled, onClick = onClick)
            .mouseClickable(onClick = onClick, enabled = enabled),
        enabled = enabled,
        scale = scale,
        glow = glow,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Drop-in replacement for TV Material3 IconButton(onClick) that automatically adds
 * [mouseClickable] so the first finger-tap fires onClick on phones/tablets.
 */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = if (LocalBrowseVisuals.current) IconButtonDefaults.scale(focusedScale = 1.035f) else IconButtonDefaults.scale(),
    glow: ButtonGlow = IconButtonDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    shape: ButtonShape = if (LocalBrowseVisuals.current) IconButtonDefaults.shape(BrowseShapes.Control) else IconButtonDefaults.shape(),
    colors: ButtonColors = if (LocalBrowseVisuals.current) IconButtonDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = BrowseColors.TextSecondary,
        focusedContainerColor = BrowseColors.SurfaceEmphasis,
        focusedContentColor = BrowseColors.TextPrimary,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = BrowseColors.TextDisabled,
    ) else IconButtonDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = AppColors.TextPrimary,
        focusedContainerColor = AppColors.SurfaceEmphasis,
        focusedContentColor = AppColors.TextPrimary,
    ),
    border: ButtonBorder = if (LocalBrowseVisuals.current) IconButtonDefaults.border(
        focusedBorder = Border(BorderStroke(2.dp, BrowseColors.Focus), shape = BrowseShapes.Control)
    ) else IconButtonDefaults.border(),
    content: @Composable BoxScope.() -> Unit,
) {
    val activationTracker = remember { RemoteKeyActivationTracker() }
    IconButton(
        onClick = onClick,
        onLongClick = onLongClick,
        // Same reason as TvClickableSurface: if a long-click handler is set,
        // let the native TV IconButton see the full key sequence instead of
        // collapsing it via activateOnRemoteKey.
        modifier = modifier
            .then(
                if (onLongClick != null) Modifier
                else Modifier.activateOnRemoteKey(
                    tracker = activationTracker,
                    enabled = enabled,
                    onClick = onClick
                )
            )
            .mouseClickable(onClick = onClick, enabled = enabled),
        enabled = enabled,
        scale = scale,
        glow = glow,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
        border = border,
        content = content,
    )
}

internal enum class RemoteActivationHandling {
    Ignore,
    Consume,
    Activate,
}

internal fun remoteActivationHandling(
    enabled: Boolean,
    keyCode: Int,
    action: Int,
    hasLongClick: Boolean,
): RemoteActivationHandling {
    if (!enabled || hasLongClick) return RemoteActivationHandling.Ignore

    val isActivationKey = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }
    if (!isActivationKey) return RemoteActivationHandling.Ignore

    return when (action) {
        KeyEvent.ACTION_UP -> RemoteActivationHandling.Activate
        KeyEvent.ACTION_DOWN -> RemoteActivationHandling.Consume
        else -> RemoteActivationHandling.Ignore
    }
}

/**
 * Tracks a remote activation key across DOWN/UP for a single control so it only
 * fires [RemoteActivationHandling.Activate] after receiving the matching DOWN.
 *
 * Compose dispatches each key event to whatever node holds focus at that moment.
 * A control can therefore gain focus between the DOWN and UP of one physical
 * press (for example, a back button that appears after OK opened an overlay).
 * Without this guard the orphaned UP would be treated as that control's own
 * activation, making a single OK press both open the overlay and immediately
 * dismiss it.
 */
internal class RemoteKeyActivationTracker {
    private var downReceived = false

    fun handlingFor(
        enabled: Boolean,
        keyCode: Int,
        action: Int,
        hasLongClick: Boolean,
    ): RemoteActivationHandling =
        when (remoteActivationHandling(enabled, keyCode, action, hasLongClick)) {
            RemoteActivationHandling.Ignore -> {
                if (action == KeyEvent.ACTION_UP) downReceived = false
                RemoteActivationHandling.Ignore
            }
            RemoteActivationHandling.Consume -> {
                downReceived = true
                RemoteActivationHandling.Consume
            }
            RemoteActivationHandling.Activate -> {
                val activate = downReceived
                downReceived = false
                if (activate) RemoteActivationHandling.Activate else RemoteActivationHandling.Consume
            }
        }
}

private fun Modifier.activateOnRemoteKey(
    tracker: RemoteKeyActivationTracker,
    enabled: Boolean,
    onClick: () -> Unit
): Modifier = onPreviewKeyEvent { event ->
    val nativeEvent = event.nativeKeyEvent
    when (
        tracker.handlingFor(
            enabled = enabled,
            keyCode = nativeEvent.keyCode,
            action = nativeEvent.action,
            hasLongClick = false,
        )
    ) {
        RemoteActivationHandling.Ignore -> false
        RemoteActivationHandling.Consume -> true
        RemoteActivationHandling.Activate -> {
            onClick()
            true
        }
    }
}
