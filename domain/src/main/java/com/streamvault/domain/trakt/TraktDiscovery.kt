package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.StateFlow

enum class TraktDiscoveryMedia(val apiPath: String) {
    MOVIE("movies"),
    SHOW("shows"),
}

enum class TraktDiscoveryKind(val apiPath: String) {
    TRENDING("trending"),
    POPULAR("popular"),
}

data class TraktDiscoveryCategory(
    val media: TraktDiscoveryMedia,
    val kind: TraktDiscoveryKind,
)

data class TraktDiscoveryItem(
    val tmdbId: Long?,
    val title: String,
    val year: Int? = null,
)

data class TraktDiscoveryPage(
    val tmdbIds: List<Long>,
    val skippedItems: Int = 0,
    val entries: List<TraktDiscoveryItem> = emptyList(),
)

data class TraktDiscoveryState(
    val itemsByCategory: Map<TraktDiscoveryCategory, List<Long>> = emptyMap(),
    val entriesByCategory: Map<TraktDiscoveryCategory, List<TraktDiscoveryItem>> = emptyMap(),
    val loadedAtByCategory: Map<TraktDiscoveryCategory, Long> = emptyMap(),
    val refreshing: Set<TraktDiscoveryCategory> = emptySet(),
    val failed: Set<TraktDiscoveryCategory> = emptySet(),
) {
    fun items(category: TraktDiscoveryCategory): List<Long> = itemsByCategory[category].orEmpty()
    fun entries(category: TraktDiscoveryCategory): List<TraktDiscoveryItem> =
        entriesByCategory[category]?.takeIf { it.isNotEmpty() }
            ?: items(category).map { TraktDiscoveryItem(tmdbId = it, title = "") }
    fun isLoaded(category: TraktDiscoveryCategory): Boolean = category in loadedAtByCategory
}

sealed interface TraktDiscoveryRefreshResult {
    data class Success(val itemCount: Int, val skipped: Int = 0) : TraktDiscoveryRefreshResult
    data object Failure : TraktDiscoveryRefreshResult
}

/** Public Trakt discovery data. It does not require a signed-in account. */
interface TraktDiscovery {
    val state: StateFlow<TraktDiscoveryState>
    suspend fun refresh(category: TraktDiscoveryCategory, force: Boolean = false): TraktDiscoveryRefreshResult
}

interface TraktDiscoverySource {
    suspend fun discover(category: TraktDiscoveryCategory, page: Int): TraktDiscoveryPage
}
