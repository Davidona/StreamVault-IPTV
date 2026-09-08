package com.streamvault.core.ui.theme

import com.streamvault.core.ui.design.AppColors

val Primary get() = AppColors.Brand
val PrimaryLight get() = AppColors.BrandStrong
val PrimaryGlow get() = AppColors.BrandMuted

val BackgroundDeep get() = AppColors.Canvas
val Background get() = AppColors.CanvasElevated
val Surface get() = AppColors.Surface
val SurfaceElevated get() = AppColors.SurfaceElevated
val SurfaceHighlight get() = AppColors.SurfaceEmphasis

val TextPrimary get() = AppColors.TextPrimary
val TextSecondary get() = AppColors.TextSecondary
val TextTertiary get() = AppColors.TextTertiary
val TextDisabled get() = AppColors.TextDisabled

val OnBackground get() = TextPrimary
val OnSurface get() = TextPrimary
val OnSurfaceDim get() = TextTertiary

val AccentRed get() = AppColors.Live
val AccentGreen get() = AppColors.Success
val AccentAmber get() = AppColors.Warning
val AccentCyan get() = AppColors.Info

val OnPrimary get() = AppColors.OnPrimary
val Secondary get() = AppColors.Success
val ErrorColor get() = AccentRed

val GradientOverlayTop get() = AppColors.HeroTop
val GradientOverlayBottom get() = AppColors.HeroBottom

val FocusBorder get() = AppColors.Focus
val ProgressBarBackground get() = AppColors.SurfaceAccent
