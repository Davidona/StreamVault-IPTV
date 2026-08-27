package com.streamvault.app.ui.screens.settings

import com.streamvault.app.R
import com.streamvault.core.ui.localization.localeForLanguageTag
import com.streamvault.core.ui.localization.supportedAppLanguageTags
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.domain.model.VodViewMode
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.RemoteShortcutSelectionMode
import java.text.DateFormat
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

internal fun formatTimestamp(
    timestampMs: Long,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    if (timestampMs <= 0L) return "--:--"
    return dateTimeFormat.format(java.util.Date(timestampMs))
}

internal data class AppLanguageOption(
    val tag: String,
    val label: String
)

internal fun supportedAppLanguages(systemDefaultLabel: String): List<AppLanguageOption> {
    val localeTags = listOf("system") + supportedAppLanguageTags()

    return localeTags.map { tag ->
        AppLanguageOption(
            tag = tag,
            label = if (tag == "system") {
                systemDefaultLabel
            } else {
                val locale = localeForLanguageTag(tag)
                locale.getDisplayLanguage(locale)
                    .replaceFirstChar { character ->
                        if (character.isLowerCase()) {
                            character.titlecase(locale)
                        } else {
                            character.toString()
                        }
                    }
            }
        )
    }
}

internal fun supportedAudioLanguages(autoLabel: String): List<AppLanguageOption> {
    return buildList {
        add(AppLanguageOption(tag = "auto", label = autoLabel))
        addAll(supportedAppLanguages(autoLabel).filterNot { it.tag == "system" })
    }
}

internal data class SubtitleScaleOption(
    val scale: Float,
    val label: (android.content.Context) -> String
)

internal data class SubtitleColorOption(
    val colorArgb: Int,
    val label: String
)

internal fun subtitleSizeOptions(): List<SubtitleScaleOption> {
    return listOf(
        SubtitleScaleOption(0.85f) { it.getString(R.string.settings_subtitle_size_small) },
        SubtitleScaleOption(1f) { it.getString(R.string.settings_subtitle_size_default) },
        SubtitleScaleOption(1.15f) { it.getString(R.string.settings_subtitle_size_large) },
        SubtitleScaleOption(1.3f) { it.getString(R.string.settings_subtitle_size_extra_large) }
    )
}

internal fun subtitleTextColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0xFFFFFFFF.toInt(), context.getString(R.string.settings_subtitle_color_white)),
        SubtitleColorOption(0xFFFFEB3B.toInt(), context.getString(R.string.settings_subtitle_color_yellow)),
        SubtitleColorOption(0xFF80DEEA.toInt(), context.getString(R.string.settings_subtitle_color_cyan)),
        SubtitleColorOption(0xFFA5D6A7.toInt(), context.getString(R.string.settings_subtitle_color_green))
    )
}

internal fun subtitleBackgroundColorOptions(context: android.content.Context): List<SubtitleColorOption> {
    return listOf(
        SubtitleColorOption(0x00000000, context.getString(R.string.settings_subtitle_background_transparent)),
        SubtitleColorOption(0x80000000.toInt(), context.getString(R.string.settings_subtitle_background_dim)),
        SubtitleColorOption(0xCC000000.toInt(), context.getString(R.string.settings_subtitle_background_black)),
        SubtitleColorOption(0xCC102A43.toInt(), context.getString(R.string.settings_subtitle_background_blue))
    )
}

internal fun displayLanguageLabel(languageTag: String, defaultLabel: String): String {
    if (languageTag.isBlank() || languageTag == "system" || languageTag == "auto") return defaultLabel
    val locale = localeForLanguageTag(languageTag)
    if (locale.language.isBlank()) return defaultLabel
    return locale.getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
}

internal fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

internal fun playerTimeoutOptions(): List<Int> = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30)

