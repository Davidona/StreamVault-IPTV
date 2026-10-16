package com.streamvault.data.trakt

import com.streamvault.domain.trakt.TraktDiscovery
import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryRefreshResult
import com.streamvault.domain.trakt.TraktDiscoverySource
import com.streamvault.domain.trakt.TraktDiscoveryItem
import com.streamvault.domain.trakt.TraktDiscoveryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Caches the small public discovery pages StreamVault uses and deduplicates refreshes. */
@Singleton
class TraktDiscoveryService @Inject constructor(
    private val source: TraktDiscoverySource,
) : TraktDiscovery {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TraktDiscoveryState())
    override val state = mutableState.asStateFlow()

    override suspend fun refresh(
        category: TraktDiscoveryCategory,
        force: Boolean,
    ): TraktDiscoveryRefreshResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableState.value
            val loadedAt = current.loadedAtByCategory[category]
            if (!force && loadedAt != null && System.currentTimeMillis() - loadedAt < CACHE_TTL_MS) {
                mutableState.value = current.copy(failed = current.failed - category)
                return@withLock TraktDiscoveryRefreshResult.Success(current.items(category).size)
            }

            mutableState.value = current.copy(
                refreshing = current.refreshing + category,
                failed = current.failed - category,
            )
            try {
                val page = source.discover(category, page = 1)
                val entries = if (page.entries.isEmpty()) {
                    page.tmdbIds.asSequence()
                        .filter { it > 0L }
                        .distinct()
                        .take(PAGE_SIZE)
                        .map { TraktDiscoveryItem(tmdbId = it, title = "") }
                        .toList()
                } else {
                    page.entries.asSequence()
                        .filter { (it.tmdbId?.let { id -> id > 0L } == true) || it.title.isNotBlank() }
                        .distinctBy { it.tmdbId ?: "${it.title.lowercase()}|${it.year}" }
                        .take(PAGE_SIZE)
                        .toList()
                }
                val items = entries.mapNotNull { it.tmdbId?.takeIf { id -> id > 0L } }.distinct().take(PAGE_SIZE)
                val latest = mutableState.value
                mutableState.value = latest.copy(
                    itemsByCategory = latest.itemsByCategory + (category to items),
                    entriesByCategory = latest.entriesByCategory + (category to entries),
                    loadedAtByCategory = latest.loadedAtByCategory + (category to System.currentTimeMillis()),
                    refreshing = latest.refreshing - category,
                    failed = latest.failed - category,
                )
                TraktDiscoveryRefreshResult.Success(entries.size, page.skippedItems)
            } catch (failure: CancellationException) {
                mutableState.value = mutableState.value.copy(refreshing = mutableState.value.refreshing - category)
                throw failure
            } catch (_: Exception) {
                val latest = mutableState.value
                mutableState.value = latest.copy(
                    refreshing = latest.refreshing - category,
                    failed = latest.failed + category,
                )
                TraktDiscoveryRefreshResult.Failure
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }
}
