package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.R
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode

public fun LiveTvChannelMode.labelResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro
}

public fun LiveTvChannelMode.descriptionResId(): Int = when (this) {
    LiveTvChannelMode.COMFORTABLE -> R.string.settings_live_tv_mode_comfortable_desc
    LiveTvChannelMode.COMPACT -> R.string.settings_live_tv_mode_compact_desc
    LiveTvChannelMode.PRO -> R.string.settings_live_tv_mode_pro_desc
}

public fun LiveTvQuickFilterVisibilityMode.labelResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always
}

public fun LiveTvQuickFilterVisibilityMode.descriptionResId(): Int = when (this) {
    LiveTvQuickFilterVisibilityMode.HIDE -> R.string.settings_live_tv_quick_filter_visibility_hide_desc
    LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> R.string.settings_live_tv_quick_filter_visibility_available_desc
    LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> R.string.settings_live_tv_quick_filter_visibility_always_desc
}