internal fun formatDecoderModeLabel(mode: DecoderMode, context: android.content.Context): String {
    return when (mode) {
        DecoderMode.AUTO -> context.getString(R.string.settings_decoder_auto)
        DecoderMode.HARDWARE -> context.getString(R.string.settings_decoder_hardware)
        DecoderMode.SOFTWARE -> context.getString(R.string.settings_decoder_software)
        DecoderMode.COMPATIBILITY -> context.getString(R.string.settings_decoder_compatibility)
    }
}

internal fun formatAudioOutputPreferenceLabel(
    preference: AudioOutputPreference,
    context: android.content.Context
): String = when (preference) {
    AudioOutputPreference.AUTO -> context.getString(R.string.settings_audio_output_auto)
    AudioOutputPreference.PREFER_PASSTHROUGH -> context.getString(R.string.settings_audio_output_prefer_passthrough)
    AudioOutputPreference.DISABLE_PASSTHROUGH -> context.getString(R.string.settings_audio_output_disable_passthrough)
}

internal fun formatSurfaceModeLabel(
    mode: PlayerSurfaceMode,
    context: android.content.Context
): String = when (mode) {
    PlayerSurfaceMode.AUTO -> context.getString(R.string.settings_surface_auto)
    PlayerSurfaceMode.SURFACE_VIEW -> context.getString(R.string.settings_surface_surface_view)
    PlayerSurfaceMode.TEXTURE_VIEW -> context.getString(R.string.settings_surface_texture_view)
}

internal fun formatTimeoutSecondsLabel(seconds: Int, context: android.content.Context): String {
    return context.resources.getQuantityString(
        R.plurals.settings_timeout_seconds,
        seconds,
        seconds
    )
}

internal fun formatSubtitleSizeLabel(scale: Float, context: android.content.Context): String {
    return subtitleSizeOptions().firstOrNull { it.scale == scale }?.label?.invoke(context)
        ?: context.getString(R.string.settings_subtitle_size_default)
}

internal fun formatSubtitleColorLabel(colorArgb: Int, options: List<SubtitleColorOption>): String {
    return options.firstOrNull { it.colorArgb == colorArgb }?.label ?: options.first().label
}

internal fun formatQualityCapLabel(maxHeight: Int?, autoLabel: String): String {
    return maxHeight?.let { "${it}p" } ?: autoLabel
}

internal fun formatSpeedTestValueLabel(speedTest: InternetSpeedTestUiModel): String {
    return String.format(Locale.getDefault(), "%.1f Mbps", speedTest.megabitsPerSecond)
}

internal fun formatSpeedTestSummary(
    speedTest: InternetSpeedTestUiModel,
    context: android.content.Context,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    val transportLabel = when (speedTest.transportLabel) {
        InternetSpeedTestTransport.WIFI.name -> context.getString(R.string.settings_speed_test_transport_wifi)
        InternetSpeedTestTransport.ETHERNET.name -> context.getString(R.string.settings_speed_test_transport_ethernet)
        InternetSpeedTestTransport.CELLULAR.name -> context.getString(R.string.settings_speed_test_transport_cellular)
        InternetSpeedTestTransport.OTHER.name -> context.getString(R.string.settings_speed_test_transport_other)
        else -> context.getString(R.string.settings_speed_test_transport_unknown)
    }
    val measuredAtLabel = formatTimestamp(speedTest.measuredAtMs, dateTimeFormat)
    return if (speedTest.isEstimated) {
        context.getString(R.string.settings_speed_test_summary_estimated, transportLabel, measuredAtLabel)
    } else {
        context.getString(R.string.settings_speed_test_summary_measured, transportLabel, measuredAtLabel)
    }
}

internal fun sortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return when (mode) {
        CategorySortMode.DEFAULT -> context.getString(R.string.settings_category_sort_default)
        CategorySortMode.TITLE_ASC -> context.getString(R.string.settings_category_sort_az)
        CategorySortMode.TITLE_DESC -> context.getString(R.string.settings_category_sort_za)
        CategorySortMode.COUNT_DESC -> context.getString(R.string.settings_category_sort_most_items)
        CategorySortMode.COUNT_ASC -> context.getString(R.string.settings_category_sort_least_items)
    }
}

