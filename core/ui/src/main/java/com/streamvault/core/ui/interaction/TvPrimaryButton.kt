package com.streamvault.core.ui.interaction

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.LocalBrowseVisuals
import com.streamvault.core.ui.design.browsePrimaryButtonColors
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.FocusSpec

/** Shared primary action treatment for TV screens, including a white focus ring. */
@Composable
fun TvPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 7.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val browseStyle = LocalBrowseVisuals.current
    val shape = if (browseStyle) BrowseShapes.Control else RoundedCornerShape(8.dp)
    TvButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape),
        colors = if (browseStyle) browsePrimaryButtonColors() else ButtonDefaults.colors(
            containerColor = AppColors.Brand,
            contentColor = AppColors.OnPrimary,
            focusedContainerColor = AppColors.Brand.copy(alpha = 0.88f),
            focusedContentColor = AppColors.OnPrimary,
            disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.56f),
            disabledContentColor = AppColors.TextDisabled,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, if (browseStyle) BrowseColors.Focus else AppColors.Focus),
                shape = shape,
            ),
        ),
        scale = if (browseStyle) ButtonDefaults.scale(focusedScale = 1.035f, pressedScale = 0.98f)
        else ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        contentPadding = contentPadding,
        content = content,
    )
}
