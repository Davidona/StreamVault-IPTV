package com.streamvault.data.manager

enum class PreferenceBackupClassification {
    PORTABLE_GLOBAL,
    PORTABLE_PROVIDER_CONTENT,
    DEVICE_BOUND,
    RUNTIME_CACHE
}

/** Single review point for every preference admitted to a portable backup. */
internal object PreferenceBackupRegistry {
    private val portableStorageKeys = setOf(
        "last_active_provider_id", "active_live_source_type", "active_live_source_id",
        "default_view_mode", "parental_control_level", "parental_pin_hash", "parental_pin_salt",
        "default_category_id", "app_language", "dark_theme", "app_landing_destination", "app_top_level_destinations",
        "app_home_dashboard_shelves", "app_time_format", "live_tv_channel_mode",
        "show_live_source_switcher", "show_favorites_category", "show_all_channels_category",
        "show_recent_channels_category", "live_tv_category_filters", "live_tv_quick_filter_visibility",
        "hide_decorative_live_rows", "live_channel_numbering_mode", "live_channel_grouping_mode",
        "grouped_channel_label_mode", "live_variant_preference_mode", "live_variant_selections",
        "vod_view_mode", "vod_infinite_scroll", "vod_category_load_mode", "vod_duplicate_handling_mode",
        "vod_variant_preference_mode", "vod_variant_selections", "guide_density", "guide_channel_mode",
        "guide_default_category_id", "guide_favorites_only", "guide_anchor_time",
        "epg_time_shift_by_provider", "promoted_live_group_ids", "multiview_preset_1",
        "multiview_preset_2", "multiview_preset_3", "multiview_performance_mode",
        "multiview_center_two_slot_layout", "multiview_respect_provider_connection_limit",
        "is_incognito_mode", "player_muted", "player_media_session_enabled",
        "player_fast_retry_on_transient_failures", "player_audio_decoder_mode", "player_video_decoder_mode",
        "player_playback_buffer_mode", "player_live_stream_format_mode", "player_vod_http_protocol_mode",
        "player_audio_output_preference", "player_compatibility_memory_enabled", "player_surface_mode",
        "player_playback_speed", "player_external_playback_mode", "player_av_sync_enabled",
        "player_av_offset_ms", "preferred_audio_language", "player_subtitle_text_scale",
        "player_subtitle_text_color", "player_subtitle_background_color", "player_live_translation_enabled",
        "player_live_translation_endpoint", "player_controls_timeout_seconds",
        "player_live_overlay_timeout_seconds", "player_notice_timeout_seconds",
        "player_diagnostics_timeout_seconds", "player_wifi_max_video_height",
        "player_ethernet_max_video_height", "player_timeshift_enabled", "player_timeshift_depth_minutes",
        "player_timeshift_backend", "default_stop_playback_timer_minutes",
        "default_idle_standby_timer_minutes", "guide_scheduled_only", "xtream_text_classification",
        "xtream_base64_text_compatibility", "zap_auto_revert", "prevent_standby_during_playback",
        "auto_play_next_episode", "auto_check_app_updates", "auto_download_app_updates",
        "recording_wifi_only", "recording_padding_before_minutes", "recording_padding_after_minutes",
        "max_concurrent_streams"
    )
    private val deviceBoundStorageKeys = setOf("download_tree_uri")
    private val runtimeStorageKeys = setOf(
        "parental_v2_migrated", "parental_pin", "live_variant_observations", "vod_variant_observations",
        "player_decoder_mode", "player_movie_http_protocol_mode", "last_speed_test_megabits",
        "last_speed_test_timestamp", "last_speed_test_transport", "last_speed_test_recommended_height",
        "last_speed_test_estimated", "recent_search_queries", "xtream_text_import_generation",
        "last_app_update_check_timestamp", "last_app_update_attempt_timestamp",
        "last_app_update_failure_timestamp", "last_app_update_outcome", "app_update_download_id",
        "app_update_download_version_name", "app_update_downloaded_version_name",
        "app_update_latest_version_name", "app_update_latest_version_code", "app_update_release_url",
        "app_update_download_url", "app_update_download_sha256", "app_update_release_notes",
        "app_update_published_at", "last_maintenance_at", "last_maintenance_deleted_programs",
        "last_maintenance_deleted_external_programmes", "last_maintenance_deleted_orphan_episodes",
        "last_maintenance_deleted_stale_favorites", "last_maintenance_vacuum_ran",
        "last_maintenance_main_db_bytes", "last_maintenance_wal_bytes",
        "last_maintenance_reclaimable_bytes", "last_maintenance_channel_rows",
        "last_maintenance_movie_rows", "last_maintenance_series_rows", "last_maintenance_episode_rows",
        "last_maintenance_program_rows", "last_maintenance_epg_programme_rows",
        "last_maintenance_playback_history_rows", "last_maintenance_favorite_rows"
    )
    private val providerStoragePrefixes = setOf(
        "hidden_channels_", "hidden_categories_", "pinned_categories_", "category_sort_"
    )
    private val globalStoragePrefixes = setOf("remote_shortcut_")
    private val runtimeStoragePrefixes = setOf(
        "xtream_text_import_applied_generation_", "last_live_category_id_",
        "last_split_catalog_type_", "aspect_ratio_"
    )