internal fun formatCategorySortModeLabel(mode: CategorySortMode, context: android.content.Context): String {
    return sortModeLabel(mode, context)
}

internal fun categoryTypeLabel(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_sort_live)
        ContentType.MOVIE,
        ContentType.VOD -> context.getString(R.string.nav_vod)
        ContentType.SERIES -> context.getString(R.string.settings_category_sort_series)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_sort_series)
    }
}

internal fun categoryTypeDescription(type: ContentType, context: android.content.Context): String {
    return when (type) {
        ContentType.LIVE -> context.getString(R.string.settings_category_type_live_description)
        ContentType.MOVIE,
        ContentType.VOD -> context.getString(R.string.settings_category_type_movies_description)
        ContentType.SERIES -> context.getString(R.string.settings_category_type_series_description)
        ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_type_series_description)
    }
}

internal fun LiveTvChannelMode.labelResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro
}

internal fun LiveTvChannelMode.descriptionResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable_desc
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact_desc
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro_desc
}

internal fun LiveTvQuickFilterVisibilityMode.labelResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always
}

internal fun LiveTvQuickFilterVisibilityMode.descriptionResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide_desc
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available_desc
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always_desc
}

internal fun VodViewMode.labelResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic
}

internal fun VodViewMode.descriptionResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern_desc
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic_desc
}

internal fun VodDuplicateHandlingMode.labelResId(): Int = when (this) {
    VodDuplicateHandlingMode.SHOW_ALL -> R.string.settings_vod_duplicate_handling_show_all
    VodDuplicateHandlingMode.GROUPED -> R.string.settings_vod_duplicate_handling_grouped
    VodDuplicateHandlingMode.SMART -> R.string.settings_vod_duplicate_handling_smart
}

internal fun VodDuplicateHandlingMode.descriptionResId(): Int = when (this) {
    VodDuplicateHandlingMode.SHOW_ALL -> R.string.settings_vod_duplicate_handling_show_all_desc
    VodDuplicateHandlingMode.GROUPED -> R.string.settings_vod_duplicate_handling_grouped_desc
    VodDuplicateHandlingMode.SMART -> R.string.settings_vod_duplicate_handling_smart_desc
}

internal fun VodVariantPreferenceMode.labelResId(): Int = when (this) {
    VodVariantPreferenceMode.BALANCED -> R.string.settings_vod_variant_preference_balanced
    VodVariantPreferenceMode.FORCE_LATEST -> R.string.settings_vod_variant_preference_force_latest
    VodVariantPreferenceMode.BEST_QUALITY -> R.string.settings_vod_variant_preference_best_quality
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> R.string.settings_vod_variant_preference_latest_best_quality
    VodVariantPreferenceMode.MOST_RELIABLE -> R.string.settings_vod_variant_preference_most_reliable
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> R.string.settings_vod_variant_preference_manual_last_choice
}

internal fun VodVariantPreferenceMode.descriptionResId(): Int = when (this) {
    VodVariantPreferenceMode.BALANCED -> R.string.settings_vod_variant_preference_balanced_desc
    VodVariantPreferenceMode.FORCE_LATEST -> R.string.settings_vod_variant_preference_force_latest_desc
    VodVariantPreferenceMode.BEST_QUALITY -> R.string.settings_vod_variant_preference_best_quality_desc
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> R.string.settings_vod_variant_preference_latest_best_quality_desc
    VodVariantPreferenceMode.MOST_RELIABLE -> R.string.settings_vod_variant_preference_most_reliable_desc
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> R.string.settings_vod_variant_preference_manual_last_choice_desc
}

internal fun ChannelNumberingMode.labelResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden
}

internal fun ChannelNumberingMode.descriptionResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group_desc
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider_desc
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden_desc
}

