package com.streamvault.data.mapper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.entity.*
import com.streamvault.data.remote.stalker.StalkerPlaybackDescriptor
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider

private val qualityOptionsGson = Gson()
private val channelQualityOptionsType = object : TypeToken<List<ChannelQualityOption>>() {}.type
private val providerAllowedOutputFormatsType = object : TypeToken<List<String>>() {}.type

// ── Provider ───────────────────────────────────────────────────────

fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    type = type,
    serverUrl = "",
    isActive = isActive,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

fun Provider.toEntity() = ProviderEntity(
    id = id,
    name = name,
    type = type,
    isActive = isActive,
    status = status,
    lastSyncedAt = lastSyncedAt,
    createdAt = createdAt
)

fun CombinedM3uProfileEntity.toDomain(
    members: List<CombinedM3uProfileMember> = emptyList()
) = CombinedM3uProfile(
    id = id,
    name = name,
    enabled = enabled,
    members = members,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CombinedM3uProfileMemberWithProvider.toDomain() = CombinedM3uProfileMember(
    id = id,
    profileId = profileId,
    providerId = providerId,
    priority = priority,
    enabled = enabled,
    providerName = providerName
)

// ── Channel ────────────────────────────────────────────────────────

fun ChannelEntity.toDomain(): Channel {
    val qualityOptions = decodeQualityOptions(qualityOptionsJson)
    val stalkerToken = StalkerUrlFactory.parseInternalStreamUrl(streamUrl)
    val stalkerAlternateStreams = stalkerToken?.playbackDescriptor
        ?.candidates
        ?.drop(1)
        ?.mapIndexed { index, variant ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = stalkerToken.providerId,
                kind = stalkerToken.kind,
                itemId = stalkerToken.itemId,
                cmd = variant.cmd,
                containerExtension = stalkerToken.containerExtension,
                seriesNumber = stalkerToken.seriesNumber,
                playbackDescriptor = StalkerPlaybackDescriptor(
                    primaryMode = variant.playbackMode,
                    candidates = listOf(
                        variant.copy(priority = 0)
                    ),
                    capabilities = stalkerToken.playbackDescriptor.capabilities
                )
            )
        }
        .orEmpty()
    return Channel(
        id = id,
        name = name,
        logoUrl = logoUrl,
        groupTitle = groupTitle,
        categoryId = categoryId,
        categoryName = categoryName,
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
        number = number,
        catchUpSupported = catchUpSupported,
        catchUpDays = catchUpDays,
        catchUpSource = catchUpSource,
        catchUpType = catchUpType,
        catchUpCorrectionSeconds = catchUpCorrectionSeconds,
        providerId = providerId,
        isAdult = isAdult,
        isUserProtected = isUserProtected,
        logicalGroupId = logicalGroupId,
        errorCount = errorCount,
        qualityOptions = qualityOptions,
        alternativeStreams = (
            qualityOptions.mapNotNull { it.url }.filter { it != streamUrl } +
                stalkerAlternateStreams.filter { it != streamUrl }
            ).distinct(),
        streamId = streamId
    )
}

fun Channel.toEntity(): ChannelEntity {
    val xtreamLiveToken = XtreamUrlFactory.parseInternalStreamUrl(streamUrl)
        ?.takeIf { it.kind == XtreamStreamKind.LIVE }
    val persistedStreamUrl = xtreamLiveToken?.let { token ->
        XtreamUrlFactory.buildInternalStreamUrl(
            providerId = token.providerId,
            kind = token.kind,
            streamId = token.streamId,
            containerExtension = token.containerExtension
        )
    } ?: streamUrl
    return ChannelEntity(
        id = id,
        streamId = streamId.takeIf { it > 0 } ?: id,
        name = name,
        logoUrl = logoUrl,
        groupTitle = groupTitle,
        categoryId = categoryId,
        categoryName = categoryName,
        streamUrl = persistedStreamUrl,
        epgChannelId = epgChannelId,
        number = number,
        catchUpSupported = catchUpSupported,
        catchUpDays = catchUpDays,
        catchUpSource = catchUpSource.takeUnless { xtreamLiveToken != null },
        catchUpType = catchUpType.takeUnless { xtreamLiveToken != null },
        catchUpCorrectionSeconds = catchUpCorrectionSeconds,
        providerId = providerId,
        isAdult = isAdult,
        isUserProtected = isUserProtected,
        logicalGroupId = logicalGroupId,
        errorCount = errorCount,
        qualityOptionsJson = if (xtreamLiveToken == null) encodeQualityOptions(qualityOptions) else null
    )
}

