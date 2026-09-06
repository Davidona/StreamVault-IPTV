package com.streamvault.app.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FocusSpec {
    const val FocusedScale = 1.06f
    const val PressedScale = 0.98f

    // The original blue theme uses a thick bright ring; the M3 purple theme
    // uses a subtle 2dp border-role ring. Follows the active palette.
    val BorderWidth: Dp get() = if (AppColors.current.isDark) 2.dp else 3.dp
    val CardBorderWidth: Dp get() = if (AppColors.current.isDark) 2.dp else 4.dp
}
