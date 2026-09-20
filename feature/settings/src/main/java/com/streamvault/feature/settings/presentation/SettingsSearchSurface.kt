package com.streamvault.feature.settings.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.design.AppColors
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.feature.settings.R

@Composable
internal fun SettingsSearchSurface(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultSelected: (ResolvedSettingsCatalogEntry) -> Unit,
    unavailableIds: Set<String> = emptySet(),
    disabledExplanations: Map<String, String> = emptyMap(),
    listState: LazyListState = rememberLazyListState(),
    returnResultId: String? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val searchFocusRequester = remember { FocusRequester() }
    val resultFocusRequester = remember { FocusRequester() }
    var searchPlaced by remember { mutableStateOf(false) }
    var resultPlaced by remember(returnResultId) { mutableStateOf(false) }
    val entries = resolvedSettingsCatalog(unavailableIds, disabledExplanations)
    val results = remember(entries, query) {
        if (query.isBlank()) {
            val suggestions = setOf(
                "appearance.theme",
                "playback.external",
                "playback.audio_language",
                "live.mode",
                "vod.view_mode",
                "privacy.protection_level",
                "recording.folder",
                "backup.create",
            )
            entries.filter { it.available && it.id in suggestions }
        } else {
            SettingsCatalogSearch.search(entries, query)
        }
    }
    LaunchedEffect(returnResultId, searchPlaced, resultPlaced) {
        when {
            returnResultId != null && resultPlaced -> resultFocusRequester.requestFocus()
            returnResultId == null && searchPlaced -> searchFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = SettingsDesignTokens.compactInset,
            vertical = SettingsDesignTokens.space12,
        ),
        verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
    ) {
        SettingsLocalHeader(
            title = stringResource(R.string.settings_search_title),
            description = "",
            parentTitle = stringResource(R.string.settings_title),
            onBack = onDismiss,
        )
        SearchInput(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.settings_search_hint),
            focusRequester = searchFocusRequester,
            modifier = Modifier.onGloballyPositioned { searchPlaced = true },
        )
        Text(
            text = stringResource(
                if (query.isBlank()) R.string.settings_search_suggestions else R.string.settings_search_results
            ),
            style = MaterialTheme.typography.labelLarge,
            color = colors.secondaryText,
            fontWeight = FontWeight.Medium,
        )
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
                modifier = Modifier.padding(SettingsDesignTokens.space16),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).imePadding(),
                contentPadding = PaddingValues(bottom = SettingsDesignTokens.space24),
                verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4),
            ) {
                items(results, key = { it.id }) { result ->
                    ClickableSettingsRow(
                        label = result.label,
                        value = buildString {
                            append(stringResource(result.category.title))
                            result.page?.let {
                                append(" · ")
                                append(stringResource(it.title))
                            }
                            result.disabledExplanation?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        onClick = { onResultSelected(result) },
                        enabled = result.enabled,
                        modifier = if (result.id == returnResultId) {
                            Modifier
                                .focusRequester(resultFocusRequester)
                                .onGloballyPositioned { resultPlaced = true }
                        } else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun resolvedSettingsCatalog(
    unavailableIds: Set<String>,
    disabledExplanations: Map<String, String>,
): List<ResolvedSettingsCatalogEntry> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration, unavailableIds, disabledExplanations) {
    val pageEntries = SettingsPage.entries.map { page ->
        val category = SettingsCategory.fromId(page.categoryId)
        ResolvedSettingsCatalogEntry(
            id = "page.${page.name.lowercase()}",
            category = category,
            page = page,
            label = context.getString(page.title),
            aliases = setOf(context.getString(page.description)),
        )
    }
    fun setting(
        id: String,
        category: SettingsCategory,
        page: SettingsPage?,
        labelRes: Int,
        vararg aliases: String,
    ) = ResolvedSettingsCatalogEntry(
        id = id,
        category = category,
        page = page,
        label = context.getString(labelRes),
        aliases = aliases.toSet(),
    )
    fun remoteSetting(
        profile: RemoteShortcutProfile,
        profileLabel: Int,
        button: RemoteColorButton,
        buttonLabel: Int,
    ) = ResolvedSettingsCatalogEntry(
        id = remoteShortcutSettingId(profile, button),
        category = SettingsCategory.APP,
        page = SettingsPage.REMOTE,
        label = "${context.getString(profileLabel)} · ${context.getString(buttonLabel)}",
        aliases = setOf(context.getString(R.string.settings_remote_shortcuts_title)),
    )
    val controlEntries = listOf(
        setting("sources.add", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_add_provider, "playlist", "source"),
        setting("sources.active_provider", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_provider_selector_hint, "active source"),
        setting("sources.edit", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_edit, "provider details"),
        setting("sources.sync", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_sync_btn, "refresh provider"),
        setting("sources.delete", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_delete, "remove provider"),
        setting("sources.parental_categories", SettingsCategory.PRIVACY, null, R.string.settings_provider_category_controls_action, "protected categories", "hidden categories"),
        setting("sources.combined.active", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_combined_use_live, "combined source"),
        setting("sources.combined.members", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_combined_add_playlist, "combined source members"),
        setting("sources.combined.delete", SettingsCategory.SOURCES, SettingsPage.PROVIDERS, R.string.settings_combined_delete, "remove combined source"),
        setting("sources.m3u_vod_classification", SettingsCategory.SOURCES, SettingsPage.SOURCE_COMPATIBILITY, R.string.settings_m3u_vod_classification_title, "movies", "series"),
        setting("sources.xtream_text_classification", SettingsCategory.SOURCES, SettingsPage.SOURCE_COMPATIBILITY, R.string.settings_xtream_text_classification),
        setting("sources.xtream_base64", SettingsCategory.SOURCES, SettingsPage.SOURCE_COMPATIBILITY, R.string.settings_xtream_base64_compatibility),
        setting("guide.source.add", SettingsCategory.GUIDE, null, R.string.settings_epg_sources_title, "add XMLTV", "guide source"),
        setting("guide.source.enabled", SettingsCategory.GUIDE, null, R.string.settings_epg_sources_title, "enable guide source"),
        setting("guide.source.refresh", SettingsCategory.GUIDE, null, R.string.settings_epg_refresh, "refresh XMLTV"),
        setting("guide.source.timezone", SettingsCategory.GUIDE, null, R.string.settings_epg_timezone_action, "XMLTV timezone"),
        setting("guide.source.delete", SettingsCategory.GUIDE, null, R.string.settings_epg_confirm_delete, "delete guide source"),
        setting("guide.assignment", SettingsCategory.GUIDE, null, R.string.settings_epg_assignments_title, "provider guide"),
        setting("guide.policy", SettingsCategory.GUIDE, null, R.string.settings_epg_provider_controls_search, "guide policy"),
        setting("guide.logo_policy", SettingsCategory.GUIDE, null, R.string.settings_epg_logo_supplier_preferred, "channel logo source"),
        setting("guide.time_shift", SettingsCategory.GUIDE, null, R.string.settings_epg_time_shift_title, "timezone", "early", "late"),
        setting("guide.default_category", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_guide_default_category, "guide opening category"),
        setting("playback.media_session", SettingsCategory.PLAYBACK, SettingsPage.GENERAL, R.string.settings_media_session, "system controls", "notification"),
        setting("playback.external", SettingsCategory.PLAYBACK, SettingsPage.GENERAL, R.string.settings_external_playback, "external player"),
        setting("playback.speed", SettingsCategory.PLAYBACK, SettingsPage.GENERAL, R.string.settings_default_playback_speed, "speed"),
        setting("playback.av_sync", SettingsCategory.PLAYBACK, SettingsPage.AUDIO, R.string.settings_audio_video_sync_enabled, "lip sync"),
        setting("playback.av_offset", SettingsCategory.PLAYBACK, SettingsPage.AUDIO, R.string.settings_audio_video_sync_default, "audio offset", "lip sync"),
        setting("playback.audio_decoder", SettingsCategory.PLAYBACK, SettingsPage.AUDIO, R.string.settings_audio_decoder_mode, "decoder"),
        setting("playback.audio_output", SettingsCategory.PLAYBACK, SettingsPage.AUDIO, R.string.settings_audio_output_mode, "passthrough"),
        setting("playback.audio_language", SettingsCategory.PLAYBACK, SettingsPage.AUDIO, R.string.settings_preferred_audio_language, "language"),
        setting("playback.live_translation", SettingsCategory.PLAYBACK, SettingsPage.SUBTITLES, R.string.settings_live_translation_enabled, "translate subtitles"),
        setting("playback.translation_endpoint", SettingsCategory.PLAYBACK, SettingsPage.SUBTITLES, R.string.settings_live_translation_endpoint, "translation server"),
        setting("playback.subtitle_size", SettingsCategory.PLAYBACK, SettingsPage.SUBTITLES, R.string.settings_subtitle_size, "captions"),
        setting("playback.subtitle_text_color", SettingsCategory.PLAYBACK, SettingsPage.SUBTITLES, R.string.settings_subtitle_text_color, "captions color"),
        setting("playback.subtitle_background", SettingsCategory.PLAYBACK, SettingsPage.SUBTITLES, R.string.settings_subtitle_background, "captions background"),
        setting("playback.fast_retry", SettingsCategory.PLAYBACK, SettingsPage.NETWORK, R.string.settings_fast_retry_on_transient_failures, "reconnect"),
        setting("playback.buffer", SettingsCategory.PLAYBACK, SettingsPage.NETWORK, R.string.settings_live_buffer_size, "buffering"),
        setting("playback.wifi_cap", SettingsCategory.PLAYBACK, SettingsPage.NETWORK, R.string.settings_wifi_quality_cap, "quality"),
        setting("playback.ethernet_cap", SettingsCategory.PLAYBACK, SettingsPage.NETWORK, R.string.settings_ethernet_quality_cap, "quality"),
        setting("playback.speed_test", SettingsCategory.PLAYBACK, SettingsPage.NETWORK, R.string.settings_speed_test_title, "connection speed"),
        setting("playback.back_button", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_player_back_button, "player controls"),
        setting("playback.confirm_close", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_confirm_close_playback, "exit confirm"),
        setting("playback.controls_timeout", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_player_controls_timeout),
        setting("playback.live_overlay_timeout", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_live_overlay_timeout),
        setting("playback.notice_timeout", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_player_notice_timeout),
        setting("playback.diagnostics_timeout", SettingsCategory.PLAYBACK, SettingsPage.CONTROLS, R.string.settings_player_diagnostics_timeout),
        setting("playback.prevent_standby", SettingsCategory.PLAYBACK, SettingsPage.TIMERS, R.string.settings_prevent_standby, "keep awake"),
        setting("playback.stop_timer", SettingsCategory.PLAYBACK, SettingsPage.TIMERS, R.string.settings_default_stop_timer, "sleep timer"),
        setting("playback.idle_timer", SettingsCategory.PLAYBACK, SettingsPage.TIMERS, R.string.settings_default_idle_standby_timer, "sleep timer"),
        setting("playback.compatibility_memory", SettingsCategory.PLAYBACK, SettingsPage.COMPATIBILITY, R.string.settings_ffmpeg_compatibility_memory),
        setting("playback.clear_compatibility", SettingsCategory.PLAYBACK, SettingsPage.COMPATIBILITY, R.string.settings_ffmpeg_compatibility_clear),
        setting("playback.video_decoder", SettingsCategory.PLAYBACK, SettingsPage.COMPATIBILITY, R.string.settings_video_decoder_mode),
        setting("playback.surface", SettingsCategory.PLAYBACK, SettingsPage.COMPATIBILITY, R.string.settings_surface_mode),
        setting("playback.live_format", SettingsCategory.PLAYBACK, SettingsPage.COMPATIBILITY, R.string.settings_live_stream_format_title),
        setting("live.mode", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_live_tv_channel_mode, "preview", "channel mode"),
        setting("live.auto_hide_categories", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_live_tv_auto_hide_categories, "categories"),
        setting("live.source_switcher", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_show_live_source_switcher),
        setting("live.favorites_category", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_show_favorites_category),
        setting("live.all_category", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_show_all_channels_category),
        setting("live.recent_category", SettingsCategory.LIVE_TV, SettingsPage.LIVE_LAYOUT, R.string.settings_show_recent_channels_category),
        setting("live.hide_decorative_rows", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_hide_decorative_live_rows),
        setting("live.numbering", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_live_channel_numbering_mode),
        setting("live.grouping", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_live_channel_grouping_mode),
        setting("live.group_label", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_grouped_channel_label_mode),
        setting("live.variant_preference", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_live_variant_preference_mode),
        setting("live.sort", SettingsCategory.LIVE_TV, SettingsPage.LIVE_CHANNELS, R.string.settings_category_sort_live),
        setting("live.quick_filters", SettingsCategory.LIVE_TV, SettingsPage.LIVE_FILTERS, R.string.settings_live_tv_quick_filters),
        setting("live.filter_visibility", SettingsCategory.LIVE_TV, SettingsPage.LIVE_FILTERS, R.string.settings_live_tv_quick_filter_visibility),
        setting("live.timeshift_enabled", SettingsCategory.LIVE_TV, SettingsPage.TIMESHIFT, R.string.settings_live_timeshift),
        setting("live.timeshift_depth", SettingsCategory.LIVE_TV, SettingsPage.TIMESHIFT, R.string.settings_live_timeshift_depth),
        setting("live.timeshift_backend", SettingsCategory.LIVE_TV, SettingsPage.TIMESHIFT, R.string.settings_live_timeshift_backend),
        setting("live.zap_auto_revert", SettingsCategory.LIVE_TV, SettingsPage.TIMESHIFT, R.string.settings_zap_auto_revert, "bad channel return"),
        setting("live.clock_enabled", SettingsCategory.LIVE_TV, SettingsPage.CLOCK, R.string.settings_live_clock),
        setting("live.clock_position", SettingsCategory.LIVE_TV, SettingsPage.CLOCK, R.string.settings_live_clock_position),
        setting("live.clock_size", SettingsCategory.LIVE_TV, SettingsPage.CLOCK, R.string.settings_live_clock_size),
        setting("live.clock_font", SettingsCategory.LIVE_TV, SettingsPage.CLOCK, R.string.settings_live_clock_font),
        setting("live.multiview_center_two", SettingsCategory.LIVE_TV, SettingsPage.MULTIVIEW, R.string.settings_multiview_center_two_slot_layout),
        setting("live.multiview_connection_limit", SettingsCategory.LIVE_TV, SettingsPage.MULTIVIEW, R.string.settings_multiview_respect_provider_connection_limit),
        setting("vod.view_mode", SettingsCategory.MOVIES, SettingsPage.VOD_LIBRARY, R.string.settings_vod_view_mode, "library layout"),
        setting("vod.type_badge_icon", SettingsCategory.MOVIES, SettingsPage.VOD_LIBRARY, R.string.settings_vod_type_badge_icons),
        setting("vod.complete_on_open", SettingsCategory.MOVIES, SettingsPage.VOD_LIBRARY, R.string.settings_vod_complete_on_open, "loading"),
        setting("vod.infinite_scroll", SettingsCategory.MOVIES, SettingsPage.VOD_LIBRARY, R.string.settings_vod_infinite_scroll),
        setting("vod.portal_search", SettingsCategory.MOVIES, SettingsPage.VOD_LIBRARY, R.string.settings_vod_portal_search),
        setting("vod.duplicate_handling", SettingsCategory.MOVIES, SettingsPage.VOD_ORGANIZATION, R.string.settings_vod_duplicate_handling_mode),
        setting("vod.variant_preference", SettingsCategory.MOVIES, SettingsPage.VOD_ORGANIZATION, R.string.settings_vod_variant_preference_mode),
        setting("vod.movie_sort", SettingsCategory.MOVIES, SettingsPage.VOD_ORGANIZATION, R.string.settings_category_sort_movies),
        setting("vod.series_sort", SettingsCategory.MOVIES, SettingsPage.VOD_ORGANIZATION, R.string.settings_category_sort_series),
        setting("vod.auto_next_episode", SettingsCategory.MOVIES, SettingsPage.VOD_PLAYBACK, R.string.settings_auto_play_next_episode),
        setting("vod.http_protocol", SettingsCategory.MOVIES, SettingsPage.VOD_PLAYBACK, R.string.settings_vod_http_protocol_mode),
        setting("appearance.theme", SettingsCategory.APP, SettingsPage.APPEARANCE, R.string.settings_theme, "color", "light", "purple", "blue"),
        setting("appearance.language", SettingsCategory.APP, SettingsPage.APPEARANCE, R.string.settings_app_language),
        setting("appearance.time_format", SettingsCategory.APP, SettingsPage.APPEARANCE, R.string.settings_time_format, "clock"),
        setting("appearance.top_navigation", SettingsCategory.APP, SettingsPage.HOME, R.string.settings_top_navigation, "menu"),
        setting("appearance.home_shelves", SettingsCategory.APP, SettingsPage.HOME, R.string.settings_customize_home, "dashboard"),
        setting("appearance.landing", SettingsCategory.APP, SettingsPage.HOME, R.string.settings_default_landing_screen, "startup"),
        remoteSetting(RemoteShortcutProfile.GLOBAL, R.string.settings_remote_profile_global, RemoteColorButton.RED, R.string.settings_remote_button_red),
        remoteSetting(RemoteShortcutProfile.GLOBAL, R.string.settings_remote_profile_global, RemoteColorButton.GREEN, R.string.settings_remote_button_green),
        remoteSetting(RemoteShortcutProfile.GLOBAL, R.string.settings_remote_profile_global, RemoteColorButton.YELLOW, R.string.settings_remote_button_yellow),
        remoteSetting(RemoteShortcutProfile.GLOBAL, R.string.settings_remote_profile_global, RemoteColorButton.BLUE, R.string.settings_remote_button_blue),
        remoteSetting(RemoteShortcutProfile.PLAYBACK, R.string.settings_remote_profile_playback, RemoteColorButton.RED, R.string.settings_remote_button_red),
        remoteSetting(RemoteShortcutProfile.PLAYBACK, R.string.settings_remote_profile_playback, RemoteColorButton.GREEN, R.string.settings_remote_button_green),
        remoteSetting(RemoteShortcutProfile.PLAYBACK, R.string.settings_remote_profile_playback, RemoteColorButton.YELLOW, R.string.settings_remote_button_yellow),
        remoteSetting(RemoteShortcutProfile.PLAYBACK, R.string.settings_remote_profile_playback, RemoteColorButton.BLUE, R.string.settings_remote_button_blue),
        remoteSetting(RemoteShortcutProfile.BROWSE, R.string.settings_remote_profile_browse, RemoteColorButton.RED, R.string.settings_remote_button_red),
        remoteSetting(RemoteShortcutProfile.BROWSE, R.string.settings_remote_profile_browse, RemoteColorButton.GREEN, R.string.settings_remote_button_green),
        remoteSetting(RemoteShortcutProfile.BROWSE, R.string.settings_remote_profile_browse, RemoteColorButton.YELLOW, R.string.settings_remote_button_yellow),
        remoteSetting(RemoteShortcutProfile.BROWSE, R.string.settings_remote_profile_browse, RemoteColorButton.BLUE, R.string.settings_remote_button_blue),
        setting("privacy.incognito", SettingsCategory.PRIVACY, null, R.string.settings_incognito_mode, "private viewing", "history"),
        setting("privacy.clear_history", SettingsCategory.PRIVACY, null, R.string.settings_clear_history, "watch progress", "recents"),
        setting("privacy.protection_level", SettingsCategory.PRIVACY, null, R.string.settings_protection_level, "parental controls", "lock"),
        setting("privacy.pin", SettingsCategory.PRIVACY, null, R.string.settings_parental_pin, "password", "change pin"),
        setting("privacy.category_protection", SettingsCategory.PRIVACY, null, R.string.settings_provider_category_controls_action, "protected categories"),
        setting("privacy.category_visibility", SettingsCategory.PRIVACY, null, R.string.settings_provider_category_controls_action, "hidden categories"),
        setting("recording.browser", SettingsCategory.RECORDING, SettingsPage.RECORDING_STATUS, R.string.settings_recording_open_browser, "files"),
        setting("recording.reconcile", SettingsCategory.RECORDING, SettingsPage.RECORDING_STATUS, R.string.settings_recording_reconcile, "scan"),
        setting("recording.folder", SettingsCategory.RECORDING, SettingsPage.RECORDING_STORAGE, R.string.settings_recording_choose_folder),
        setting("recording.app_storage", SettingsCategory.RECORDING, SettingsPage.RECORDING_STORAGE, R.string.settings_recording_use_app_storage),
        setting("recording.usb_storage", SettingsCategory.RECORDING, SettingsPage.RECORDING_STORAGE, R.string.settings_recording_use_usb_storage),
        setting("recording.filename", SettingsCategory.RECORDING, SettingsPage.RECORDING_DEFAULTS, R.string.settings_recording_pattern_title, "file name"),
        setting("recording.retention", SettingsCategory.RECORDING, SettingsPage.RECORDING_DEFAULTS, R.string.settings_recording_retention_title),
        setting("recording.concurrency", SettingsCategory.RECORDING, SettingsPage.RECORDING_DEFAULTS, R.string.settings_recording_concurrency_title, "simultaneous"),
        setting("recording.padding", SettingsCategory.RECORDING, SettingsPage.RECORDING_DEFAULTS, R.string.settings_recording_padding_title),
        setting("recording.wifi_only", SettingsCategory.RECORDING, SettingsPage.RECORDING_DEFAULTS, R.string.settings_recording_wifi_only),
        setting("backup.create", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_backup_data, "export"),
        setting("backup.restore", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_restore_data, "import"),
        setting("backup.manage_local", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_manage_local_backups),
        setting("backup.share", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_backup_share_data),
        setting("backup.usb_create", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_backup_usb_data),
        setting("backup.usb_restore", SettingsCategory.BACKUP, SettingsPage.LOCAL_BACKUP, R.string.settings_restore_usb_data),
        setting("backup.drive_auth", SettingsCategory.BACKUP, SettingsPage.DRIVE_BACKUP, R.string.settings_drive_signin, "google drive", "account"),
        setting("backup.drive_push", SettingsCategory.BACKUP, SettingsPage.DRIVE_BACKUP, R.string.settings_drive_push, "upload"),
        setting("backup.drive_pull", SettingsCategory.BACKUP, SettingsPage.DRIVE_BACKUP, R.string.settings_drive_pull, "download"),
        setting("backup.drive_manage", SettingsCategory.BACKUP, SettingsPage.DRIVE_BACKUP, R.string.settings_manage_drive_backups),
        setting("about.auto_update_check", SettingsCategory.ABOUT, SettingsPage.UPDATES, R.string.settings_update_auto_check),
        setting("about.auto_update_download", SettingsCategory.ABOUT, SettingsPage.UPDATES, R.string.settings_update_auto_download),
        setting("about.check_update", SettingsCategory.ABOUT, SettingsPage.UPDATES, R.string.settings_update_check_now),
        setting("about.download_install", SettingsCategory.ABOUT, SettingsPage.UPDATES, R.string.settings_update_download, "install"),
        setting("about.release", SettingsCategory.ABOUT, SettingsPage.UPDATES, R.string.settings_update_view_release, "release notes"),
        setting("support.crash_view", SettingsCategory.ABOUT, SettingsPage.REPORTS, R.string.settings_crash_report_view, "diagnostics"),
        setting("support.crash_share", SettingsCategory.ABOUT, SettingsPage.REPORTS, R.string.settings_crash_report_share),
        setting("support.crash_delete", SettingsCategory.ABOUT, SettingsPage.REPORTS, R.string.settings_crash_report_delete),
        setting("about.github", SettingsCategory.ABOUT, SettingsPage.APP_INFO, R.string.settings_github, "source code"),
        setting("about.donate", SettingsCategory.ABOUT, SettingsPage.APP_INFO, R.string.settings_donate, "support"),
        setting("about.close_app", SettingsCategory.ABOUT, SettingsPage.APP_INFO, R.string.settings_close_app, "exit"),
    )
    (pageEntries + controlEntries).map { entry ->
        when {
            entry.id in unavailableIds -> entry.copy(available = false)
            entry.id in disabledExplanations -> entry.copy(
                enabled = false,
                disabledExplanation = disabledExplanations.getValue(entry.id),
            )
            else -> entry
        }
    }
    }
}
