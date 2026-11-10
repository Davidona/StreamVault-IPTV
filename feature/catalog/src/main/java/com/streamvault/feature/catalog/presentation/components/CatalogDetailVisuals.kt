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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.design.BrowseFocusSpec
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.focusRingButtonBorder
import com.streamvault.core.ui.design.secondaryButtonColors
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.feature.catalog.R

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
    TvButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = detailControlShape),
        // A focused primary stays filled brand; the ring and scale carry focus.
        colors = if (primary) {
            ButtonDefaults.colors(
                containerColor = AppColors.Brand,
                contentColor = AppColors.OnPrimary,
                focusedContainerColor = AppColors.Brand,
                focusedContentColor = AppColors.OnPrimary
            )
        } else {
            secondaryButtonColors()
        },
        border = focusRingButtonBorder(detailControlShape),
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

/**
 * Back is an icon-only circle in the secondary control style (opaque fill, no scrim), like the favorite
 * button. The fill stays readable over any hero artwork, unlike a translucent scrim on a dark hero top.
 */
@Composable
fun DetailBackButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(BrowseFocusSpec.SelectableRingWidth, AppColors.Focus),
                shape = CircleShape
            )
        ),
        colors = ButtonDefaults.colors(
            containerColor = AppColors.ControlContainer,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.SurfaceAccent,
            focusedContentColor = AppColors.TextPrimary
        )
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = contentDescription)
    }
}

/** Favorite is a secondary control in both states; on and off differ by icon shape, not by a brand fill. */
@Composable
fun DetailFavoriteButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvIconButton(
        onClick = onToggle,
        modifier = modifier.size(48.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape = CircleShape),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(BrowseFocusSpec.SelectableRingWidth, AppColors.Focus),
                shape = CircleShape
            )
        ),
        colors = ButtonDefaults.colors(
            containerColor = AppColors.ControlContainer,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.SurfaceAccent,
            focusedContentColor = AppColors.TextPrimary
        )
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(
                if (isFavorite) R.string.favorites_remove else R.string.favorites_add
            )
        )
    }
}

/** Version picker entry: selected is the tonal container (never a second filled-brand button next to Play). */
@Composable
fun DetailVariantButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvButton(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = BrowseShapes.Control),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) AppColors.SelectedContainer else AppColors.ControlContainer,
            contentColor = if (selected) AppColors.OnSelectedContainer else AppColors.TextPrimary,
            focusedContainerColor = if (selected) AppColors.SelectedContainer else AppColors.SurfaceAccent,
            focusedContentColor = if (selected) AppColors.OnSelectedContainer else AppColors.TextPrimary
        ),
        border = focusRingButtonBorder(BrowseShapes.Control)
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
