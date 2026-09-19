package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SeriesCatalogOrigin
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.XmltvTimezonePolicy

@Entity(
    tableName = "m3u_classification_overrides",
    primaryKeys = ["provider_id", "source_key"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class M3uClassificationOverrideEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "stream_id") val streamId: Long,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "group_key") val groupKey: String = "",
    @ColumnInfo(name = "series_key") val seriesKey: String? = null,
    @ColumnInfo(name = "series_name") val seriesName: String? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null,
    @ColumnInfo(name = "episode_title") val episodeTitle: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "m3u_category_classification_rules",
    primaryKeys = ["provider_id", "group_key"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class M3uCategoryClassificationRuleEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "group_key") val groupKey: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "providers",
    indices = [Index(value = ["type"]), Index(value = ["is_active"])]
)
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: ProviderType,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

/** Authoritative typed configuration payload introduced in schema 73. */
@Entity(
    tableName = "provider_configs",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"], unique = true),
        Index(value = ["identity_key"], unique = true),
        Index(value = ["type"])
    ]
)
data class ProviderConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val type: ProviderType,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "configuration_generation") val configurationGeneration: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    /** JSON with credential values encrypted before serialization. */
    @ColumnInfo(name = "encrypted_config_json") val encryptedConfigJson: String,
    @ColumnInfo(name = "guide_source_policy") val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    @ColumnInfo(name = "channel_logo_source_policy") val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/** Account and catalog observations, intentionally separate from user configuration. */
@Entity(
    tableName = "provider_account_runtime",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"], unique = true)]
)
data class ProviderAccountRuntimeEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "max_connections") val maxConnections: Int = 1,
    @ColumnInfo(name = "expiration_date") val expirationDate: Long? = null,
    @ColumnInfo(name = "api_version") val apiVersion: String? = null,
    @ColumnInfo(name = "allowed_output_formats_json") val allowedOutputFormatsJson: String = "[]",
    @ColumnInfo(name = "catalog_layout") val catalogLayout: CatalogLayout = CatalogLayout.SPLIT,
    @ColumnInfo(name = "catalog_layout_detection_version") val catalogLayoutDetectionVersion: Int = 0,
    @ColumnInfo(name = "observed_at") val observedAt: Long = 0L
)

/** Lifecycle of a provider configuration which has not yet replaced the committed row. */
enum class ProviderConfigRevisionState {
    PENDING,
    SYNCING,
    FAILED,
    COMMITTED,
    SUPERSEDED
}

/**
 * A candidate provider configuration that has not yet replaced the committed provider row.
 *
 * The configuration payload always contains an already-encrypted password. Keeping it outside
 * of [ProviderEntity] lets an active provider continue to resolve and play its committed catalog
 * while a replacement configuration is authenticated and synchronized.
 */
@Entity(
    tableName = "provider_config_revisions",
    primaryKeys = ["provider_id", "revision"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id", "state"])]
)
data class ProviderConfigRevisionEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val revision: Long,
    @ColumnInfo(name = "config_json") val configJson: String,
    val state: ProviderConfigRevisionState,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * Checkpoints a cross-store backup restore. Room is transactional, while DataStore and alarm
 * scheduling are not; keeping each completed boundary durable lets a retry resume truthfully.
 */
@Entity(tableName = "backup_restore_checkpoints")
data class BackupRestoreCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "restore_key") val restoreKey: String,
    @ColumnInfo(name = "room_complete") val roomComplete: Boolean = false,
    @ColumnInfo(name = "preferences_complete") val preferencesComplete: Boolean = false,
    @ColumnInfo(name = "presets_complete") val presetsComplete: Boolean = false,
    @ColumnInfo(name = "schedules_complete") val schedulesComplete: Boolean = false,
    val state: String,
    @ColumnInfo(name = "preference_snapshot_json") val preferenceSnapshotJson: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "stream_id"], unique = true),
        Index(value = ["logical_group_id"]),
        Index(value = ["provider_id", "category_id", "logical_group_id"])
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "group_title") val groupTitle: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null,
    val number: Int = 0,
    @ColumnInfo(name = "catch_up_supported") val catchUpSupported: Boolean = false,
    @ColumnInfo(name = "catch_up_days") val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "logical_group_id") val logicalGroupId: String = "",
    @ColumnInfo(name = "error_count") val errorCount: Int = 0,
    @ColumnInfo(name = "quality_options_json") val qualityOptionsJson: String? = null,
    @ColumnInfo(name = "playback_metadata_json") val playbackMetadataJson: String? = null,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = ""
)

