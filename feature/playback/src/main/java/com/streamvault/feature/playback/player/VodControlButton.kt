package com.streamvault.feature.playback.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.IconButtonDefaults
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvIconButton

/** Round stepper button for the panel lists: tonal at rest, filled with the focus color when focused. */
@Composable
internal fun VodControlButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    TvIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.colors(
            containerColor = AppColors.current.controlContainer,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Focus,
            focusedContentColor = AppColors.Canvas,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = AppColors.TextDisabled
        ),
        scale = IconButtonDefaults.scale(focusedScale = 1.06f),
        content = content
    )
}
