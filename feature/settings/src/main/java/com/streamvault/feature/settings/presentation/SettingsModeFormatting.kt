package com.streamvault.feature.settings.presentation

import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutAction
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.RemoteShortcutSelectionMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.VodViewMode

import com.streamvault.feature.settings.R

public fun VodViewMode.labelResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic
}

public fun VodViewMode.descriptionResId(): Int = when (this) {
    VodViewMode.MODERN -> R.string.settings_vod_view_mode_modern_desc
    VodViewMode.CLASSIC -> R.string.settings_vod_view_mode_classic_desc
}

public fun VodDuplicateHandlingMode.labelResId(): Int = when (this) {
    VodDuplicateHandlingMode.SHOW_ALL -> R.string.settings_vod_duplicate_handling_show_all
    VodDuplicateHandlingMode.GROUPED -> R.string.settings_vod_duplicate_handling_grouped
    VodDuplicateHandlingMode.SMART -> R.string.settings_vod_duplicate_handling_smart
}

public fun VodDuplicateHandlingMode.descriptionResId(): Int = when (this) {
    VodDuplicateHandlingMode.SHOW_ALL -> R.string.settings_vod_duplicate_handling_show_all_desc
    VodDuplicateHandlingMode.GROUPED -> R.string.settings_vod_duplicate_handling_grouped_desc
    VodDuplicateHandlingMode.SMART -> R.string.settings_vod_duplicate_handling_smart_desc
}

public fun VodVariantPreferenceMode.labelResId(): Int = when (this) {
    VodVariantPreferenceMode.BALANCED -> R.string.settings_vod_variant_preference_balanced
    VodVariantPreferenceMode.FORCE_LATEST -> R.string.settings_vod_variant_preference_force_latest
    VodVariantPreferenceMode.BEST_QUALITY -> R.string.settings_vod_variant_preference_best_quality
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> R.string.settings_vod_variant_preference_latest_best_quality
    VodVariantPreferenceMode.MOST_RELIABLE -> R.string.settings_vod_variant_preference_most_reliable
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> R.string.settings_vod_variant_preference_manual_last_choice
}

public fun VodVariantPreferenceMode.descriptionResId(): Int = when (this) {
    VodVariantPreferenceMode.BALANCED -> R.string.settings_vod_variant_preference_balanced_desc
    VodVariantPreferenceMode.FORCE_LATEST -> R.string.settings_vod_variant_preference_force_latest_desc
    VodVariantPreferenceMode.BEST_QUALITY -> R.string.settings_vod_variant_preference_best_quality_desc
    VodVariantPreferenceMode.LATEST_BEST_QUALITY -> R.string.settings_vod_variant_preference_latest_best_quality_desc
    VodVariantPreferenceMode.MOST_RELIABLE -> R.string.settings_vod_variant_preference_most_reliable_desc
    VodVariantPreferenceMode.MANUAL_LAST_CHOICE -> R.string.settings_vod_variant_preference_manual_last_choice_desc
}

public fun ChannelNumberingMode.labelResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden
}

public fun ChannelNumberingMode.descriptionResId(): Int = when (this) {
    ChannelNumberingMode.GROUP -> R.string.settings_live_channel_numbering_group_desc
    ChannelNumberingMode.PROVIDER -> R.string.settings_live_channel_numbering_provider_desc
    ChannelNumberingMode.HIDDEN -> R.string.settings_live_channel_numbering_hidden_desc
}

public fun LiveChannelGroupingMode.labelResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants
}

public fun LiveChannelGroupingMode.descriptionResId(): Int = when (this) {
    LiveChannelGroupingMode.GROUPED -> R.string.settings_live_channel_grouping_grouped_desc
    LiveChannelGroupingMode.RAW_VARIANTS -> R.string.settings_live_channel_grouping_raw_variants_desc
}

public fun GroupedChannelLabelMode.labelResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid
}

public fun GroupedChannelLabelMode.descriptionResId(): Int = when (this) {
    GroupedChannelLabelMode.CANONICAL -> R.string.settings_grouped_channel_label_canonical_desc
    GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> R.string.settings_grouped_channel_label_original_desc
    GroupedChannelLabelMode.HYBRID -> R.string.settings_grouped_channel_label_hybrid_desc
}

public fun LiveVariantPreferenceMode.labelResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first
}

public fun LiveVariantPreferenceMode.descriptionResId(): Int = when (this) {
    LiveVariantPreferenceMode.BEST_QUALITY -> R.string.settings_live_variant_preference_best_quality_desc
    LiveVariantPreferenceMode.OBSERVED_ONLY -> R.string.settings_live_variant_preference_observed_only_desc
    LiveVariantPreferenceMode.BALANCED -> R.string.settings_live_variant_preference_balanced_desc
    LiveVariantPreferenceMode.STABILITY_FIRST -> R.string.settings_live_variant_preference_stability_first_desc
}

public fun RemoteColorButton.labelResId(): Int = when (this) {
    RemoteColorButton.RED -> R.string.settings_remote_button_red
    RemoteColorButton.GREEN -> R.string.settings_remote_button_green
    RemoteColorButton.YELLOW -> R.string.settings_remote_button_yellow
    RemoteColorButton.BLUE -> R.string.settings_remote_button_blue
}

public fun RemoteShortcutAction.labelResId(): Int = when (this) {
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

public fun RemoteShortcutProfile.labelResId(): Int = when (this) {
    RemoteShortcutProfile.GLOBAL -> R.string.settings_remote_profile_global
    RemoteShortcutProfile.PLAYBACK -> R.string.settings_remote_profile_playback
    RemoteShortcutProfile.BROWSE -> R.string.settings_remote_profile_browse
}

public fun formatRemoteShortcutSelectionLabel(
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
            context.getString(R.string.settings_remote_selection_profile_default, resolvedLabel)
        }
        RemoteShortcutSelectionMode.GLOBAL_DEFAULT -> context.getString(
            R.string.settings_remote_selection_global_default,
            resolvedLabel
        )
        RemoteShortcutSelectionMode.ACTION -> resolvedLabel
    }
}