data class ChannelBrowseEntity(
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "group_title") val groupTitle: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null,
    val number: Int = 0,
    @ColumnInfo(name = "catch_up_supported") val catchUpSupported: Boolean = false,
    @ColumnInfo(name = "catch_up_days") val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "guide_source_policy") val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    @ColumnInfo(name = "channel_logo_source_policy") val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    @ColumnInfo(name = "epg_icon_url") val epgIconUrl: String? = null,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "logical_group_id") val logicalGroupId: String = "",
    @ColumnInfo(name = "error_count") val errorCount: Int = 0
)

data class ChannelGuideLookupEntity(
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null
)

data class ChannelGuideSyncEntity(
    val name: String,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null
)

@Entity(
    tableName = "channel_preferences",
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channel_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["channel_id"], unique = true)]
)
data class ChannelPreferenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "aspect_ratio") val aspectRatio: String? = null,
    @ColumnInfo(name = "audio_video_offset_ms") val audioVideoOffsetMs: Int? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playback_compatibility_records",
    indices = [
        Index(
            value = [
                "device_fingerprint",
                "stream_type",
                "video_mime_type",
                "resolution_bucket",
                "decoder_name",
                "surface_type"
            ],
            unique = true
        ),
        Index(value = ["device_fingerprint", "stream_type", "video_mime_type", "resolution_bucket"]),
        Index(value = ["last_failed_at"]),
        Index(value = ["last_succeeded_at"])
    ]
)
data class PlaybackCompatibilityRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "device_fingerprint") val deviceFingerprint: String,
    @ColumnInfo(name = "device_model") val deviceModel: String,
    @ColumnInfo(name = "android_sdk") val androidSdk: Int,
    @ColumnInfo(name = "stream_type") val streamType: String,
    @ColumnInfo(name = "video_mime_type") val videoMimeType: String,
    @ColumnInfo(name = "resolution_bucket") val resolutionBucket: String,
    @ColumnInfo(name = "decoder_name") val decoderName: String,
    @ColumnInfo(name = "surface_type") val surfaceType: String,
    @ColumnInfo(name = "failure_type") val failureType: String = "",
    @ColumnInfo(name = "last_failed_at") val lastFailedAt: Long = 0L,
    @ColumnInfo(name = "last_succeeded_at") val lastSucceededAt: Long = 0L,
    @ColumnInfo(name = "failure_count") val failureCount: Int = 0,
    @ColumnInfo(name = "success_count") val successCount: Int = 0
)

@Entity(
    tableName = "movies",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "stream_id"], unique = true),
        Index(name = "index_movies_provider_id_name_id", value = ["provider_id", "name", "id"]),
        Index(name = "index_movies_provider_id_category_id_name_id", value = ["provider_id", "category_id", "name", "id"]),
        Index(name = "index_movies_provider_id_rating_name_id", value = ["provider_id", "rating", "name", "id"]),
        Index(name = "index_movies_provider_id_added_at_release_date_name_id", value = ["provider_id", "added_at", "release_date", "name", "id"])
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val year: String? = null,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "watch_count") val watchCount: Int = 0,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = "",
    @ColumnInfo(name = "added_at") val addedAt: Long = 0L,
    @ColumnInfo(name = "cache_state") val cacheState: String = "DETAIL_HYDRATED",
    @ColumnInfo(name = "detail_hydrated_at") val detailHydratedAt: Long = 0L,
    @ColumnInfo(name = "remote_stale_at") val remoteStaleAt: Long = 0L,
    @ColumnInfo(name = "playback_metadata_json") val playbackMetadataJson: String? = null
)

data class MovieBrowseEntity(
    val id: Long = 0,
    @ColumnInfo(name = "stream_id") val streamId: Long = 0,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val year: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAt: Long = 0L
)