// ── Movie ──────────────────────────────────────────────────────────

fun MovieEntity.toDomain() = Movie(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    streamId = streamId,
    addedAt = addedAt
)

fun MovieBrowseEntity.toDomain() = Movie(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = null,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = null,
    cast = null,
    director = null,
    genre = genre,
    releaseDate = releaseDate,
    duration = null,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = null,
    youtubeTrailer = null,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    streamId = streamId,
    addedAt = addedAt
)

fun Movie.toEntity() = MovieEntity(
    id = id,
    streamId = streamId.takeIf { it > 0 } ?: id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    year = year,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    addedAt = addedAt
)

// ── Series ─────────────────────────────────────────────────────────

fun SeriesEntity.toDomain() = Series(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    seriesId = seriesId,
    providerSeriesId = providerSeriesId,
    catalogOrigin = catalogOrigin,
    episodePlaybackTemplateUrl = episodePlaybackTemplateUrl
)

fun SeriesBrowseEntity.toDomain() = Series(
    id = id,
    name = name,
    posterUrl = posterUrl,
    backdropUrl = null,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = null,
    cast = null,
    director = null,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = null,
    episodeRunTime = null,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    seriesId = seriesId,
    providerSeriesId = providerSeriesId,
    catalogOrigin = catalogOrigin,
    episodePlaybackTemplateUrl = episodePlaybackTemplateUrl
)

fun Series.toEntity() = SeriesEntity(
    id = id,
    seriesId = seriesId.takeIf { it > 0 } ?: id,
    providerSeriesId = providerSeriesId?.takeIf { it.isNotBlank() }
        ?: seriesId.takeIf { it > 0 }?.toString()
        ?: id.takeIf { it > 0 }?.toString(),
    name = name,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    categoryId = categoryId,
    categoryName = categoryName,
    plot = plot,
    cast = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    tmdbId = tmdbId,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    lastModified = lastModified,
    providerId = providerId,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    catalogOrigin = catalogOrigin,
    episodePlaybackTemplateUrl = episodePlaybackTemplateUrl
)

// ── Episode ────────────────────────────────────────────────────────

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    episodeId = episodeId
)

fun EpisodeBrowseEntity.toDomain() = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected,
    episodeId = episodeId
)

