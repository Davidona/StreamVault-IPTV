package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.interaction.TvButton

private val detailPanelShape = RoundedCornerShape(20.dp)
private val detailControlShape = RoundedCornerShape(11.dp)

@Composable
fun DetailInfoPanel(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(detailPanelShape)
            .background(
                Brush.horizontalGradient(
                    listOf(AppColors.SurfaceElevated.copy(alpha = 0.96f),
                        AppColors.Surface.copy(alpha = 0.89f))
                )
            )
            .border(1.dp, AppColors.Outline, detailPanelShape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

@Composable
fun DetailSectionHeading(title: String, subtitle: String? = null) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            Modifier.width(4.dp).height(if (subtitle == null) 24.dp else 38.dp)
                .background(AppColors.Brand, RoundedCornerShape(3.dp))
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
fun DetailActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = if (primary) Icons.Default.PlayArrow else null,
    compact: Boolean = false
) {
    val fill = if (primary) AppColors.Brand else AppColors.SurfaceAccent
    val ink = if (primary) AppColors.OnPrimary else AppColors.TextPrimary
    TvButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = detailControlShape),
        colors = ButtonDefaults.colors(
            containerColor = fill,
            contentColor = ink,
            focusedContainerColor = fill,
            focusedContentColor = ink
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, AppColors.Outline), shape = detailControlShape),
            focusedBorder = Border(BorderStroke(2.dp, AppColors.Focus), shape = detailControlShape)
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(
            horizontal = if (compact) 12.dp else 18.dp,
            vertical = if (compact) 8.dp else 11.dp
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelMedium
            else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