internal fun LiveChannelGroupingMode.labelResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants
}

internal fun LiveChannelGroupingMode.descriptionResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped_desc
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants_desc
}

internal fun GroupedChannelLabelMode.labelResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid
}

internal fun GroupedChannelLabelMode.descriptionResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical_desc
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original_desc
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid_desc
}

internal fun LiveVariantPreferenceMode.labelResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first
}

internal fun LiveVariantPreferenceMode.descriptionResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality_desc
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only_desc
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced_desc
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first_desc
}

internal fun RemoteColorButton.labelResId(): Int = when (this) {
    RemoteColorButton.RED -> R.string.settings_remote_button_red
    RemoteColorButton.GREEN -> R.string.settings_remote_button_green
    RemoteColorButton.YELLOW -> R.string.settings_remote_button_yellow
    RemoteColorButton.BLUE -> R.string.settings_remote_button_blue
}

internal fun RemoteShortcutAction.labelResId(): Int = when (this) {
    RemoteShortcutAction.NONE -> R.string.settings_remote_action_none
    RemoteShortcutAction.OPEN_GUIDE -> R.string.settings_remote_action_open_guide
    RemoteShortcutAction.OPEN_PLAYER_CONTROLS -> R.string.settings_remote_action_open_player_controls
    RemoteShortcutAction.OPEN_CHANNEL_INFO -> R.string.settings_remote_action_open_channel_info
    RemoteShortcutAction.LAST_CHANNEL -> R.string.settings_remote_action_last_channel
    RemoteShortcutAction.NEXT_CHANNEL -> R.string.settings_remote_action_next_channel
    RemoteShortcutAction.PREVIOUS_CHANNEL -> R.string.settings_remote_action_previous_channel
    RemoteShortcutAction.OPEN_CHANNEL_LIST -> R.string.settings_remote_action_open_channel_list
    RemoteShortcutAction.OPEN_CATEGORY_LIST -> R.string.settings_remote_action_open_category_list
    RemoteShortcutAction.ADD_TO_SPLIT_SCREEN -> R.string.settings_remote_action_add_to_split_screen
    RemoteShortcutAction.TOGGLE_FAVORITE -> R.string.settings_remote_action_toggle_favorite
    RemoteShortcutAction.PLAY_CHANNEL -> R.string.settings_remote_action_play_channel
    RemoteShortcutAction.PIN_CATEGORY -> R.string.settings_remote_action_pin_category
    RemoteShortcutAction.TOGGLE_CATEGORY_LOCK -> R.string.settings_remote_action_toggle_category_lock
    RemoteShortcutAction.HIDE_CATEGORY -> R.string.settings_remote_action_hide_category
}

internal fun RemoteShortcutProfile.labelResId(): Int = when (this) {
    RemoteShortcutProfile.GLOBAL -> R.string.settings_remote_profile_global
    RemoteShortcutProfile.PLAYBACK -> R.string.settings_remote_profile_playback
    RemoteShortcutProfile.BROWSE -> R.string.settings_remote_profile_browse
}

internal fun formatRemoteShortcutSelectionLabel(
    selection: RemoteShortcutSelection,
    profile: RemoteShortcutProfile,
    button: RemoteColorButton,
    context: android.content.Context
): String {
    val resolvedLabel = context.getString(selection.resolve(profile, button).labelResId())
    return when (selection.mode) {
        RemoteShortcutSelectionMode.PROFILE_DEFAULT -> if (profile == RemoteShortcutProfile.GLOBAL) {
            resolvedLabel
        } else {
            context.getString(
                R.string.settings_remote_selection_profile_default,
                resolvedLabel
            )
        }
        RemoteShortcutSelectionMode.GLOBAL_DEFAULT -> context.getString(
            R.string.settings_remote_selection_global_default,
            resolvedLabel
        )
        RemoteShortcutSelectionMode.ACTION -> resolvedLabel
    }
}
