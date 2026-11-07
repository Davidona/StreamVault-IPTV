package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.CategoryCount
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.VirtualGroupEntity
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.FavoriteOrderSaveResult
import com.streamvault.data.mapper.toDomain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteRepositoryImplTest {

    private val favoriteDao: FavoriteDao = mock()
    private val virtualGroupDao: VirtualGroupDao = mock {
        on { getByType(any(), any()) } doAnswer { emptyFlow() }
    }
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private val repository = FavoriteRepositoryImpl(
        favoriteDao = favoriteDao,
        virtualGroupDao = virtualGroupDao,
        transactionRunner = transactionRunner
    )

    @Test fun `conditional reorder checks full membership and positions inside transaction`() = runTest {
        var inside = false
        var checkedInside = false
        var updatedInside = false
        val first = FavoriteEntity(id = 1, providerId = 7, contentId = 101, contentType = ContentType.LIVE, position = 0, addedAt = 1)
        val second = FavoriteEntity(id = 2, providerId = 7, contentId = 102, contentType = ContentType.LIVE, position = 1024, addedAt = 2)
        whenever(favoriteDao.getPartitionSnapshot(7, "LIVE", null)).thenAnswer { checkedInside = inside; listOf(first, second) }
        whenever(favoriteDao.updateAll(any())).thenAnswer { updatedInside = inside; Unit }
        val runner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                inside = true
                return try { block() } finally { inside = false }
            }
        }
        val repo = FavoriteRepositoryImpl(favoriteDao, virtualGroupDao, runner)
        val expected = listOf(first.toDomain(), second.toDomain())
        assertThat(repo.reorderFavoritesIfUnchanged(7, ContentType.LIVE, null, expected, listOf(2, 1))).isEqualTo(FavoriteOrderSaveResult.Saved)
        assertThat(checkedInside).isTrue()
        assertThat(updatedInside).isTrue()
        assertThat(repo.reorderFavoritesIfUnchanged(7, ContentType.LIVE, null, expected.dropLast(1), listOf(1))).isEqualTo(FavoriteOrderSaveResult.Conflict)
        assertThat(repo.reorderFavoritesIfUnchanged(7, ContentType.LIVE, null, expected, listOf(1, 1))).isEqualTo(FavoriteOrderSaveResult.Invalid)
    }

    @Test
    fun `addFavorite runs max-position lookup and insert in one transaction`() = runTest {
        var inTransaction = false
        var getInsideTransaction = false
        var getMaxInsideTransaction = false
        var insertInsideTransaction = false

        val transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T {
                check(!inTransaction)
                inTransaction = true
                return try {
                    block()
                } finally {
                    inTransaction = false
                }
            }
        }

        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, null)).thenAnswer {
            getInsideTransaction = inTransaction
            null
        }
        whenever(favoriteDao.getMaxPosition(7L, null)).thenAnswer {
            getMaxInsideTransaction = inTransaction
            4
        }
        whenever(favoriteDao.insert(any())).thenAnswer {
            insertInsideTransaction = inTransaction
            Unit
        }

        val repository = FavoriteRepositoryImpl(
            favoriteDao = favoriteDao,
            virtualGroupDao = virtualGroupDao,
            transactionRunner = transactionRunner
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = null
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(getInsideTransaction).isTrue()
        assertThat(getMaxInsideTransaction).isTrue()
        assertThat(insertInsideTransaction).isTrue()

        val favoriteCaptor = argumentCaptor<FavoriteEntity>()
        verify(favoriteDao).get(7L, 42L, ContentType.LIVE.name, null)
        verify(favoriteDao).insert(favoriteCaptor.capture())
        verify(favoriteDao).getMaxPosition(7L, null)
        assertThat(favoriteCaptor.firstValue.providerId).isEqualTo(7L)
        assertThat(favoriteCaptor.firstValue.position).isEqualTo(1028)
    }

    @Test
    fun `addFavorite is a no-op when global favorite already exists`() = runTest {
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, null)).thenReturn(
            FavoriteEntity(
                id = 9L,
                providerId = 7L,
                contentId = 42L,
                contentType = ContentType.LIVE,
                position = 512,
                groupId = null,
                addedAt = 1_000L
            )
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = null
        )

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).get(7L, 42L, ContentType.LIVE.name, null)
        verify(favoriteDao, never()).getMaxPosition(any(), any())
        verify(favoriteDao, never()).insert(any())
    }

    @Test
    fun `addFavorite is a no-op when group favorite already exists`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 7L,
                name = "Live Group",
                contentType = ContentType.LIVE
            )
        )
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, 11L)).thenReturn(
            FavoriteEntity(
                id = 12L,
                providerId = 7L,
                contentId = 42L,
                contentType = ContentType.LIVE,
                position = 256,
                groupId = 11L,
                addedAt = 2_000L
            )
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = 11L
        )

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).get(7L, 42L, ContentType.LIVE.name, 11L)
        verify(favoriteDao, never()).getMaxPosition(any(), any())
        verify(favoriteDao, never()).insert(any())
    }

    @Test
    fun `getGroupFavoriteCounts for providers aggregates grouped counts across provider set`() = runTest {
        whenever(favoriteDao.getGroupFavoriteCountsForProviders(listOf(7L, 8L), ContentType.LIVE.name)).thenReturn(
            flowOf(
                listOf(
                    CategoryCount(categoryId = 11L, item_count = 2),
                    CategoryCount(categoryId = 12L, item_count = 1)
                )
            )
        )

        val counts = repository.getGroupFavoriteCounts(listOf(7L, 8L), ContentType.LIVE).first()

        assertThat(counts).containsExactly(11L, 2, 12L, 1)
    }

    @Test
    fun `getGroupFavoriteCounts for providers skips dao when provider set is empty`() = runTest {
        val counts = repository.getGroupFavoriteCounts(emptyList(), ContentType.LIVE).first()

        assertThat(counts).isEmpty()
        verify(favoriteDao, never()).getGroupFavoriteCountsForProviders(any(), any())
    }

    @Test
    fun `createGroup assigns sparse position after existing groups`() = runTest {
        whenever(virtualGroupDao.getMaxPosition(7L, ContentType.LIVE.name)).thenReturn(2_048)
        whenever(virtualGroupDao.insert(any())).thenReturn(15L)

        val result = repository.createGroup(
            providerId = 7L,
            name = "Sports",
            iconEmoji = "",
            contentType = ContentType.LIVE
        )

        assertThat(result.isSuccess).isTrue()
        val groupCaptor = argumentCaptor<VirtualGroupEntity>()
        verify(virtualGroupDao).getMaxPosition(7L, ContentType.LIVE.name)
        verify(virtualGroupDao).insert(groupCaptor.capture())
        assertThat(groupCaptor.firstValue.position).isEqualTo(3_072)
        assertThat(result.getOrNull()?.position).isEqualTo(3_072)
    }

    @Test
    fun `createGroup starts first group at zero position`() = runTest {
        whenever(virtualGroupDao.getMaxPosition(7L, ContentType.MOVIE.name)).thenReturn(null)
        whenever(virtualGroupDao.insert(any())).thenReturn(16L)

        val result = repository.createGroup(
            providerId = 7L,
            name = "Movies",
            iconEmoji = null,
            contentType = ContentType.MOVIE
        )

        assertThat(result.isSuccess).isTrue()
        val groupCaptor = argumentCaptor<VirtualGroupEntity>()
        verify(virtualGroupDao).getMaxPosition(7L, ContentType.MOVIE.name)
        verify(virtualGroupDao).insert(groupCaptor.capture())
        assertThat(groupCaptor.firstValue.position).isEqualTo(0)
        assertThat(result.getOrNull()?.position).isEqualTo(0)
    }

    @Test
    fun `addFavorite rejects groups from another provider`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 99L,
                name = "Other Provider",
                contentType = ContentType.LIVE
            )
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = 11L
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessageOrNull()).contains("belongs to provider")
        verify(favoriteDao, never()).getMaxPosition(any(), any())
        verify(favoriteDao, never()).insert(any())
    }

    @Test
    fun `addFavorite rejects groups with another content type`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 7L,
                name = "Movies",
                contentType = ContentType.MOVIE
            )
        )

        val result = repository.addFavorite(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            groupId = 11L
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessageOrNull()).contains("accepts MOVIE")
        verify(favoriteDao, never()).getMaxPosition(any(), any())
        verify(favoriteDao, never()).insert(any())
    }

    @Test
    fun `reorderFavorites updates only affected favorites when sparse gap is available`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1_024),
            favorite(id = 3, contentId = 103, position = 2_048),
            favorite(id = 4, contentId = 104, position = 3_072)
        )

        val result = repository.reorderFavorites(
            listOf(favorites[0], favorites[2], favorites[1], favorites[3])
        )

        assertThat(result.isSuccess).isTrue()
        val favoritesCaptor = argumentCaptor<List<FavoriteEntity>>()
        verify(favoriteDao).updateAll(favoritesCaptor.capture())
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::id)).containsExactly(3L, 2L).inOrder()
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::position)).containsExactly(1023, 2046).inOrder()
    }

    @Test
    fun `reorderFavorites normalizes whole list when dense positions leave no gap`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1),
            favorite(id = 3, contentId = 103, position = 2),
            favorite(id = 4, contentId = 104, position = 3)
        )

        val result = repository.reorderFavorites(
            listOf(favorites[1], favorites[0], favorites[2], favorites[3])
        )

        assertThat(result.isSuccess).isTrue()
        val favoritesCaptor = argumentCaptor<List<FavoriteEntity>>()
        verify(favoriteDao).updateAll(favoritesCaptor.capture())
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::id)).containsExactly(2L, 1L, 3L, 4L).inOrder()
        assertThat(favoritesCaptor.firstValue.map(FavoriteEntity::position))
            .containsExactly(0, 1_024, 2_048, 3_072)
            .inOrder()
    }

    @Test
    fun `reorderFavorites skips writes when order is unchanged`() = runTest {
        val favorites = listOf(
            favorite(id = 1, contentId = 101, position = 0),
            favorite(id = 2, contentId = 102, position = 1_024),
            favorite(id = 3, contentId = 103, position = 2_048)
        )

        val result = repository.reorderFavorites(favorites)

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao, never()).updateAll(any())
    }

    @Test
    fun `reorderFavorites rejects mixed provider partitions`() = runTest {
        val result = repository.reorderFavorites(
            listOf(
                favorite(id = 1, contentId = 101, position = 0, providerId = 7L),
                favorite(id = 2, contentId = 102, position = 1_024, providerId = 8L)
            )
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessageOrNull()).contains("one provider and group partition")
        verify(favoriteDao, never()).updateAll(any())
    }

    @Test
    fun `reorderFavorites rejects mixed group partitions`() = runTest {
        val result = repository.reorderFavorites(
            listOf(
                favorite(id = 1, contentId = 101, position = 0, groupId = null),
                favorite(id = 2, contentId = 102, position = 1_024, groupId = 11L)
            )
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessageOrNull()).contains("one provider and group partition")
        verify(favoriteDao, never()).updateAll(any())
    }

    @Test
    fun `moveFavoriteToGroup updates group in one transaction when target is empty`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 7L,
                name = "Live Group",
                contentType = ContentType.LIVE
            )
        )
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, null)).thenReturn(
            FavoriteEntity(
                id = 9L,
                providerId = 7L,
                contentId = 42L,
                contentType = ContentType.LIVE,
                position = 512,
                groupId = null,
                addedAt = 1_000L
            )
        )
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, 11L)).thenReturn(null)

        val result = repository.moveFavoriteToGroup(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            fromGroupId = null,
            targetGroupId = 11L
        )

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).updateGroup(9L, 11L)
        verify(favoriteDao, never()).insert(any())
        verify(favoriteDao, never()).delete(7L, 42L, ContentType.LIVE.name, null)
    }

    @Test
    fun `moveFavoriteToGroup removes source duplicate when target already contains item`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 7L,
                name = "Live Group",
                contentType = ContentType.LIVE
            )
        )
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, null)).thenReturn(
            FavoriteEntity(
                id = 9L,
                providerId = 7L,
                contentId = 42L,
                contentType = ContentType.LIVE,
                position = 512,
                groupId = null,
                addedAt = 1_000L
            )
        )
        whenever(favoriteDao.get(7L, 42L, ContentType.LIVE.name, 11L)).thenReturn(
            FavoriteEntity(
                id = 10L,
                providerId = 7L,
                contentId = 42L,
                contentType = ContentType.LIVE,
                position = 768,
                groupId = 11L,
                addedAt = 2_000L
            )
        )

        val result = repository.moveFavoriteToGroup(
            providerId = 7L,
            contentId = 42L,
            contentType = ContentType.LIVE,
            fromGroupId = null,
            targetGroupId = 11L
        )

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).delete(7L, 42L, ContentType.LIVE.name, null)
        verify(favoriteDao, never()).updateGroup(any(), any())
    }

    @Test
    fun `mergeGroupInto moves unique favorites removes duplicates and deletes source group`() = runTest {
        whenever(virtualGroupDao.getById(11L)).thenReturn(
            VirtualGroupEntity(
                id = 11L,
                providerId = 7L,
                name = "Source",
                contentType = ContentType.LIVE
            )
        )
        whenever(virtualGroupDao.getById(12L)).thenReturn(
            VirtualGroupEntity(
                id = 12L,
                providerId = 7L,
                name = "Target",
                contentType = ContentType.LIVE
            )
        )
        whenever(favoriteDao.getByGroup(11L)).thenReturn(
            flowOf(
                listOf(
                    FavoriteEntity(
                        id = 1L,
                        providerId = 7L,
                        contentId = 101L,
                        contentType = ContentType.LIVE,
                        position = 0,
                        groupId = 11L,
                        addedAt = 1_000L
                    ),
                    FavoriteEntity(
                        id = 2L,
                        providerId = 7L,
                        contentId = 102L,
                        contentType = ContentType.LIVE,
                        position = 1_024,
                        groupId = 11L,
                        addedAt = 2_000L
                    )
                )
            )
        )
        whenever(favoriteDao.get(7L, 101L, ContentType.LIVE.name, 12L)).thenReturn(null)
        whenever(favoriteDao.get(7L, 102L, ContentType.LIVE.name, 12L)).thenReturn(
            FavoriteEntity(
                id = 3L,
                providerId = 7L,
                contentId = 102L,
                contentType = ContentType.LIVE,
                position = 2_048,
                groupId = 12L,
                addedAt = 3_000L
            )
        )

        val result = repository.mergeGroupInto(11L, 12L)

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).updateGroup(1L, 12L)
        verify(favoriteDao).delete(7L, 102L, ContentType.LIVE.name, 11L)
        verify(virtualGroupDao).delete(11L)
    }

    @Test
    fun `deleteGroup promotes members to global favorites when no duplicate exists`() = runTest {
        whenever(favoriteDao.getByGroup(11L)).thenReturn(
            flowOf(
                listOf(
                    FavoriteEntity(
                        id = 1L,
                        providerId = 7L,
                        contentId = 101L,
                        contentType = ContentType.LIVE,
                        position = 0,
                        groupId = 11L,
                        groupKey = 11L,
                        addedAt = 1_000L
                    )
                )
            )
        )
        whenever(favoriteDao.get(7L, 101L, ContentType.LIVE.name, null)).thenReturn(null)

        val result = repository.deleteGroup(11L)

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).updateGroup(1L, null)
        verify(favoriteDao, never()).delete(7L, 101L, ContentType.LIVE.name, 11L)
        verify(virtualGroupDao).delete(11L)
    }

    @Test
    fun `deleteGroup removes source membership when a global favorite already exists`() = runTest {
        whenever(favoriteDao.getByGroup(11L)).thenReturn(
            flowOf(
                listOf(
                    FavoriteEntity(
                        id = 1L,
                        providerId = 7L,
                        contentId = 101L,
                        contentType = ContentType.LIVE,
                        position = 0,
                        groupId = 11L,
                        groupKey = 11L,
                        addedAt = 1_000L
                    )
                )
            )
        )
        whenever(favoriteDao.get(7L, 101L, ContentType.LIVE.name, null)).thenReturn(
            FavoriteEntity(
                id = 2L,
                providerId = 7L,
                contentId = 101L,
                contentType = ContentType.LIVE,
                position = 1_024,
                groupId = null,
                groupKey = 0L,
                addedAt = 2_000L
            )
        )

        val result = repository.deleteGroup(11L)

        assertThat(result.isSuccess).isTrue()
        verify(favoriteDao).delete(7L, 101L, ContentType.LIVE.name, 11L)
        verify(favoriteDao, never()).updateGroup(1L, null)
        verify(virtualGroupDao).delete(11L)
    }

    private fun favorite(
        id: Long,
        contentId: Long,
        position: Int,
        providerId: Long = 7L,
        groupId: Long? = null
    ) = Favorite(
        id = id,
        providerId = providerId,
        contentId = contentId,
        contentType = ContentType.LIVE,
        position = position,
        groupId = groupId
    )
}
