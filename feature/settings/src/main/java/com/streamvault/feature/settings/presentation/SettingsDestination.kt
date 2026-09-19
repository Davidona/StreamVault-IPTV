package com.streamvault.feature.settings.presentation

import com.streamvault.feature.settings.R

/** IDs retain the existing backup-import destination. Page names are saveable navigation keys. */
enum class SettingsCategory(val legacyId: Int, val title: Int, val description: Int) {
    SOURCES(0, R.string.settings_providers, R.string.settings_sources_description),
    PLAYBACK(1, R.string.settings_playback, R.string.settings_playback_description),
    LIVE_TV(2, R.string.settings_domain_live, R.string.settings_live_description),
    MOVIES(8, R.string.settings_domain_movies, R.string.settings_movies_description),
    APP(9, R.string.settings_domain_app, R.string.settings_app_description),
    PRIVACY(3, R.string.settings_privacy, R.string.settings_privacy_description),
    RECORDING(4, R.string.settings_recording_title, R.string.settings_recording_description),
    BACKUP(5, R.string.settings_backup_restore, R.string.settings_backup_description),
    GUIDE(6, R.string.settings_domain_guide, R.string.settings_guide_description),
    ABOUT(7, R.string.settings_about, R.string.settings_about_description);

    val pages: List<SettingsPage> get() = SettingsPage.entries.filter { it.categoryId == legacyId }
    companion object {
        fun fromId(id: Int): SettingsCategory = entries.firstOrNull { it.legacyId == id } ?: SOURCES
    }
}

enum class SettingsPage(val categoryId: Int, val title: Int, val description: Int) {
    LOCAL_BACKUP(5, R.string.settings_page_local_backup, R.string.settings_page_local_backup_description),
    DRIVE_BACKUP(5, R.string.settings_page_drive_backup, R.string.settings_page_drive_backup_description),
    UPDATES(7, R.string.settings_updates_title, R.string.settings_updates_subtitle),
    REPORTS(7, R.string.settings_crash_reports_title, R.string.settings_crash_reports_subtitle),
    APP_INFO(7, R.string.settings_page_app_info, R.string.settings_page_app_info_description),
    RECORDING_STATUS(4, R.string.settings_page_recording_status, R.string.settings_page_recording_status_description),
    RECORDING_STORAGE(4, R.string.settings_page_recording_storage, R.string.settings_page_recording_storage_description),
    RECORDING_DEFAULTS(4, R.string.settings_page_recording_defaults, R.string.settings_page_recording_defaults_description),
    PROVIDERS(0, R.string.settings_providers, R.string.settings_page_providers_description),
    SOURCE_COMPATIBILITY(0, R.string.settings_page_source_compatibility, R.string.settings_page_source_compatibility_description),
    GENERAL(1, R.string.settings_page_general, R.string.settings_page_general_description),
    AUDIO(1, R.string.settings_page_audio, R.string.settings_page_audio_description),
    SUBTITLES(1, R.string.settings_page_subtitles, R.string.settings_page_subtitles_description),
    NETWORK(1, R.string.settings_page_network, R.string.settings_page_network_description),
    CONTROLS(1, R.string.settings_page_controls, R.string.settings_page_controls_description),
    TIMERS(1, R.string.settings_page_timers, R.string.settings_page_timers_description),
    COMPATIBILITY(1, R.string.settings_page_compatibility, R.string.settings_page_compatibility_description),
    LIVE_LAYOUT(2, R.string.settings_page_live_layout, R.string.settings_page_live_layout_description),
    LIVE_CHANNELS(2, R.string.settings_page_live_channels, R.string.settings_page_live_channels_description),
    LIVE_FILTERS(2, R.string.settings_page_live_filters, R.string.settings_page_live_filters_description),
    TIMESHIFT(2, R.string.settings_page_timeshift, R.string.settings_page_timeshift_description),
    CLOCK(2, R.string.settings_live_clock, R.string.settings_page_clock_description),
    MULTIVIEW(2, R.string.settings_page_multiview, R.string.settings_page_multiview_description),
    VOD_LIBRARY(8, R.string.settings_page_vod_library, R.string.settings_page_vod_library_description),
    VOD_ORGANIZATION(8, R.string.settings_page_vod_organization, R.string.settings_page_vod_organization_description),
    VOD_PLAYBACK(8, R.string.settings_page_vod_playback, R.string.settings_page_vod_playback_description),
    APPEARANCE(9, R.string.settings_page_appearance, R.string.settings_page_appearance_description),
    HOME(9, R.string.settings_page_home, R.string.settings_page_home_description),
    REMOTE(9, R.string.settings_remote_shortcuts_title, R.string.settings_page_remote_description);
}
