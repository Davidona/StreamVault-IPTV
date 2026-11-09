package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VirtualGroup
import kotlinx.coroutines.flow.Flow

sealed interface FavoriteOrderSaveResult {
    data object Saved : FavoriteOrderSaveResult
    data object Conflict : FavoriteOrderSaveResult
    data object Invalid : FavoriteOrderSaveResult
}

interface FavoriteRepository {
    fun getFavorites(providerId: Long, contentType: ContentType? = null): Flow<List<Favorite>>
    fun getFavorites(providerIds: List<Long>, contentType: ContentType? = null): Flow<List<Favorite>>
    fun getAllFavorites(providerIds: List<Long>, contentType: ContentType): Flow<List<Favorite>>
    @Deprecated(
        "Use getFavorites(providerId, contentType) instead",
        replaceWith = ReplaceWith("getFavorites(providerId, contentType)")
    )
    fun getAllFavorites(providerId: Long, contentType: ContentType): Flow<List<Favorite>>
    fun getFavoritesByGroup(groupId: Long): Flow<List<Favorite>>
    fun getGroups(providerId: Long, contentType: ContentType): Flow<List<VirtualGroup>>
    fun getGroups(providerIds: List<Long>, contentType: ContentType): Flow<List<VirtualGroup>>

    fun getGlobalFavoriteCount(providerId: Long, contentType: ContentType): Flow<Int>
    fun getGroupFavoriteCounts(providerId: Long, contentType: ContentType): Flow<Map<Long, Int>>
    fun getGroupFavoriteCounts(providerIds: List<Long>, contentType: ContentType): Flow<Map<Long, Int>>

    suspend fun addFavorite(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long? = null): Result<Unit>
    suspend fun removeFavorite(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long? = null): Result<Unit>
    suspend fun moveFavoriteToGroup(
        providerId: Long,
        contentId: Long,
        contentType: ContentType,
        fromGroupId: Long?,
        targetGroupId: Long?
    ): Result<Unit>
    suspend fun mergeGroupInto(sourceGroupId: Long, targetGroupId: Long): Result<Unit>
    
    suspend fun reorderFavorites(favorites: List<Favorite>): Result<Unit>
    /** Rechecks complete partition membership and positions in the same transaction as the update. */
    suspend fun reorderFavoritesIfUnchanged(providerId: Long, contentType: ContentType, groupId: Long?,
        expected: List<Favorite>, orderedIds: List<Long>): FavoriteOrderSaveResult = throw UnsupportedOperationException()
    suspend fun isFavorite(providerId: Long, contentId: Long, contentType: ContentType): Boolean
    
    suspend fun getGroupMemberships(providerId: Long, contentId: Long, contentType: ContentType): List<Long>

    suspend fun createGroup(providerId: Long, name: String, iconEmoji: String? = null, contentType: ContentType): Result<VirtualGroup>
    suspend fun deleteGroup(groupId: Long): Result<Unit>
    suspend fun renameGroup(groupId: Long, newName: String): Result<Unit>
}
