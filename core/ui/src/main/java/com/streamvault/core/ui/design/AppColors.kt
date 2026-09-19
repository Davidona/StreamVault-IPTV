package com.streamvault.core.ui.design

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * State-backed compatibility facade for the shared app palette.
 *
 * Existing UI components can keep using named colors while Compose observes
 * palette changes made by the single application theme root.
 */
object AppColors {
    private val activeState: MutableState<AppPalette> = mutableStateOf(AppPalette.classicBlue())

    var current: AppPalette
        get() = activeState.value
        set(value) {
            activeState.value = value
        }

    val Canvas: Color get() = current.canvas
    val CanvasElevated: Color get() = current.canvasElevated
    val Surface: Color get() = current.surface
    val SurfaceElevated: Color get() = current.surfaceElevated
    val SurfaceEmphasis: Color get() = current.surfaceEmphasis
    val SurfaceAccent: Color get() = current.surfaceAccent

    val Brand: Color get() = current.brand
    val BrandMuted: Color get() = current.brandMuted
    val BrandStrong: Color get() = current.brandStrong
    val OnPrimary: Color get() = current.onPrimary
    val Focus: Color get() = current.focus

    val TextPrimary: Color get() = current.textPrimary
    val TextSecondary: Color get() = current.textSecondary
    val TextTertiary: Color get() = current.textTertiary
    val TextDisabled: Color get() = current.textDisabled

    val Live: Color get() = current.live
    val Success: Color get() = current.success
    val Warning: Color get() = current.warning
    val Info: Color get() = current.info

    val Divider: Color get() = current.divider
    val Outline: Color get() = current.outline

    val HeroTop: Color get() = current.heroTop
    val HeroBottom: Color get() = current.heroBottom
}
