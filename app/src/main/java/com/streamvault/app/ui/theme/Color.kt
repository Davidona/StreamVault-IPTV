package com.streamvault.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.streamvault.app.ui.design.AppColors

// Every alias below is a computed getter over the theme-reactive AppColors
// palette, so existing `import ...ui.theme.Primary` call sites follow the
// active dark/light theme without any changes at the call site.

val Primary: Color get() = AppColors.Brand
val PrimaryLight: Color get() = AppColors.BrandStrong
val PrimaryGlow: Color get() = AppColors.BrandMuted

val BackgroundDeep: Color get() = AppColors.Canvas
val Background: Color get() = AppColors.CanvasElevated
val Surface: Color get() = AppColors.Surface
val SurfaceElevated: Color get() = AppColors.SurfaceElevated
val SurfaceHighlight: Color get() = AppColors.SurfaceEmphasis

val TextPrimary: Color get() = AppColors.TextPrimary
val TextSecondary: Color get() = AppColors.TextSecondary
val TextTertiary: Color get() = AppColors.TextTertiary
val TextDisabled: Color get() = AppColors.TextDisabled

val OnBackground: Color get() = TextPrimary
val OnSurface: Color get() = TextPrimary
val OnSurfaceDim: Color get() = TextTertiary

val AccentRed: Color get() = AppColors.Live
val AccentGreen: Color get() = AppColors.Success
val AccentAmber: Color get() = AppColors.Warning
val AccentCyan: Color get() = AppColors.Info

val OnPrimary: Color get() = AppColors.OnPrimary
val Secondary: Color get() = AppColors.Success
val ErrorColor: Color get() = AccentRed

val GradientOverlayTop: Color get() = AppColors.HeroTop
val GradientOverlayBottom: Color get() = AppColors.HeroBottom

val FocusBorder: Color get() = AppColors.Focus
val ProgressBarBackground: Color get() = AppColors.SurfaceAccent
