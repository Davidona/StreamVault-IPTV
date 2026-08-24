package com.streamvault.feature.playback.player.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.feature.playback.R
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvClickableSurface
import java.util.Locale
import com.streamvault.core.ui.design.AppColors.Brand as Primary

@Composable
internal fun PlayerOverlayPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        border = Border(
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                AppColors.Focus.copy(alpha = 0.05f)
            )
        ),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = AppColors.Canvas.copy(alpha = 0.38f)
        )
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppColors.BrandMuted.copy(alpha = 0.06f),
                            AppColors.SurfaceElevated.copy(alpha = 0.34f),
                            AppColors.Surface.copy(alpha = 0.28f)
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
internal fun PlayerMetaRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = AppColors.TextTertiary,
            modifier = Modifier.weight(0.44f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(0.56f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PlayerOverlaySectionLabel(text: String) {
    Text(
        text = text,
        color = Primary,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        fontWeight = FontWeight.Bold
    )
}

internal fun formatTimeLabel(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

@Composable
internal fun QuickActionButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    colors: androidx.tv.material3.ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = AppColors.SurfaceEmphasis,
        focusedContainerColor = Primary.copy(alpha = 0.85f)
    ),
    onInteraction: () -> Unit = {},
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = {
            onInteraction()
            onClick()
        },
        modifier = modifier
            .widthIn(min = 84.dp, max = 138.dp)
            .onFocusChanged {
                if (it.isFocused) onInteraction()
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = colors,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                },
                label = "iconToggle"
            ) { targetIcon ->
                Text(
                    text = targetIcon.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = AppColors.BrandStrong,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}