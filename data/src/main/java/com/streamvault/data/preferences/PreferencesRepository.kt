package com.streamvault.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.VodVariantObservation
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.domain.model.RemoteShortcutSelection
import com.streamvault.domain.model.SearchHistoryScope
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.manager.ParentalPinVerifier
import com.streamvault.domain.manager.ParentalControlSessionState
import com.streamvault.domain.manager.ParentalControlSessionStore
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val PREFERENCES_DATASTORE_NAME = "user_preferences"

@Singleton
class PreferencesCorruptionRecovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val MAX_SNAPSHOT_BYTES = 4L * 1024L * 1024L
        const val MAX_SNAPSHOTS = 3
    }

    private val lastRecoveryAt = AtomicLong(0L)

    fun recover(
        cause: Throwable,
        dataStoreName: String = PREFERENCES_DATASTORE_NAME
    ): Preferences {
        val recoveredAt = System.currentTimeMillis()
        lastRecoveryAt.set(recoveredAt)
        val directory = File(context.filesDir, "preference-recovery").apply { mkdirs() }
        runCatching {
            val source = context.preferencesDataStoreFile(dataStoreName)
            if (!source.isFile) return@runCatching
            val snapshot = File(directory, "$dataStoreName.corrupt-$recoveredAt")
            source.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = MAX_SNAPSHOT_BYTES
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            directory.listFiles { _, name ->
                name.startsWith("$dataStoreName.corrupt-")
            }?.sortedByDescending(File::lastModified)
                ?.drop(MAX_SNAPSHOTS)
                ?.forEach(File::delete)
        }
        runCatching {
            File(directory, "$dataStoreName.recovery").writeText(
                "timestamp=$recoveredAt\n" +
                    "type=${cause::class.java.name}\n" +
                    "message=${cause.message.orEmpty().take(512)}\n"
            )
        }
        Log.e("PreferencesRecovery", "Preferences DataStore was corrupt; defaults restored", cause)
        return emptyPreferences()
    }

    fun lastRecoveryTimestamp(): Long = lastRecoveryAt.get()
}

private fun sanitizePlaybackTimerMinutes(minutes: Int): Int = when (minutes) {
    0, 15, 30, 45, 60, 90, 120 -> minutes
    in Int.MIN_VALUE..7 -> 0
    in 8..22 -> 15
    in 23..37 -> 30
    in 38..52 -> 45
    in 53..75 -> 60
    in 76..105 -> 90
    else -> 120
}

internal fun parsePlaybackBufferModePreference(saved: String?): PlaybackBufferMode =
    saved?.let { value -> PlaybackBufferMode.entries.firstOrNull { it.name == value } }
        ?: PlaybackBufferMode.AUTO

internal fun parseDecoderModePreference(saved: String?, legacySaved: String? = null): DecoderMode =
    saved?.let { value -> DecoderMode.entries.firstOrNull { it.name == value } }
        ?: legacySaved?.let { value -> DecoderMode.entries.firstOrNull { it.name == value } }
        ?: DecoderMode.AUTO