@Entity(
    tableName = "series",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id"]),
        Index(value = ["provider_id", "series_id"], unique = true),
        Index(name = "index_series_provider_id_name_id", value = ["provider_id", "name", "id"]),
        Index(name = "index_series_provider_id_category_id_name_id", value = ["provider_id", "category_id", "name", "id"]),
        Index(name = "index_series_provider_id_rating_name_id", value = ["provider_id", "rating", "name", "id"]),
        Index(name = "index_series_provider_id_last_modified_name_id", value = ["provider_id", "last_modified", "name", "id"])
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    @ColumnInfo(name = "provider_series_id") val providerSeriesId: String? = null,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val rating: Float = 0f,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "episode_run_time") val episodeRunTime: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0L,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = "",
    @ColumnInfo(name = "cache_state") val cacheState: String = "DETAIL_HYDRATED",
    @ColumnInfo(name = "detail_hydrated_at") val detailHydratedAt: Long = 0L,
    @ColumnInfo(name = "remote_stale_at") val remoteStaleAt: Long = 0L,
    @ColumnInfo(name = "catalog_origin") val catalogOrigin: SeriesCatalogOrigin = SeriesCatalogOrigin.NATIVE,
    @ColumnInfo(name = "episode_playback_template_url") val episodePlaybackTemplateUrl: String? = null
)

data class SeriesBrowseEntity(
    val id: Long = 0,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    @ColumnInfo(name = "provider_series_id") val providerSeriesId: String? = null,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val rating: Float = 0f,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0L,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "catalog_origin") val catalogOrigin: SeriesCatalogOrigin = SeriesCatalogOrigin.NATIVE,
    @ColumnInfo(name = "episode_playback_template_url") val episodePlaybackTemplateUrl: String? = null
)

@Fts4(contentEntity = ChannelEntity::class)
@Entity(tableName = "channels_fts")
data class ChannelFtsEntity(
    val name: String
)

@Fts4(contentEntity = MovieEntity::class)
@Entity(tableName = "movies_fts")
data class MovieFtsEntity(
    val name: String
)

@Fts4(contentEntity = SeriesEntity::class)
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val name: String
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["series_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["series_id"]),
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "episode_id"], unique = true)
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "episode_id") val episodeId: Long = 0,
    val title: String,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

data class EpisodeBrowseEntity(
    val id: Long = 0,
    @ColumnInfo(name = "episode_id") val episodeId: Long = 0,
    val title: String,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    val plot: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "series_id") val seriesId: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "watch_progress") val watchProgress: Long = 0L,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false
)

@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "type"]),
        Index(value = ["provider_id", "category_id", "type"], unique = true)
    ]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long = 0,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val type: ContentType = ContentType.LIVE,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "provider_order", defaultValue = "0") val providerOrder: Int = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "is_user_protected") val isUserProtected: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = ""
)

@Entity(
    tableName = "channel_import_stage",
    primaryKeys = ["session_id", "provider_id", "stream_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["session_id", "provider_id"])
    ]
)
data class ChannelImportStageEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "stream_id") val streamId: Long,
    val name: String,
    @ColumnInfo(name = "logo_url") val logoUrl: String? = null,
    @ColumnInfo(name = "group_title") val groupTitle: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "epg_channel_id") val epgChannelId: String? = null,
    val number: Int = 0,
    @ColumnInfo(name = "catch_up_supported") val catchUpSupported: Boolean = false,
    @ColumnInfo(name = "catch_up_days") val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "logical_group_id") val logicalGroupId: String = "",
    @ColumnInfo(name = "error_count") val errorCount: Int = 0,
    @ColumnInfo(name = "playback_metadata_json") val playbackMetadataJson: String? = null,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = ""
)

@Entity(
    tableName = "movie_import_stage",
    primaryKeys = ["session_id", "provider_id", "stream_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["session_id", "provider_id"])
    ]
)
data class MovieImportStageEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "stream_id") val streamId: Long,
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "container_extension") val containerExtension: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val duration: String? = null,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int = 0,
    val rating: Float = 0f,
    val year: String? = null,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = "",
    @ColumnInfo(name = "playback_metadata_json") val playbackMetadataJson: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long = 0L
)

