package com.streamvault.core.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor as TvContentColor
import androidx.compose.material3.LocalContentColor as TouchContentColor
import androidx.compose.material3.MaterialTheme as TouchMaterialTheme

/** Browsing surfaces deliberately do not change the settings or VOD player palette. */
internal fun AppPalette.forBrowsing(): AppPalette = copy(
    canvas = if (isLight) canvas else lerp(canvas, Color.Black, 0.32f),
    canvasElevated = if (isLight) canvas else lerp(canvasElevated, Color.Black, 0.22f),
    surface = if (isLight) surface else lerp(surface, Color.Black, 0.16f),
    surfaceElevated = lerp(surface, surfaceElevated, 0.48f),
    surfaceEmphasis = lerp(surfaceEmphasis, brand, if (isLight) 0.04f else 0.08f),
    textTertiary = if (isLight) lerp(textSecondary, textTertiary, 0.15f)
        else lerp(textTertiary, textSecondary, 0.35f),
    onPrimary = contentOnColor(brand),
    focus = brandStrong,
    outline = textPrimary.copy(alpha = if (isLight) 0.12f else 0.09f),
    heroBottom = if (isLight) heroBottom else lerp(heroBottom, Color.Black, 0.24f),
)

/** Picks readable ink for a solid accent, including future custom palettes. */
fun contentOnColor(color: Color): Color = if (color.luminance() > 0.179f) Color.Black else Color.White

@Composable
fun browsePrimaryButtonColors() = ButtonDefaults.colors(
    containerColor = BrowseColors.Brand,
    contentColor = BrowseColors.OnPrimary,
    focusedContainerColor = BrowseColors.BrandStrong,
    focusedContentColor = contentOnColor(BrowseColors.BrandStrong),
)

/** Theme-derived colors, also available to non-composable drawing helpers. */
object BrowseColors {
    private var source = AppPalette.classicBlue()
    private var resolved = source.forBrowsing()
    val current: AppPalette
        get() {
            val palette = AppColors.current
            if (palette != source) {
                source = palette
                resolved = palette.forBrowsing()
            }
            return resolved
        }
    val Canvas get() = current.canvas
    val CanvasElevated get() = current.canvasElevated
    val Surface get() = current.surface
    val SurfaceElevated get() = current.surfaceElevated
    val SurfaceEmphasis get() = current.surfaceEmphasis
    val SurfaceAccent get() = current.surfaceAccent
    val Brand get() = current.brand
    val BrandMuted get() = current.brandMuted
    val BrandStrong get() = current.brandStrong
    val OnPrimary get() = current.onPrimary
    val Focus get() = current.focus
    val TextPrimary get() = current.textPrimary
    val TextSecondary get() = current.textSecondary
    val TextTertiary get() = current.textTertiary
    val TextDisabled get() = current.textDisabled
    val Live get() = current.live
    val Success get() = current.success
    val Warning get() = current.warning
    // Badges over artwork keep bright ink regardless of the surrounding screen theme.
    val MediaWarning = Color(0xFFFFC766)
    val Info get() = current.info
    val Divider get() = current.divider
    val Outline get() = current.outline
    val HeroTop get() = current.heroTop
    val HeroBottom get() = current.heroBottom
}

object BrowseShapes {
    val Artwork = RoundedCornerShape(10.dp)
    val Control = RoundedCornerShape(10.dp)
    val Panel = RoundedCornerShape(16.dp)
    val Hero = RoundedCornerShape(20.dp)
}

object BrowseFocusSpec {
    const val FocusedScale = 1.035f
    const val PressedScale = 0.98f
    val BorderWidth = 2.dp
    val CardBorderWidth = 2.dp
}

val LocalBrowseVisuals = staticCompositionLocalOf { false }

@Composable
fun BrowseVisualStyle(enabled: Boolean = true, content: @Composable () -> Unit) {
    val palette = BrowseColors.current
    val originalShapes = LocalAppShapes.current
    val typography = MaterialTheme.typography
    val touchTypography = TouchMaterialTheme.typography
    CompositionLocalProvider(
        LocalBrowseVisuals provides enabled,
        TvContentColor provides if (enabled) palette.textPrimary else TvContentColor.current,
        TouchContentColor provides if (enabled) palette.textPrimary else TouchContentColor.current,
        LocalAppShapes provides if (enabled) AppShapes(
            xSmall = RoundedCornerShape(6.dp),
            small = BrowseShapes.Artwork,
            medium = BrowseShapes.Panel,
            large = BrowseShapes.Hero,
            xLarge = BrowseShapes.Hero,
        ) else originalShapes,
    ) {
        MaterialTheme(typography = if (enabled) typography.copy(
            displaySmall = typography.displaySmall.copy(letterSpacing = (-0.6).sp),
            titleLarge = typography.titleLarge.copy(letterSpacing = (-0.3).sp),
            titleMedium = typography.titleMedium.copy(letterSpacing = (-0.2).sp),
        ) else typography, colorScheme = if (enabled) MaterialTheme.colorScheme.copy(
            onPrimary = palette.onPrimary,
            background = palette.canvas,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
        ) else MaterialTheme.colorScheme) {
            TouchMaterialTheme(typography = if (enabled) touchTypography.copy(
                titleLarge = typography.titleLarge,
                titleMedium = typography.titleMedium,
                titleSmall = typography.titleSmall,
                bodyLarge = typography.bodyLarge,
                bodyMedium = typography.bodyMedium,
                bodySmall = typography.bodySmall,
                labelLarge = typography.labelLarge,
                labelMedium = typography.labelMedium,
                labelSmall = typography.labelSmall,
            ) else touchTypography, colorScheme = if (enabled) TouchMaterialTheme.colorScheme.copy(
                onPrimary = palette.onPrimary,
                background = palette.canvas,
                surface = palette.surface,
                surfaceVariant = palette.surfaceElevated,
                outline = palette.outline,
            ) else TouchMaterialTheme.colorScheme, content = content)
        }
    }
}

/** Static ambient light: no blur layers, animation, or extra focus targets on TV. */
fun Modifier.browseBackdrop(): Modifier = drawWithCache {
    val palette = BrowseColors.current
    val base = Brush.verticalGradient(listOf(palette.canvasElevated, palette.canvas))
    val ambient = Brush.radialGradient(
        colors = listOf(palette.brand.copy(alpha = if (palette.isLight) 0.07f else 0.10f), Color.Transparent),
        center = Offset(size.width * 0.8f, -size.height * 0.2f),
        radius = size.width * 0.85f,
    )
    onDrawBehind { drawRect(base); drawRect(ambient) }
}

fun Modifier.browsePanel(shape: RoundedCornerShape = BrowseShapes.Panel): Modifier =
    clip(shape)
        .background(Brush.verticalGradient(listOf(BrowseColors.SurfaceElevated, BrowseColors.Surface)))
        .border(1.dp, BrowseColors.Outline, shape)
