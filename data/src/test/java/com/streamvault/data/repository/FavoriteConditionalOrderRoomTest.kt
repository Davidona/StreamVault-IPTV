package com.streamvault.data.repository

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.FavoriteOrderSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FavoriteConditionalOrderRoomTest {
    private lateinit var database: StreamVaultDatabase
    private lateinit var repository: FavoriteRepositoryImpl

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = FavoriteRepositoryImpl(database.favoriteDao(), database.virtualGroupDao(), RoomDatabaseTransactionRunner(database))
    }

    @After fun close() = database.close()

    @Test fun `concurrent phone and TV drafts cannot overwrite one another`() = runTest {
        database.providerDao().insert(ProviderEntity(id = 7, name = "Provider", type = ProviderType.M3U))
        val dao = database.favoriteDao()
        (1L..3L).forEach { id -> dao.insert(FavoriteEntity(id = id, providerId = 7, contentId = 100 + id,
            contentType = ContentType.LIVE, position = (id * 1024).toInt(), addedAt = id)) }
        val snapshot = dao.getPartitionSnapshot(7, ContentType.LIVE.name, null).map { it.toDomain() }
        val outcomes = withContext(Dispatchers.Default) {
            listOf(listOf(3L, 2L, 1L), listOf(2L, 1L, 3L)).map { ids ->
                async { repository.reorderFavoritesIfUnchanged(7, ContentType.LIVE, null, snapshot, ids) }
            }.awaitAll()
        }
        assertThat(outcomes.count { it == FavoriteOrderSaveResult.Saved }).isEqualTo(1)
        assertThat(outcomes.count { it == FavoriteOrderSaveResult.Conflict }).isEqualTo(1)
        assertThat(dao.getPartitionSnapshot(7, ContentType.LIVE.name, null).map { it.id })
            .isAnyOf(listOf(3L, 2L, 1L), listOf(2L, 1L, 3L))
    }
}
