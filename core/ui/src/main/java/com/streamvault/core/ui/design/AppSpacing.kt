package com.streamvault.core.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AppSpacing(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 40.dp,
    val screenGutter: Dp = 56.dp,
    val railWidth: Dp = 124.dp,
    val sectionGap: Dp = 32.dp,
    val cardGap: Dp = 16.dp,
    val chipGap: Dp = 10.dp,
    val safeTop: Dp = 32.dp,
    val safeBottom: Dp = 32.dp,
    val safeHoriz: Dp = 56.dp
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
