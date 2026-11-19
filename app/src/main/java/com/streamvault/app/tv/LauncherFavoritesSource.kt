package com.streamvault.app.tv

import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal data class LauncherFavoriteItem(
    val link: ExternalItemLink,
    val title: String,
    val posterUrl: String?,
    val durationMs: Long = 0,
    val positionMs: Long = 0
)

internal data class LauncherFavoritesSnapshot(val providerId: Long?, val items: List<LauncherFavoriteItem>)

/** Reads only favorited catalogue IDs. Raw rows retain locks even during an unlocked PIN session. */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherFavoritesSource @Inject constructor(
    private val providers: ProviderRepository,
    private val favorites: FavoriteRepository,
    private val movies: MovieDao,
    private val series: SeriesDao,
    private val channels: ChannelDao,
    private val categories: CategoryRepository,
    private val preferences: PreferencesRepository
) {
    internal fun observe(): Flow<LauncherFavoritesSnapshot> = providers.getActiveProvider()
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { provider ->
            if (provider == null) flowOf(LauncherFavoritesSnapshot(null, emptyList()))
            else favorites.getFavorites(provider.id).flatMapLatest { saved ->
                val ordered = saved.asSequence()
                    .filter { it.providerId == provider.id && it.groupId == null && it.contentId > 0 }
                    .sortedWith(compareBy({ it.position }, { it.id }))
                    .distinctBy { it.contentType to it.contentId }.toList()
                if (ordered.isEmpty()) return@flatMapLatest flowOf(LauncherFavoritesSnapshot(provider.id, emptyList()))
                fun ids(type: ContentType) = ordered.filter { it.contentType == type }.map { it.contentId }
                val catalog = combine(
                    batched(ids(ContentType.MOVIE), movies::getByIds),
                    batched(ids(ContentType.SERIES), series::getByIds),
                    batched(ids(ContentType.LIVE), channels::getByIds)
                ) { movieRows, seriesRows, channelRows ->
                    movieRows.map { row -> CatalogItem(row.id, row.providerId, ContentType.MOVIE, row.categoryId,
                        row.isAdult || row.isUserProtected, row.name, row.posterUrl,
                        row.durationSeconds.toLong() * 1000L, row.watchProgress) } +
                    seriesRows.map { row -> CatalogItem(row.id, row.providerId, ContentType.SERIES, row.categoryId,
                        row.isAdult || row.isUserProtected, row.name, row.posterUrl) } +
                    channelRows.map { row -> CatalogItem(row.id, row.providerId, ContentType.LIVE, row.categoryId,
                        row.isAdult || row.isUserProtected, row.name, row.logoUrl) }
                }
                val hidden = combine(
                    ContentType.entries.filter { it != ContentType.SERIES_EPISODE }.map { type ->
                        preferences.getHiddenCategoryIds(provider.id, type).map { type to it }
                    }
                ) { entries -> entries.toMap() }
                combine(catalog, categories.getCategories(provider.id), hidden,
                    preferences.getHiddenChannelIds(provider.id)) { items, categoryRows, hiddenCategories, hiddenChannels ->
                    val byIdentity = items.associateBy { it.type to it.id }
                    val categoryIndex = categoryRows.associateBy { it.type to it.id }
                    val visible = ordered.asSequence().mapNotNull { favorite ->
                        val item = byIdentity[favorite.contentType to favorite.contentId] ?: return@mapNotNull null
                        if (item.providerId != provider.id || item.protected || item.title.isBlank()) return@mapNotNull null
                        if (item.type == ContentType.LIVE && item.id in hiddenChannels) return@mapNotNull null
                        if (!categoryAllowed(item, categoryIndex, hiddenCategories)) return@mapNotNull null
                        val type = when (item.type) {
                            ContentType.MOVIE -> ExternalItemType.MOVIE
                            ContentType.SERIES -> ExternalItemType.SERIES
                            ContentType.LIVE -> ExternalItemType.LIVE
                            else -> return@mapNotNull null
                        }
                        LauncherFavoriteItem(ExternalItemLink(type, item.id, provider.id,
                            if (type == ExternalItemType.LIVE) ExternalItemAction.PLAY else ExternalItemAction.RESUME),
                            item.title, item.posterUrl, item.durationMs, item.positionMs)
                    }.take(MAX_ITEMS).toList()
                    LauncherFavoritesSnapshot(provider.id, visible)
                }
            }
        }.distinctUntilChanged()

    private fun categoryAllowed(item: CatalogItem, categories: Map<Pair<ContentType, Long>, Category>,
        hidden: Map<ContentType, Set<Long>>): Boolean {
        val id = item.categoryId ?: return true
        if (id in hidden[item.type].orEmpty()) return false
        val category = categories[item.type to id]
            ?: categories[ContentType.VOD to id].takeIf { item.type != ContentType.LIVE }
            ?: return false
        return !category.isAdult && !category.isUserProtected && id !in hidden[category.type].orEmpty()
    }

    private fun <T> batched(ids: List<Long>, query: (List<Long>) -> Flow<List<T>>): Flow<List<T>> =
        if (ids.isEmpty()) flowOf(emptyList())
        else combine(ids.chunked(250).map(query)) { chunks -> chunks.flatMap { it } }

    private data class CatalogItem(val id: Long, val providerId: Long, val type: ContentType,
        val categoryId: Long?, val protected: Boolean, val title: String, val posterUrl: String?,
        val durationMs: Long = 0, val positionMs: Long = 0)

    internal companion object { const val MAX_ITEMS = 24 }
}