@Entity(
    tableName = "series_import_stage",
    primaryKeys = ["session_id", "provider_id", "provider_series_key"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["session_id", "provider_id"])
    ]
)
data class SeriesImportStageEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "series_id") val seriesId: Long,
    @ColumnInfo(name = "provider_series_id") val providerSeriesId: String? = null,
    @ColumnInfo(name = "provider_series_key") val providerSeriesKey: String = providerSeriesId?.takeIf { it.isNotBlank() } ?: seriesId.toString(),
    val name: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "category_name") val categoryName: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    val rating: Float = 0f,
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long? = null,
    @ColumnInfo(name = "youtube_trailer") val youtubeTrailer: String? = null,
    @ColumnInfo(name = "episode_run_time") val episodeRunTime: String? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0L,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = ""
)

@Entity(
    tableName = "category_import_stage",
    primaryKeys = ["session_id", "provider_id", "category_id", "type"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["session_id", "provider_id"]),
        Index(value = ["provider_id", "type"])
    ]
)
data class CategoryImportStageEntity(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val type: ContentType = ContentType.LIVE,
    @ColumnInfo(name = "provider_order", defaultValue = "0") val providerOrder: Int = 0,
    @ColumnInfo(name = "is_adult") val isAdult: Boolean = false,
    @ColumnInfo(name = "sync_fingerprint") val syncFingerprint: String = ""
)

@Entity(
    tableName = "programs",
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "channel_id"]),
        Index(value = ["provider_id", "end_time", "channel_id"]),
        Index(value = ["provider_id", "start_time", "end_time"]),
        Index(value = ["start_time"]),
        Index(value = ["provider_id", "channel_id", "start_time"]),
        Index(value = ["provider_id", "channel_id", "start_time", "end_time"], unique = true)
    ]
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: String,
    val title: String,
    val description: String = "",
    @ColumnInfo(name = "start_time") val startTime: Long = 0,
    @ColumnInfo(name = "end_time") val endTime: Long = 0,
    val lang: String = "",
    val rating: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    val genre: String? = null,
    val category: String? = null,
    @ColumnInfo(name = "has_archive") val hasArchive: Boolean = false
)

data class ProgramBrowseEntity(
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: String,
    val title: String,
    val description: String = "",
    @ColumnInfo(name = "start_time") val startTime: Long = 0,
    @ColumnInfo(name = "end_time") val endTime: Long = 0,
    val lang: String = "",
    val rating: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    val genre: String? = null,
    val category: String? = null,
    @ColumnInfo(name = "has_archive") val hasArchive: Boolean = false
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VirtualGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["provider_id", "content_id", "content_type", "group_key"], unique = true),
        Index(value = ["provider_id", "content_type", "group_id"]),
        Index(value = ["group_id", "position"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    val position: Int = 0,
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
    @ColumnInfo(name = "group_key") val groupKey: Long = groupId ?: 0L,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "virtual_groups",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id", "content_type"]),
        Index(value = ["position"]),
        Index(value = ["content_type"])
    ]
)
data class VirtualGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val name: String,
    @ColumnInfo(name = "icon_emoji") val iconEmoji: String? = null,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "content_type") val contentType: ContentType = ContentType.LIVE
)

data class CategoryCount(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "item_count") val item_count: Int
)

@Entity(
    tableName = "playback_history",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["content_id", "content_type", "provider_id"], unique = true),
        Index(value = ["last_watched_at"]),
        Index(value = ["provider_id"]),
        Index(name = "index_playback_history_provider_id_content_type_content_id", value = ["provider_id", "content_type", "content_id"]),
        Index(name = "index_playback_history_provider_id_content_type_last_watched_at", value = ["provider_id", "content_type", "last_watched_at"])
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val title: String = "",
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long = 0,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0,
    @ColumnInfo(name = "watch_count") val watchCount: Int = 1,
    @ColumnInfo(name = "watched_status") val watchedStatus: String = "IN_PROGRESS",
    @ColumnInfo(name = "series_id") val seriesId: Long? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null
)

@Entity(
    tableName = "tmdb_identity",
    primaryKeys = ["tmdb_id", "content_type"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["canonical_provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["content_type"]),
        Index(value = ["canonical_provider_id"])
    ]
)
data class TmdbIdentityEntity(
    @ColumnInfo(name = "tmdb_id") val tmdbId: Long,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "canonical_provider_id") val canonicalProviderId: Long,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long
)

