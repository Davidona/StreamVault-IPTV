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
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.MaterialTheme
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
    val shape = RoundedCornerShape(8.dp)
    TvButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = AppColors.Brand,
            contentColor = AppColors.OnPrimary,
            focusedContainerColor = AppColors.Brand.copy(alpha = 0.88f),
            focusedContentColor = AppColors.OnPrimary,
            disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.56f),
            disabledContentColor = AppColors.TextDisabled,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = shape,
            ),
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        contentPadding = contentPadding,
        content = content,
    )
}
