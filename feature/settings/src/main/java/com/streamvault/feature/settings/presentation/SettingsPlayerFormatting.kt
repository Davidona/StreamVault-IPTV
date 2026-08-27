package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.feature.settings.R

public data class SubtitleScaleOption(
    val scale: Float,
    val label: (Context) -> String
)

public data class SubtitleColorOption(
    val colorArgb: Int,
    val label: String
)

public fun subtitleSizeOptions(): List<SubtitleScaleOption> = listOf(
    SubtitleScaleOption(0.85f) { it.getString(R.string.settings_subtitle_size_small) },
    SubtitleScaleOption(1f) { it.getString(R.string.settings_subtitle_size_default) },
    SubtitleScaleOption(1.15f) { it.getString(R.string.settings_subtitle_size_large) },
    SubtitleScaleOption(1.3f) { it.getString(R.string.settings_subtitle_size_extra_large) }
)

public fun subtitleTextColorOptions(context: Context): List<SubtitleColorOption> = listOf(
    SubtitleColorOption(0xFFFFFFFF.toInt(), context.getString(R.string.settings_subtitle_color_white)),
    SubtitleColorOption(0xFFFFEB3B.toInt(), context.getString(R.string.settings_subtitle_color_yellow)),
    SubtitleColorOption(0xFF80DEEA.toInt(), context.getString(R.string.settings_subtitle_color_cyan)),
    SubtitleColorOption(0xFFA5D6A7.toInt(), context.getString(R.string.settings_subtitle_color_green))
)

public fun subtitleBackgroundColorOptions(context: Context): List<SubtitleColorOption> = listOf(
    SubtitleColorOption(0x00000000, context.getString(R.string.settings_subtitle_background_transparent)),
    SubtitleColorOption(0x80000000.toInt(), context.getString(R.string.settings_subtitle_background_dim)),
    SubtitleColorOption(0xCC000000.toInt(), context.getString(R.string.settings_subtitle_background_black)),
    SubtitleColorOption(0xCC102A43.toInt(), context.getString(R.string.settings_subtitle_background_blue))
)

public fun formatDecoderModeLabel(mode: DecoderMode, context: Context): String = when (mode) {
    DecoderMode.AUTO -> context.getString(R.string.settings_decoder_auto)
    DecoderMode.HARDWARE -> context.getString(R.string.settings_decoder_hardware)
    DecoderMode.SOFTWARE -> context.getString(R.string.settings_decoder_software)
    DecoderMode.COMPATIBILITY -> context.getString(R.string.settings_decoder_compatibility)
}

public fun formatAudioOutputPreferenceLabel(
    preference: AudioOutputPreference,
    context: Context
): String = when (preference) {
    AudioOutputPreference.AUTO -> context.getString(R.string.settings_audio_output_auto)
    AudioOutputPreference.PREFER_PASSTHROUGH -> context.getString(R.string.settings_audio_output_prefer_passthrough)
    AudioOutputPreference.DISABLE_PASSTHROUGH -> context.getString(R.string.settings_audio_output_disable_passthrough)
}

public fun formatSurfaceModeLabel(mode: PlayerSurfaceMode, context: Context): String = when (mode) {
    PlayerSurfaceMode.AUTO -> context.getString(R.string.settings_surface_auto)
    PlayerSurfaceMode.SURFACE_VIEW -> context.getString(R.string.settings_surface_surface_view)
    PlayerSurfaceMode.TEXTURE_VIEW -> context.getString(R.string.settings_surface_texture_view)
}

public fun formatSubtitleSizeLabel(scale: Float, context: Context): String =
    subtitleSizeOptions().firstOrNull { it.scale == scale }?.label?.invoke(context)
        ?: context.getString(R.string.settings_subtitle_size_default)

public fun formatSubtitleColorLabel(colorArgb: Int, options: List<SubtitleColorOption>): String =
    options.firstOrNull { it.colorArgb == colorArgb }?.label ?: options.first().label