fun Episode.toEntity() = EpisodeEntity(
    id = id,
    episodeId = episodeId.takeIf { it > 0 } ?: id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    streamUrl = streamUrl,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
    plot = plot,
    duration = duration,
    durationSeconds = durationSeconds,
    rating = rating,
    releaseDate = releaseDate,
    seriesId = seriesId,
    providerId = providerId,
    watchProgress = watchProgress,
    lastWatchedAt = lastWatchedAt,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Category ───────────────────────────────────────────────────────

fun CategoryEntity.toDomain() = com.streamvault.domain.model.Category(
    id = categoryId,
    roomId = id,
    name = name,
    parentId = parentId,
    type = type,
    providerOrder = providerOrder,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

fun com.streamvault.domain.model.Category.toEntity(providerId: Long) = CategoryEntity(
    categoryId = id,
    name = name,
    parentId = parentId,
    type = type,
    providerId = providerId,
    providerOrder = providerOrder,
    isAdult = isAdult,
    isUserProtected = isUserProtected
)

// ── Program ────────────────────────────────────────────────────────

fun ProgramEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

fun ProgramBrowseEntity.toDomain() = Program(
    id = id,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

fun Program.toEntity() = ProgramEntity(
    id = 0,
    channelId = channelId,
    title = title,
    description = description,
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    genre = genre,
    category = category,
    hasArchive = hasArchive,
    providerId = providerId
)

// ── Favorite ───────────────────────────────────────────────────────

fun FavoriteEntity.toDomain() = Favorite(
    id = id,
    providerId = providerId,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

fun Favorite.toEntity() = FavoriteEntity(
    id = id,
    providerId = providerId,
    contentId = contentId,
    contentType = contentType,
    position = position,
    groupId = groupId,
    addedAt = addedAt
)

// ── Virtual Group ──────────────────────────────────────────────────

fun VirtualGroupEntity.toDomain() = VirtualGroup(
    id = id,
    providerId = providerId,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

fun VirtualGroup.toEntity() = VirtualGroupEntity(
    id = id,
    providerId = providerId,
    name = name,
    iconEmoji = iconEmoji,
    position = position,
    createdAt = createdAt,
    contentType = contentType
)

// ── Playback History ───────────────────────────────────────────────

fun PlaybackHistoryEntity.toDomain() = PlaybackHistory(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.toPlaybackWatchedStatus(),
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

fun PlaybackHistoryLiteEntity.toDomain() = PlaybackHistory(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.toPlaybackWatchedStatus(),
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

fun PlaybackHistory.toEntity() = PlaybackHistoryEntity(
    id = id,
    contentId = contentId,
    contentType = contentType,
    providerId = providerId,
    title = title,
    posterUrl = posterUrl,
    streamUrl = streamUrl,
    resumePositionMs = resumePositionMs,
    totalDurationMs = totalDurationMs,
    lastWatchedAt = lastWatchedAt,
    watchCount = watchCount,
    watchedStatus = watchedStatus.name,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber
)

// ── Sync Metadata ──────────────────────────────────────────────────

fun SyncMetadataEntity.toDomain() = SyncMetadata(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastLiveSuccess = lastLiveSuccess,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastSeriesSuccess = lastSeriesSuccess,
    lastEpgSync = lastEpgSync,
    lastEpgSuccess = lastEpgSuccess,
    lastMovieAttempt = lastMovieAttempt,
    lastMovieSuccess = lastMovieSuccess,
    lastMoviePartial = lastMoviePartial,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus,
    movieSyncMode = runCatching { com.streamvault.domain.model.VodSyncMode.valueOf(movieSyncMode) }
        .getOrDefault(com.streamvault.domain.model.VodSyncMode.UNKNOWN),
    movieWarningsCount = movieWarningsCount,
    movieCatalogStale = movieCatalogStale,
    liveAvoidFullUntil = liveAvoidFullUntil,
    movieAvoidFullUntil = movieAvoidFullUntil,
    seriesAvoidFullUntil = seriesAvoidFullUntil,
    liveSequentialFailuresRemembered = liveSequentialFailuresRemembered,
    liveHealthySyncStreak = liveHealthySyncStreak,
    movieParallelFailuresRemembered = movieParallelFailuresRemembered,
    movieHealthySyncStreak = movieHealthySyncStreak,
    seriesSequentialFailuresRemembered = seriesSequentialFailuresRemembered,
    seriesHealthySyncStreak = seriesHealthySyncStreak
)

fun SyncMetadata.toEntity() = SyncMetadataEntity(
    providerId = providerId,
    lastLiveSync = lastLiveSync,
    lastLiveSuccess = lastLiveSuccess,
    lastMovieSync = lastMovieSync,
    lastSeriesSync = lastSeriesSync,
    lastSeriesSuccess = lastSeriesSuccess,
    lastEpgSync = lastEpgSync,
    lastEpgSuccess = lastEpgSuccess,
    lastMovieAttempt = lastMovieAttempt,
    lastMovieSuccess = lastMovieSuccess,
    lastMoviePartial = lastMoviePartial,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    epgCount = epgCount,
    lastSyncStatus = lastSyncStatus,
    movieSyncMode = movieSyncMode.name,
    movieWarningsCount = movieWarningsCount,
    movieCatalogStale = movieCatalogStale,
    liveAvoidFullUntil = liveAvoidFullUntil,
    movieAvoidFullUntil = movieAvoidFullUntil,
    seriesAvoidFullUntil = seriesAvoidFullUntil,
    liveSequentialFailuresRemembered = liveSequentialFailuresRemembered,
    liveHealthySyncStreak = liveHealthySyncStreak,
    movieParallelFailuresRemembered = movieParallelFailuresRemembered,
    movieHealthySyncStreak = movieHealthySyncStreak,
    seriesSequentialFailuresRemembered = seriesSequentialFailuresRemembered,
    seriesHealthySyncStreak = seriesHealthySyncStreak
)

private fun encodeQualityOptions(options: List<ChannelQualityOption>): String? {
    if (options.isEmpty()) {
        return null
    }
    return qualityOptionsGson.toJson(options, channelQualityOptionsType)
}

private fun decodeQualityOptions(encoded: String?): List<ChannelQualityOption> {
    if (encoded.isNullOrBlank()) {
        return emptyList()
    }
    return runCatching {
        qualityOptionsGson.fromJson<List<ChannelQualityOption>>(encoded, channelQualityOptionsType).orEmpty()
    }.getOrDefault(emptyList())
}

private fun decodeAllowedOutputFormats(encoded: String?): List<String> {
    if (encoded.isNullOrBlank()) {
        return emptyList()
    }
    return runCatching {
        qualityOptionsGson.fromJson<List<String>>(encoded, providerAllowedOutputFormatsType)
            .orEmpty()
            .mapNotNull { value -> value.trim().lowercase().takeIf { it.isNotEmpty() } }
            .distinct()
    }.getOrDefault(emptyList())
}

private fun encodeAllowedOutputFormats(formats: List<String>): String {
    val normalized = formats
        .mapNotNull { value -> value.trim().lowercase().takeIf { it.isNotEmpty() } }
        .distinct()
    return qualityOptionsGson.toJson(normalized, providerAllowedOutputFormatsType)
}

private fun String?.toPlaybackWatchedStatus(): PlaybackWatchedStatus =
    runCatching {
        this?.let(PlaybackWatchedStatus::valueOf)
    }.getOrNull() ?: PlaybackWatchedStatus.IN_PROGRESS

// ── EPG Source ─────────────────────────────────────────────────────

fun EpgSourceEntity.toDomain() = com.streamvault.domain.model.EpgSource(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    lastRefreshAt = lastRefreshAt,
    lastSuccessAt = lastSuccessAt,
    lastError = lastError,
    priority = priority,
    createdAt = createdAt,
    updatedAt = updatedAt,
    etag = etag,
    lastModifiedHeader = lastModifiedHeader,
    timezonePolicy = timezonePolicy,
    timezoneId = timezoneId
)

fun com.streamvault.domain.model.EpgSource.toEntity() = EpgSourceEntity(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    lastRefreshAt = lastRefreshAt,
    lastSuccessAt = lastSuccessAt,
    lastError = lastError,
    priority = priority,
    createdAt = createdAt,
    updatedAt = updatedAt,
    etag = etag,
    lastModifiedHeader = lastModifiedHeader,
    timezonePolicy = timezonePolicy,
    timezoneId = timezoneId
)

// ── Provider EPG Source Assignment ─────────────────────────────────

fun com.streamvault.data.local.dao.ProviderEpgSourceWithDetails.toDomain() =
    com.streamvault.domain.model.ProviderEpgSourceAssignment(
        id = id,
        providerId = providerId,
        epgSourceId = epgSourceId,
        priority = priority,
        enabled = enabled,
        epgSourceName = epgSourceName,
        epgSourceUrl = epgSourceUrl
    )

fun com.streamvault.domain.model.ProviderEpgSourceAssignment.toEntity() = ProviderEpgSourceEntity(
    id = id,
    providerId = providerId,
    epgSourceId = epgSourceId,
    priority = priority,
    enabled = enabled
)

// ── EPG Channel ────────────────────────────────────────────────────

fun EpgChannelEntity.toDomain() = com.streamvault.domain.model.EpgChannelInfo(
    id = id,
    epgSourceId = epgSourceId,
    xmltvChannelId = xmltvChannelId,
    displayName = displayName,
    normalizedName = normalizedName,
    iconUrl = iconUrl
)

// ── EPG Programme → Program ────────────────────────────────────────

fun EpgProgrammeEntity.toDomainProgram(providerId: Long = 0L) = Program(
    id = id,
    channelId = xmltvChannelId,
    title = title,
    description = buildString {
        subtitle?.let { append(it); append("\n") }
        append(description)
    }.trim(),
    startTime = startTime,
    endTime = endTime,
    lang = lang,
    rating = rating,
    imageUrl = imageUrl,
    category = category,
    providerId = providerId
)

// ── Channel EPG Mapping ────────────────────────────────────────────

fun ChannelEpgMappingEntity.toDomain() = com.streamvault.domain.model.ChannelEpgMapping(
    id = id,
    providerChannelId = providerChannelId,
    providerId = providerId,
    sourceType = runCatching { com.streamvault.domain.model.EpgSourceType.valueOf(sourceType) }
        .getOrDefault(com.streamvault.domain.model.EpgSourceType.NONE),
    epgSourceId = epgSourceId,
    xmltvChannelId = xmltvChannelId,
    matchType = matchType?.let {
        runCatching { com.streamvault.domain.model.EpgMatchType.valueOf(it) }.getOrNull()
    },
    confidence = confidence,
    matchedAt = matchedAt,
    failedAttempts = failedAttempts,
    source = source,
    isManualOverride = isManualOverride,
    updatedAt = updatedAt
)

fun com.streamvault.domain.model.ChannelEpgMapping.toEntity() = ChannelEpgMappingEntity(
    id = id,
    providerChannelId = providerChannelId,
    providerId = providerId,
    sourceType = sourceType.name,
    epgSourceId = epgSourceId,
    xmltvChannelId = xmltvChannelId,
    matchType = matchType?.name,
    confidence = confidence,
    matchedAt = matchedAt,
    failedAttempts = failedAttempts,
    source = source,
    isManualOverride = isManualOverride,
    updatedAt = updatedAt
)
