package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import android.util.Log
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelBrowseEntity
import com.streamvault.data.local.entity.CategoryCount
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.util.rankSearchResults
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.LiveChannelVariant
import com.streamvault.domain.model.LiveChannelVariantAttributes
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.util.ChannelNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val favoriteDao: FavoriteDao,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: com.streamvault.domain.manager.ParentalControlManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : ChannelRepository {
    private companion object {
        const val TAG = "ChannelRepository"
        const val GLOBAL_SEARCH_LIMIT = 500
        const val CATEGORY_SEARCH_LIMIT = 300
        const val MIN_SEARCH_QUERY_LENGTH = 2
    }

    private data class ChannelPresentationSettings(
        val numberingMode: ChannelNumberingMode,
        val groupingMode: LiveChannelGroupingMode,
        val labelMode: GroupedChannelLabelMode,
        val preferenceMode: LiveVariantPreferenceMode,
        val preferredVariants: Map<String, Long>,
        val observedQualities: Map<Long, LiveChannelObservedQuality>
    )

    private val channelNumberComparator = compareBy<Channel>(
        { it.number <= 0 },
        { it.number.takeIf { number -> number > 0 } ?: Int.MAX_VALUE },
        { it.name.lowercase() }
    )

    override fun getChannels(providerId: Long): Flow<List<Channel>> =
        observeChannels(channelDao.getByProvider(providerId), providerId)

    override fun getChannelCount(providerId: Long): Flow<Int> =
        preferencesRepository.hideDecorativeLiveRows.flatMapLatest { hideDecorativeRows ->
            if (hideDecorativeRows) {
                channelDao.getCount(providerId)
            } else {
                channelDao.getRawCount(providerId)
            }
        }

    override fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)

    override fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>> =
        observeChannels(channelFlowPage(providerId, categoryId, limit), providerId)

    override fun searchChannelsByCategoryPaged(
        providerId: Long,
        categoryId: Long,
        query: String,
        limit: Int
    ): Flow<List<Channel>> = searchChannelEntities(providerId, categoryId, query, limit)
        .let { flow ->
            observeChannels(flow, providerId)
        }

    override fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun getChannelsWithoutErrors(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlowWithoutErrors(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun getChannelsWithoutErrorsPage(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<Channel>> =
        observeChannels(channelFlowWithoutErrorsPage(providerId, categoryId, limit), providerId)
            .map(::sortChannelsByNumber)

    override suspend fun getChannelsByCategoryPageOffset(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): List<Channel> = channelPageOneShot(providerId, categoryId, limit, offset, withoutErrors = false)

    override suspend fun getChannelsWithoutErrorsPageOffset(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): List<Channel> = channelPageOneShot(providerId, categoryId, limit, offset, withoutErrors = true)

    private suspend fun channelPageOneShot(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int,
        withoutErrors: Boolean
    ): List<Channel> = withContext(Dispatchers.Default) {
        val settings = currentPresentationSettings()
        val level = preferencesRepository.parentalControlLevel.first()
        val unlockedCats = parentalControlManager.unlockedCategoriesForProvider(providerId).first()
        val hiddenIds = preferencesRepository.getHiddenChannelIds(providerId).first()
        val hideDecorativeRows = preferencesRepository.hideDecorativeLiveRows.first()
        val entities = if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            if (withoutErrors) channelDao.getByProviderWithoutErrorsBrowsePageOffset(providerId, limit, offset)
            else channelDao.getByProviderBrowsePageOffset(providerId, limit, offset)
        } else {
            if (withoutErrors) channelDao.getByCategoryWithoutErrorsBrowsePageOffset(providerId, categoryId, limit, offset)
            else channelDao.getByCategoryBrowsePageOffset(providerId, categoryId, limit, offset)
        }
        val filtered = applyVisibilityFilter(entities, level, unlockedCats, hideDecorativeRows)
            .filterNot { it.id in hiddenIds }
        val presented = buildPresentedChannels(filtered, settings, unlockedCats)
        applyNumbering(presented, settings.numberingMode, offset)
    }

    override fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>> =
        searchChannelEntities(providerId, categoryId, query, CATEGORY_SEARCH_LIMIT)
            .let { flow -> observeChannels(flow, providerId) }

    // Scope for shareIn-cached flows; lives as long as this @Singleton repository.
    private val sharedFlowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Stale-while-revalidate cache for the category list. Computing it runs a
    // GROUP BY aggregate over the whole channels table (50k+ rows for large
    // providers), so the result is kept hot per provider: re-subscribers get the
    // last list instantly via replay while Room re-runs the query and emits
    // fresh data only if something changed.
    private val categoriesFlowCache = ConcurrentHashMap<Long, Flow<List<Category>>>()

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        categoriesFlowCache.computeIfAbsent(providerId) {
            buildCategoriesFlow(providerId).distinctUntilChanged().shareIn(
                scope = sharedFlowScope,
                // Stop the upstream query the moment the last subscriber leaves:
                // during catalog syncs every table write invalidates this heavy
                // aggregate, and a keep-alive would re-run it continuously in the
                // background, starving other screens' queries. The replay cache
                // survives the stop, so re-entering the screen still renders the
                // last list instantly while a fresh query runs.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L),
                replay = 1
            )
        }

    private fun buildCategoriesFlow(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.LIVE.name),
            decorativeAwareCategoryCountFlow(providerId),
            preferencesRepository.parentalControlLevel,
            parentalControlManager.unlockedCategoriesForProvider(providerId)
        ) { categories: List<CategoryEntity>, categoryCounts: List<CategoryCount>, level: Int, unlockedCats: Set<Long> ->
            val countMap = categoryCounts.associate { count -> count.categoryId to count.item_count }
            val countedCategories = categories.map { entity ->
                entity.toDomain().copy(count = countMap[entity.categoryId] ?: 0)
            }
            val visibleCategories = if (level >= 3) {
                countedCategories.filter { category -> !category.isAdult && !category.isUserProtected }
            } else {
                countedCategories
            }
            val filteredCategories = visibleCategories.map { category ->
                if (level < 3 && unlockedCats.contains(category.id)) {
                    category.copy(isUserProtected = false)
                } else {
                    category
                }
            }

            val allChannelsCategory = Category(
                id = ChannelRepository.ALL_CHANNELS_ID,
                name = "All Channels",
                type = ContentType.LIVE,
                count = filteredCategories.sumOf(Category::count)
            )

            listOf(allChannelsCategory) + filteredCategories
        }.flowOn(Dispatchers.Default)

    override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> {
        val favoritesFlow = favoriteDao.getAllByType(providerId, ContentType.LIVE.name)
        return searchChannelEntities(providerId, ChannelRepository.ALL_CHANNELS_ID, query, GLOBAL_SEARCH_LIMIT)
            .let { flow -> observeChannels(flow, providerId) }
            .map { channels ->
                channels.rankSearchResults(query) { channel ->
                    buildString {
                        append(channel.name)
                        append(' ')
                        append(channel.canonicalName)
                        channel.currentVariant?.originalName?.let {
                            append(' ')
                            append(it)
                        }
                    }
                }
            }
            .combine(favoritesFlow) { channels, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                channels.map { channel ->
                    if (channel.allVariantRawIds().any(favoriteIds::contains)) {
                        channel.copy(isFavorite = true)
                    } else {
                        channel
                    }
                }
            }
            .flowOn(Dispatchers.Default)
    }

    // Note: getChannel(channelId) returns the channel even when hidden — callers
    // look up a specific channel by id (e.g. HiddenChannelsDialog resolves the
    // hidden set back to Channel objects), they must not be gated by visibility.
    override suspend fun getChannel(channelId: Long): Channel? {
        val entity = channelDao.getBrowseById(channelId) ?: return null
        val settings = currentPresentationSettings()
        val observation = settings.observedQualities[channelId]
        if (settings.groupingMode == LiveChannelGroupingMode.RAW_VARIANTS || entity.logicalGroupId.isBlank()) {
            return entity.toPresentedRawChannel(observation)
        }
        val groupedEntities = channelDao.getByLogicalGroupId(entity.providerId, entity.logicalGroupId)
            .ifEmpty { listOf(entity) }
        return buildGroupedChannels(groupedEntities, settings).firstOrNull()
    }

    override suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveAndCommitMetadata(
            url = channel.streamUrl,
            fallbackProviderId = channel.providerId,
            fallbackStreamId = channel.streamId.takeIf { it > 0L }
                ?: channel.epgChannelId?.toLongOrNull()
                ?: channel.id.takeIf { it > 0L },
            fallbackContentType = ContentType.LIVE,
            preferStableUrl = preferStableUrl
        )?.let { resolvedStream ->
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = channel.name,
                    headers = resolvedStream.headers,
                    userAgent = resolvedStream.userAgent,
                    playbackTransportPolicy = resolvedStream.playbackTransportPolicy,
                    allowInvalidSsl = resolvedStream.allowInvalidSsl,
                    proxyHost = resolvedStream.proxyHost,
                    proxyPort = resolvedStream.proxyPort,
                    streamType = StreamType.fromContainerExtension(resolvedStream.containerExtension),
                    containerExtension = resolvedStream.containerExtension,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for channel: ${channel.name}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for channel: ${channel.name}", e)
    }

    override suspend fun refreshChannels(providerId: Long): Result<Unit> =
        Result.success(Unit)

    override fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return channelDao.getByIds(ids).flatMapLatest { requestedEntities ->
            if (requestedEntities.isEmpty()) {
                return@flatMapLatest flowOf(emptyList())
            }
            val logicalGroupIds = requestedEntities.mapNotNull { entity ->
                entity.logicalGroupId.takeIf(String::isNotBlank)
            }.distinct()
            val entityPoolFlow = if (logicalGroupIds.isEmpty()) {
                flowOf(requestedEntities)
            } else {
                channelDao.getByLogicalGroupIds(logicalGroupIds)
            }
            combine(
                flowOf(requestedEntities),
                entityPoolFlow,
                preferencesRepository.parentalControlLevel,
                currentPresentationSettingsFlow(),
                preferencesRepository.hideDecorativeLiveRows
            ) { requested, entityPool, level, settings, hideDecorativeRows ->
                val filteredRequested = applyVisibilityFilter(requested, level, emptySet(), hideDecorativeRows)
                val filteredPool = applyVisibilityFilter(entityPool, level, emptySet(), hideDecorativeRows)
                buildChannelsForRequestedIds(
                    requestedIds = ids,
                    requestedEntities = filteredRequested,
                    entityPool = filteredPool.ifEmpty { filteredRequested },
                    settings = settings
                )
            }.flowOn(Dispatchers.Default)
        }
    }

    override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.incrementErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to increment channel error count", e)
    }

    override suspend fun resetChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.resetErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reset channel error count", e)
    }

    private fun observeChannels(
        source: Flow<List<ChannelBrowseEntity>>,
        providerId: Long
    ): Flow<List<Channel>> = combine(
        combine(
            source,
            preferencesRepository.parentalControlLevel,
            parentalControlManager.unlockedCategoriesForProvider(providerId),
            currentPresentationSettingsFlow(),
            preferencesRepository.getHiddenChannelIds(providerId)
        ) { entities, level, unlockedCats, settings, hiddenIds ->
            arrayOf(entities, level, unlockedCats, settings, hiddenIds)
        },
        preferencesRepository.hideDecorativeLiveRows
    ) { values, hideDecorativeRows ->
        @Suppress("UNCHECKED_CAST")
        val entities = values[0] as List<ChannelBrowseEntity>
        val level = values[1] as Int
        val unlockedCats = values[2] as Set<Long>
        val settings = values[3] as ChannelPresentationSettings
        val hiddenIds = values[4] as Set<Long>
        val filtered = applyVisibilityFilter(entities, level, unlockedCats, hideDecorativeRows)
            .filterNot { it.id in hiddenIds }
        applyNumbering(buildPresentedChannels(filtered, settings, unlockedCats), settings.numberingMode)
    }.flowOn(Dispatchers.Default)

    private fun decorativeAwareCategoryCountFlow(providerId: Long): Flow<List<CategoryCount>> =
        combine(
            preferencesRepository.liveChannelGroupingMode,
            preferencesRepository.hideDecorativeLiveRows
        ) { groupingMode, hideDecorativeRows ->
            groupingMode to hideDecorativeRows
        }.flatMapLatest { (groupingMode, hideDecorativeRows) ->
            when {
                groupingMode == LiveChannelGroupingMode.GROUPED && hideDecorativeRows ->
                    channelDao.getGroupedCategoryCounts(providerId)
                groupingMode == LiveChannelGroupingMode.GROUPED ->
                    channelDao.getRawGroupedCategoryCounts(providerId)
                hideDecorativeRows ->
                    channelDao.getCategoryCounts(providerId)
                else ->
                    channelDao.getRawCategoryCounts(providerId)
            }
        }

    private fun currentPresentationSettingsFlow(): Flow<ChannelPresentationSettings> =
        combine(
            combine(
                preferencesRepository.liveChannelNumberingMode,
                preferencesRepository.liveChannelGroupingMode,
                preferencesRepository.groupedChannelLabelMode,
                preferencesRepository.liveVariantPreferenceMode
            ) { numberingMode, groupingMode, labelMode, preferenceMode ->
                arrayOf(numberingMode, groupingMode, labelMode, preferenceMode)
            },
            preferencesRepository.liveVariantSelections,
            preferencesRepository.liveVariantObservations
        ) { settingsArray, preferredVariants, observedQualities ->
            ChannelPresentationSettings(
                numberingMode = settingsArray[0] as ChannelNumberingMode,
                groupingMode = settingsArray[1] as LiveChannelGroupingMode,
                labelMode = settingsArray[2] as GroupedChannelLabelMode,
                preferenceMode = settingsArray[3] as LiveVariantPreferenceMode,
                preferredVariants = preferredVariants,
                observedQualities = observedQualities
            )
        }

    private suspend fun currentPresentationSettings(): ChannelPresentationSettings =
        currentPresentationSettingsFlow().first()

    private fun searchChannelEntities(
        providerId: Long,
        categoryId: Long,
        query: String,
        limit: Int
    ): Flow<List<ChannelBrowseEntity>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
                flowOf(emptyList())
            } else if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
                safeSearchFlow(
                    source = channelDao.search(providerId, ftsQuery, limit),
                    fallback = { channelDao.searchFallback(providerId, query.trim().toSqlLikePattern(), limit) },
                    rawQuery = query.trim()
                )
            } else {
                safeSearchFlow(
                    source = channelDao.searchByCategory(providerId, categoryId, ftsQuery, limit),
                    fallback = {
                        channelDao.searchByCategoryFallback(providerId, categoryId, query.trim().toSqlLikePattern(), limit)
                    },
                    rawQuery = query.trim()
                )
            }
        }

    private fun buildChannelsForRequestedIds(
        requestedIds: List<Long>,
        requestedEntities: List<ChannelBrowseEntity>,
        entityPool: List<ChannelBrowseEntity>,
        settings: ChannelPresentationSettings
    ): List<Channel> {
        if (requestedEntities.isEmpty()) return emptyList()
        if (settings.groupingMode == LiveChannelGroupingMode.RAW_VARIANTS) {
            val rawById = requestedEntities.associateBy { it.id }
            return requestedIds.mapNotNull { rawId ->
                rawById[rawId]?.toPresentedRawChannel(settings.observedQualities[rawId])
            }
        }

        val groupedChannels = buildGroupedChannels(entityPool, settings)
        val groupedByRawId = buildRawVariantLookup(groupedChannels)
        val seenLogicalGroups = linkedSetOf<String>()
        return requestedIds.mapNotNull { rawId ->
            groupedByRawId[rawId]
        }.filter { channel ->
            seenLogicalGroups.add(channel.logicalGroupId.ifBlank { channel.id.toString() })
        }
    }

    private fun buildPresentedChannels(
        entities: List<ChannelBrowseEntity>,
        settings: ChannelPresentationSettings,
        unlockedCats: Set<Long>
    ): List<Channel> {
        val base = if (settings.groupingMode == LiveChannelGroupingMode.RAW_VARIANTS) {
            entities.map { entity ->
                entity.toPresentedRawChannel(settings.observedQualities[entity.id])
            }
        } else {
            buildGroupedChannels(entities, settings)
        }
        return base.map { channel ->
            if (channel.categoryId != null && unlockedCats.contains(channel.categoryId)) {
                channel.copy(isUserProtected = false, isAdult = false)
            } else {
                channel
            }
        }
    }

    private fun buildGroupedChannels(
        entities: List<ChannelBrowseEntity>,
        settings: ChannelPresentationSettings
    ): List<Channel> {
        val grouped = linkedMapOf<String, MutableList<ChannelBrowseEntity>>()
        entities.forEach { entity ->
            val key = channelGroupKey(entity)
            grouped.getOrPut(key) { mutableListOf() }.add(entity)
        }
        return grouped.values.map { groupEntities ->
            val variants = groupEntities.map { entity ->
                entity.toVariant(settings.observedQualities[entity.id])
            }
            val canonicalName = variants.firstNotNullOfOrNull { variant ->
                variant.canonicalName.takeIf(String::isNotBlank)
            } ?: groupEntities.first().name
            val selectedVariant = resolveSelectedVariant(
                providerId = groupEntities.first().providerId,
                logicalGroupId = groupEntities.first().logicalGroupId,
                variants = variants,
                preferenceMode = settings.preferenceMode,
                preferredVariants = settings.preferredVariants
            )
            val representative = groupEntities.first { it.id == selectedVariant.rawChannelId }
            val orderedVariants = variants
                .sortedWith(compareByDescending<LiveChannelVariant> {
                    variantScore(it, settings.preferenceMode)
                }.thenBy { it.errorCount }.thenBy { it.originalName.length })
                .let { sorted ->
                    listOf(selectedVariant) + sorted.filterNot { it.rawChannelId == selectedVariant.rawChannelId }
                }
            val displayName = when (settings.labelMode) {
                GroupedChannelLabelMode.ORIGINAL_PROVIDER_LABEL -> selectedVariant.originalName
                GroupedChannelLabelMode.CANONICAL,
                GroupedChannelLabelMode.HYBRID -> canonicalName
            }
            val qualityOptions = orderedVariants.mapNotNull(::variantQualityOption)
                .distinctBy { option -> option.url ?: "${option.height}:${option.label}" }
            val alternativeStreams = orderedVariants
                .map(LiveChannelVariant::streamUrl)
                .filter { it.isNotBlank() && it != selectedVariant.streamUrl }
                .distinct()

            Channel(
                id = selectedVariant.rawChannelId,
                name = displayName,
                canonicalName = canonicalName,
                logoUrl = representative.resolveLogoUrl(),
                groupTitle = representative.groupTitle,
                categoryId = representative.categoryId,
                categoryName = representative.categoryName,
                streamUrl = selectedVariant.streamUrl,
                epgChannelId = selectedVariant.epgChannelId,
                number = representative.number,
                isFavorite = false,
                catchUpSupported = selectedVariant.catchUpSupported,
                catchUpDays = selectedVariant.catchUpDays,
                catchUpSource = selectedVariant.catchUpSource,
                providerId = representative.providerId,
                isAdult = representative.isAdult,
                isUserProtected = representative.isUserProtected,
                logicalGroupId = representative.logicalGroupId,
                selectedVariantId = selectedVariant.rawChannelId,
                errorCount = selectedVariant.errorCount,
                qualityOptions = qualityOptions,
                alternativeStreams = alternativeStreams,
                variants = orderedVariants,
                streamId = selectedVariant.streamId
            )
        }
    }

    private fun resolveSelectedVariant(
        providerId: Long,
        logicalGroupId: String,
        variants: List<LiveChannelVariant>,
        preferenceMode: LiveVariantPreferenceMode,
        preferredVariants: Map<String, Long>
    ): LiveChannelVariant {
        val stickyKey = "${providerId}|${logicalGroupId.trim()}"
        preferredVariants[stickyKey]
            ?.let { preferredId -> variants.firstOrNull { it.rawChannelId == preferredId } }
            ?.let { return it }
        return variants.maxWithOrNull(
            compareBy<LiveChannelVariant> { variantScore(it, preferenceMode) }
                .thenByDescending { it.observedQuality.successCount }
                .thenByDescending { it.observedQuality.lastSuccessfulAt }
                .thenBy { -it.errorCount }
                .thenBy { -it.originalName.length }
        ) ?: variants.first()
    }

    private fun variantScore(
        variant: LiveChannelVariant,
        preferenceMode: LiveVariantPreferenceMode
    ): Long {
        val observedHeight = variant.observedQuality.lastObservedHeight
        val declaredHeight = variant.attributes.declaredHeight ?: 0
        val cappedObservedHeight = when {
            declaredHeight > 0 && observedHeight > 0 -> minOf(declaredHeight, observedHeight)
            observedHeight > 0 -> observedHeight
            else -> 0
        }
        val tagPreferredHeight = declaredHeight.takeIf { it > 0 } ?: observedHeight
        val conservativeHeight = when {
            cappedObservedHeight > 0 -> cappedObservedHeight
            declaredHeight > 0 -> declaredHeight
            else -> observedHeight
        }
        val declaredFrameRate = variant.attributes.frameRate ?: 0
        val observedFrameRate = variant.observedQuality.lastObservedFrameRate.toInt()
        val frameRateBonus = when {
            maxOf(declaredFrameRate, observedFrameRate) >= 60 -> 180L
            maxOf(declaredFrameRate, observedFrameRate) >= 50 -> 120L
            else -> 0L
        }
        val observedFrameRateBonus = when {
            observedFrameRate >= 60 -> 180L
            observedFrameRate >= 50 -> 120L
            else -> 0L
        }
        val codecBonus = when (variant.attributes.codecLabel) {
            "AV1" -> 160L
            "HEVC" -> 110L
            "HDR10", "HDR", "Dolby Vision" -> 90L
            else -> 0L
        }
        val hdrBonus = if (variant.attributes.isHdr) 140L else 0L
        val sourcePenalty = when (variant.attributes.sourceHint) {
            "Backup" -> 220L
            "Alternate" -> 140L
            "Lite", "Mobile", "Low" -> 260L
            "Test" -> 420L
            else -> 0L
        }
        val healthPenalty = variant.errorCount.toLong() * when (preferenceMode) {
            LiveVariantPreferenceMode.BEST_QUALITY -> 180L
            LiveVariantPreferenceMode.OBSERVED_ONLY -> 220L
            LiveVariantPreferenceMode.BALANCED -> 260L
            LiveVariantPreferenceMode.STABILITY_FIRST -> 420L
        }
        val successBonus = variant.observedQuality.successCount.toLong() * when (preferenceMode) {
            LiveVariantPreferenceMode.BEST_QUALITY -> 20L
            LiveVariantPreferenceMode.OBSERVED_ONLY -> 40L
            LiveVariantPreferenceMode.BALANCED -> 35L
            LiveVariantPreferenceMode.STABILITY_FIRST -> 60L
        }
        return when (preferenceMode) {
            LiveVariantPreferenceMode.BEST_QUALITY ->
                tagPreferredHeight.toLong() * 8 +
                    frameRateBonus +
                    codecBonus +
                    hdrBonus +
                    successBonus -
                    healthPenalty -
                    sourcePenalty
            LiveVariantPreferenceMode.OBSERVED_ONLY ->
                observedHeight.toLong() * 8 +
                    (variant.observedQuality.lastObservedBitrate / 250_000L) +
                    observedFrameRateBonus +
                    successBonus -
                    healthPenalty
            LiveVariantPreferenceMode.BALANCED ->
                conservativeHeight.toLong() * 6 +
                    frameRateBonus +
                    (codecBonus / 2) +
                    (hdrBonus / 2) +
                    successBonus -
                    healthPenalty -
                    sourcePenalty
            LiveVariantPreferenceMode.STABILITY_FIRST ->
                conservativeHeight.toLong() * 2 +
                    successBonus +
                    variant.observedQuality.lastSuccessfulAt / 60000L -
                    healthPenalty -
                    (sourcePenalty * 2)
        }
    }

    private fun variantQualityOption(variant: LiveChannelVariant): ChannelQualityOption? {
        if (variant.streamUrl.isBlank()) return null
        val labelParts = buildList {
            variant.attributes.resolutionLabel?.let(::add)
            variant.attributes.codecLabel?.takeIf { it == "HEVC" || it == "AV1" }?.let(::add)
            variant.attributes.transportLabel?.takeIf { it == "HLS" || it == "MPEG-TS" }?.let(::add)
            variant.attributes.sourceHint?.takeIf { it == "Backup" || it == "Alternate" }?.let(::add)
        }
        val label = labelParts.joinToString(" ").ifBlank {
            variant.attributes.resolutionLabel ?: variant.originalName
        }
        return ChannelQualityOption(
            label = label,
            height = variant.attributes.declaredHeight ?: variant.observedQuality.lastObservedHeight.takeIf { it > 0 },
            url = variant.streamUrl
        )
    }

    private fun buildRawVariantLookup(channels: List<Channel>): Map<Long, Channel> =
        buildMap {
            channels.forEach { channel ->
                channel.allVariantRawIds().forEach { rawId ->
                    put(rawId, channel)
                }
            }
        }

    private fun applyVisibilityFilter(
        entities: List<ChannelBrowseEntity>,
        level: Int,
        unlockedCats: Set<Long>,
        hideDecorativeRows: Boolean
    ): List<ChannelBrowseEntity> {
        val usable = if (hideDecorativeRows) {
            entities.filterNot { entity ->
                ChannelNormalizer.isHashWrappedHeader(entity.name)
            }
        } else {
            entities
        }
        return if (level >= 3) {
            usable.filter { entity ->
                val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                (!entity.isAdult && !entity.isUserProtected) || isUnlocked
            }
        } else {
            usable
        }
    }

    private fun sortChannelsByNumber(channels: List<Channel>): List<Channel> =
        channels.sortedWith(channelNumberComparator)

    private fun applyNumbering(
        channels: List<Channel>,
        numberingMode: ChannelNumberingMode,
        offset: Int = 0
    ): List<Channel> = when (numberingMode) {
        ChannelNumberingMode.GROUP -> channels.mapIndexed { index, channel ->
            channel.copy(number = offset + index + 1)
        }
        ChannelNumberingMode.PROVIDER -> channels
        ChannelNumberingMode.HIDDEN -> channels.map { it.copy(number = 0) }
    }

    private fun channelFlow(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProvider(providerId)
        } else {
            channelDao.getByCategory(providerId, categoryId)
        }

    private fun channelFlowPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProviderBrowsePage(providerId, limit)
        } else {
            channelDao.getByCategoryBrowsePage(providerId, categoryId, limit)
        }

    private fun channelFlowWithoutErrors(providerId: Long, categoryId: Long): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProviderWithoutErrors(providerId)
        } else {
            channelDao.getByCategoryWithoutErrors(providerId, categoryId)
        }

    private fun channelFlowWithoutErrorsPage(
        providerId: Long,
        categoryId: Long,
        limit: Int
    ): Flow<List<ChannelBrowseEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProviderWithoutErrorsBrowsePage(providerId, limit)
        } else {
            channelDao.getByCategoryWithoutErrorsBrowsePage(providerId, categoryId, limit)
        }

    private fun safeSearchFlow(
        source: Flow<List<ChannelBrowseEntity>>,
        fallback: () -> Flow<List<ChannelBrowseEntity>>,
        rawQuery: String
    ): Flow<List<ChannelBrowseEntity>> = flow {
        try {
            source.collect { ftsRows ->
                if (ftsRows.isEmpty()) {
                    emitAll(fallback())
                } else {
                    emit(ftsRows)
                }
            }
        } catch (error: SQLiteException) {
            Log.w(TAG, "FTS channel search failed for query '$rawQuery'; using LIKE-only search", error)
            emitAll(fallback())
        }
    }

    private fun String.toSqlLikePattern(): String {
        val escaped = buildString(length) {
            this@toSqlLikePattern.forEach { char ->
                when (char) {
                    '%', '_', '\\' -> append('\\')
                }
                append(char)
            }
        }
        return "%$escaped%"
    }

    private fun channelGroupKey(entity: ChannelBrowseEntity): String =
        entity.logicalGroupId.takeIf(String::isNotBlank) ?: entity.id.toString()

    private fun ChannelBrowseEntity.toVariant(
        observedQuality: LiveChannelObservedQuality?
    ): LiveChannelVariant {
        val classification = ChannelNormalizer.classify(
            channelName = name,
            providerId = providerId,
            streamUrl = streamUrl
        )
        return LiveChannelVariant(
            rawChannelId = id,
            logicalGroupId = logicalGroupId.takeIf(String::isNotBlank) ?: classification.logicalGroupId,
            providerId = providerId,
            originalName = name,
            canonicalName = classification.canonicalName,
            streamUrl = streamUrl,
            streamId = streamId,
            epgChannelId = epgChannelId,
            number = number,
            errorCount = errorCount,
            catchUpSupported = catchUpSupported,
            catchUpDays = catchUpDays,
            catchUpSource = catchUpSource,
            attributes = classification.attributes,
            observedQuality = observedQuality ?: LiveChannelObservedQuality()
        )
    }

    private fun ChannelBrowseEntity.toPresentedRawChannel(
        observedQuality: LiveChannelObservedQuality?
    ): Channel {
        val variant = toVariant(observedQuality)
        return Channel(
            id = id,
            name = name,
            canonicalName = variant.canonicalName,
            logoUrl = resolveLogoUrl(),
            groupTitle = groupTitle,
            categoryId = categoryId,
            categoryName = categoryName,
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            number = number,
            isFavorite = false,
            catchUpSupported = catchUpSupported,
            catchUpDays = catchUpDays,
            catchUpSource = catchUpSource,
            providerId = providerId,
            isAdult = isAdult,
            isUserProtected = isUserProtected,
            logicalGroupId = logicalGroupId.takeIf(String::isNotBlank) ?: variant.logicalGroupId,
            selectedVariantId = id,
            errorCount = errorCount,
            qualityOptions = listOfNotNull(variantQualityOption(variant)),
            alternativeStreams = emptyList(),
            variants = listOf(variant),
            streamId = streamId
        )
    }

    private fun ChannelBrowseEntity.resolveLogoUrl(): String? {
        val supplierLogo = logoUrl?.takeIf { it.isNotBlank() }
        val epgLogo = epgIconUrl?.takeIf { it.isNotBlank() }
        return when (channelLogoSourcePolicy) {
            ChannelLogoSourcePolicy.SUPPLIER_PREFERRED -> supplierLogo ?: epgLogo
            ChannelLogoSourcePolicy.EPG_PREFERRED -> epgLogo ?: supplierLogo
            ChannelLogoSourcePolicy.SUPPLIER_ONLY -> supplierLogo
            ChannelLogoSourcePolicy.EPG_ONLY -> epgLogo
        }
    }
}
