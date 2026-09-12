package com.streamvault.feature.playback.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.IconButtonDefaults
import com.streamvault.core.ui.interaction.TvIconButton

/** Quiet video chrome with a high-contrast remote focus state. */
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
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF10151D),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.25f)
        ),
        scale = IconButtonDefaults.scale(focusedScale = 1.06f),
        content = content
    )
}