internal fun parseTimeshiftBackendPreference(saved: String?): TimeshiftBackendPreference =
    saved?.let { value -> TimeshiftBackendPreference.entries.firstOrNull { it.name == value } }
        ?: TimeshiftBackendPreference.AUTOMATIC

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelPreferenceDao: ChannelPreferenceDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val corruptionRecovery: PreferencesCorruptionRecovery
) : ParentalControlSessionStore, ParentalPinVerifier {
    private val preferencesDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { cause ->
                corruptionRecovery.recover(cause)
            },
            produceFile = { context.preferencesDataStoreFile(PREFERENCES_DATASTORE_NAME) }
        )
    }

    private val Context.dataStore: DataStore<Preferences>
        get() = preferencesDataStore
    companion object {
        private const val AUDIO_VIDEO_OFFSET_MIN_MS = -2_000
        private const val AUDIO_VIDEO_OFFSET_MAX_MS = 2_000
        private const val PIN_SALT_BYTES = 16
        private const val PIN_HASH_ITERATIONS = 120_000
        private const val PIN_HASH_KEY_BITS = 256
        private const val PIN_MAX_ATTEMPTS = 5
        private const val PIN_LOCKOUT_DURATION_MS = 15 * 60 * 1000L
        private val secureRandom = SecureRandom()
    }

    private object PreferencesKeys {
        val LAST_ACTIVE_PROVIDER_ID = longPreferencesKey("last_active_provider_id")
        val ACTIVE_LIVE_SOURCE_TYPE = stringPreferencesKey("active_live_source_type")
        val ACTIVE_LIVE_SOURCE_ID = longPreferencesKey("active_live_source_id")
        val DEFAULT_VIEW_MODE = stringPreferencesKey("default_view_mode")
        val PARENTAL_CONTROL_LEVEL = intPreferencesKey("parental_control_level")
        val PARENTAL_V2_MIGRATED = booleanPreferencesKey("parental_v2_migrated")
        val LEGACY_PARENTAL_PIN = stringPreferencesKey("parental_pin")
        val PARENTAL_PIN_HASH = stringPreferencesKey("parental_pin_hash")
        val PARENTAL_PIN_SALT = stringPreferencesKey("parental_pin_salt")
        val DEFAULT_CATEGORY_ID = longPreferencesKey("default_category_id")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val APP_LANDING_DESTINATION = stringPreferencesKey("app_landing_destination")
        val APP_TOP_LEVEL_DESTINATIONS = stringPreferencesKey("app_top_level_destinations")
        val APP_HOME_DASHBOARD_SHELVES = stringPreferencesKey("app_home_dashboard_shelves")
        val APP_TIME_FORMAT = stringPreferencesKey("app_time_format")
        val LIVE_TV_CHANNEL_MODE = stringPreferencesKey("live_tv_channel_mode")
        val SHOW_LIVE_SOURCE_SWITCHER = booleanPreferencesKey("show_live_source_switcher")
        val SHOW_FAVORITES_CATEGORY = booleanPreferencesKey("show_favorites_category")
        val SHOW_ALL_CHANNELS_CATEGORY = booleanPreferencesKey("show_all_channels_category")
        val SHOW_RECENT_CHANNELS_CATEGORY = booleanPreferencesKey("show_recent_channels_category")
        val LIVE_TV_CATEGORY_FILTERS = stringPreferencesKey("live_tv_category_filters")
        val LIVE_TV_QUICK_FILTER_VISIBILITY = stringPreferencesKey("live_tv_quick_filter_visibility")
        val HIDE_DECORATIVE_LIVE_ROWS = booleanPreferencesKey("hide_decorative_live_rows")
        val LIVE_CHANNEL_NUMBERING_MODE = stringPreferencesKey("live_channel_numbering_mode")
        val LIVE_CHANNEL_GROUPING_MODE = stringPreferencesKey("live_channel_grouping_mode")
        val GROUPED_CHANNEL_LABEL_MODE = stringPreferencesKey("grouped_channel_label_mode")
        val LIVE_VARIANT_PREFERENCE_MODE = stringPreferencesKey("live_variant_preference_mode")
        val LIVE_VARIANT_SELECTIONS = stringPreferencesKey("live_variant_selections")
        val LIVE_VARIANT_OBSERVATIONS = stringPreferencesKey("live_variant_observations")
        val VOD_VIEW_MODE = stringPreferencesKey("vod_view_mode")
        val VOD_INFINITE_SCROLL = booleanPreferencesKey("vod_infinite_scroll")
        val VOD_CATEGORY_LOAD_MODE = stringPreferencesKey("vod_category_load_mode")
        val VOD_DUPLICATE_HANDLING_MODE = stringPreferencesKey("vod_duplicate_handling_mode")
        val VOD_VARIANT_PREFERENCE_MODE = stringPreferencesKey("vod_variant_preference_mode")
        val VOD_VARIANT_SELECTIONS = stringPreferencesKey("vod_variant_selections")
        val VOD_VARIANT_OBSERVATIONS = stringPreferencesKey("vod_variant_observations")
        val GUIDE_DENSITY = stringPreferencesKey("guide_density")
        val GUIDE_CHANNEL_MODE = stringPreferencesKey("guide_channel_mode")
        val GUIDE_DEFAULT_CATEGORY_ID = longPreferencesKey("guide_default_category_id")
        val GUIDE_FAVORITES_ONLY = intPreferencesKey("guide_favorites_only")
        val GUIDE_ANCHOR_TIME = longPreferencesKey("guide_anchor_time")
        val EPG_TIME_SHIFT_BY_PROVIDER = stringPreferencesKey("epg_time_shift_by_provider")
        val PROMOTED_LIVE_GROUP_IDS = stringPreferencesKey("promoted_live_group_ids")
        val MULTIVIEW_PRESET_1 = stringPreferencesKey("multiview_preset_1")
        val MULTIVIEW_PRESET_2 = stringPreferencesKey("multiview_preset_2")
        val MULTIVIEW_PRESET_3 = stringPreferencesKey("multiview_preset_3")
        val MULTIVIEW_PERFORMANCE_MODE = stringPreferencesKey("multiview_performance_mode")
        val MULTIVIEW_CENTER_TWO_SLOT_LAYOUT = booleanPreferencesKey("multiview_center_two_slot_layout")
        val MULTIVIEW_RESPECT_PROVIDER_CONNECTION_LIMIT =
            booleanPreferencesKey("multiview_respect_provider_connection_limit")
        val IS_INCOGNITO_MODE = booleanPreferencesKey("is_incognito_mode")
        val PLAYER_MUTED = booleanPreferencesKey("player_muted")
        val PLAYER_MEDIA_SESSION_ENABLED = booleanPreferencesKey("player_media_session_enabled")
        val PLAYER_FAST_RETRY_ON_TRANSIENT_FAILURES =
            booleanPreferencesKey("player_fast_retry_on_transient_failures")
        val PLAYER_DECODER_MODE = stringPreferencesKey("player_decoder_mode")
        val PLAYER_AUDIO_DECODER_MODE = stringPreferencesKey("player_audio_decoder_mode")
        val PLAYER_VIDEO_DECODER_MODE = stringPreferencesKey("player_video_decoder_mode")
        val PLAYER_PLAYBACK_BUFFER_MODE = stringPreferencesKey("player_playback_buffer_mode")
        val PLAYER_LIVE_STREAM_FORMAT_MODE = stringPreferencesKey("player_live_stream_format_mode")
        val PLAYER_VOD_HTTP_PROTOCOL_MODE = stringPreferencesKey("player_vod_http_protocol_mode")
        val LEGACY_PLAYER_MOVIE_HTTP_PROTOCOL_MODE = stringPreferencesKey("player_movie_http_protocol_mode")
        val PLAYER_AUDIO_OUTPUT_PREFERENCE = stringPreferencesKey("player_audio_output_preference")
        val PLAYER_COMPATIBILITY_MEMORY_ENABLED = booleanPreferencesKey("player_compatibility_memory_enabled")
        val PLAYER_SURFACE_MODE = stringPreferencesKey("player_surface_mode")
        val PLAYER_PLAYBACK_SPEED = stringPreferencesKey("player_playback_speed")
        val PLAYER_EXTERNAL_PLAYBACK_MODE = stringPreferencesKey("player_external_playback_mode")
        val PLAYER_AUDIO_VIDEO_SYNC_ENABLED = booleanPreferencesKey("player_av_sync_enabled")
        val PLAYER_AUDIO_VIDEO_OFFSET_MS = intPreferencesKey("player_av_offset_ms")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val PLAYER_SUBTITLE_TEXT_SCALE = stringPreferencesKey("player_subtitle_text_scale")
        val PLAYER_SUBTITLE_TEXT_COLOR = intPreferencesKey("player_subtitle_text_color")
        val PLAYER_SUBTITLE_BACKGROUND_COLOR = intPreferencesKey("player_subtitle_background_color")
        val PLAYER_LIVE_TRANSLATION_ENABLED = booleanPreferencesKey("player_live_translation_enabled")
        val PLAYER_LIVE_TRANSLATION_ENDPOINT = stringPreferencesKey("player_live_translation_endpoint")
        val PLAYER_CONTROLS_TIMEOUT_SECONDS = intPreferencesKey("player_controls_timeout_seconds")
        val PLAYER_LIVE_OVERLAY_TIMEOUT_SECONDS = intPreferencesKey("player_live_overlay_timeout_seconds")
        val PLAYER_NOTICE_TIMEOUT_SECONDS = intPreferencesKey("player_notice_timeout_seconds")
        val PLAYER_DIAGNOSTICS_TIMEOUT_SECONDS = intPreferencesKey("player_diagnostics_timeout_seconds")
        val PLAYER_WIFI_MAX_VIDEO_HEIGHT = intPreferencesKey("player_wifi_max_video_height")
        val PLAYER_ETHERNET_MAX_VIDEO_HEIGHT = intPreferencesKey("player_ethernet_max_video_height")
        val PLAYER_TIMESHIFT_ENABLED = booleanPreferencesKey("player_timeshift_enabled")
        val PLAYER_TIMESHIFT_DEPTH_MINUTES = intPreferencesKey("player_timeshift_depth_minutes")
        val PLAYER_TIMESHIFT_BACKEND = stringPreferencesKey("player_timeshift_backend")
        val DEFAULT_STOP_PLAYBACK_TIMER_MINUTES = intPreferencesKey("default_stop_playback_timer_minutes")
        val DEFAULT_IDLE_STANDBY_TIMER_MINUTES = intPreferencesKey("default_idle_standby_timer_minutes")
        val LAST_SPEED_TEST_MEGABITS = stringPreferencesKey("last_speed_test_megabits")
        val LAST_SPEED_TEST_TIMESTAMP = longPreferencesKey("last_speed_test_timestamp")
        val LAST_SPEED_TEST_TRANSPORT = stringPreferencesKey("last_speed_test_transport")
        val LAST_SPEED_TEST_RECOMMENDED_HEIGHT = intPreferencesKey("last_speed_test_recommended_height")
        val LAST_SPEED_TEST_ESTIMATED = booleanPreferencesKey("last_speed_test_estimated")
        val GUIDE_SCHEDULED_ONLY = intPreferencesKey("guide_scheduled_only")
        val RECENT_SEARCH_QUERIES = stringPreferencesKey("recent_search_queries")
        val XTREAM_TEXT_CLASSIFICATION = booleanPreferencesKey("xtream_text_classification")
        val XTREAM_BASE64_TEXT_COMPATIBILITY = booleanPreferencesKey("xtream_base64_text_compatibility")
        val XTREAM_TEXT_IMPORT_GENERATION = longPreferencesKey("xtream_text_import_generation")
        val ZAP_AUTO_REVERT = booleanPreferencesKey("zap_auto_revert")
        val PREVENT_STANDBY_DURING_PLAYBACK = booleanPreferencesKey("prevent_standby_during_playback")
        val AUTO_PLAY_NEXT_EPISODE = booleanPreferencesKey("auto_play_next_episode")
        val AUTO_CHECK_APP_UPDATES = booleanPreferencesKey("auto_check_app_updates")
        val AUTO_DOWNLOAD_APP_UPDATES = booleanPreferencesKey("auto_download_app_updates")
        val RECORDING_WIFI_ONLY = booleanPreferencesKey("recording_wifi_only")
        val RECORDING_PADDING_BEFORE_MINUTES = intPreferencesKey("recording_padding_before_minutes")
        val RECORDING_PADDING_AFTER_MINUTES = intPreferencesKey("recording_padding_after_minutes")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        val MAX_CONCURRENT_STREAMS = intPreferencesKey("max_concurrent_streams")
        val LAST_APP_UPDATE_CHECK_TIMESTAMP = longPreferencesKey("last_app_update_check_timestamp")
        val LAST_APP_UPDATE_ATTEMPT_TIMESTAMP = longPreferencesKey("last_app_update_attempt_timestamp")
        val LAST_APP_UPDATE_FAILURE_TIMESTAMP = longPreferencesKey("last_app_update_failure_timestamp")
        val LAST_APP_UPDATE_OUTCOME = stringPreferencesKey("last_app_update_outcome")
        val APP_UPDATE_DOWNLOAD_ID = longPreferencesKey("app_update_download_id")
        val APP_UPDATE_DOWNLOAD_VERSION_NAME = stringPreferencesKey("app_update_download_version_name")
        val APP_UPDATE_DOWNLOADED_VERSION_NAME = stringPreferencesKey("app_update_downloaded_version_name")
        val APP_UPDATE_LATEST_VERSION_NAME = stringPreferencesKey("app_update_latest_version_name")
        val APP_UPDATE_LATEST_VERSION_CODE = intPreferencesKey("app_update_latest_version_code")
        val APP_UPDATE_RELEASE_URL = stringPreferencesKey("app_update_release_url")
        val APP_UPDATE_DOWNLOAD_URL = stringPreferencesKey("app_update_download_url")
        val APP_UPDATE_DOWNLOAD_SHA256 = stringPreferencesKey("app_update_download_sha256")
        val APP_UPDATE_RELEASE_NOTES = stringPreferencesKey("app_update_release_notes")
        val APP_UPDATE_PUBLISHED_AT = stringPreferencesKey("app_update_published_at")
        val LAST_MAINTENANCE_AT = longPreferencesKey("last_maintenance_at")
        val LAST_MAINTENANCE_DELETED_PROGRAMS = intPreferencesKey("last_maintenance_deleted_programs")
        val LAST_MAINTENANCE_DELETED_EXTERNAL_PROGRAMMES = intPreferencesKey("last_maintenance_deleted_external_programmes")
        val LAST_MAINTENANCE_DELETED_ORPHAN_EPISODES = intPreferencesKey("last_maintenance_deleted_orphan_episodes")
        val LAST_MAINTENANCE_DELETED_STALE_FAVORITES = intPreferencesKey("last_maintenance_deleted_stale_favorites")
        val LAST_MAINTENANCE_VACUUM_RAN = booleanPreferencesKey("last_maintenance_vacuum_ran")
        val LAST_MAINTENANCE_MAIN_DB_BYTES = longPreferencesKey("last_maintenance_main_db_bytes")
        val LAST_MAINTENANCE_WAL_BYTES = longPreferencesKey("last_maintenance_wal_bytes")
        val LAST_MAINTENANCE_RECLAIMABLE_BYTES = longPreferencesKey("last_maintenance_reclaimable_bytes")
        val LAST_MAINTENANCE_CHANNEL_ROWS = longPreferencesKey("last_maintenance_channel_rows")
        val LAST_MAINTENANCE_MOVIE_ROWS = longPreferencesKey("last_maintenance_movie_rows")
        val LAST_MAINTENANCE_SERIES_ROWS = longPreferencesKey("last_maintenance_series_rows")
        val LAST_MAINTENANCE_EPISODE_ROWS = longPreferencesKey("last_maintenance_episode_rows")
        val LAST_MAINTENANCE_PROGRAM_ROWS = longPreferencesKey("last_maintenance_program_rows")
        val LAST_MAINTENANCE_EPG_PROGRAMME_ROWS = longPreferencesKey("last_maintenance_epg_programme_rows")
        val LAST_MAINTENANCE_PLAYBACK_HISTORY_ROWS = longPreferencesKey("last_maintenance_playback_history_rows")
        val LAST_MAINTENANCE_FAVORITE_ROWS = longPreferencesKey("last_maintenance_favorite_rows")
    }

    private object ParentalSessionKeys {
        const val FILE_NAME = "parental_control_session"
        const val UNLOCK_ENTRIES = "unlock_entries"
        const val UNLOCK_TIMEOUT_MS = "unlock_timeout_ms"
        const val PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        const val PIN_LOCKOUT_UNTIL_MS = "pin_lockout_until_ms"
    }

    private val parentalSessionPreferences: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ParentalSessionKeys.FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { e ->
            // Android 9 (API 28) on certain OEM devices (MediaTek, some Samsung/Huawei) has
            // buggy Keystore HAL implementations that throw GeneralSecurityException or
            // KeyStoreException during MasterKey generation. Fall back to unencrypted prefs
            // so the app stays functional. Only PIN lockout timestamps are affected — the PIN
            // hash/salt itself is stored in DataStore and is unaffected by this fallback.
            Log.w(
                "PreferencesRepository",
                "EncryptedSharedPreferences unavailable on API ${Build.VERSION.SDK_INT} " +
                    "(Keystore error). Parental PIN lockout persistence uses unencrypted fallback.",
                e
            )
            context.getSharedPreferences(
                "${ParentalSessionKeys.FILE_NAME}_fallback",
                Context.MODE_PRIVATE
            )
        }
    }

    val lastActiveProviderId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_ACTIVE_PROVIDER_ID]
    }

    val activeLiveSource: Flow<ActiveLiveSource?> = context.dataStore.data.map { preferences ->
        val sourceId = preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_ID] ?: return@map null
        when (preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_TYPE]) {
            "provider" -> ActiveLiveSource.ProviderSource(sourceId)
            "combined_m3u" -> ActiveLiveSource.CombinedM3uSource(sourceId)
            else -> null
        }
    }

    val defaultViewMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEFAULT_VIEW_MODE]
    }

    val isIncognitoMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.IS_INCOGNITO_MODE] ?: false
    }

    val useXtreamTextClassification: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.XTREAM_TEXT_CLASSIFICATION] ?: true // default ON
    }

    val xtreamBase64TextCompatibility: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.XTREAM_BASE64_TEXT_COMPATIBILITY] ?: false
    }

    val xtreamTextImportGeneration: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.XTREAM_TEXT_IMPORT_GENERATION] ?: 0L
    }

    val playerMuted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_MUTED] ?: false
    }

    val playerMediaSessionEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_MEDIA_SESSION_ENABLED] ?: true
    }

    val playerFastRetryOnTransientFailures: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_FAST_RETRY_ON_TRANSIENT_FAILURES] ?: false
    }

    val playerAudioDecoderMode: Flow<DecoderMode> = context.dataStore.data.map { preferences ->
        parseDecoderModePreference(
            saved = preferences[PreferencesKeys.PLAYER_AUDIO_DECODER_MODE],
            legacySaved = preferences[PreferencesKeys.PLAYER_DECODER_MODE]
        )
    }

    val playerVideoDecoderMode: Flow<DecoderMode> = context.dataStore.data.map { preferences ->
        parseDecoderModePreference(
            saved = preferences[PreferencesKeys.PLAYER_VIDEO_DECODER_MODE],
            legacySaved = preferences[PreferencesKeys.PLAYER_DECODER_MODE]
        )
    }

    val playerPlaybackBufferMode: Flow<PlaybackBufferMode> = context.dataStore.data.map { preferences ->
        parsePlaybackBufferModePreference(preferences[PreferencesKeys.PLAYER_PLAYBACK_BUFFER_MODE])
    }

    val playerSurfaceMode: Flow<PlayerSurfaceMode> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_SURFACE_MODE]
            ?.let { saved -> PlayerSurfaceMode.entries.firstOrNull { it.name == saved } }
            ?: PlayerSurfaceMode.AUTO
    }

    val playerLiveStreamFormatMode: Flow<LiveStreamFormatMode> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_LIVE_STREAM_FORMAT_MODE]
            ?.let { saved -> LiveStreamFormatMode.entries.firstOrNull { it.name == saved } }
            ?: LiveStreamFormatMode.AUTO
    }

    val playerVodHttpProtocolMode: Flow<VodHttpProtocolMode> = context.dataStore.data.map { preferences ->
        (
            preferences[PreferencesKeys.PLAYER_VOD_HTTP_PROTOCOL_MODE]
                ?: preferences[PreferencesKeys.LEGACY_PLAYER_MOVIE_HTTP_PROTOCOL_MODE]
            )
            ?.let { saved -> VodHttpProtocolMode.entries.firstOrNull { it.name == saved } }
            ?: VodHttpProtocolMode.COMPATIBILITY_HTTP1
    }

    val playerAudioOutputPreference: Flow<AudioOutputPreference> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_AUDIO_OUTPUT_PREFERENCE]
            ?.let { saved -> AudioOutputPreference.entries.firstOrNull { it.name == saved } }
            ?: AudioOutputPreference.AUTO
    }

    val playerCompatibilityMemoryEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_COMPATIBILITY_MEMORY_ENABLED] ?: true
    }

    val playerPlaybackSpeed: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_PLAYBACK_SPEED]
            ?.toFloatOrNull()
            ?.coerceIn(0.5f, 2f)
            ?: 1f
    }

    val playerExternalPlaybackMode: Flow<com.streamvault.domain.model.ExternalPlaybackMode> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_EXTERNAL_PLAYBACK_MODE]
            ?.let { saved -> com.streamvault.domain.model.ExternalPlaybackMode.fromStorageValue(saved) }
            ?: com.streamvault.domain.model.ExternalPlaybackMode.INTERNAL_PLAYER
    }

    val playerAudioVideoOffsetMs: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.PLAYER_AUDIO_VIDEO_OFFSET_MS] ?: 0)
            .coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
    }

    val playerAudioVideoSyncEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_AUDIO_VIDEO_SYNC_ENABLED] ?: false
    }

    val preferredAudioLanguage: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "auto"
    }

    val playerSubtitleTextScale: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_SUBTITLE_TEXT_SCALE]
            ?.toFloatOrNull()
            ?.coerceIn(0.75f, 1.75f)
            ?: 1f
    }

    val playerSubtitleTextColor: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_SUBTITLE_TEXT_COLOR] ?: 0xFFFFFFFF.toInt()
    }

    val playerSubtitleBackgroundColor: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_SUBTITLE_BACKGROUND_COLOR] ?: 0x80000000.toInt()
    }

    val playerLiveTranslationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_LIVE_TRANSLATION_ENABLED] ?: false
    }

    val playerLiveTranslationEndpoint: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_LIVE_TRANSLATION_ENDPOINT]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:8765"
    }

    val playerControlsTimeoutSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.PLAYER_CONTROLS_TIMEOUT_SECONDS] ?: 5).coerceIn(2, 60)
    }

    val playerLiveOverlayTimeoutSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.PLAYER_LIVE_OVERLAY_TIMEOUT_SECONDS] ?: 4).coerceIn(2, 60)
    }

    val playerNoticeTimeoutSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.PLAYER_NOTICE_TIMEOUT_SECONDS] ?: 6).coerceIn(2, 60)
    }

    val playerDiagnosticsTimeoutSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.PLAYER_DIAGNOSTICS_TIMEOUT_SECONDS] ?: 15).coerceIn(2, 60)
    }

    val playerWifiMaxVideoHeight: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_WIFI_MAX_VIDEO_HEIGHT]?.takeIf { it > 0 }
    }

    val playerEthernetMaxVideoHeight: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_ETHERNET_MAX_VIDEO_HEIGHT]?.takeIf { it > 0 }
    }

    val playerTimeshiftEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYER_TIMESHIFT_ENABLED] ?: false
    }

    val playerTimeshiftDepthMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        when (preferences[PreferencesKeys.PLAYER_TIMESHIFT_DEPTH_MINUTES] ?: 30) {
            15, 30, 60 -> preferences[PreferencesKeys.PLAYER_TIMESHIFT_DEPTH_MINUTES] ?: 30
            in Int.MIN_VALUE..22 -> 15
            in 23..45 -> 30
            else -> 60
        }
    }

    val playerTimeshiftBackend: Flow<TimeshiftBackendPreference> = context.dataStore.data.map { preferences ->
        parseTimeshiftBackendPreference(preferences[PreferencesKeys.PLAYER_TIMESHIFT_BACKEND])
    }

    val defaultStopPlaybackTimerMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        sanitizePlaybackTimerMinutes(preferences[PreferencesKeys.DEFAULT_STOP_PLAYBACK_TIMER_MINUTES] ?: 0)
    }

    val defaultIdleStandbyTimerMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        sanitizePlaybackTimerMinutes(preferences[PreferencesKeys.DEFAULT_IDLE_STANDBY_TIMER_MINUTES] ?: 0)
    }

    val lastSpeedTestMegabits: Flow<Double?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_SPEED_TEST_MEGABITS]?.toDoubleOrNull()
    }

    val lastSpeedTestTimestamp: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_SPEED_TEST_TIMESTAMP]?.takeIf { it > 0L }
    }

    val lastSpeedTestTransport: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_SPEED_TEST_TRANSPORT]?.takeIf { it.isNotBlank() }
    }

    val lastSpeedTestRecommendedHeight: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_SPEED_TEST_RECOMMENDED_HEIGHT]?.takeIf { it > 0 }
    }

    val lastSpeedTestEstimated: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_SPEED_TEST_ESTIMATED] ?: false
    }

    val recentSearchQueries: Flow<List<String>> = getRecentSearchQueries(SearchHistoryScope.ALL, null)

    val parentalControlLevel: Flow<Int> = context.dataStore.data
        .map { preferences ->
            val stored = preferences[PreferencesKeys.PARENTAL_CONTROL_LEVEL] ?: 2 // Default to 2 = PRIVATE
            // Migration: users who had the old HIDDEN level (stored as 2) are promoted to the new
            // HIDDEN level (3). The v2-migrated flag prevents re-mapping after the user explicitly
            // sets level 2 (PRIVATE) in the new scheme.
            val migrated = preferences[PreferencesKeys.PARENTAL_V2_MIGRATED] ?: false
            if (!migrated && stored == 2) 3 else stored
        }

    val hasParentalPin: Flow<Boolean> = context.dataStore.data.map(::hasStoredParentalPin)

    suspend fun setLastActiveProviderId(id: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_ACTIVE_PROVIDER_ID] = id
        }
    }

    suspend fun setActiveLiveSource(source: ActiveLiveSource?) {
        context.dataStore.edit { preferences ->
            if (source == null) {
                preferences.remove(PreferencesKeys.ACTIVE_LIVE_SOURCE_TYPE)
                preferences.remove(PreferencesKeys.ACTIVE_LIVE_SOURCE_ID)
                return@edit
            }
            when (source) {
                is ActiveLiveSource.ProviderSource -> {
                    preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_TYPE] = "provider"
                    preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_ID] = source.providerId
                }
                is ActiveLiveSource.CombinedM3uSource -> {
                    preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_TYPE] = "combined_m3u"
                    preferences[PreferencesKeys.ACTIVE_LIVE_SOURCE_ID] = source.profileId
                }
            }
        }
    }

    suspend fun setDefaultViewMode(viewMode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_VIEW_MODE] = viewMode
        }
    }

    suspend fun setParentalControlLevel(level: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PARENTAL_CONTROL_LEVEL] = level
            preferences[PreferencesKeys.PARENTAL_V2_MIGRATED] = true
        }
    }

    suspend fun setIncognitoMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_INCOGNITO_MODE] = enabled
        }
    }

    suspend fun setUseXtreamTextClassification(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.XTREAM_TEXT_CLASSIFICATION] = enabled
        }
    }

    suspend fun setXtreamBase64TextCompatibility(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.XTREAM_BASE64_TEXT_COMPATIBILITY] = enabled
        }
    }

    suspend fun bumpXtreamTextImportGeneration(): Long {
        var nextGeneration = 0L
        context.dataStore.edit { preferences ->
            nextGeneration = (preferences[PreferencesKeys.XTREAM_TEXT_IMPORT_GENERATION] ?: 0L) + 1L
            preferences[PreferencesKeys.XTREAM_TEXT_IMPORT_GENERATION] = nextGeneration
        }
        return nextGeneration
    }

    suspend fun getXtreamTextImportGeneration(): Long {
        return xtreamTextImportGeneration.first()
    }

    suspend fun getXtreamTextImportAppliedGeneration(providerId: Long): Long {
        val key = longPreferencesKey(xtreamTextImportAppliedGenerationKey(providerId))
        return context.dataStore.data.first()[key] ?: 0L
    }

    suspend fun markXtreamTextImportApplied(providerId: Long, generation: Long) {
        val key = longPreferencesKey(xtreamTextImportAppliedGenerationKey(providerId))
        context.dataStore.edit { preferences ->
            if (generation <= 0L) {
                preferences.remove(key)
            } else {
                preferences[key] = generation
            }
        }
    }

    val preventStandbyDuringPlayback: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PREVENT_STANDBY_DURING_PLAYBACK] ?: true
    }

    val autoPlayNextEpisode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_PLAY_NEXT_EPISODE] ?: true
    }

    val autoCheckAppUpdates: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_CHECK_APP_UPDATES] ?: true
    }

    val autoDownloadAppUpdates: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_DOWNLOAD_APP_UPDATES] ?: false
    }

    val lastAppUpdateCheckTimestamp: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_APP_UPDATE_CHECK_TIMESTAMP]?.takeIf { it > 0L }
    }

    val lastAppUpdateAttemptTimestamp: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_APP_UPDATE_ATTEMPT_TIMESTAMP]?.takeIf { it > 0L }
    }

    val lastAppUpdateFailureTimestamp: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_APP_UPDATE_FAILURE_TIMESTAMP]?.takeIf { it > 0L }
    }

    val lastAppUpdateOutcome: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LAST_APP_UPDATE_OUTCOME]?.takeIf { it.isNotBlank() }
    }

    val appUpdateDownloadId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_ID]?.takeIf { it > 0L }
    }

    val appUpdateDownloadVersionName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_VERSION_NAME]?.takeIf { it.isNotBlank() }
    }

    val downloadedAppUpdateVersionName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_DOWNLOADED_VERSION_NAME]?.takeIf { it.isNotBlank() }
    }

    val cachedAppUpdateVersionName: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_LATEST_VERSION_NAME]?.takeIf { it.isNotBlank() }
    }

    val cachedAppUpdateVersionCode: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_LATEST_VERSION_CODE]
    }

    val cachedAppUpdateReleaseUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_RELEASE_URL]?.takeIf { it.isNotBlank() }
    }

    val cachedAppUpdateDownloadUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_URL]?.takeIf { it.isNotBlank() }
    }

    val cachedAppUpdateDownloadSha256: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_SHA256]?.takeIf { it.isNotBlank() }
    }

    val cachedAppUpdateReleaseNotes: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_RELEASE_NOTES].orEmpty()
    }

    val cachedAppUpdatePublishedAt: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_UPDATE_PUBLISHED_AT]?.takeIf { it.isNotBlank() }
    }

    val lastMaintenanceSnapshot: Flow<DatabaseMaintenanceSnapshot?> = context.dataStore.data.map { preferences ->
        val ranAt = preferences[PreferencesKeys.LAST_MAINTENANCE_AT] ?: return@map null
        DatabaseMaintenanceSnapshot(
            ranAt = ranAt,
            deletedPrograms = preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_PROGRAMS] ?: 0,
            deletedExternalProgrammes = preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_EXTERNAL_PROGRAMMES] ?: 0,
            deletedOrphanEpisodes = preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_ORPHAN_EPISODES] ?: 0,
            deletedStaleFavorites = preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_STALE_FAVORITES] ?: 0,
            vacuumRan = preferences[PreferencesKeys.LAST_MAINTENANCE_VACUUM_RAN] ?: false,
            mainDbBytes = preferences[PreferencesKeys.LAST_MAINTENANCE_MAIN_DB_BYTES] ?: 0L,
            walBytes = preferences[PreferencesKeys.LAST_MAINTENANCE_WAL_BYTES] ?: 0L,
            reclaimableBytes = preferences[PreferencesKeys.LAST_MAINTENANCE_RECLAIMABLE_BYTES] ?: 0L,
            channelRows = preferences[PreferencesKeys.LAST_MAINTENANCE_CHANNEL_ROWS] ?: 0L,
            movieRows = preferences[PreferencesKeys.LAST_MAINTENANCE_MOVIE_ROWS] ?: 0L,
            seriesRows = preferences[PreferencesKeys.LAST_MAINTENANCE_SERIES_ROWS] ?: 0L,
            episodeRows = preferences[PreferencesKeys.LAST_MAINTENANCE_EPISODE_ROWS] ?: 0L,
            programRows = preferences[PreferencesKeys.LAST_MAINTENANCE_PROGRAM_ROWS] ?: 0L,
            epgProgrammeRows = preferences[PreferencesKeys.LAST_MAINTENANCE_EPG_PROGRAMME_ROWS] ?: 0L,
            playbackHistoryRows = preferences[PreferencesKeys.LAST_MAINTENANCE_PLAYBACK_HISTORY_ROWS] ?: 0L,
            favoriteRows = preferences[PreferencesKeys.LAST_MAINTENANCE_FAVORITE_ROWS] ?: 0L
        )
    }

    val zapAutoRevert: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.ZAP_AUTO_REVERT] ?: true
    }

    val recordingWifiOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.RECORDING_WIFI_ONLY] ?: false
    }

    val recordingPaddingBeforeMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.RECORDING_PADDING_BEFORE_MINUTES] ?: 0).coerceIn(0, 30)
    }

    val recordingPaddingAfterMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.RECORDING_PADDING_AFTER_MINUTES] ?: 0).coerceIn(0, 30)
    }

    val downloadTreeUri: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DOWNLOAD_TREE_URI]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    val maxConcurrentStreams: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.MAX_CONCURRENT_STREAMS] ?: 2).coerceIn(1, 4)
    }

    suspend fun setZapAutoRevert(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ZAP_AUTO_REVERT] = enabled
        }
    }

    suspend fun setRecordingWifiOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECORDING_WIFI_ONLY] = enabled
        }
    }

    suspend fun setRecordingPaddingBeforeMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECORDING_PADDING_BEFORE_MINUTES] = minutes.coerceIn(0, 30)
        }
    }

    suspend fun setRecordingPaddingAfterMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.RECORDING_PADDING_AFTER_MINUTES] = minutes.coerceIn(0, 30)
        }
    }

    suspend fun setDownloadTreeUri(uri: String?) {
        context.dataStore.edit { preferences ->
            val normalized = uri?.trim()?.takeIf { it.isNotBlank() }
            if (normalized == null) {
                preferences.remove(PreferencesKeys.DOWNLOAD_TREE_URI)
            } else {
                preferences[PreferencesKeys.DOWNLOAD_TREE_URI] = normalized
            }
        }
    }

    suspend fun setMaxConcurrentStreams(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_CONCURRENT_STREAMS] = count.coerceIn(1, 4)
        }
    }

    suspend fun setPreventStandbyDuringPlayback(prevent: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREVENT_STANDBY_DURING_PLAYBACK] = prevent
        }
    }

    suspend fun setAutoPlayNextEpisode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_PLAY_NEXT_EPISODE] = enabled
        }
    }

    suspend fun setAutoCheckAppUpdates(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_CHECK_APP_UPDATES] = enabled
        }
    }

    suspend fun setAutoDownloadAppUpdates(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_DOWNLOAD_APP_UPDATES] = enabled
        }
    }

    suspend fun setLastAppUpdateCheckTimestamp(timestampMs: Long?) {
        context.dataStore.edit { preferences ->
            if (timestampMs == null || timestampMs <= 0L) {
                preferences.remove(PreferencesKeys.LAST_APP_UPDATE_CHECK_TIMESTAMP)
            } else {
                preferences[PreferencesKeys.LAST_APP_UPDATE_CHECK_TIMESTAMP] = timestampMs
            }
        }
    }

    suspend fun setLastAppUpdateFailureTimestamp(timestampMs: Long?) {
        context.dataStore.edit { preferences ->
            if (timestampMs == null || timestampMs <= 0L) {
                preferences.remove(PreferencesKeys.LAST_APP_UPDATE_FAILURE_TIMESTAMP)
            } else {
                preferences[PreferencesKeys.LAST_APP_UPDATE_FAILURE_TIMESTAMP] = timestampMs
            }
        }
    }

    suspend fun setLastAppUpdateAttemptTimestamp(timestampMs: Long?) {
        context.dataStore.edit { preferences ->
            if (timestampMs == null || timestampMs <= 0L) {
                preferences.remove(PreferencesKeys.LAST_APP_UPDATE_ATTEMPT_TIMESTAMP)
            } else {
                preferences[PreferencesKeys.LAST_APP_UPDATE_ATTEMPT_TIMESTAMP] = timestampMs
            }
        }
    }

    suspend fun setLastAppUpdateOutcome(outcome: String?) {
        context.dataStore.edit { preferences ->
            if (outcome.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.LAST_APP_UPDATE_OUTCOME)
            } else {
                preferences[PreferencesKeys.LAST_APP_UPDATE_OUTCOME] = outcome.take(256)
            }
        }
    }

    suspend fun setAppUpdateDownloadId(downloadId: Long?) {
        context.dataStore.edit { preferences ->
            if (downloadId == null || downloadId <= 0L) {
                preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_ID)
            } else {
                preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_ID] = downloadId
            }
        }
    }

    suspend fun setAppUpdateDownloadVersionName(versionName: String?) {
        context.dataStore.edit { preferences ->
            if (versionName.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_VERSION_NAME)
            } else {
                preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_VERSION_NAME] = versionName
            }
        }
    }

    suspend fun setDownloadedAppUpdateVersionName(versionName: String?) {
        context.dataStore.edit { preferences ->
            if (versionName.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOADED_VERSION_NAME)
            } else {
                preferences[PreferencesKeys.APP_UPDATE_DOWNLOADED_VERSION_NAME] = versionName
            }
        }
    }

    suspend fun setCachedAppUpdateRelease(
        versionName: String?,
        versionCode: Int?,
        releaseUrl: String?,
        downloadUrl: String?,
        downloadSha256: String?,
        releaseNotes: String?,
        publishedAt: String?
    ) {
        context.dataStore.edit { preferences ->
            if (versionName.isNullOrBlank() || releaseUrl.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.APP_UPDATE_LATEST_VERSION_NAME)
                preferences.remove(PreferencesKeys.APP_UPDATE_LATEST_VERSION_CODE)
                preferences.remove(PreferencesKeys.APP_UPDATE_RELEASE_URL)
                preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_URL)
                preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_SHA256)
                preferences.remove(PreferencesKeys.APP_UPDATE_RELEASE_NOTES)
                preferences.remove(PreferencesKeys.APP_UPDATE_PUBLISHED_AT)
            } else {
                preferences[PreferencesKeys.APP_UPDATE_LATEST_VERSION_NAME] = versionName
                if (versionCode == null) {
                    preferences.remove(PreferencesKeys.APP_UPDATE_LATEST_VERSION_CODE)
                } else {
                    preferences[PreferencesKeys.APP_UPDATE_LATEST_VERSION_CODE] = versionCode
                }
                preferences[PreferencesKeys.APP_UPDATE_RELEASE_URL] = releaseUrl
                if (downloadUrl.isNullOrBlank()) {
                    preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_URL)
                } else {
                    preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_URL] = downloadUrl
                }
                if (downloadSha256.isNullOrBlank()) {
                    preferences.remove(PreferencesKeys.APP_UPDATE_DOWNLOAD_SHA256)
                } else {
                    preferences[PreferencesKeys.APP_UPDATE_DOWNLOAD_SHA256] = downloadSha256
                }
                if (releaseNotes.isNullOrBlank()) {
                    preferences.remove(PreferencesKeys.APP_UPDATE_RELEASE_NOTES)
                } else {
                    preferences[PreferencesKeys.APP_UPDATE_RELEASE_NOTES] = releaseNotes
                }
                if (publishedAt.isNullOrBlank()) {
                    preferences.remove(PreferencesKeys.APP_UPDATE_PUBLISHED_AT)
                } else {
                    preferences[PreferencesKeys.APP_UPDATE_PUBLISHED_AT] = publishedAt
                }
            }
        }
    }

    suspend fun setLastMaintenanceSnapshot(snapshot: DatabaseMaintenanceSnapshot?) {
        context.dataStore.edit { preferences ->
            if (snapshot == null) {
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_AT)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_DELETED_PROGRAMS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_DELETED_EXTERNAL_PROGRAMMES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_DELETED_ORPHAN_EPISODES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_DELETED_STALE_FAVORITES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_VACUUM_RAN)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_MAIN_DB_BYTES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_WAL_BYTES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_RECLAIMABLE_BYTES)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_CHANNEL_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_MOVIE_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_SERIES_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_EPISODE_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_PROGRAM_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_EPG_PROGRAMME_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_PLAYBACK_HISTORY_ROWS)
                preferences.remove(PreferencesKeys.LAST_MAINTENANCE_FAVORITE_ROWS)
            } else {
                preferences[PreferencesKeys.LAST_MAINTENANCE_AT] = snapshot.ranAt
                preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_PROGRAMS] = snapshot.deletedPrograms
                preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_EXTERNAL_PROGRAMMES] = snapshot.deletedExternalProgrammes
                preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_ORPHAN_EPISODES] = snapshot.deletedOrphanEpisodes
                preferences[PreferencesKeys.LAST_MAINTENANCE_DELETED_STALE_FAVORITES] = snapshot.deletedStaleFavorites
                preferences[PreferencesKeys.LAST_MAINTENANCE_VACUUM_RAN] = snapshot.vacuumRan
                preferences[PreferencesKeys.LAST_MAINTENANCE_MAIN_DB_BYTES] = snapshot.mainDbBytes
                preferences[PreferencesKeys.LAST_MAINTENANCE_WAL_BYTES] = snapshot.walBytes
                preferences[PreferencesKeys.LAST_MAINTENANCE_RECLAIMABLE_BYTES] = snapshot.reclaimableBytes
                preferences[PreferencesKeys.LAST_MAINTENANCE_CHANNEL_ROWS] = snapshot.channelRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_MOVIE_ROWS] = snapshot.movieRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_SERIES_ROWS] = snapshot.seriesRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_EPISODE_ROWS] = snapshot.episodeRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_PROGRAM_ROWS] = snapshot.programRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_EPG_PROGRAMME_ROWS] = snapshot.epgProgrammeRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_PLAYBACK_HISTORY_ROWS] = snapshot.playbackHistoryRows
                preferences[PreferencesKeys.LAST_MAINTENANCE_FAVORITE_ROWS] = snapshot.favoriteRows
            }
        }
    }

    suspend fun setPlayerMuted(muted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_MUTED] = muted
        }
    }

    suspend fun setPlayerMediaSessionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_MEDIA_SESSION_ENABLED] = enabled
        }
    }

    suspend fun setPlayerFastRetryOnTransientFailures(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_FAST_RETRY_ON_TRANSIENT_FAILURES] = enabled
        }
    }

    suspend fun setPlayerAudioDecoderMode(mode: DecoderMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_AUDIO_DECODER_MODE] = mode.name
        }
    }

    suspend fun setPlayerVideoDecoderMode(mode: DecoderMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_VIDEO_DECODER_MODE] = mode.name
        }
    }

    suspend fun setPlayerPlaybackBufferMode(mode: PlaybackBufferMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_PLAYBACK_BUFFER_MODE] = mode.name
        }
    }

    suspend fun setPlayerAudioOutputPreference(preference: AudioOutputPreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_AUDIO_OUTPUT_PREFERENCE] = preference.name
        }
    }

    suspend fun setPlayerCompatibilityMemoryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_COMPATIBILITY_MEMORY_ENABLED] = enabled
        }
    }

    suspend fun setPlayerSurfaceMode(mode: PlayerSurfaceMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_SURFACE_MODE] = mode.name
        }
    }

    suspend fun setPlayerLiveStreamFormatMode(mode: LiveStreamFormatMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_LIVE_STREAM_FORMAT_MODE] = mode.name
        }
    }

    suspend fun setPlayerVodHttpProtocolMode(mode: VodHttpProtocolMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_VOD_HTTP_PROTOCOL_MODE] = mode.name
            preferences.remove(PreferencesKeys.LEGACY_PLAYER_MOVIE_HTTP_PROTOCOL_MODE)
        }
    }

    suspend fun setPlayerPlaybackSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2f).toString()
        }
    }

    suspend fun setPlayerExternalPlaybackMode(mode: com.streamvault.domain.model.ExternalPlaybackMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_EXTERNAL_PLAYBACK_MODE] = mode.storageValue
        }
    }

    suspend fun setPlayerAudioVideoOffsetMs(offsetMs: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_AUDIO_VIDEO_OFFSET_MS] =
                offsetMs.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
        }
    }

    suspend fun setPlayerAudioVideoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_AUDIO_VIDEO_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setPreferredAudioLanguage(languageTag: String?) {
        context.dataStore.edit { preferences ->
            val normalized = languageTag
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            if (normalized == null) {
                preferences[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE] = "auto"
            } else {
                preferences[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE] = normalized
            }
        }
    }

    suspend fun setPlayerSubtitleTextScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_SUBTITLE_TEXT_SCALE] = scale.coerceIn(0.75f, 1.75f).toString()
        }
    }

    suspend fun setPlayerSubtitleTextColor(colorArgb: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_SUBTITLE_TEXT_COLOR] = colorArgb
        }
    }

    suspend fun setPlayerSubtitleBackgroundColor(colorArgb: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_SUBTITLE_BACKGROUND_COLOR] = colorArgb
        }
    }

    suspend fun setPlayerLiveTranslationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_LIVE_TRANSLATION_ENABLED] = enabled
        }
    }

    suspend fun setPlayerLiveTranslationEndpoint(endpoint: String) {
        context.dataStore.edit { preferences ->
            val normalized = endpoint.trim()
            if (normalized.isBlank()) {
                preferences.remove(PreferencesKeys.PLAYER_LIVE_TRANSLATION_ENDPOINT)
            } else {
                preferences[PreferencesKeys.PLAYER_LIVE_TRANSLATION_ENDPOINT] = normalized
            }
        }
    }

    suspend fun setPlayerControlsTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_CONTROLS_TIMEOUT_SECONDS] = seconds.coerceIn(2, 60)
        }
    }

    suspend fun setPlayerLiveOverlayTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_LIVE_OVERLAY_TIMEOUT_SECONDS] = seconds.coerceIn(2, 60)
        }
    }

    suspend fun setPlayerNoticeTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_NOTICE_TIMEOUT_SECONDS] = seconds.coerceIn(2, 60)
        }
    }

    suspend fun setPlayerDiagnosticsTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_DIAGNOSTICS_TIMEOUT_SECONDS] = seconds.coerceIn(2, 60)
        }
    }

    suspend fun setPlayerWifiMaxVideoHeight(maxHeight: Int?) {
        context.dataStore.edit { preferences ->
            val normalized = maxHeight?.takeIf { it > 0 }
            if (normalized == null) {
                preferences.remove(PreferencesKeys.PLAYER_WIFI_MAX_VIDEO_HEIGHT)
            } else {
                preferences[PreferencesKeys.PLAYER_WIFI_MAX_VIDEO_HEIGHT] = normalized
            }
        }
    }

    suspend fun setPlayerEthernetMaxVideoHeight(maxHeight: Int?) {
        context.dataStore.edit { preferences ->
            val normalized = maxHeight?.takeIf { it > 0 }
            if (normalized == null) {
                preferences.remove(PreferencesKeys.PLAYER_ETHERNET_MAX_VIDEO_HEIGHT)
            } else {
                preferences[PreferencesKeys.PLAYER_ETHERNET_MAX_VIDEO_HEIGHT] = normalized
            }
        }
    }

    suspend fun setPlayerTimeshiftEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_TIMESHIFT_ENABLED] = enabled
        }
    }

    suspend fun setPlayerTimeshiftDepthMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_TIMESHIFT_DEPTH_MINUTES] = when (minutes) {
                15, 30, 60 -> minutes
                in Int.MIN_VALUE..22 -> 15
                in 23..45 -> 30
                else -> 60
            }
        }
    }

    suspend fun setPlayerTimeshiftBackend(preference: TimeshiftBackendPreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYER_TIMESHIFT_BACKEND] = preference.name
        }
    }

    suspend fun setDefaultStopPlaybackTimerMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_STOP_PLAYBACK_TIMER_MINUTES] = sanitizePlaybackTimerMinutes(minutes)
        }
    }

    suspend fun setDefaultIdleStandbyTimerMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_IDLE_STANDBY_TIMER_MINUTES] = sanitizePlaybackTimerMinutes(minutes)
        }
    }

    suspend fun setLastSpeedTestResult(
        megabitsPerSecond: Double?,
        measuredAtMs: Long?,
        transport: String?,
        recommendedMaxHeight: Int?,
        estimated: Boolean
    ) {
        context.dataStore.edit { preferences ->
            val normalizedMbps = megabitsPerSecond?.takeIf { it > 0.0 }
            val normalizedTimestamp = measuredAtMs?.takeIf { it > 0L }
            val normalizedTransport = transport?.trim()?.takeIf { it.isNotBlank() }
            val normalizedHeight = recommendedMaxHeight?.takeIf { it > 0 }

            if (normalizedMbps == null || normalizedTimestamp == null || normalizedTransport == null) {
                preferences.remove(PreferencesKeys.LAST_SPEED_TEST_MEGABITS)
                preferences.remove(PreferencesKeys.LAST_SPEED_TEST_TIMESTAMP)
                preferences.remove(PreferencesKeys.LAST_SPEED_TEST_TRANSPORT)
                preferences.remove(PreferencesKeys.LAST_SPEED_TEST_RECOMMENDED_HEIGHT)
                preferences.remove(PreferencesKeys.LAST_SPEED_TEST_ESTIMATED)
            } else {
                preferences[PreferencesKeys.LAST_SPEED_TEST_MEGABITS] = normalizedMbps.toString()
                preferences[PreferencesKeys.LAST_SPEED_TEST_TIMESTAMP] = normalizedTimestamp
                preferences[PreferencesKeys.LAST_SPEED_TEST_TRANSPORT] = normalizedTransport
                if (normalizedHeight == null) {
                    preferences.remove(PreferencesKeys.LAST_SPEED_TEST_RECOMMENDED_HEIGHT)
                } else {
                    preferences[PreferencesKeys.LAST_SPEED_TEST_RECOMMENDED_HEIGHT] = normalizedHeight
                }
                preferences[PreferencesKeys.LAST_SPEED_TEST_ESTIMATED] = estimated
            }
        }
    }

    fun getRecentSearchQueries(
        scope: SearchHistoryScope,
        providerId: Long?,
        limit: Int = 6
    ): Flow<List<String>> {
        return searchHistoryDao.observeRecent(scope.name, searchHistoryProviderKey(providerId), limit)
            .map { rows -> rows.map { it.query } }
    }

    suspend fun recordRecentSearchQuery(
        query: String,
        scope: SearchHistoryScope,
        providerId: Long?,
        usedAt: Long = System.currentTimeMillis()
    ) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistoryDao.record(
            query = normalized,
            contentScope = scope.name,
            providerId = searchHistoryProviderKey(providerId),
            usedAt = usedAt
        )
        clearLegacyRecentSearchQueries()
    }

    suspend fun clearRecentSearchQueries(
        scope: SearchHistoryScope,
        providerId: Long?
    ) {
        searchHistoryDao.deleteByScope(scope.name, searchHistoryProviderKey(providerId))
        clearLegacyRecentSearchQueries()
    }

    suspend fun setRecentSearchQueries(queries: List<String>) {
        searchHistoryDao.deleteByScope(SearchHistoryScope.ALL.name, searchHistoryProviderKey(null))
        var usedAt = System.currentTimeMillis()
        queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .forEach { query ->
                searchHistoryDao.record(
                    query = query,
                    contentScope = SearchHistoryScope.ALL.name,
                    providerId = searchHistoryProviderKey(null),
                    usedAt = usedAt--
                )
            }
        clearLegacyRecentSearchQueries()
    }

    suspend fun setParentalPin(pin: String) {
        val salt = ByteArray(PIN_SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashPin(pin, salt)
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PARENTAL_PIN_HASH] = hash
            preferences[PreferencesKeys.PARENTAL_PIN_SALT] = saltBase64
            preferences.remove(PreferencesKeys.LEGACY_PARENTAL_PIN)
        }
    }

    override suspend fun verifyParentalPin(pin: String): Boolean {
        if (isPinLockedOut()) {
            return false
        }

        val preferences = context.dataStore.data.first()

        val storedHash = preferences[PreferencesKeys.PARENTAL_PIN_HASH]
        val storedSaltBase64 = preferences[PreferencesKeys.PARENTAL_PIN_SALT]

        if (!storedHash.isNullOrBlank() && !storedSaltBase64.isNullOrBlank()) {
            val salt = runCatching { java.util.Base64.getDecoder().decode(storedSaltBase64) }.getOrNull()
                ?: return false
            val valid = hashPin(pin, salt) == storedHash
            updatePinAttemptState(valid)
            return valid
        }

        val legacyPin = preferences[PreferencesKeys.LEGACY_PARENTAL_PIN]
        val valid = !legacyPin.isNullOrBlank() && pin == legacyPin

        if (valid) {
            // One-way migrate legacy PIN storage to hashed PIN storage.
            setParentalPin(pin)
        }

        updatePinAttemptState(valid)

        return valid
    }

    suspend fun exportParentalPinBackup(): ParentalPinBackupData? {
        val preferences = context.dataStore.data.first()
        val hash = preferences[PreferencesKeys.PARENTAL_PIN_HASH]
        val saltBase64 = preferences[PreferencesKeys.PARENTAL_PIN_SALT]
        if (hash.isNullOrBlank() || saltBase64.isNullOrBlank()) {
            return null
        }
        return ParentalPinBackupData(hash = hash, saltBase64 = saltBase64)
    }

    suspend fun restoreParentalPinBackup(backup: ParentalPinBackupData?) {
        context.dataStore.edit { preferences ->
            if (backup == null || backup.hash.isBlank() || backup.saltBase64.isBlank()) {
                preferences.remove(PreferencesKeys.PARENTAL_PIN_HASH)
                preferences.remove(PreferencesKeys.PARENTAL_PIN_SALT)
                preferences.remove(PreferencesKeys.LEGACY_PARENTAL_PIN)
            } else {
                preferences[PreferencesKeys.PARENTAL_PIN_HASH] = backup.hash
                preferences[PreferencesKeys.PARENTAL_PIN_SALT] = backup.saltBase64
                preferences.remove(PreferencesKeys.LEGACY_PARENTAL_PIN)
            }
        }
    }

    override fun readSessionState(): ParentalControlSessionState {
        return ParentalControlSessionState(
            unlockedCategoryIdsByProvider = decodeUnlockEntries(
                parentalSessionPreferences.getString(ParentalSessionKeys.UNLOCK_ENTRIES, null)
            )
        )
    }

    override fun writeSessionState(state: ParentalControlSessionState) {
        parentalSessionPreferences.edit().apply {
            remove(ParentalSessionKeys.UNLOCK_TIMEOUT_MS)
            remove(ParentalSessionKeys.UNLOCK_ENTRIES)
        }.apply()
    }

    suspend fun clearDefaultViewMode() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.DEFAULT_VIEW_MODE)
        }
    }

    val defaultCategoryId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEFAULT_CATEGORY_ID]
    }

    suspend fun setDefaultCategory(categoryId: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_CATEGORY_ID] = categoryId
        }
    }

    fun getLastLiveCategoryId(providerId: Long): Flow<Long?> {
        val key = longPreferencesKey("last_live_category_id_$providerId")
        return context.dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    suspend fun setLastLiveCategoryId(providerId: Long, categoryId: Long) {
        val key = longPreferencesKey("last_live_category_id_$providerId")
        context.dataStore.edit { preferences ->
            preferences[key] = categoryId
        }
    }

    fun getLastSplitCatalogType(providerId: Long): Flow<ContentType> {
        val key = stringPreferencesKey("last_split_catalog_type_$providerId")
        return context.dataStore.data.map { preferences ->
            when (preferences[key]) {
                ContentType.SERIES.name -> ContentType.SERIES
                else -> ContentType.MOVIE
            }
        }
    }

    suspend fun setLastSplitCatalogType(providerId: Long, type: ContentType) {
        require(type == ContentType.MOVIE || type == ContentType.SERIES)
        val key = stringPreferencesKey("last_split_catalog_type_$providerId")
        context.dataStore.edit { preferences ->
            preferences[key] = type.name
        }
    }

    val appLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_LANGUAGE] ?: "system"
    }

    val remoteShortcutPreferences: Flow<RemoteShortcutPreferences> = context.dataStore.data.map { preferences ->
        decodeRemoteShortcutPreferences { profile, button ->
            preferences[stringPreferencesKey(remoteShortcutKey(profile, button))]
        }
    }

    suspend fun setAppLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language
        }
    }

    suspend fun setRemoteShortcutSelection(
        profile: RemoteShortcutProfile,
        button: RemoteColorButton,
        selection: RemoteShortcutSelection
    ) {
        val key = stringPreferencesKey(remoteShortcutKey(profile, button))
        context.dataStore.edit { preferences ->
            val encoded = encodeRemoteShortcutSelection(profile, selection)
            if (encoded.isNullOrBlank()) {
                preferences.remove(key)
            } else {
                preferences[key] = encoded
            }
        }
    }

    val appLandingDestination: Flow<AppLandingDestination> = context.dataStore.data.map { preferences ->
        AppLandingDestination.fromStorage(preferences[PreferencesKeys.APP_LANDING_DESTINATION])
    }

    val appTopLevelDestinations: Flow<List<AppTopLevelDestination>> = context.dataStore.data.map { preferences ->
        decodeAppTopLevelDestinations(preferences[PreferencesKeys.APP_TOP_LEVEL_DESTINATIONS])
    }

    val appHomeDashboardShelves: Flow<List<AppHomeDashboardShelf>> = context.dataStore.data.map { preferences ->
        decodeAppHomeDashboardShelves(preferences[PreferencesKeys.APP_HOME_DASHBOARD_SHELVES])
    }

    suspend fun setAppLandingDestination(destination: AppLandingDestination) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANDING_DESTINATION] = destination.storageValue
        }
    }

    suspend fun setAppTopLevelDestinations(destinations: List<AppTopLevelDestination>) {
        val normalized = AppTopLevelDestination.normalizeForStorage(destinations)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_TOP_LEVEL_DESTINATIONS] = encodeAppTopLevelDestinations(normalized)
        }
    }

    suspend fun setAppHomeDashboardShelves(shelves: List<AppHomeDashboardShelf>) {
        val normalized = AppHomeDashboardShelf.normalizeForStorage(shelves)
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_HOME_DASHBOARD_SHELVES] = encodeAppHomeDashboardShelves(normalized)
        }
    }

    val appTimeFormat: Flow<AppTimeFormat> = context.dataStore.data.map { preferences ->
        AppTimeFormat.fromStorage(preferences[PreferencesKeys.APP_TIME_FORMAT])
    }

    suspend fun setAppTimeFormat(format: AppTimeFormat) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_TIME_FORMAT] = format.storageValue
        }
    }

    val liveTvChannelMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LIVE_TV_CHANNEL_MODE]
    }

    suspend fun setLiveTvChannelMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_TV_CHANNEL_MODE] = mode
        }
    }

    val showLiveSourceSwitcher: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SHOW_LIVE_SOURCE_SWITCHER] ?: false
    }

    suspend fun setShowLiveSourceSwitcher(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_LIVE_SOURCE_SWITCHER] = enabled
        }
    }

    val showFavoritesCategory: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SHOW_FAVORITES_CATEGORY] ?: true
    }

    suspend fun setShowFavoritesCategory(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_FAVORITES_CATEGORY] = enabled
        }
    }

    val showAllChannelsCategory: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SHOW_ALL_CHANNELS_CATEGORY] ?: true
    }

    suspend fun setShowAllChannelsCategory(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_ALL_CHANNELS_CATEGORY] = enabled
        }
    }

    val showRecentChannelsCategory: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SHOW_RECENT_CHANNELS_CATEGORY] ?: true
    }

    suspend fun setShowRecentChannelsCategory(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_RECENT_CHANNELS_CATEGORY] = enabled
        }
    }

    val liveTvQuickFilterVisibility: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LIVE_TV_QUICK_FILTER_VISIBILITY]
    }

    suspend fun setLiveTvQuickFilterVisibility(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_TV_QUICK_FILTER_VISIBILITY] = mode
        }
    }

    val liveTvCategoryFilters: Flow<List<String>> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.LIVE_TV_CATEGORY_FILTERS]
            ?.split('\n')
            .orEmpty()
            .normalizeLiveTvCategoryFilters()
    }

    suspend fun setLiveTvCategoryFilters(filters: List<String>) {
        context.dataStore.edit { preferences ->
            val normalized = filters.normalizeLiveTvCategoryFilters()
            if (normalized.isEmpty()) {
                preferences.remove(PreferencesKeys.LIVE_TV_CATEGORY_FILTERS)
            } else {
                preferences[PreferencesKeys.LIVE_TV_CATEGORY_FILTERS] = normalized.joinToString("\n")
            }
        }
    }

    suspend fun addLiveTvCategoryFilter(filter: String): Boolean {
        val normalized = filter.trim()
        if (normalized.isBlank()) return false
        val current = liveTvCategoryFilters.first()
        if (current.any { it.equals(normalized, ignoreCase = true) }) {
            return false
        }
        setLiveTvCategoryFilters(current + normalized)
        return true
    }

    suspend fun removeLiveTvCategoryFilter(filter: String): Boolean {
        val current = liveTvCategoryFilters.first()
        val updated = current.filterNot { it.equals(filter, ignoreCase = true) }
        if (updated.size == current.size) {
            return false
        }
        setLiveTvCategoryFilters(updated)
        return true
    }

    val hideDecorativeLiveRows: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.HIDE_DECORATIVE_LIVE_ROWS] ?: true
    }

    suspend fun setHideDecorativeLiveRows(hide: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIDE_DECORATIVE_LIVE_ROWS] = hide
        }
    }

    val liveChannelNumberingMode: Flow<ChannelNumberingMode> = context.dataStore.data.map { preferences ->
        ChannelNumberingMode.fromStorage(preferences[PreferencesKeys.LIVE_CHANNEL_NUMBERING_MODE])
    }

    suspend fun setLiveChannelNumberingMode(mode: ChannelNumberingMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_CHANNEL_NUMBERING_MODE] = mode.storageValue
        }
    }

    val liveChannelGroupingMode: Flow<LiveChannelGroupingMode> = context.dataStore.data.map { preferences ->
        LiveChannelGroupingMode.fromStorage(preferences[PreferencesKeys.LIVE_CHANNEL_GROUPING_MODE])
    }

    suspend fun setLiveChannelGroupingMode(mode: LiveChannelGroupingMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_CHANNEL_GROUPING_MODE] = mode.storageValue
        }
    }

    val groupedChannelLabelMode: Flow<GroupedChannelLabelMode> = context.dataStore.data.map { preferences ->
        GroupedChannelLabelMode.fromStorage(preferences[PreferencesKeys.GROUPED_CHANNEL_LABEL_MODE])
    }

    suspend fun setGroupedChannelLabelMode(mode: GroupedChannelLabelMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GROUPED_CHANNEL_LABEL_MODE] = mode.storageValue
        }
    }

    val liveVariantPreferenceMode: Flow<LiveVariantPreferenceMode> = context.dataStore.data.map { preferences ->
        LiveVariantPreferenceMode.fromStorage(preferences[PreferencesKeys.LIVE_VARIANT_PREFERENCE_MODE])
    }

    suspend fun setLiveVariantPreferenceMode(mode: LiveVariantPreferenceMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_VARIANT_PREFERENCE_MODE] = mode.storageValue
        }
    }

    val liveVariantSelections: Flow<Map<String, Long>> = context.dataStore.data.map { preferences ->
        decodeLiveVariantSelections(preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS])
    }

    suspend fun setPreferredLiveVariant(providerId: Long, logicalGroupId: String, rawChannelId: Long) {
        if (providerId <= 0L || logicalGroupId.isBlank() || rawChannelId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeLiveVariantSelections(preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS]).toMutableMap()
            updated[liveVariantSelectionKey(providerId, logicalGroupId)] = rawChannelId
            preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS] = encodeLiveVariantSelections(updated)
        }
    }

    suspend fun clearPreferredLiveVariant(providerId: Long, logicalGroupId: String) {
        if (providerId <= 0L || logicalGroupId.isBlank()) return
        context.dataStore.edit { preferences ->
            val updated = decodeLiveVariantSelections(preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS]).toMutableMap()
            updated.remove(liveVariantSelectionKey(providerId, logicalGroupId))
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.LIVE_VARIANT_SELECTIONS)
            } else {
                preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS] = encodeLiveVariantSelections(updated)
            }
        }
    }

    suspend fun clearPreferredLiveVariants(providerId: Long) {
        if (providerId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeLiveVariantSelections(preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS])
                .filterKeys { !it.startsWith("$providerId|") }
            if (updated.isEmpty()) preferences.remove(PreferencesKeys.LIVE_VARIANT_SELECTIONS)
            else preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS] = encodeLiveVariantSelections(updated)
        }
    }

    val liveVariantObservations: Flow<Map<Long, LiveChannelObservedQuality>> = context.dataStore.data.map { preferences ->
        decodeLiveVariantObservations(preferences[PreferencesKeys.LIVE_VARIANT_OBSERVATIONS])
    }

    suspend fun recordLiveVariantObservation(rawChannelId: Long, observedQuality: LiveChannelObservedQuality) {
        if (rawChannelId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeLiveVariantObservations(preferences[PreferencesKeys.LIVE_VARIANT_OBSERVATIONS]).toMutableMap()
            updated[rawChannelId] = observedQuality
            preferences[PreferencesKeys.LIVE_VARIANT_OBSERVATIONS] = encodeLiveVariantObservations(updated)
        }
    }

    val vodDuplicateHandlingMode: Flow<VodDuplicateHandlingMode> = context.dataStore.data.map { preferences ->
        VodDuplicateHandlingMode.fromStorage(preferences[PreferencesKeys.VOD_DUPLICATE_HANDLING_MODE])
    }

    suspend fun setVodDuplicateHandlingMode(mode: VodDuplicateHandlingMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOD_DUPLICATE_HANDLING_MODE] = mode.storageValue
        }
    }

    val vodVariantPreferenceMode: Flow<VodVariantPreferenceMode> = context.dataStore.data.map { preferences ->
        VodVariantPreferenceMode.fromStorage(preferences[PreferencesKeys.VOD_VARIANT_PREFERENCE_MODE])
    }

    suspend fun setVodVariantPreferenceMode(mode: VodVariantPreferenceMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOD_VARIANT_PREFERENCE_MODE] = mode.storageValue
        }
    }

    val vodVariantSelections: Flow<Map<String, Long>> = context.dataStore.data.map { preferences ->
        decodeVodVariantSelections(preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS])
    }

    suspend fun setPreferredVodVariant(providerId: Long, logicalGroupId: String, rawItemId: Long) {
        if (providerId <= 0L || logicalGroupId.isBlank() || rawItemId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeVodVariantSelections(preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS]).toMutableMap()
            updated[vodVariantSelectionKey(providerId, logicalGroupId)] = rawItemId
            preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS] = encodeVodVariantSelections(updated)
        }
    }

    suspend fun clearPreferredVodVariant(providerId: Long, logicalGroupId: String) {
        if (providerId <= 0L || logicalGroupId.isBlank()) return
        context.dataStore.edit { preferences ->
            val updated = decodeVodVariantSelections(preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS]).toMutableMap()
            updated.remove(vodVariantSelectionKey(providerId, logicalGroupId))
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.VOD_VARIANT_SELECTIONS)
            } else {
                preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS] = encodeVodVariantSelections(updated)
            }
        }
    }

    suspend fun clearPreferredVodVariants(providerId: Long) {
        if (providerId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeVodVariantSelections(preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS])
                .filterKeys { !it.startsWith("$providerId|") }
            if (updated.isEmpty()) preferences.remove(PreferencesKeys.VOD_VARIANT_SELECTIONS)
            else preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS] = encodeVodVariantSelections(updated)
        }
    }

    val vodVariantObservations: Flow<Map<Long, VodVariantObservation>> = context.dataStore.data.map { preferences ->
        decodeVodVariantObservations(preferences[PreferencesKeys.VOD_VARIANT_OBSERVATIONS])
    }

    suspend fun recordVodVariantObservation(rawItemId: Long, observation: VodVariantObservation) {
        if (rawItemId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeVodVariantObservations(preferences[PreferencesKeys.VOD_VARIANT_OBSERVATIONS]).toMutableMap()
            updated[rawItemId] = observation
            preferences[PreferencesKeys.VOD_VARIANT_OBSERVATIONS] = encodeVodVariantObservations(updated)
        }
    }

    val vodViewMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.VOD_VIEW_MODE]
    }

    suspend fun setVodViewMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOD_VIEW_MODE] = mode
        }
    }

    val vodInfiniteScroll: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.VOD_INFINITE_SCROLL] ?: true
    }

    suspend fun setVodInfiniteScroll(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOD_INFINITE_SCROLL] = enabled
        }
    }

    val vodCategoryLoadMode: Flow<com.streamvault.domain.model.VodCategoryLoadMode> =
        context.dataStore.data.map { preferences ->
            com.streamvault.domain.model.VodCategoryLoadMode.fromStorage(
                preferences[PreferencesKeys.VOD_CATEGORY_LOAD_MODE]
            )
        }

    suspend fun setVodCategoryLoadMode(mode: com.streamvault.domain.model.VodCategoryLoadMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VOD_CATEGORY_LOAD_MODE] = mode.storageValue
        }
    }

    val guideDensity: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_DENSITY]
    }

    suspend fun setGuideDensity(density: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_DENSITY] = density
        }
    }

    val guideChannelMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_CHANNEL_MODE]
    }

    suspend fun setGuideChannelMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_CHANNEL_MODE] = mode
        }
    }

    val guideDefaultCategoryId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_DEFAULT_CATEGORY_ID]
    }

    suspend fun setGuideDefaultCategoryId(categoryId: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_DEFAULT_CATEGORY_ID] = categoryId
        }
    }

    suspend fun clearGuideDefaultCategoryId() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.GUIDE_DEFAULT_CATEGORY_ID)
        }
    }

    val guideFavoritesOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.GUIDE_FAVORITES_ONLY] ?: 0) == 1
    }

    val guideScheduledOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        (preferences[PreferencesKeys.GUIDE_SCHEDULED_ONLY] ?: 0) == 1
    }

    suspend fun setGuideFavoritesOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_FAVORITES_ONLY] = if (enabled) 1 else 0
        }
    }

    suspend fun setGuideScheduledOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_SCHEDULED_ONLY] = if (enabled) 1 else 0
        }
    }

    val guideAnchorTime: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.GUIDE_ANCHOR_TIME]
    }

    suspend fun setGuideAnchorTime(anchorTimeMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GUIDE_ANCHOR_TIME] = anchorTimeMs
        }
    }

    suspend fun clearGuideAnchorTime() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.GUIDE_ANCHOR_TIME)
        }
    }

    private fun decodeEpgTimeShifts(raw: String?): Map<Long, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val minutes = parts[1].toIntOrNull() ?: return@mapNotNull null
            id to minutes
        }.toMap()
    }

    private fun encodeEpgTimeShifts(map: Map<Long, Int>): String =
        map.entries
            .filter { it.value != 0 }
            .joinToString(",") { "${it.key}:${it.value}" }

    val epgTimeShiftsByProvider: Flow<Map<Long, Int>> = context.dataStore.data.map { prefs ->
        decodeEpgTimeShifts(prefs[PreferencesKeys.EPG_TIME_SHIFT_BY_PROVIDER])
    }

    fun epgTimeShiftMinutes(providerId: Long): Flow<Int> =
        epgTimeShiftsByProvider.map { it[providerId] ?: 0 }

    suspend fun getEpgTimeShiftMinutes(providerId: Long): Int =
        epgTimeShiftsByProvider.first()[providerId] ?: 0

    suspend fun setEpgTimeShiftMinutes(providerId: Long, minutes: Int) {
        context.dataStore.edit { prefs ->
            val current = decodeEpgTimeShifts(prefs[PreferencesKeys.EPG_TIME_SHIFT_BY_PROVIDER]).toMutableMap()
            if (minutes == 0) current.remove(providerId) else current[providerId] = minutes
            val encoded = encodeEpgTimeShifts(current)
            if (encoded.isEmpty()) {
                prefs.remove(PreferencesKeys.EPG_TIME_SHIFT_BY_PROVIDER)
            } else {
                prefs[PreferencesKeys.EPG_TIME_SHIFT_BY_PROVIDER] = encoded
            }
        }
    }

    val promotedLiveGroupIds: Flow<Set<Long>> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PROMOTED_LIVE_GROUP_IDS]
            ?.split(",")
            ?.mapNotNull { token -> token.toLongOrNull() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun setPromotedLiveGroupIds(groupIds: Set<Long>) {
        context.dataStore.edit { preferences ->
            if (groupIds.isEmpty()) {
                preferences.remove(PreferencesKeys.PROMOTED_LIVE_GROUP_IDS)
            } else {
                preferences[PreferencesKeys.PROMOTED_LIVE_GROUP_IDS] = groupIds.sorted().joinToString(",")
            }
        }
    }

    fun getHiddenCategoryIds(providerId: Long, type: ContentType): Flow<Set<Long>> {
        val key = stringPreferencesKey(hiddenCategoriesKey(providerId, type))
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toSet()
                .orEmpty()
        }
    }

    suspend fun setCategoryHidden(
        providerId: Long,
        type: ContentType,
        categoryId: Long,
        hidden: Boolean
    ) {
        val key = stringPreferencesKey(hiddenCategoriesKey(providerId, type))
        context.dataStore.edit { preferences ->
            val current = preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (hidden) {
                current += categoryId
            } else {
                current -= categoryId
            }
            if (current.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = current.sorted().joinToString(",")
            }
        }
    }

    suspend fun setHiddenCategoryIds(
        providerId: Long,
        type: ContentType,
        categoryIds: Set<Long>
    ) {
        val key = stringPreferencesKey(hiddenCategoriesKey(providerId, type))
        context.dataStore.edit { preferences ->
            if (categoryIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = categoryIds.sorted().joinToString(",")
            }
        }
    }

    fun getHiddenChannelIds(providerId: Long): Flow<Set<Long>> {
        val key = stringPreferencesKey(hiddenChannelsKey(providerId))
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toSet()
                .orEmpty()
        }
    }

    suspend fun setChannelHidden(
        providerId: Long,
        channelId: Long,
        hidden: Boolean
    ) {
        val key = stringPreferencesKey(hiddenChannelsKey(providerId))
        context.dataStore.edit { preferences ->
            val current = preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (hidden) {
                current += channelId
            } else {
                current -= channelId
            }
            if (current.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = current.sorted().joinToString(",")
            }
        }
    }

    suspend fun setHiddenChannelIds(
        providerId: Long,
        channelIds: Set<Long>
    ) {
        val key = stringPreferencesKey(hiddenChannelsKey(providerId))
        context.dataStore.edit { preferences ->
            if (channelIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = channelIds.sorted().joinToString(",")
            }
        }
    }

    fun getPinnedCategoryIds(providerId: Long, type: ContentType): Flow<Set<Long>> {
        val key = stringPreferencesKey(pinnedCategoriesKey(providerId, type))
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toSet()
                .orEmpty()
        }
    }

    suspend fun setCategoryPinned(
        providerId: Long,
        type: ContentType,
        categoryId: Long,
        pinned: Boolean
    ) {
        val key = stringPreferencesKey(pinnedCategoriesKey(providerId, type))
        context.dataStore.edit { preferences ->
            val current = preferences[key]
                ?.split(',')
                ?.mapNotNull { token -> token.toLongOrNull() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (pinned) {
                current += categoryId
            } else {
                current -= categoryId
            }
            if (current.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = current.sorted().joinToString(",")
            }
        }
    }

    suspend fun replacePreferredVodVariants(
        providerId: Long,
        selections: Map<String, Long>
    ) {
        if (providerId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeVodVariantSelections(preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS])
                .toMutableMap()
            val providerPrefix = "$providerId|"
            updated.keys.removeAll { it.startsWith(providerPrefix) }
            selections.forEach { (logicalGroupId, rawItemId) ->
                if (logicalGroupId.isNotBlank() && rawItemId > 0L) {
                    updated[vodVariantSelectionKey(providerId, logicalGroupId)] = rawItemId
                }
            }
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.VOD_VARIANT_SELECTIONS)
            } else {
                preferences[PreferencesKeys.VOD_VARIANT_SELECTIONS] = encodeVodVariantSelections(updated)
            }
        }
    }

    suspend fun replacePreferredLiveVariants(
        providerId: Long,
        selections: Map<String, Long>
    ) {
        if (providerId <= 0L) return
        context.dataStore.edit { preferences ->
            val updated = decodeLiveVariantSelections(preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS])
                .toMutableMap()
            val providerPrefix = "$providerId|"
            updated.keys.removeAll { it.startsWith(providerPrefix) }
            selections.forEach { (logicalGroupId, rawChannelId) ->
                if (logicalGroupId.isNotBlank() && rawChannelId > 0L) {
                    updated[liveVariantSelectionKey(providerId, logicalGroupId)] = rawChannelId
                }
            }
            if (updated.isEmpty()) {
                preferences.remove(PreferencesKeys.LIVE_VARIANT_SELECTIONS)
            } else {
                preferences[PreferencesKeys.LIVE_VARIANT_SELECTIONS] = encodeLiveVariantSelections(updated)
            }
        }
    }

    suspend fun setPinnedCategoryIds(
        providerId: Long,
        type: ContentType,
        categoryIds: Set<Long>
    ) {
        val key = stringPreferencesKey(pinnedCategoriesKey(providerId, type))
        context.dataStore.edit { preferences ->
            if (categoryIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = categoryIds.sorted().joinToString(",")
            }
        }
    }

    fun getCategorySortMode(providerId: Long, type: ContentType): Flow<CategorySortMode> {
        val key = stringPreferencesKey(categorySortModeKey(providerId, type))
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.let { saved -> CategorySortMode.entries.firstOrNull { it.name == saved } }
                ?: CategorySortMode.DEFAULT
        }
    }

    suspend fun setCategorySortMode(providerId: Long, type: ContentType, mode: CategorySortMode) {
        val key = stringPreferencesKey(categorySortModeKey(providerId, type))
        context.dataStore.edit { preferences ->
            preferences[key] = mode.name
        }
    }

    fun getMultiViewPreset(presetIndex: Int): Flow<List<Long>> {
        val key = when (presetIndex) {
            0 -> PreferencesKeys.MULTIVIEW_PRESET_1
            1 -> PreferencesKeys.MULTIVIEW_PRESET_2
            else -> PreferencesKeys.MULTIVIEW_PRESET_3
        }
        return context.dataStore.data.map { preferences ->
            preferences[key]
                ?.split(",")
                ?.mapNotNull { token -> token.toLongOrNull() }
                .orEmpty()
        }
    }

    suspend fun setMultiViewPreset(presetIndex: Int, channelIds: List<Long>) {
        val key = when (presetIndex) {
            0 -> PreferencesKeys.MULTIVIEW_PRESET_1
            1 -> PreferencesKeys.MULTIVIEW_PRESET_2
            else -> PreferencesKeys.MULTIVIEW_PRESET_3
        }
        context.dataStore.edit { preferences ->
            if (channelIds.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = channelIds.joinToString(",")
            }
        }
    }

    val multiViewPerformanceMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MULTIVIEW_PERFORMANCE_MODE]
    }

    suspend fun setMultiViewPerformanceMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MULTIVIEW_PERFORMANCE_MODE] = mode
        }
    }

    val multiViewCenterTwoSlotLayout: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MULTIVIEW_CENTER_TWO_SLOT_LAYOUT] ?: false
    }

    suspend fun setMultiViewCenterTwoSlotLayout(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MULTIVIEW_CENTER_TWO_SLOT_LAYOUT] = enabled
        }
    }

    val multiViewRespectProviderConnectionLimit: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MULTIVIEW_RESPECT_PROVIDER_CONNECTION_LIMIT] ?: true
    }

    suspend fun setMultiViewRespectProviderConnectionLimit(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MULTIVIEW_RESPECT_PROVIDER_CONNECTION_LIMIT] = enabled
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAspectRatioForChannel(channelId: Long): Flow<String?> {
        return channelPreferenceDao.observeAspectRatio(channelId).flatMapLatest { persistedRatio ->
            if (persistedRatio != null) {
                flowOf(persistedRatio)
            } else {
                val legacyKey = stringPreferencesKey("aspect_ratio_$channelId")
                context.dataStore.data.map { preferences ->
                    preferences[legacyKey]
                }
            }
        }
    }

    suspend fun setAspectRatioForChannel(channelId: Long, ratio: String) {
        channelPreferenceDao.setAspectRatio(channelId, ratio)
        val key = stringPreferencesKey("aspect_ratio_$channelId")
        context.dataStore.edit { preferences ->
            preferences.remove(key)
        }
    }

    fun observeAudioVideoOffsetForChannel(channelId: Long): Flow<Int?> =
        channelPreferenceDao.observeAudioVideoOffset(channelId)
            .map { offset -> offset?.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS) }

    suspend fun setAudioVideoOffsetForChannel(channelId: Long, offsetMs: Int) {
        channelPreferenceDao.setAudioVideoOffset(
            channelId = channelId,
            offsetMs = offsetMs.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
        )
    }

    suspend fun clearAudioVideoOffsetForChannel(channelId: Long) {
        channelPreferenceDao.setAudioVideoOffset(channelId = channelId, offsetMs = null)
    }

    suspend fun clearAllRecentData() {
        channelPreferenceDao.deleteAll()
        searchHistoryDao.deleteAll()
        context.dataStore.edit { preferences ->
            val keysToRemove = preferences.asMap().keys.filter { key ->
                key.name.startsWith("last_live_category_id_") || 
                key.name.startsWith("aspect_ratio_") ||
                key.name == PreferencesKeys.DEFAULT_CATEGORY_ID.name ||
                key.name == PreferencesKeys.RECENT_SEARCH_QUERIES.name
            }
            keysToRemove.forEach { key ->
                preferences.remove(key)
            }
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_HASH_ITERATIONS, PIN_HASH_KEY_BITS)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return java.util.Base64.getEncoder().encodeToString(secret.encoded)
    }

    private fun hiddenCategoriesKey(providerId: Long, type: ContentType): String =
        "hidden_categories_${providerId}_${type.name}"

    private fun hiddenChannelsKey(providerId: Long): String =
        "hidden_channels_${providerId}"

    private fun pinnedCategoriesKey(providerId: Long, type: ContentType): String =
        "pinned_categories_${providerId}_${type.name}"

    private fun remoteShortcutKey(profile: RemoteShortcutProfile, button: RemoteColorButton): String =
        "remote_shortcut_${profile.storageValue}_${button.storageValue}"

    private fun liveVariantSelectionKey(providerId: Long, logicalGroupId: String): String =
        "${providerId}|${logicalGroupId.trim()}"

    private fun vodVariantSelectionKey(providerId: Long, logicalGroupId: String): String =
        "${providerId}|${logicalGroupId.trim()}"

    private fun encodeLiveVariantSelections(values: Map<String, Long>): String =
        values.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, rawChannelId) -> "$key=$rawChannelId" }

    private fun encodeAppTopLevelDestinations(destinations: List<AppTopLevelDestination>): String =
        AppTopLevelDestination.normalizeForStorage(destinations)
            .joinToString(",") { it.storageValue }

    private fun decodeAppTopLevelDestinations(encoded: String?): List<AppTopLevelDestination> {
        val decoded = encoded
            .orEmpty()
            .split(',')
            .asSequence()
            .mapNotNull { token -> AppTopLevelDestination.fromStorage(token.trim()) }
            .toList()
        return if (decoded.isEmpty()) {
            AppTopLevelDestination.defaultOrder
        } else {
            AppTopLevelDestination.normalizeForStorage(decoded)
        }
    }

    private fun encodeAppHomeDashboardShelves(shelves: List<AppHomeDashboardShelf>): String =
        AppHomeDashboardShelf.normalizeForStorage(shelves)
            .joinToString(",") { it.storageValue }

    private fun decodeAppHomeDashboardShelves(encoded: String?): List<AppHomeDashboardShelf> {
        if (encoded == null) {
            return AppHomeDashboardShelf.defaultOrder
        }
        if (encoded.isBlank()) {
            return emptyList()
        }
        val decoded = encoded
            .split(',')
            .asSequence()
            .mapNotNull { token -> AppHomeDashboardShelf.fromStorage(token.trim()) }
            .toList()
        return if (decoded.isEmpty()) {
            AppHomeDashboardShelf.defaultOrder
        } else {
            AppHomeDashboardShelf.normalizeForStorage(decoded)
        }
    }

    private fun decodeLiveVariantSelections(encoded: String?): Map<String, Long> =
        encoded
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                val rawChannelId = line.substring(separator + 1).trim().toLongOrNull() ?: return@mapNotNull null
                key.takeIf { it.isNotBlank() }?.let { it to rawChannelId }
            }
            .toMap()

    private fun encodeLiveVariantObservations(values: Map<Long, LiveChannelObservedQuality>): String =
        values.entries
            .sortedByDescending { it.value.lastSuccessfulAt }
            .take(500)
            .joinToString("\n") { (rawChannelId, observation) ->
                listOf(
                    rawChannelId,
                    observation.lastObservedWidth,
                    observation.lastObservedHeight,
                    observation.lastObservedBitrate,
                    observation.lastObservedFrameRate,
                    observation.successCount,
                    observation.lastSuccessfulAt
                ).joinToString("|")
            }

    private fun decodeLiveVariantObservations(encoded: String?): Map<Long, LiveChannelObservedQuality> =
        encoded
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 7) return@mapNotNull null
                val rawChannelId = parts[0].toLongOrNull() ?: return@mapNotNull null
                rawChannelId to LiveChannelObservedQuality(
                    lastObservedWidth = parts[1].toIntOrNull() ?: 0,
                    lastObservedHeight = parts[2].toIntOrNull() ?: 0,
                    lastObservedBitrate = parts[3].toIntOrNull() ?: 0,
                    lastObservedFrameRate = parts[4].toFloatOrNull() ?: 0f,
                    successCount = parts[5].toIntOrNull() ?: 0,
                    lastSuccessfulAt = parts[6].toLongOrNull() ?: 0L
                )
            }
            .toMap()

    private fun encodeVodVariantSelections(values: Map<String, Long>): String =
        values.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, rawItemId) -> "$key=$rawItemId" }

    private fun decodeVodVariantSelections(encoded: String?): Map<String, Long> =
        encoded
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                val rawItemId = line.substring(separator + 1).trim().toLongOrNull() ?: return@mapNotNull null
                key.takeIf { it.isNotBlank() }?.let { it to rawItemId }
            }
            .toMap()

    private fun encodeVodVariantObservations(values: Map<Long, VodVariantObservation>): String =
        values.entries
            .sortedByDescending { maxOf(it.value.lastSuccessfulAt, it.value.lastFailedAt) }
            .take(500)
            .joinToString("\n") { (rawItemId, observation) ->
                listOf(
                    rawItemId,
                    observation.successCount,
                    observation.failureCount,
                    observation.lastSuccessfulAt,
                    observation.lastFailedAt
                ).joinToString("|")
            }

    private fun decodeVodVariantObservations(encoded: String?): Map<Long, VodVariantObservation> =
        encoded
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 5) return@mapNotNull null
                val rawItemId = parts[0].toLongOrNull() ?: return@mapNotNull null
                rawItemId to VodVariantObservation(
                    successCount = parts[1].toIntOrNull() ?: 0,
                    failureCount = parts[2].toIntOrNull() ?: 0,
                    lastSuccessfulAt = parts[3].toLongOrNull() ?: 0L,
                    lastFailedAt = parts[4].toLongOrNull() ?: 0L
                )
            }
            .toMap()

    private suspend fun clearLegacyRecentSearchQueries() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.RECENT_SEARCH_QUERIES)
        }
    }

    private fun searchHistoryProviderKey(providerId: Long?): Long = providerId ?: 0L


    private fun List<String>.normalizeLiveTvCategoryFilters(): List<String> {
        val seen = linkedSetOf<String>()
        val normalized = mutableListOf<String>()
        forEach { filter ->
            val trimmed = filter.trim()
            if (trimmed.isBlank()) return@forEach
            if (seen.add(trimmed.lowercase())) {
                normalized += trimmed
            }
        }
        return normalized
    }
    private fun categorySortModeKey(providerId: Long, type: ContentType): String =
        "category_sort_${providerId}_${type.name}"

    private fun isPinLockedOut(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lockoutUntilMs = parentalSessionPreferences.getLong(ParentalSessionKeys.PIN_LOCKOUT_UNTIL_MS, 0L)
        if (lockoutUntilMs <= nowMs) {
            if (lockoutUntilMs != 0L || parentalSessionPreferences.getInt(ParentalSessionKeys.PIN_FAILED_ATTEMPTS, 0) != 0) {
                parentalSessionPreferences.edit()
                    .remove(ParentalSessionKeys.PIN_LOCKOUT_UNTIL_MS)
                    .remove(ParentalSessionKeys.PIN_FAILED_ATTEMPTS)
                    .apply()
            }
            return false
        }
        return true
    }

    private fun updatePinAttemptState(valid: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val editor = parentalSessionPreferences.edit()
        if (valid) {
            editor.remove(ParentalSessionKeys.PIN_FAILED_ATTEMPTS)
            editor.remove(ParentalSessionKeys.PIN_LOCKOUT_UNTIL_MS)
            editor.apply()
            return
        }

        val failedAttempts = parentalSessionPreferences.getInt(ParentalSessionKeys.PIN_FAILED_ATTEMPTS, 0) + 1
        if (failedAttempts >= PIN_MAX_ATTEMPTS) {
            editor.remove(ParentalSessionKeys.PIN_FAILED_ATTEMPTS)
            editor.putLong(ParentalSessionKeys.PIN_LOCKOUT_UNTIL_MS, nowMs + PIN_LOCKOUT_DURATION_MS)
        } else {
            editor.putInt(ParentalSessionKeys.PIN_FAILED_ATTEMPTS, failedAttempts)
        }
        editor.apply()
    }

    private fun decodeUnlockEntries(encoded: String?): Map<Long, Set<Long>> {
        return encoded
            .orEmpty()
            .split(',')
            .asSequence()
            .mapNotNull { token ->
                val parts = token.split(':')
                if (parts.size < 2) return@mapNotNull null
                val providerId = parts[0].toLongOrNull() ?: return@mapNotNull null
                val categoryId = parts[1].toLongOrNull() ?: return@mapNotNull null
                providerId to categoryId
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, categoryIds) -> categoryIds.toSet() }
    }

    private fun hasStoredParentalPin(preferences: Preferences): Boolean {
        val storedHash = preferences[PreferencesKeys.PARENTAL_PIN_HASH]
        val storedSaltBase64 = preferences[PreferencesKeys.PARENTAL_PIN_SALT]
        if (!storedHash.isNullOrBlank() && !storedSaltBase64.isNullOrBlank()) {
            return true
        }

        return !preferences[PreferencesKeys.LEGACY_PARENTAL_PIN].isNullOrBlank()
    }

    private fun xtreamTextImportAppliedGenerationKey(providerId: Long): String =
        "xtream_text_import_applied_generation_$providerId"
}

data class ParentalPinBackupData(
    val hash: String,
    val saltBase64: String
)

data class DatabaseMaintenanceSnapshot(
    val ranAt: Long,
    val deletedPrograms: Int,
    val deletedExternalProgrammes: Int,
    val deletedOrphanEpisodes: Int,
    val deletedStaleFavorites: Int,
    val vacuumRan: Boolean,
    val mainDbBytes: Long,
    val walBytes: Long,
    val reclaimableBytes: Long,
    val channelRows: Long,
    val movieRows: Long,
    val seriesRows: Long,
    val episodeRows: Long,
    val programRows: Long,
    val epgProgrammeRows: Long,
    val playbackHistoryRows: Long,
    val favoriteRows: Long
)