data class PlaybackHistoryLiteEntity(
    val id: Long = 0,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val title: String = "",
    @ColumnInfo(name = "poster_url") val posterUrl: String? = null,
    @ColumnInfo(name = "stream_url") val streamUrl: String = "",
    @ColumnInfo(name = "resume_position_ms") val resumePositionMs: Long = 0,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0,
    @ColumnInfo(name = "watch_count") val watchCount: Int = 1,
    @ColumnInfo(name = "watched_status") val watchedStatus: String = "IN_PROGRESS",
    @ColumnInfo(name = "series_id") val seriesId: Long? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null
)

@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["content_scope", "provider_id", "used_at"]),
        Index(value = ["used_at"]),
        Index(value = ["provider_id"]),
        Index(value = ["query", "content_scope", "provider_id"], unique = true)
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val query: String,
    @ColumnInfo(name = "content_scope") val contentScope: String,
    @ColumnInfo(name = "provider_id") val providerId: Long = 0,
    @ColumnInfo(name = "used_at") val usedAt: Long,
    @ColumnInfo(name = "use_count") val useCount: Int = 1
)

@Entity(
    tableName = "sync_metadata",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SyncMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "last_live_sync") val lastLiveSync: Long = 0,
    @ColumnInfo(name = "last_live_success") val lastLiveSuccess: Long = 0,
    @ColumnInfo(name = "last_movie_sync") val lastMovieSync: Long = 0,
    @ColumnInfo(name = "last_series_sync") val lastSeriesSync: Long = 0,
    @ColumnInfo(name = "last_series_success") val lastSeriesSuccess: Long = 0,
    @ColumnInfo(name = "last_epg_sync") val lastEpgSync: Long = 0,
    @ColumnInfo(name = "last_epg_success") val lastEpgSuccess: Long = 0,
    @ColumnInfo(name = "last_movie_attempt") val lastMovieAttempt: Long = 0,
    @ColumnInfo(name = "last_movie_success") val lastMovieSuccess: Long = 0,
    @ColumnInfo(name = "last_movie_partial") val lastMoviePartial: Long = 0,
    @ColumnInfo(name = "live_count") val liveCount: Int = 0,
    @ColumnInfo(name = "movie_count") val movieCount: Int = 0,
    @ColumnInfo(name = "series_count") val seriesCount: Int = 0,
    @ColumnInfo(name = "epg_count") val epgCount: Int = 0,
    @ColumnInfo(name = "last_sync_status") val lastSyncStatus: String = "NONE",
    @ColumnInfo(name = "movie_sync_mode") val movieSyncMode: String = "UNKNOWN",
    @ColumnInfo(name = "movie_warnings_count") val movieWarningsCount: Int = 0,
    @ColumnInfo(name = "movie_catalog_stale") val movieCatalogStale: Boolean = false,
    @ColumnInfo(name = "live_avoid_full_until") val liveAvoidFullUntil: Long = 0,
    @ColumnInfo(name = "movie_avoid_full_until") val movieAvoidFullUntil: Long = 0,
    @ColumnInfo(name = "series_avoid_full_until") val seriesAvoidFullUntil: Long = 0,
    @ColumnInfo(name = "live_sequential_failures_remembered") val liveSequentialFailuresRemembered: Boolean = false,
    @ColumnInfo(name = "live_healthy_sync_streak") val liveHealthySyncStreak: Int = 0,
    @ColumnInfo(name = "movie_parallel_failures_remembered") val movieParallelFailuresRemembered: Boolean = false,
    @ColumnInfo(name = "movie_healthy_sync_streak") val movieHealthySyncStreak: Int = 0,
    @ColumnInfo(name = "series_sequential_failures_remembered") val seriesSequentialFailuresRemembered: Boolean = false,
    @ColumnInfo(name = "series_healthy_sync_streak") val seriesHealthySyncStreak: Int = 0
)

