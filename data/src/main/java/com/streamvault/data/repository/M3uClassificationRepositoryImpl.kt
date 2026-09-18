package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.EpisodeDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.M3uClassificationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.EpisodeEntity
import com.streamvault.data.local.entity.M3uCategoryClassificationRuleEntity
import com.streamvault.data.local.entity.M3uClassificationOverrideEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.parser.M3uSourceIdentity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SeriesCatalogOrigin
import com.streamvault.domain.repository.M3uClassificationRepository
import com.streamvault.domain.repository.M3uCategoryItem
import com.streamvault.domain.repository.M3uClassificationTarget
import com.streamvault.domain.repository.M3uSeriesAssignment
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class M3uClassificationRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val categoryDao: CategoryDao,
    private val classificationDao: M3uClassificationDao,
    private val transactionRunner: DatabaseTransactionRunner
) : M3uClassificationRepository {

    override suspend fun getCategoryItems(
        providerId: Long,
        categoryId: Long
    ): Result<List<M3uCategoryItem>> = runOperation(providerId) {
        val categoryExists = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .any { it.categoryId == categoryId }
        require(categoryExists) { "Category no longer exists" }
        channelDao.getByCategorySync(providerId, categoryId).map { channel ->
            M3uCategoryItem(
                channelId = channel.id,
                title = channel.name,
                suggestedAssignment = inferSeriesAssignment(channel.name)
                    ?: M3uSeriesAssignment(
                        seriesName = cleanSeriesName(channel.name),
                        episodeNumber = 0,
                        episodeTitle = channel.name
                    )
            )
        }
    }

    override suspend fun classifyChannel(
        providerId: Long,
        channelId: Long,
        target: M3uClassificationTarget,
        series: M3uSeriesAssignment?
    ): Result<Unit> = runOperation(providerId) {
        val channel = channelDao.getById(channelId)
            ?: throw IllegalArgumentException("Channel no longer exists")
        classifyChannelInternal(providerId, channel, target, series)
    }

    override suspend fun classifyChannelByStream(
        providerId: Long,
        streamId: Long,
        target: M3uClassificationTarget,
        series: M3uSeriesAssignment?
    ): Result<Unit> = runOperation(providerId) {
        val channel = channelDao.getByStreamId(providerId, streamId)
            ?: throw IllegalArgumentException("Channel no longer exists")
        classifyChannelInternal(providerId, channel, target, series)
    }

    private suspend fun classifyChannelInternal(
        providerId: Long,
        channel: ChannelEntity,
        target: M3uClassificationTarget,
        series: M3uSeriesAssignment?
    ) {
        val sourceKey = M3uSourceIdentity.fromChannel(channel)
        val groupKey = M3uSourceIdentity.groupKey(channel.groupTitle)
        when (target) {
            M3uClassificationTarget.LIVE -> {
                classificationDao.upsertOverride(
                    overrideEntity(
                        providerId = providerId,
                        sourceKey = sourceKey,
                        streamId = channel.streamId,
                        target = target,
                        groupKey = groupKey
                    )
                )
            }
            M3uClassificationTarget.MOVIE -> {
                val movieId = upsertMovie(channel)
                migrateHistory(channel.id, ContentType.LIVE, movieId, ContentType.MOVIE, channel)
                classificationDao.upsertOverride(
                    overrideEntity(providerId, sourceKey, channel.streamId, target, groupKey)
                )
                channelDao.deleteById(channel.id)
            }
            M3uClassificationTarget.SERIES -> {
                val assignment = series ?: inferSeriesAssignment(channel.name)
                    ?: M3uSeriesAssignment(
                        seriesName = cleanSeriesName(channel.name),
                        episodeNumber = 0,
                        episodeTitle = channel.name
                    )
                val localSeriesId = upsertSeries(channel, assignment)
                val episodeId = upsertEpisode(channel, localSeriesId, assignment)
                migrateHistory(
                    oldContentId = channel.id,
                    oldType = ContentType.LIVE,
                    newContentId = episodeId,
                    newType = ContentType.SERIES_EPISODE,
                    channel = channel,
                    newSeriesId = localSeriesId,
                    seasonNumber = assignment.seasonNumber,
                    episodeNumber = assignment.episodeNumber
                )
                classificationDao.upsertOverride(
                    overrideEntity(
                        providerId = providerId,
                        sourceKey = sourceKey,
                        streamId = channel.streamId,
                        target = target,
                        groupKey = groupKey,
                        series = assignment
                    )
                )
                channelDao.deleteById(channel.id)
            }
        }
    }

    override suspend fun classifyCategory(
        providerId: Long,
        categoryId: Long,
        target: M3uClassificationTarget,
        seriesAssignments: Map<Long, M3uSeriesAssignment>
    ): Result<Int> = try {
        ensureM3uProvider(providerId)
        val category = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .firstOrNull { it.categoryId == categoryId }
            ?: throw IllegalArgumentException("Category no longer exists")
        val groupKey = M3uSourceIdentity.groupKey(category.name)
        transactionRunner.inTransaction {
            classificationDao.upsertCategoryRule(
                M3uCategoryClassificationRuleEntity(
                    providerId = providerId,
                    groupKey = groupKey,
                    targetType = target.name
                )
            )
            val channels = channelDao.getByCategorySync(providerId, categoryId)
            channels.forEach { channel ->
                if (target == M3uClassificationTarget.SERIES) {
                    classifyChannelInternal(providerId, channel, target, seriesAssignments[channel.id])
                } else {
                    val sourceKey = M3uSourceIdentity.fromChannel(channel)
                    classificationDao.upsertOverride(
                        overrideEntity(providerId, sourceKey, channel.streamId, target, groupKey)
                    )
                    if (target == M3uClassificationTarget.MOVIE) {
                        val movieId = upsertMovie(channel)
                        migrateHistory(channel.id, ContentType.LIVE, movieId, ContentType.MOVIE, channel)
                        channelDao.deleteById(channel.id)
                    }
                }
            }
            var restoredCount = 0
            if (target == M3uClassificationTarget.LIVE) {
                val orphanSeriesIds = mutableSetOf<Long>()
                classificationDao.getOverrides(providerId)
                    .filter { it.groupKey == groupKey && it.targetType != M3uClassificationTarget.LIVE.name }
                    .forEach { override ->
                        when (override.targetType) {
                            M3uClassificationTarget.MOVIE.name -> {
                                movieDao.getByStreamId(providerId, override.streamId)?.let { movie ->
                                    restoreMovieToLive(providerId, movie)
                                    restoredCount++
                                }
                            }
                            M3uClassificationTarget.SERIES.name -> {
                                episodeDao.getByProviderAndEpisodeId(providerId, override.streamId)?.let { episode ->
                                    orphanSeriesIds += restoreEpisodeToLive(providerId, episode)
                                    restoredCount++
                                }
                            }
                        }
                    }
                orphanSeriesIds.forEach { seriesId ->
                    if (episodeDao.getEntitiesBySeriesSync(seriesId).isEmpty()) {
                        seriesDao.deleteById(seriesId)
                    }
                }
            }
            channels.size + restoredCount
        }.let { count -> Result.success(count) }
    } catch (error: Exception) {
        Result.error("Unable to classify M3U category", error)
    }

    override suspend fun moveMovieBackToLive(providerId: Long, movieId: Long): Result<Unit> =
        runOperation(providerId) {
            val movie = movieDao.getById(movieId)
                ?: throw IllegalArgumentException("Movie no longer exists")
            restoreMovieToLive(providerId, movie)
        }

    override suspend fun moveEpisodeBackToLive(providerId: Long, episodeId: Long): Result<Unit> =
        runOperation(providerId) {
            val episode = episodeDao.getById(episodeId)
                ?: throw IllegalArgumentException("Episode no longer exists")
            val seriesId = restoreEpisodeToLive(providerId, episode)
            if (episodeDao.getEntitiesBySeriesSync(seriesId).isEmpty()) {
                seriesDao.deleteById(seriesId)
            }
        }

    override suspend fun moveSeriesBackToLive(providerId: Long, seriesId: Long): Result<Unit> =
        runOperation(providerId) {
            val series = seriesDao.getById(seriesId)
                ?: throw IllegalArgumentException("Series no longer exists")
            episodeDao.getEntitiesBySeriesSync(series.id).forEach { episode ->
                restoreEpisodeToLive(providerId, episode)
            }
            seriesDao.deleteById(series.id)
        }

    private suspend fun restoreMovieToLive(providerId: Long, movie: MovieEntity) {
        val override = classificationDao.getByStreamId(providerId, movie.streamId)
        val liveDestination = liveDestination(providerId, override?.groupKey)
        val channel = ChannelEntity(
            streamId = movie.streamId,
            name = movie.name,
            logoUrl = movie.posterUrl,
            groupTitle = liveDestination.name,
            categoryId = liveDestination.id,
            categoryName = liveDestination.name,
            streamUrl = movie.streamUrl,
            providerId = providerId,
            isAdult = movie.isAdult,
            playbackMetadataJson = movie.playbackMetadataJson
        )
        val newChannelId = channelDao.insert(channel)
        migrateHistory(movie.id, ContentType.MOVIE, newChannelId, ContentType.LIVE, channel)
        classificationDao.upsertOverride(
            (override ?: M3uClassificationOverrideEntity(
                providerId = providerId,
                sourceKey = M3uSourceIdentity.fromMovie(movie),
                streamId = movie.streamId,
                targetType = M3uClassificationTarget.LIVE.name,
                groupKey = M3uSourceIdentity.groupKey(movie.categoryName)
            )).copy(targetType = M3uClassificationTarget.LIVE.name)
        )
        movieDao.deleteById(movie.id)
    }

    private suspend fun restoreEpisodeToLive(providerId: Long, episode: EpisodeEntity): Long {
        val series = seriesDao.getById(episode.seriesId)
            ?: throw IllegalArgumentException("Series no longer exists")
        val override = classificationDao.getByStreamId(providerId, episode.episodeId)
        val liveDestination = liveDestination(providerId, override?.groupKey)
        val channel = ChannelEntity(
            streamId = episode.episodeId,
            name = episode.title,
            logoUrl = episode.coverUrl,
            groupTitle = liveDestination.name,
            categoryId = liveDestination.id,
            categoryName = liveDestination.name,
            streamUrl = episode.streamUrl,
            providerId = providerId,
            isAdult = episode.isAdult
        )
        val newChannelId = channelDao.insert(channel)
        migrateHistory(
            episode.id,
            ContentType.SERIES_EPISODE,
            newChannelId,
            ContentType.LIVE,
            channel
        )
        classificationDao.upsertOverride(
            (override ?: M3uClassificationOverrideEntity(
                providerId = providerId,
                sourceKey = M3uSourceIdentity.fromChannel(channel),
                streamId = episode.episodeId,
                targetType = M3uClassificationTarget.LIVE.name,
                groupKey = M3uSourceIdentity.groupKey(series.categoryName)
            )).copy(targetType = M3uClassificationTarget.LIVE.name)
        )
        episodeDao.deleteById(episode.id)
        return series.id
    }

    private suspend fun upsertMovie(channel: ChannelEntity): Long {
        ensureCategory(channel.providerId, ContentType.MOVIE, "Movies")
        val existing = movieDao.getByStreamId(channel.providerId, channel.streamId)
        return movieDao.insert(
            MovieEntity(
                id = existing?.id ?: 0L,
                streamId = channel.streamId,
                name = channel.name,
                posterUrl = channel.logoUrl,
                categoryId = manualCategoryId(channel.providerId, ContentType.MOVIE),
                categoryName = "Movies",
                streamUrl = channel.streamUrl,
                providerId = channel.providerId,
                isAdult = channel.isAdult,
                playbackMetadataJson = channel.playbackMetadataJson
            )
        )
    }

    private suspend fun upsertSeries(channel: ChannelEntity, assignment: M3uSeriesAssignment): Long {
        ensureCategory(channel.providerId, ContentType.SERIES, "Series")
        val seriesKey = seriesKey(channel.providerId, assignment.seriesName)
        val existing = seriesDao.getByProviderSeriesId(channel.providerId, seriesKey)
        return if (existing != null) {
            existing.id
        } else {
            seriesDao.insert(
                SeriesEntity(
                    seriesId = hashToId(seriesKey),
                    providerSeriesId = seriesKey,
                    name = assignment.seriesName,
                    posterUrl = channel.logoUrl,
                    categoryId = manualCategoryId(channel.providerId, ContentType.SERIES),
                    categoryName = "Series",
                    providerId = channel.providerId,
                    catalogOrigin = SeriesCatalogOrigin.VOD_DERIVED
                )
            )
        }
    }

    private suspend fun upsertEpisode(
        channel: ChannelEntity,
        localSeriesId: Long,
        assignment: M3uSeriesAssignment
    ): Long {
        val existing = episodeDao.getByProviderSeriesAndEpisodeId(
            channel.providerId,
            localSeriesId,
            channel.streamId
        )
        return episodeDao.insert(
            EpisodeEntity(
                id = existing?.id ?: 0L,
                episodeId = channel.streamId,
                title = assignment.episodeTitle?.takeIf(String::isNotBlank) ?: channel.name,
                episodeNumber = assignment.episodeNumber?.coerceAtLeast(0) ?: 0,
                seasonNumber = assignment.seasonNumber.coerceAtLeast(1),
                streamUrl = channel.streamUrl,
                coverUrl = channel.logoUrl,
                seriesId = localSeriesId,
                providerId = channel.providerId,
                isAdult = channel.isAdult
            )
        )
    }

    private suspend fun migrateHistory(
        oldContentId: Long,
        oldType: ContentType,
        newContentId: Long,
        newType: ContentType,
        channel: ChannelEntity,
        newSeriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) {
        playbackHistoryDao.delete(newContentId, newType.name, channel.providerId)
        favoriteDao.deleteByContent(channel.providerId, newContentId, newType.name)
        playbackHistoryDao.migrateContent(
            oldContentId = oldContentId,
            oldContentType = oldType.name,
            newContentId = newContentId,
            newContentType = newType.name,
            providerId = channel.providerId,
            newSeriesId = newSeriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
        favoriteDao.migrateContent(
            providerId = channel.providerId,
            oldContentId = oldContentId,
            oldContentType = oldType.name,
            newContentId = newContentId,
            newContentType = newType.name
        )
    }

    private suspend fun ensureM3uProvider(providerId: Long) {
        require(providerDao.getById(providerId)?.type == ProviderType.M3U) {
            "M3U classification is unavailable for this provider"
        }
    }

    private suspend fun <T> runOperation(providerId: Long, block: suspend () -> T): Result<T> = try {
        ensureM3uProvider(providerId)
        transactionRunner.inTransaction { block() }.let { value -> Result.success(value) }
    } catch (error: Exception) {
        Result.error("Unable to update M3U classification", error)
    }

    private suspend fun ensureCategory(providerId: Long, type: ContentType, name: String) {
        val exists = categoryDao.getByProviderAndTypeSync(providerId, type.name)
            .any { it.categoryId == manualCategoryId(providerId, type) }
        if (!exists) {
            categoryDao.insertAll(
                listOf(
                    CategoryEntity(
                        categoryId = manualCategoryId(providerId, type),
                        name = name,
                        type = type,
                        providerId = providerId,
                        syncFingerprint = "m3u-manual-${type.name.lowercase(Locale.ROOT)}"
                    )
                )
            )
        }
    }

    private fun overrideEntity(
        providerId: Long,
        sourceKey: String,
        streamId: Long,
        target: M3uClassificationTarget,
        groupKey: String,
        series: M3uSeriesAssignment? = null
    ) = M3uClassificationOverrideEntity(
        providerId = providerId,
        sourceKey = sourceKey,
        streamId = streamId,
        targetType = target.name,
        groupKey = groupKey,
        seriesKey = series?.let { seriesKey(providerId, it.seriesName) },
        seriesName = series?.seriesName,
        seasonNumber = series?.seasonNumber,
        episodeNumber = series?.episodeNumber,
        episodeTitle = series?.episodeTitle
    )

    private fun cleanSeriesName(title: String): String = title
        .replace(Regex("(?i)\\b(?:s\\d+e\\d+|\\d+x\\d+|season\\s+\\d+\\s+episode\\s+\\d+|phần\\s+\\d+\\s*-\\s*tập\\s+\\d+)\\b"), "")
        .replace(Regex("(?i)\\b(?:episode|ep|tập)\\s+(?:cuối|\\d+)\\b"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .ifBlank { title.trim() }

    private fun inferSeriesAssignment(title: String): M3uSeriesAssignment? {
        val patterns = listOf(
            Regex("(?i)^(.*?)[\\s._-]*s(\\d{1,2})[\\s._-]*e(\\d{1,3}).*$"),
            Regex("(?i)^(.*?)[\\s._-]*(\\d{1,2})x(\\d{1,3}).*$"),
            Regex("(?i)^(.*?)\\s+season\\s+(\\d{1,2})\\s+episode\\s+(\\d{1,3}).*$"),
            Regex("(?i)^(.*?)\\s+phần\\s+(\\d{1,2})\\s*-\\s*tập\\s+(\\d{1,3}).*$"),
            Regex("(?i)^(.*?)[\\s._-]*(?:episode|ep|tập)\\s+(\\d{1,3}).*$")
        )
        patterns.forEach { pattern ->
            val match = pattern.matchEntire(title.trim()) ?: return@forEach
            val groups = match.groupValues
            val hasSeason = groups.size >= 4 && groups[2].toIntOrNull() != null && groups[3].toIntOrNull() != null
            val season = if (hasSeason) groups[2].toInt() else 1
            val episode = if (hasSeason) groups[3].toIntOrNull() else groups.getOrNull(2)?.toIntOrNull()
            if (episode != null) {
                return M3uSeriesAssignment(
                    seriesName = cleanSeriesName(groups[1]),
                    seasonNumber = season.coerceAtLeast(1),
                    episodeNumber = episode.coerceAtLeast(1),
                    episodeTitle = title.trim()
                )
            }
        }
        // Keep the item in the series projection, but mark its episode number as unresolved
        // rather than inventing E1. The original title is retained for manual correction.
        return M3uSeriesAssignment(
            seriesName = cleanSeriesName(title),
            episodeNumber = 0,
            episodeTitle = title.trim()
        )
    }

    private fun seriesKey(providerId: Long, name: String): String =
        "m3u-series:${hashToId("$providerId|${M3uSourceIdentity.groupKey(name)}")}" 

    private fun manualCategoryId(providerId: Long, type: ContentType): Long =
        hashToId("m3u-manual-category:$providerId:${type.name}")

    private suspend fun liveDestination(providerId: Long, groupKey: String?): LiveDestination {
        val category = categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name)
            .firstOrNull { category ->
                groupKey?.isNotBlank() == true &&
                    M3uSourceIdentity.groupKey(category.name) == groupKey
            }
        return if (category != null) {
            LiveDestination(category.categoryId, category.name)
        } else {
            val uncategorizedId = hashToId("$providerId/LIVE/Uncategorized")
            categoryDao.insertAll(
                listOf(
                    CategoryEntity(
                        categoryId = uncategorizedId,
                        name = "Uncategorized",
                        type = ContentType.LIVE,
                        providerId = providerId
                    )
                )
            )
            LiveDestination(
                id = uncategorizedId,
                name = "Uncategorized"
            )
        }
    }

    private data class LiveDestination(val id: Long, val name: String)

    private fun hashToId(value: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        var result = 0L
        repeat(8) { index -> result = (result shl 8) or (bytes[index].toLong() and 0xff) }
        return (result and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
