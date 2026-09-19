package com.streamvault.feature.settings.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.design.AppPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class SettingsColorRoles(
    val canvas: Color,
    val groupSurface: Color,
    val overviewRowSurface: Color,
    val detailRowSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val disabledText: Color,
    val accent: Color,
    val focusOutline: Color,
    val focusedSurface: Color,
    val divider: Color,
    val error: Color,
    val searchSurface: Color,
    val searchFocusedSurface: Color,
    val searchText: Color,
    val searchPlaceholder: Color,
    val cursor: Color,
    val selection: Color,
)

object SettingsDesignTokens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp
    val space32 = 32.dp

    val tvHorizontalInset = 32.dp
    val tvVerticalInset = 24.dp
    val compactInset = 16.dp
    val railWidth = 208.dp
    val railContentGap = 24.dp
    val contentMaxWidth = 720.dp
    val headerMinHeight = 56.dp
    val simpleRowMinHeight = 56.dp
    val explanatoryRowMinHeight = 72.dp
    val groupRadius = 12.dp
    val focusStroke = 2.dp

    fun colors(palette: AppPalette): SettingsColorRoles = SettingsColorRoles(
        canvas = palette.canvas,
        groupSurface = palette.surface,
        overviewRowSurface = palette.surface,
        detailRowSurface = palette.surface,
        primaryText = palette.textPrimary,
        secondaryText = palette.textSecondary,
        // Disabled rows still explain prerequisites, so their copy remains informative text.
        disabledText = palette.textSecondary,
        accent = palette.brand,
        focusOutline = palette.focus,
        focusedSurface = palette.surfaceAccent,
        divider = palette.divider,
        error = palette.live,
        searchSurface = palette.surfaceElevated,
        searchFocusedSurface = palette.surfaceEmphasis,
        searchText = palette.textPrimary,
        searchPlaceholder = palette.textSecondary,
        cursor = palette.brandStrong,
        selection = palette.brandMuted,
    )
}

internal fun settingsContrastRatio(foreground: Color, background: Color): Double {
    val lighter = max(settingsRelativeLuminance(foreground), settingsRelativeLuminance(background))
    val darker = min(settingsRelativeLuminance(foreground), settingsRelativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun settingsRelativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) +
        0.7152 * linear(color.green) +
        0.0722 * linear(color.blue)
}