    private val providerKeys = setOf(
        "guideDefaultCategoryId",
        "lastActiveProviderId",
        "promotedLiveGroupIds"
    )
    private val providerPrefixes = setOf(
        "hiddenChannels_",
        "hiddenCategories_",
        "pinnedCategories_",
        "categorySortMode_",
        "epgTimeShift_",
        "liveVariantSelections_",
        "vodVariantSelections_"
    )
    private val globalKeys = setOf(
        "parentalControlLevel", "parentalPinHash", "parentalPinSalt", "appLanguage", "darkTheme", "appTimeFormat",
        "defaultViewMode", "appLandingDestination", "appTopLevelDestinations", "appHomeDashboardShelves",
        "remoteShortcutPreferences", "liveTvCategoryFilters", "liveTvQuickFilterVisibility", "liveTvChannelMode",
        "showLiveSourceSwitcher", "showFavoritesCategory", "showAllChannelsCategory", "showRecentChannelsCategory",
        "hideDecorativeLiveRows", "liveChannelNumberingMode", "liveChannelGroupingMode", "groupedChannelLabelMode",
        "liveVariantPreferenceMode", "vodViewMode", "vodInfiniteScroll", "vodCategoryLoadMode",
        "vodDuplicateHandlingMode", "vodVariantPreferenceMode", "playerMediaSessionEnabled",
        "playerFastRetryOnTransientFailures", "playerAudioDecoderMode", "playerVideoDecoderMode",
        "playerPlaybackBufferMode", "playerAudioOutputPreference", "playerCompatibilityMemoryEnabled",
        "playerSurfaceMode", "playerLiveStreamFormatMode", "playerVodHttpProtocolMode", "playerPlaybackSpeed",
        "playerExternalPlaybackMode", "playerAudioVideoSyncEnabled", "playerAudioVideoOffsetMs", "playerMuted",
        "multiViewPerformanceMode", "multiViewCenterTwoSlotLayout", "multiViewRespectProviderConnectionLimit",
        "preferredAudioLanguage", "playerSubtitleTextScale", "playerSubtitleTextColor",
        "playerSubtitleBackgroundColor", "playerLiveTranslationEnabled", "playerLiveTranslationEndpoint",
        "playerControlsTimeoutSeconds", "playerLiveOverlayTimeoutSeconds", "playerNoticeTimeoutSeconds",
        "playerDiagnosticsTimeoutSeconds", "playerWifiMaxVideoHeight", "playerEthernetMaxVideoHeight",
        "playerTimeshiftEnabled", "playerTimeshiftDepthMinutes", "playerTimeshiftBackend",
        "defaultStopPlaybackTimerMinutes", "defaultIdleStandbyTimerMinutes", "preventStandbyDuringPlayback",
        "zapAutoRevert", "autoPlayNextEpisode", "autoCheckAppUpdates", "autoDownloadAppUpdates",
        "recordingWifiOnly", "recordingPaddingBeforeMinutes", "recordingPaddingAfterMinutes",
        "maxConcurrentStreams", "isIncognitoMode", "useXtreamTextClassification",
        "xtreamBase64TextCompatibility", "guideDensity", "guideChannelMode", "guideFavoritesOnly",
        "guideScheduledOnly", "guideAnchorTime"
    )

    fun classification(key: String): PreferenceBackupClassification? = when {
        key in globalKeys -> PreferenceBackupClassification.PORTABLE_GLOBAL
        key in providerKeys || providerPrefixes.any(key::startsWith) ->
            PreferenceBackupClassification.PORTABLE_PROVIDER_CONTENT
        else -> null
    }

    fun storageClassification(key: String): PreferenceBackupClassification? = when {
        key in portableStorageKeys -> if (
            key in setOf(
                "last_active_provider_id", "active_live_source_type", "active_live_source_id",
                "default_category_id", "guide_default_category_id", "epg_time_shift_by_provider",
                "promoted_live_group_ids", "live_variant_selections", "vod_variant_selections"
            )
        ) PreferenceBackupClassification.PORTABLE_PROVIDER_CONTENT
        else PreferenceBackupClassification.PORTABLE_GLOBAL
        key in deviceBoundStorageKeys -> PreferenceBackupClassification.DEVICE_BOUND
        key in runtimeStorageKeys -> PreferenceBackupClassification.RUNTIME_CACHE
        providerStoragePrefixes.any(key::startsWith) -> PreferenceBackupClassification.PORTABLE_PROVIDER_CONTENT
        globalStoragePrefixes.any(key::startsWith) -> PreferenceBackupClassification.PORTABLE_GLOBAL
        runtimeStoragePrefixes.any(key::startsWith) -> PreferenceBackupClassification.RUNTIME_CACHE
        else -> null
    }

    fun portableCodecForStorageKey(key: String): String? = when {
        storageClassification(key) == PreferenceBackupClassification.PORTABLE_GLOBAL -> "preferences"
        storageClassification(key) == PreferenceBackupClassification.PORTABLE_PROVIDER_CONTENT -> when (key) {
            "active_live_source_type", "active_live_source_id" -> "activeLiveSource"
            "live_variant_selections", "vod_variant_selections" -> "portableVariantChoices"
            "epg_time_shift_by_provider", "last_active_provider_id", "default_category_id",
            "guide_default_category_id", "promoted_live_group_ids" -> "portableProviderPreferences"
            else -> "portableProviderPreferences"
        }
        else -> null
    }

    fun requirePortableCodecs(keys: Set<String>) {
        val unclassified = keys.filter { classification(it) == null }
        require(unclassified.isEmpty()) {
            "Portable preference codec/classification missing for: ${unclassified.sorted().joinToString()}"
        }
    }
}
