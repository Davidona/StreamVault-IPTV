package com.streamvault.data.trakt

import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.trakt.TraktAccount
import com.streamvault.domain.trakt.TraktAccountState
import com.streamvault.domain.trakt.TraktCustomList
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItem
import com.streamvault.domain.trakt.TraktCustomListItemsPage
import com.streamvault.domain.trakt.TraktCustomListItemsPageResult
import com.streamvault.domain.trakt.TraktCustomListWriteResult
import com.streamvault.domain.trakt.TraktCustomListsPageResult
import com.streamvault.domain.trakt.TraktCustomListsSource
import com.streamvault.domain.trakt.TraktCustomListActionResult
import com.streamvault.domain.trakt.TraktCustomListRefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TraktCustomListsServiceTest {
    private class Account : TraktAccount {
        override val state = MutableStateFlow(TraktAccountState(configured = true, connected = true, username = "viewer"))
        override suspend fun load() = Unit
        override suspend fun beginSignIn() = error("Not used")
        override suspend fun completeSignIn(callback: String) = error("Not used")
        override suspend fun cancelSignIn() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun refreshProfile() = Unit
    }

    private class Source : TraktCustomListsSource {
        var listsValue = listOf(
            TraktCustomList(1, "First", itemCount = 1),
            TraktCustomList(2, "Second", itemCount = 2),
            TraktCustomList(3, "Third", itemCount = 3),
        )
        var writeResult = TraktCustomListWriteResult.Success
        var itemsValue = emptyList<TraktCustomListItem>()
        val reordered = mutableListOf<List<Long>>()
        val reorderedItems = mutableListOf<Pair<Long, List<Long>>>()
        val deleted = mutableListOf<Long>()
        override suspend fun lists() = TraktCustomListsPageResult.Success(listsValue)
        override suspend fun items(listId: Long, type: TraktCustomListItemType, page: Int) =
            TraktCustomListItemsPageResult.Success(TraktCustomListItemsPage(
                itemsValue.filter { it.identity.type == type }, page, hasMore = false,
            ))
        override suspend fun createList(name: String, description: String?) = writeResult
        override suspend fun deleteList(listId: Long): TraktCustomListWriteResult {
            deleted += listId
            return writeResult
        }
        override suspend fun reorderLists(listIds: List<Long>): TraktCustomListWriteResult {
            reordered += listIds
            return writeResult
        }
        override suspend fun reorderItems(listId: Long, listItemIds: List<Long>): TraktCustomListWriteResult {
            reorderedItems += listId to listItemIds
            return writeResult
        }
        override suspend fun setItem(
            listId: Long,
            identity: com.streamvault.domain.trakt.TraktCustomListItemIdentity,
            included: Boolean,
        ) = writeResult
    }

    private fun service(account: Account, source: Source, incognito: Boolean = false): TraktCustomListsService {
        val history: PlaybackHistoryRepository = mock()
        runBlocking { whenever(history.isIncognitoModeEnabled()).thenReturn(incognito) }
        return TraktCustomListsService(account, source, history)
    }

    @Test fun `list reordering persists full order and updates visible state`() = runBlocking {
        val source = Source()
        val service = service(Account(), source)
        assertTrue(service.refreshLists(force = true) is TraktCustomListRefreshResult.Success)

        assertEquals(TraktCustomListActionResult.Success, service.reorderLists(listOf(3, 1, 2)))
        assertEquals(listOf(3L, 1L, 2L), source.reordered.single())
        assertEquals(listOf(3L, 1L, 2L), service.state.value.lists.map { it.id })
    }

    @Test fun `delete removes only the selected list and its cached contents`() = runBlocking {
        val source = Source()
        val service = service(Account(), source)
        assertTrue(service.refreshLists(force = true) is TraktCustomListRefreshResult.Success)
        assertTrue(service.refreshItems(2, force = true).isSuccess())

        assertEquals(TraktCustomListActionResult.Success, service.deleteList(2))
        assertEquals(listOf(2L), source.deleted)
        assertEquals(listOf(1L, 3L), service.state.value.lists.map { it.id })
        assertFalse(2L in service.state.value.itemsByList)
    }

    @Test fun `items can be reordered when the complete list has Trakt entry ids`() = runBlocking {
        val source = Source()
        val service = service(Account(), source)
        assertTrue(service.refreshLists(force = true) is TraktCustomListRefreshResult.Success)
        val first = TraktCustomListItem(
            com.streamvault.domain.trakt.TraktCustomListItemIdentity(TraktCustomListItemType.MOVIE, 603),
            "A", rank = 1, listItemId = 71,
        )
        val second = TraktCustomListItem(
            com.streamvault.domain.trakt.TraktCustomListItemIdentity(TraktCustomListItemType.SHOW, 1396),
            "B", rank = 2, listItemId = 72,
        )
        source.itemsValue = listOf(first, second)
        assertTrue(service.refreshItems(1, force = true).isSuccess())

        assertEquals(TraktCustomListActionResult.Success, service.reorderItems(1, listOf(second, first)))
        assertEquals(1L to listOf(72L, 71L), source.reorderedItems.single())
        assertEquals(listOf(second.identity, first.identity), service.state.value.itemsByList.getValue(1).map { it.identity })
    }

    @Test fun `incognito mode blocks deleting and reordering personal lists`() = runBlocking {
        val source = Source()
        val service = service(Account(), source, incognito = true)

        assertEquals(TraktCustomListActionResult.Failure(com.streamvault.domain.trakt.TraktCustomListsFailure.INCOGNITO),
            service.deleteList(1))
        assertEquals(TraktCustomListActionResult.Failure(com.streamvault.domain.trakt.TraktCustomListsFailure.INCOGNITO),
            service.reorderLists(listOf(1, 2, 3)))
        assertTrue(source.deleted.isEmpty())
        assertTrue(source.reordered.isEmpty())
    }

    private fun com.streamvault.domain.trakt.TraktCustomListItemsRefreshResult.isSuccess() =
        this is com.streamvault.domain.trakt.TraktCustomListItemsRefreshResult.Success
}