@Entity(
    tableName = "movie_category_hydration",
    primaryKeys = ["provider_id", "category_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class MovieCategoryHydrationEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "last_hydrated_at") val lastHydratedAt: Long = 0L,
    @ColumnInfo(name = "item_count") val itemCount: Int = 0,
    @ColumnInfo(name = "last_status") val lastStatus: String = "IDLE",
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_loaded_page") val lastLoadedPage: Int = 0,
    @ColumnInfo(name = "last_attempted_page") val lastAttemptedPage: Int = 0,
    @ColumnInfo(name = "last_successful_page") val lastSuccessfulPage: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    @ColumnInfo(name = "advertised_total_items") val advertisedTotalItems: Int? = null,
    @ColumnInfo(name = "advertised_total_pages") val advertisedTotalPages: Int? = null,
    @ColumnInfo(name = "is_complete") val isComplete: Boolean = false,
    @ColumnInfo(name = "page_size") val pageSize: Int = 0,
    @ColumnInfo(name = "retry_after_ms") val retryAfterMs: Long = 0L,
    @ColumnInfo(name = "failure_count") val failureCount: Int = 0,
    @ColumnInfo(name = "retry_budget_remaining") val retryBudgetRemaining: Int = 3,
    @ColumnInfo(name = "last_page_fingerprint") val lastPageFingerprint: String? = null
)

@Entity(
    tableName = "series_category_hydration",
    primaryKeys = ["provider_id", "category_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class SeriesCategoryHydrationEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "last_hydrated_at") val lastHydratedAt: Long = 0L,
    @ColumnInfo(name = "item_count") val itemCount: Int = 0,
    @ColumnInfo(name = "last_status") val lastStatus: String = "IDLE",
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_loaded_page") val lastLoadedPage: Int = 0,
    @ColumnInfo(name = "last_attempted_page") val lastAttemptedPage: Int = 0,
    @ColumnInfo(name = "last_successful_page") val lastSuccessfulPage: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    @ColumnInfo(name = "advertised_total_items") val advertisedTotalItems: Int? = null,
    @ColumnInfo(name = "advertised_total_pages") val advertisedTotalPages: Int? = null,
    @ColumnInfo(name = "is_complete") val isComplete: Boolean = false,
    @ColumnInfo(name = "page_size") val pageSize: Int = 0,
    @ColumnInfo(name = "retry_after_ms") val retryAfterMs: Long = 0L,
    @ColumnInfo(name = "failure_count") val failureCount: Int = 0,
    @ColumnInfo(name = "retry_budget_remaining") val retryBudgetRemaining: Int = 3,
    @ColumnInfo(name = "last_page_fingerprint") val lastPageFingerprint: String? = null
)

@Entity(
    tableName = "vod_category_hydration",
    primaryKeys = ["provider_id", "category_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["provider_id"])]
)
data class VodCategoryHydrationEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "last_loaded_page") val lastLoadedPage: Int = 0,
    @ColumnInfo(name = "last_attempted_page") val lastAttemptedPage: Int = 0,
    @ColumnInfo(name = "last_successful_page") val lastSuccessfulPage: Int = 0,
    @ColumnInfo(name = "total_pages") val totalPages: Int = 0,
    @ColumnInfo(name = "advertised_total_items") val advertisedTotalItems: Int? = null,
    @ColumnInfo(name = "advertised_total_pages") val advertisedTotalPages: Int? = null,
    @ColumnInfo(name = "page_size") val pageSize: Int = 0,
    @ColumnInfo(name = "item_count") val itemCount: Int = 0,
    @ColumnInfo(name = "is_complete") val isComplete: Boolean = false,
    @ColumnInfo(name = "has_movies") val hasMovies: Boolean = false,
    @ColumnInfo(name = "has_series") val hasSeries: Boolean = false,
    @ColumnInfo(name = "last_hydrated_at") val lastHydratedAt: Long = 0L,
    @ColumnInfo(name = "last_status") val lastStatus: String = "IDLE",
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "retry_after_ms") val retryAfterMs: Long = 0L,
    @ColumnInfo(name = "failure_count") val failureCount: Int = 0,
    @ColumnInfo(name = "retry_budget_remaining") val retryBudgetRemaining: Int = 3,
    @ColumnInfo(name = "last_page_fingerprint") val lastPageFingerprint: String? = null
)

