package com.streamvault.domain.trakt

import kotlinx.coroutines.flow.StateFlow

enum class TraktCustomListItemType(val apiPath: String, val payloadKey: String, val responseKey: String) {
    MOVIE("movies", "movies", "movie"),
    SHOW("shows", "shows", "show"),
    SEASON("seasons", "seasons", "season"),
    EPISODE("episodes", "episodes", "episode"),
}

data class TraktCustomListItemIdentity(
    val type: TraktCustomListItemType,
    /** TMDB id of the movie/show, or the parent show for season and episode entries. */
    val tmdbId: Long,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(tmdbId > 0L)
        when (type) {
            TraktCustomListItemType.MOVIE, TraktCustomListItemType.SHOW ->
                require(seasonNumber == null && episodeNumber == null)
            TraktCustomListItemType.SEASON ->
                require(seasonNumber != null && seasonNumber >= 0 && episodeNumber == null)
            TraktCustomListItemType.EPISODE ->
                require(seasonNumber != null && seasonNumber >= 0 && episodeNumber != null && episodeNumber > 0)
        }
    }
}

data class TraktCustomList(
    val id: Long,
    val name: String,
    val description: String? = null,
    val privacy: String = "private",
    val itemCount: Int = 0,
) {
    init {
        require(id > 0L)
        require(name.isNotBlank())
        require(itemCount >= 0)
    }
}

data class TraktCustomListItem(
    val identity: TraktCustomListItemIdentity,
    val title: String,
    val year: Int? = null,
    val rank: Int? = null,
    /** Trakt's stable list-entry id, required by the item-reorder endpoint. */
    val listItemId: Long? = null,
) {
    init {
        require(title.isNotBlank())
        require(listItemId == null || listItemId > 0L)
    }
}

data class TraktCustomListItemsPage(
    val items: List<TraktCustomListItem>,
    val page: Int,
    val hasMore: Boolean,
    val skippedItems: Int = 0,
)

enum class TraktCustomListsFailure {
    DISCONNECTED,
    INCOGNITO,
    ACCOUNT_CHANGED,
    READ_LIMIT,
    REQUEST_FAILED,
}

data class TraktCustomListsState(
    val accountUsername: String? = null,
    val connected: Boolean = false,
    val lists: List<TraktCustomList> = emptyList(),
    val listsLoadedAtMs: Long? = null,
    val refreshingLists: Boolean = false,
    val loadingItems: Set<Long> = emptySet(),
    val itemsByList: Map<Long, List<TraktCustomListItem>> = emptyMap(),
    val itemsLoadedAtMs: Map<Long, Long> = emptyMap(),
    val updatingItems: Set<Pair<Long, TraktCustomListItemIdentity>> = emptySet(),
    val failure: TraktCustomListsFailure? = null,
) {
    val listsLoaded: Boolean get() = listsLoadedAtMs != null
    fun list(id: Long): TraktCustomList? = lists.firstOrNull { it.id == id }
    fun itemsLoaded(id: Long): Boolean = id in itemsLoadedAtMs
    fun contains(id: Long, identity: TraktCustomListItemIdentity): Boolean =
        itemsByList[id].orEmpty().any { it.identity == identity }
}

sealed interface TraktCustomListsPageResult {
    data class Success(val lists: List<TraktCustomList>) : TraktCustomListsPageResult
    data object Disconnected : TraktCustomListsPageResult
    data object Failed : TraktCustomListsPageResult
}

sealed interface TraktCustomListItemsPageResult {
    data class Success(val page: TraktCustomListItemsPage) : TraktCustomListItemsPageResult
    data object Disconnected : TraktCustomListItemsPageResult
    data object Failed : TraktCustomListItemsPageResult
}

sealed interface TraktCustomListWriteResult {
    data object Success : TraktCustomListWriteResult
    data object Disconnected : TraktCustomListWriteResult
    data object Failed : TraktCustomListWriteResult
}

sealed interface TraktCustomListRefreshResult {
    data class Success(val listCount: Int) : TraktCustomListRefreshResult
    data class Failure(val reason: TraktCustomListsFailure) : TraktCustomListRefreshResult
}

sealed interface TraktCustomListItemsRefreshResult {
    data class Success(val itemCount: Int, val skipped: Int = 0) : TraktCustomListItemsRefreshResult
    data class Failure(val reason: TraktCustomListsFailure) : TraktCustomListItemsRefreshResult
}

sealed interface TraktCustomListActionResult {
    data object Success : TraktCustomListActionResult
    data class Failure(val reason: TraktCustomListsFailure) : TraktCustomListActionResult
}

/** Explicit, on-demand access to the connected user's personal Trakt lists. */
interface TraktCustomLists {
    val state: StateFlow<TraktCustomListsState>
    suspend fun refreshLists(force: Boolean = false): TraktCustomListRefreshResult
    suspend fun refreshItems(listId: Long, force: Boolean = false): TraktCustomListItemsRefreshResult
    suspend fun createList(name: String, description: String? = null): TraktCustomListActionResult
    suspend fun deleteList(listId: Long): TraktCustomListActionResult
    /** Reorders every list currently loaded for the connected account. */
    suspend fun reorderLists(listIds: List<Long>): TraktCustomListActionResult
    suspend fun reorderItems(listId: Long, items: List<TraktCustomListItem>): TraktCustomListActionResult
    suspend fun setItem(
        listId: Long,
        item: TraktCustomListItem,
        included: Boolean,
    ): TraktCustomListActionResult
}

/** Authenticated transport used by the account service to access personal lists. */
interface TraktCustomListsSource {
    suspend fun lists(): TraktCustomListsPageResult
    suspend fun items(
        listId: Long,
        type: TraktCustomListItemType,
        page: Int,
    ): TraktCustomListItemsPageResult
    suspend fun createList(name: String, description: String?): TraktCustomListWriteResult
    suspend fun deleteList(listId: Long): TraktCustomListWriteResult
    suspend fun reorderLists(listIds: List<Long>): TraktCustomListWriteResult
    suspend fun reorderItems(listId: Long, listItemIds: List<Long>): TraktCustomListWriteResult
    suspend fun setItem(
        listId: Long,
        identity: TraktCustomListItemIdentity,
        included: Boolean,
    ): TraktCustomListWriteResult
}
