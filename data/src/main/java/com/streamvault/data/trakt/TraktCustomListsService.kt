package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktCustomList
import com.streamvault.domain.trakt.TraktCustomListActionResult
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItem
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItemsPageResult
import com.streamvault.domain.trakt.TraktCustomListItemsRefreshResult
import com.streamvault.domain.trakt.TraktCustomListRefreshResult
import com.streamvault.domain.trakt.TraktCustomListWriteResult
import com.streamvault.domain.trakt.TraktCustomLists
import com.streamvault.domain.trakt.TraktCustomListsFailure
import com.streamvault.domain.trakt.TraktCustomListsPageResult
import com.streamvault.domain.trakt.TraktCustomListsSource
import com.streamvault.domain.trakt.TraktCustomListsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Loads personal-list metadata and contents only when the user opens them. */
@Singleton
class TraktCustomListsService @Inject constructor(
    private val account: TraktAccount,
    private val source: TraktCustomListsSource,
    private val history: PlaybackHistoryRepository,
) : TraktCustomLists {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TraktCustomListsState())
    override val state = mutableState.asStateFlow()

    override suspend fun refreshLists(force: Boolean): TraktCustomListRefreshResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (history.isIncognitoModeEnabled()) return@withLock listsFailure(TraktCustomListsFailure.INCOGNITO)
                if (!account.state.value.connected) account.load()
                val owner = account.state.value.username?.takeIf { account.state.value.connected }
                    ?: return@withLock listsFailure(TraktCustomListsFailure.DISCONNECTED)
                fun sameAccount() = account.state.value.connected && account.state.value.username == owner

                val current = mutableState.value
                if (current.accountUsername != owner) {
                    mutableState.value = TraktCustomListsState(accountUsername = owner, connected = true)
                } else if (!force && current.listsLoadedAtMs?.let {
                        System.currentTimeMillis() - it < CACHE_TTL_MS
                    } == true
                ) {
                    mutableState.value = current.copy(connected = true, failure = null)
                    return@withLock TraktCustomListRefreshResult.Success(current.lists.size)
                }

                mutableState.value = mutableState.value.copy(
                    accountUsername = owner,
                    connected = true,
                    refreshingLists = true,
                    failure = null,
                )
                val result = when (val response = source.lists()) {
                    is TraktCustomListsPageResult.Success -> response.lists
                    TraktCustomListsPageResult.Disconnected ->
                        return@withLock listsFailure(TraktCustomListsFailure.DISCONNECTED)
                    TraktCustomListsPageResult.Failed ->
                        return@withLock listsFailure(TraktCustomListsFailure.REQUEST_FAILED)
                }
                if (!sameAccount()) return@withLock listsFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                if (history.isIncognitoModeEnabled()) return@withLock listsFailure(TraktCustomListsFailure.INCOGNITO)
                if (result.any { it.id <= 0L || it.name.isBlank() || it.itemCount < 0 } ||
                    result.map { it.id }.distinct().size != result.size
                ) return@withLock listsFailure(TraktCustomListsFailure.REQUEST_FAILED)

                val previous = mutableState.value
                val validIds = result.mapTo(hashSetOf()) { it.id }
                mutableState.value = TraktCustomListsState(
                    accountUsername = owner,
                    connected = true,
                    lists = result,
                    listsLoadedAtMs = System.currentTimeMillis(),
                    itemsByList = previous.itemsByList.filterKeys { it in validIds },
                    itemsLoadedAtMs = previous.itemsLoadedAtMs.filterKeys { it in validIds },
                )
                TraktCustomListRefreshResult.Success(result.size)
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    mutableState.value = mutableState.value.copy(refreshingLists = false)
                    throw failure
                }
                listsFailure(TraktCustomListsFailure.REQUEST_FAILED)
            }
        }
    }

    override suspend fun refreshItems(
        listId: Long,
        force: Boolean,
    ): TraktCustomListItemsRefreshResult {
        require(listId > 0L)
        val current = mutableState.value
        if (!current.listsLoaded || current.accountUsername != account.state.value.username ||
            current.listsLoadedAtMs?.let { System.currentTimeMillis() - it >= CACHE_TTL_MS } != false
        ) {
            when (val refreshed = refreshLists()) {
                is TraktCustomListRefreshResult.Failure ->
                    return TraktCustomListItemsRefreshResult.Failure(refreshed.reason)
                is TraktCustomListRefreshResult.Success -> Unit
            }
        }

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock itemsFailure(TraktCustomListsFailure.INCOGNITO, listId)
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock itemsFailure(TraktCustomListsFailure.DISCONNECTED, listId)
                    val currentState = mutableState.value
                    if (currentState.accountUsername != owner || currentState.list(listId) == null) {
                        return@withLock itemsFailure(TraktCustomListsFailure.ACCOUNT_CHANGED, listId)
                    }
                    if (!force && currentState.itemsLoadedAtMs[listId]?.let {
                            System.currentTimeMillis() - it < CACHE_TTL_MS
                        } == true
                    ) return@withLock TraktCustomListItemsRefreshResult.Success(
                        currentState.itemsByList[listId].orEmpty().size,
                    )

                    fun sameAccount() = account.state.value.connected && account.state.value.username == owner
                    mutableState.value = currentState.copy(
                        loadingItems = currentState.loadingItems + listId,
                        failure = null,
                    )
                    val staged = linkedMapOf<TraktCustomListItemIdentity, com.streamvault.domain.trakt.TraktCustomListItem>()
                    var skipped = 0
                    for (type in TraktCustomListItemType.entries) {
                        var finished = false
                        for (page in 1..MAX_PAGES) {
                            if (!sameAccount()) return@withLock itemsFailure(TraktCustomListsFailure.ACCOUNT_CHANGED, listId)
                            if (history.isIncognitoModeEnabled()) return@withLock itemsFailure(TraktCustomListsFailure.INCOGNITO, listId)
                            when (val response = source.items(listId, type, page)) {
                                is TraktCustomListItemsPageResult.Success -> {
                                    val data = response.page
                                    if (data.page != page || data.items.size > PAGE_SIZE ||
                                        data.items.any { it.identity.type != type || it.title.isBlank() }
                                    ) return@withLock itemsFailure(TraktCustomListsFailure.REQUEST_FAILED, listId)
                                    data.items.forEach { staged.putIfAbsent(it.identity, it) }
                                    skipped += data.skippedItems
                                    if (!data.hasMore) {
                                        finished = true
                                        break
                                    }
                                    if (page == MAX_PAGES) return@withLock itemsFailure(TraktCustomListsFailure.READ_LIMIT, listId)
                                }
                                TraktCustomListItemsPageResult.Disconnected ->
                                    return@withLock itemsFailure(TraktCustomListsFailure.DISCONNECTED, listId)
                                TraktCustomListItemsPageResult.Failed ->
                                    return@withLock itemsFailure(TraktCustomListsFailure.REQUEST_FAILED, listId)
                            }
                        }
                        if (!finished) return@withLock itemsFailure(TraktCustomListsFailure.READ_LIMIT, listId)
                    }
                    if (!sameAccount()) return@withLock itemsFailure(TraktCustomListsFailure.ACCOUNT_CHANGED, listId)
                    if (history.isIncognitoModeEnabled()) return@withLock itemsFailure(TraktCustomListsFailure.INCOGNITO, listId)
                    val latest = mutableState.value
                    if (latest.accountUsername != owner || latest.list(listId) == null) {
                        return@withLock itemsFailure(TraktCustomListsFailure.ACCOUNT_CHANGED, listId)
                    }
                    mutableState.value = latest.copy(
                        itemsByList = latest.itemsByList + (listId to staged.values.sortedWith(
                            compareBy<com.streamvault.domain.trakt.TraktCustomListItem> { it.rank ?: Int.MAX_VALUE }
                                .thenBy { it.title.lowercase() },
                        )),
                        itemsLoadedAtMs = latest.itemsLoadedAtMs + (listId to System.currentTimeMillis()),
                        loadingItems = latest.loadingItems - listId,
                        failure = null,
                    )
                    TraktCustomListItemsRefreshResult.Success(staged.size, skipped)
                } catch (failure: Exception) {
                    if (failure is CancellationException) {
                        mutableState.value = mutableState.value.copy(loadingItems = mutableState.value.loadingItems - listId)
                        throw failure
                    }
                    itemsFailure(TraktCustomListsFailure.REQUEST_FAILED, listId)
                }
            }
        }
    }

    override suspend fun createList(name: String, description: String?): TraktCustomListActionResult {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) return TraktCustomListActionResult.Failure(TraktCustomListsFailure.REQUEST_FAILED)
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                    if (!account.state.value.connected) account.load()
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock actionFailure(TraktCustomListsFailure.DISCONNECTED)
                    when (source.createList(normalizedName, description?.trim()?.takeIf { it.isNotEmpty() })) {
                        TraktCustomListWriteResult.Success -> {
                            if (!account.state.value.connected || account.state.value.username != owner) {
                                return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                            }
                            if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                            mutableState.value = mutableState.value.copy(listsLoadedAtMs = null, failure = null)
                            TraktCustomListActionResult.Success
                        }
                        TraktCustomListWriteResult.Disconnected -> actionFailure(TraktCustomListsFailure.DISCONNECTED)
                        TraktCustomListWriteResult.Failed -> actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                }
            }
        }
        if (result == TraktCustomListActionResult.Success) refreshLists(force = true)
        return result
    }

    override suspend fun deleteList(listId: Long): TraktCustomListActionResult =
        mutateLists(deleteId = listId)

    override suspend fun reorderLists(listIds: List<Long>): TraktCustomListActionResult =
        mutateLists(orderedIds = listIds)

    private suspend fun mutateLists(
        deleteId: Long? = null,
        orderedIds: List<Long>? = null,
    ): TraktCustomListActionResult {
        require((deleteId != null) xor (orderedIds != null))
        if (deleteId != null) require(deleteId > 0L)
        if (orderedIds != null) require(orderedIds.isNotEmpty() && orderedIds.all { it > 0L } &&
            orderedIds.distinct().size == orderedIds.size)

        if (!mutableState.value.listsLoaded) {
            when (val loaded = refreshLists(force = true)) {
                is TraktCustomListRefreshResult.Failure -> return TraktCustomListActionResult.Failure(loaded.reason)
                is TraktCustomListRefreshResult.Success -> Unit
            }
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock actionFailure(TraktCustomListsFailure.DISCONNECTED)
                    val current = mutableState.value
                    if (current.accountUsername != owner || !current.listsLoaded) {
                        return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                    }
                    val currentIds = current.lists.map { it.id }
                    if (deleteId != null && deleteId !in currentIds) {
                        return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                    }
                    if (orderedIds != null && (orderedIds.size != currentIds.size || orderedIds.toSet() != currentIds.toSet())) {
                        return@withLock actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                    }
                    val response = if (deleteId != null) source.deleteList(deleteId)
                        else source.reorderLists(requireNotNull(orderedIds))
                    when (response) {
                        TraktCustomListWriteResult.Disconnected -> actionFailure(TraktCustomListsFailure.DISCONNECTED)
                        TraktCustomListWriteResult.Failed -> actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                        TraktCustomListWriteResult.Success -> {
                            if (!account.state.value.connected || account.state.value.username != owner) {
                                return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                            }
                            if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                            val latest = mutableState.value
                            val nextLists = if (deleteId != null) {
                                latest.lists.filterNot { it.id == deleteId }
                            } else {
                                val byId = latest.lists.associateBy { it.id }
                                requireNotNull(orderedIds).map { byId.getValue(it) }
                            }
                            mutableState.value = latest.copy(
                                lists = nextLists,
                                listsLoadedAtMs = System.currentTimeMillis(),
                                itemsByList = if (deleteId == null) latest.itemsByList else latest.itemsByList - deleteId,
                                itemsLoadedAtMs = if (deleteId == null) latest.itemsLoadedAtMs else latest.itemsLoadedAtMs - deleteId,
                                failure = null,
                            )
                            TraktCustomListActionResult.Success
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                }
            }
        }
    }

    override suspend fun reorderItems(
        listId: Long,
        items: List<com.streamvault.domain.trakt.TraktCustomListItem>,
    ): TraktCustomListActionResult {
        require(listId > 0L)
        if (!mutableState.value.itemsLoaded(listId)) {
            when (val loaded = refreshItems(listId, force = true)) {
                is TraktCustomListItemsRefreshResult.Failure -> return TraktCustomListActionResult.Failure(loaded.reason)
                is TraktCustomListItemsRefreshResult.Success -> Unit
            }
        }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock actionFailure(TraktCustomListsFailure.DISCONNECTED)
                    val current = mutableState.value
                    if (current.accountUsername != owner || current.list(listId) == null || !current.itemsLoaded(listId)) {
                        return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                    }
                    val currentItems = current.itemsByList[listId].orEmpty()
                    val requestedIds = items.mapNotNull { it.listItemId }
                    val currentIds = currentItems.mapNotNull { it.listItemId }
                    if (items.size != currentItems.size || items.map { it.identity }.toSet() != currentItems.map { it.identity }.toSet() ||
                        requestedIds.size != items.size || requestedIds.distinct().size != requestedIds.size ||
                        currentIds.size != currentItems.size || currentIds.toSet() != requestedIds.toSet()
                    ) return@withLock actionFailure(TraktCustomListsFailure.REQUEST_FAILED)

                    when (source.reorderItems(listId, requestedIds)) {
                        TraktCustomListWriteResult.Disconnected -> actionFailure(TraktCustomListsFailure.DISCONNECTED)
                        TraktCustomListWriteResult.Failed -> actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                        TraktCustomListWriteResult.Success -> {
                            if (!account.state.value.connected || account.state.value.username != owner) {
                                return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                            }
                            if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                            val latest = mutableState.value
                            mutableState.value = latest.copy(
                                itemsByList = latest.itemsByList + (listId to items.mapIndexed { index, item ->
                                    item.copy(rank = index + 1)
                                }),
                                itemsLoadedAtMs = latest.itemsLoadedAtMs + (listId to System.currentTimeMillis()),
                                failure = null,
                            )
                            TraktCustomListActionResult.Success
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                }
            }
        }
    }

    override suspend fun setItem(
        listId: Long,
        item: TraktCustomListItem,
        included: Boolean,
    ): TraktCustomListActionResult {
        require(listId > 0L)
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                val identity = item.identity
                val key = listId to identity
                try {
                    if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                    val owner = account.state.value.username?.takeIf { account.state.value.connected }
                        ?: return@withLock actionFailure(TraktCustomListsFailure.DISCONNECTED)
                    val current = mutableState.value
                    if (current.accountUsername != owner || current.list(listId) == null) {
                        return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                    }
                    if (listId in current.loadingItems) return@withLock actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                    val containsKnown = current.itemsLoaded(listId)
                    val exists = current.contains(listId, identity)
                    if (containsKnown && exists == included) return@withLock TraktCustomListActionResult.Success
                    mutableState.value = current.copy(updatingItems = current.updatingItems + key, failure = null)
                    when (source.setItem(listId, identity, included)) {
                        TraktCustomListWriteResult.Success -> {
                            if (!account.state.value.connected || account.state.value.username != owner) {
                                return@withLock actionFailure(TraktCustomListsFailure.ACCOUNT_CHANGED)
                            }
                            if (history.isIncognitoModeEnabled()) return@withLock actionFailure(TraktCustomListsFailure.INCOGNITO)
                            val latest = mutableState.value
                            val oldItems = latest.itemsByList[listId].orEmpty()
                            val updatedItems = if (latest.itemsLoaded(listId)) {
                                if (included) {
                                    oldItems.filterNot { it.identity == identity } + item
                                } else oldItems.filterNot { it.identity == identity }
                            } else oldItems
                            // An unloaded list stays unloaded; a successful write is reflected in the
                            // cached count only when its prior item snapshot was complete.
                            val countDelta = if (latest.itemsLoaded(listId) && exists != included) {
                                if (included) 1 else -1
                            } else 0
                            mutableState.value = latest.copy(
                                lists = latest.lists.map { list ->
                                    if (list.id == listId) list.copy(itemCount = (list.itemCount + countDelta).coerceAtLeast(0)) else list
                                },
                                itemsByList = latest.itemsByList + (listId to updatedItems),
                                updatingItems = latest.updatingItems - key,
                                failure = null,
                            )
                            TraktCustomListActionResult.Success
                        }
                        TraktCustomListWriteResult.Disconnected -> actionFailure(TraktCustomListsFailure.DISCONNECTED)
                        TraktCustomListWriteResult.Failed -> actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    actionFailure(TraktCustomListsFailure.REQUEST_FAILED)
                } finally {
                    val latest = mutableState.value
                    if (key in latest.updatingItems) {
                        mutableState.value = latest.copy(updatingItems = latest.updatingItems - key)
                    }
                }
            }
        }
        // Re-read a newly added entry so its stable Trakt list-item id is available for reordering.
        if (result == TraktCustomListActionResult.Success && included && mutableState.value.itemsLoaded(listId)) {
            refreshItems(listId, force = true)
        }
        return result
    }

    private fun listsFailure(reason: TraktCustomListsFailure): TraktCustomListRefreshResult.Failure {
        val clear = reason == TraktCustomListsFailure.DISCONNECTED || reason == TraktCustomListsFailure.INCOGNITO ||
            reason == TraktCustomListsFailure.ACCOUNT_CHANGED
        val current = mutableState.value
        mutableState.value = if (clear) TraktCustomListsState(connected = account.state.value.connected, failure = reason)
        else current.copy(refreshingLists = false, failure = reason)
        return TraktCustomListRefreshResult.Failure(reason)
    }

    private fun itemsFailure(reason: TraktCustomListsFailure, listId: Long): TraktCustomListItemsRefreshResult.Failure {
        val clear = reason == TraktCustomListsFailure.DISCONNECTED || reason == TraktCustomListsFailure.INCOGNITO ||
            reason == TraktCustomListsFailure.ACCOUNT_CHANGED
        val current = mutableState.value
        mutableState.value = if (clear) TraktCustomListsState(connected = account.state.value.connected, failure = reason)
        else current.copy(loadingItems = current.loadingItems - listId, failure = reason)
        return TraktCustomListItemsRefreshResult.Failure(reason)
    }

    private fun actionFailure(reason: TraktCustomListsFailure): TraktCustomListActionResult.Failure {
        val clear = reason == TraktCustomListsFailure.DISCONNECTED || reason == TraktCustomListsFailure.INCOGNITO ||
            reason == TraktCustomListsFailure.ACCOUNT_CHANGED
        val current = mutableState.value
        mutableState.value = if (clear) TraktCustomListsState(connected = account.state.value.connected, failure = reason)
        else current.copy(updatingItems = emptySet(), failure = reason)
        return TraktCustomListActionResult.Failure(reason)
    }

    private companion object {
        const val PAGE_SIZE = 250
        const val MAX_PAGES = 50
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
