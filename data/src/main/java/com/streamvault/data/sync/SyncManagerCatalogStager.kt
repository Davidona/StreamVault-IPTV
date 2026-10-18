package com.streamvault.data.sync

import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series

internal class SyncManagerCatalogStager(
    private val syncCatalogStore: SyncCatalogStore,
    private val fallbackStageBatchSize: Int
) {
    suspend fun stageMovieItems(
        providerId: Long,
        items: List<Movie>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        var resolvedSessionId = sessionId
        val acceptedEntities = ArrayList<MovieEntity>(items.size)
        items.forEach { movie ->
            val streamId = movieKey(movie)
            if (streamId <= 0L || !seenStreamIds.add(streamId)) {
                return@forEach
            }
            fallbackCollector.record(movie.categoryId, movie.categoryName, movie.isAdult)
            acceptedEntities += movie.toEntity()
        }
        if (acceptedEntities.isNotEmpty()) {
            if (resolvedSessionId == null) {
                syncCatalogStore.clearProviderStaging(providerId)
                resolvedSessionId = syncCatalogStore.newSessionId()
            }
            syncCatalogStore.stageMovieBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities)
        }
        return StagedCatalogSnapshot(
            sessionId = resolvedSessionId,
            acceptedCount = acceptedEntities.size,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    suspend fun stageSeriesItems(
        providerId: Long,
        items: List<Series>,
        seenSeriesIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        var resolvedSessionId = sessionId
        val acceptedEntities = ArrayList<SeriesEntity>(items.size)
        items.forEach { item ->
            val seriesId = seriesKey(item)
            if (seriesId <= 0L || !seenSeriesIds.add(seriesId)) {
                return@forEach
            }
            fallbackCollector.record(item.categoryId, item.categoryName, item.isAdult)
            acceptedEntities += item.toEntity()
        }
        if (acceptedEntities.isNotEmpty()) {
            if (resolvedSessionId == null) {
                syncCatalogStore.clearProviderStaging(providerId)
                resolvedSessionId = syncCatalogStore.newSessionId()
            }
            syncCatalogStore.stageSeriesBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities)
        }
        return StagedCatalogSnapshot(
            sessionId = resolvedSessionId,
            acceptedCount = acceptedEntities.size,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    suspend fun stageMovieSequence(
        providerId: Long,
        items: Sequence<Movie>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        val batch = ArrayList<Movie>(fallbackStageBatchSize)
        var currentSessionId = sessionId
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = stageMovieItems(
                providerId = providerId,
                items = batch,
                seenStreamIds = seenStreamIds,
                fallbackCollector = fallbackCollector,
                sessionId = currentSessionId
            )
            currentSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        items.forEach { movie ->
            batch += movie
            if (batch.size >= fallbackStageBatchSize) {
                flushBatch()
            }
        }
        flushBatch()

        return StagedCatalogSnapshot(
            sessionId = currentSessionId,
            acceptedCount = acceptedCount,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    suspend fun stageSeriesSequence(
        providerId: Long,
        items: Sequence<Series>,
        seenSeriesIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        val batch = ArrayList<Series>(fallbackStageBatchSize)
        var currentSessionId = sessionId
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = stageSeriesItems(
                providerId = providerId,
                items = batch,
                seenSeriesIds = seenSeriesIds,
                fallbackCollector = fallbackCollector,
                sessionId = currentSessionId
            )
            currentSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        items.forEach { series ->
            batch += series
            if (batch.size >= fallbackStageBatchSize) {
                flushBatch()
            }
        }
        flushBatch()

        return StagedCatalogSnapshot(
            sessionId = currentSessionId,
            acceptedCount = acceptedCount,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    private fun movieKey(movie: Movie): Long = movie.streamId.takeIf { it > 0L } ?: movie.id

    private fun seriesKey(item: Series): Long = item.seriesId.takeIf { it > 0L } ?: item.id

    private fun channelKey(channel: Channel): Long = channel.streamId.takeIf { it > 0L } ?: channel.id

    suspend fun stageChannelItems(
        providerId: Long,
        items: List<Channel>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?,
        useLiveAdmissionCount: Boolean = false
    ): StagedCatalogSnapshot {
        var resolvedSessionId = sessionId
        val acceptedEntities = ArrayList<ChannelEntity>(items.size)
        items.forEach { channel ->
            val streamId = channelKey(channel)
            if (streamId <= 0L || !seenStreamIds.add(streamId)) {
                return@forEach
            }
            fallbackCollector.record(channel.categoryId, channel.categoryName, channel.isAdult)
            acceptedEntities += channel.toEntity()
        }
        if (acceptedEntities.isNotEmpty()) {
            if (resolvedSessionId == null) {
                syncCatalogStore.clearProviderStaging(providerId)
                resolvedSessionId = syncCatalogStore.newSessionId()
            }
            if (useLiveAdmissionCount) {
                syncCatalogStore.stageAdmittedLiveBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities, seenStreamIds.size)
            } else {
                syncCatalogStore.stageChannelBatch(providerId, requireNotNull(resolvedSessionId), acceptedEntities)
            }
        }
        return StagedCatalogSnapshot(
            sessionId = resolvedSessionId,
            acceptedCount = acceptedEntities.size,
            fallbackCategories = if (useLiveAdmissionCount) null else fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }

    suspend fun stageChannelSequence(
        providerId: Long,
        items: Sequence<Channel>,
        seenStreamIds: MutableSet<Long>,
        fallbackCollector: FallbackCategoryCollector,
        sessionId: Long?
    ): StagedCatalogSnapshot {
        val batch = ArrayList<Channel>(fallbackStageBatchSize)
        var currentSessionId = sessionId
        var acceptedCount = 0

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val staged = stageChannelItems(providerId, batch, seenStreamIds, fallbackCollector, currentSessionId)
            currentSessionId = staged.sessionId
            acceptedCount += staged.acceptedCount
            batch.clear()
        }

        items.forEach { channel ->
            batch += channel
            if (batch.size >= fallbackStageBatchSize) flushBatch()
        }
        flushBatch()

        return StagedCatalogSnapshot(
            sessionId = currentSessionId,
            acceptedCount = acceptedCount,
            fallbackCategories = fallbackCollector.entities().takeIf { it.isNotEmpty() }
        )
    }
}