@Entity(
    tableName = "vod_catalog_entries",
    primaryKeys = ["provider_id", "category_id", "raw_item_id"],
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id"]),
        Index(value = ["provider_id", "category_id", "raw_page", "raw_index"]),
        Index(value = ["provider_id", "item_type", "target_id"])
    ]
)
data class VodCatalogEntryEntity(
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "raw_item_id") val rawItemId: String,
    @ColumnInfo(name = "item_type") val itemType: ContentType,
    @ColumnInfo(name = "target_id") val targetId: Long,
    @ColumnInfo(name = "raw_page") val rawPage: Int,
    @ColumnInfo(name = "raw_index") val rawIndex: Int
)

// ── External EPG Source ────────────────────────────────────────────

@Entity(
    tableName = "epg_sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_refresh_at") val lastRefreshAt: Long = 0,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    val priority: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val etag: String? = null,
    @ColumnInfo(name = "last_modified_header") val lastModifiedHeader: String? = null,
    @ColumnInfo(name = "timezone_policy")
    val timezonePolicy: XmltvTimezonePolicy = XmltvTimezonePolicy.REQUIRE_OFFSET,
    @ColumnInfo(name = "timezone_id") val timezoneId: String? = null
)

// ── Provider ↔ EPG Source Assignment ───────────────────────────────

@Entity(
    tableName = "provider_epg_sources",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["epg_source_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["provider_id", "epg_source_id"], unique = true),
        Index(value = ["epg_source_id"])
    ]
)
data class ProviderEpgSourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "epg_source_id") val epgSourceId: Long,
    val priority: Int = 0,
    val enabled: Boolean = true
)

// ── EPG Channel (from external source) ─────────────────────────────

@Entity(
    tableName = "epg_channels",
    foreignKeys = [ForeignKey(
        entity = EpgSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["epg_source_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["epg_source_id", "xmltv_channel_id"], unique = true),
        Index(value = ["epg_source_id"]),
        Index(value = ["normalized_name"])
    ]
)
data class EpgChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "epg_source_id") val epgSourceId: Long,
    @ColumnInfo(name = "xmltv_channel_id") val xmltvChannelId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String = "",
    @ColumnInfo(name = "icon_url") val iconUrl: String? = null
)

// ── EPG Programme (from external source) ───────────────────────────

@Entity(
    tableName = "epg_programmes",
    foreignKeys = [ForeignKey(
        entity = EpgSourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["epg_source_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["epg_source_id", "xmltv_channel_id", "start_time"]),
        Index(value = ["epg_source_id", "xmltv_channel_id", "start_time", "end_time"], unique = true),
        Index(value = ["epg_source_id"]),
        Index(value = ["start_time"])
    ]
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "epg_source_id") val epgSourceId: Long,
    @ColumnInfo(name = "xmltv_channel_id") val xmltvChannelId: String,
    @ColumnInfo(name = "start_time") val startTime: Long = 0,
    @ColumnInfo(name = "end_time") val endTime: Long = 0,
    val title: String,
    val subtitle: String? = null,
    val description: String = "",
    val category: String? = null,
    val lang: String = "",
    val rating: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "episode_info") val episodeInfo: String? = null
)

// ── Channel EPG Mapping (resolved) ─────────────────────────────────

@Entity(
    tableName = "channel_epg_mappings",
    foreignKeys = [ForeignKey(
        entity = ProviderEntity::class,
        parentColumns = ["id"],
        childColumns = ["provider_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["provider_id", "provider_channel_id"], unique = true),
        Index(value = ["provider_id"])
    ]
)
data class ChannelEpgMappingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_channel_id") val providerChannelId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "source_type") val sourceType: String = "NONE",
    @ColumnInfo(name = "epg_source_id") val epgSourceId: Long? = null,
    @ColumnInfo(name = "xmltv_channel_id") val xmltvChannelId: String? = null,
    @ColumnInfo(name = "match_type") val matchType: String? = null,
    val confidence: Float = 0f,
    @ColumnInfo(name = "matched_at") val matchedAt: Long = 0L,
    @ColumnInfo(name = "failed_attempts") val failedAttempts: Int = 0,
    val source: String? = null,
    @ColumnInfo(name = "is_manual_override") val isManualOverride: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
