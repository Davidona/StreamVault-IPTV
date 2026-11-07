package com.streamvault.data.repository

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.CategoryCount
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.*
import kotlinx.coroutines.flow.first
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.FavoriteOrderSaveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao,
    private val transactionRunner: DatabaseTransactionRunner
) : FavoriteRepository {
    private companion object {
        const val POSITION_STEP = 1_024
    }

    override fun getFavorites(providerId: Long, contentType: ContentType?): Flow<List<Favorite>> {
        val flow = if (contentType != null) {
            favoriteDao.getGlobalByType(providerId, contentType.name)
        } else {
            favoriteDao.getAllGlobal(providerId)
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override fun getFavorites(providerIds: List<Long>, contentType: ContentType?): Flow<List<Favorite>> {
        if (providerIds.isEmpty()) return flowOf(emptyList())
        val flow = if (contentType != null) {
            favoriteDao.getGlobalByTypeForProviders(providerIds, contentType.name)
        } else {
            favoriteDao.getAllGlobalByProviders(providerIds)
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override fun getAllFavorites(providerIds: List<Long>, contentType: ContentType): Flow<List<Favorite>> {
        if (providerIds.isEmpty()) return flowOf(emptyList())
        return favoriteDao.getAllByTypeForProviders(providerIds, contentType.name)
            .map { entities -> entities.map { it.toDomain() } }
    }

    @Deprecated(
        "Use getFavorites(providerId, contentType) instead",
        ReplaceWith("getFavorites(providerId, contentType)")
    )
    override fun getAllFavorites(providerId: Long, contentType: ContentType): Flow<List<Favorite>> =
        favoriteDao.getAllByType(providerId, contentType.name)
            .map { entities -> entities.map { it.toDomain() } }

    override fun getFavoritesByGroup(groupId: Long): Flow<List<Favorite>> =
        favoriteDao.getByGroup(groupId).map { entities -> entities.map { it.toDomain() } }

    override fun getGroups(providerId: Long, contentType: ContentType): Flow<List<VirtualGroup>> =
        virtualGroupDao.getByType(providerId, contentType.name).map { entities -> entities.map { it.toDomain() } }

    override fun getGroups(providerIds: List<Long>, contentType: ContentType): Flow<List<VirtualGroup>> {
        if (providerIds.isEmpty()) return flowOf(emptyList())
        return virtualGroupDao.getByTypeForProviders(providerIds, contentType.name)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override fun getGlobalFavoriteCount(providerId: Long, contentType: ContentType): Flow<Int> =
        favoriteDao.getGlobalFavoriteCount(providerId, contentType.name)

    override fun getGroupFavoriteCounts(providerId: Long, contentType: ContentType): Flow<Map<Long, Int>> =
        favoriteDao.getGroupFavoriteCounts(providerId, contentType.name)
            .map { list -> list.associate { it.categoryId to it.item_count } }

    override fun getGroupFavoriteCounts(providerIds: List<Long>, contentType: ContentType): Flow<Map<Long, Int>> {
        if (providerIds.isEmpty()) return flowOf(emptyMap())
        return favoriteDao.getGroupFavoriteCountsForProviders(providerIds, contentType.name)
            .map { list -> list.associate { it.categoryId to it.item_count } }
    }

    override suspend fun addFavorite(
        providerId: Long,
        contentId: Long,
        contentType: ContentType,
        groupId: Long?
    ): Result<Unit> = try {
        transactionRunner.inTransaction {
            validateGroupAssignment(providerId, contentType, groupId)
            if (favoriteDao.get(providerId, contentId, contentType.name, groupId) != null) {
                return@inTransaction
            }
            val maxPos = favoriteDao.getMaxPosition(providerId, groupId) ?: -1
            val favorite = Favorite(
                providerId = providerId,
                contentId = contentId,
                contentType = contentType,
                position = if (maxPos < 0) 0 else maxPos + POSITION_STEP,
                groupId = groupId
            )
            favoriteDao.insert(favorite.toEntity())
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to add favorite: ${e.message}", e)
    }

    override suspend fun removeFavorite(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?): Result<Unit> = try {
        favoriteDao.delete(providerId, contentId, contentType.name, groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to remove favorite: ${e.message}", e)
    }

    override suspend fun moveFavoriteToGroup(
        providerId: Long,
        contentId: Long,
        contentType: ContentType,
        fromGroupId: Long?,
        targetGroupId: Long?
    ): Result<Unit> {
        return try {
        if (fromGroupId == targetGroupId) {
            return Result.success(Unit)
        }

        transactionRunner.inTransaction {
            validateGroupAssignment(providerId, contentType, targetGroupId)
            val sourceFavorite = favoriteDao.get(providerId, contentId, contentType.name, fromGroupId)
                ?: throw IllegalArgumentException("Favorite $contentId is not saved in the requested source group")
            val targetFavorite = favoriteDao.get(providerId, contentId, contentType.name, targetGroupId)
            if (targetFavorite != null) {
                favoriteDao.delete(providerId, contentId, contentType.name, fromGroupId)
            } else {
                favoriteDao.updateGroup(sourceFavorite.id, targetGroupId)
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to move favorite: ${e.message}", e)
        }
    }

    override suspend fun mergeGroupInto(sourceGroupId: Long, targetGroupId: Long): Result<Unit> = try {
        require(sourceGroupId != targetGroupId) { "Source and target groups must differ" }

        transactionRunner.inTransaction {
            val sourceGroup = virtualGroupDao.getById(sourceGroupId)
                ?: throw IllegalArgumentException("Favorite group $sourceGroupId does not exist")
            val targetGroup = virtualGroupDao.getById(targetGroupId)
                ?: throw IllegalArgumentException("Favorite group $targetGroupId does not exist")

            require(sourceGroup.providerId == targetGroup.providerId) {
                "Favorite groups must belong to the same provider"
            }
            require(sourceGroup.contentType == targetGroup.contentType) {
                "Favorite groups must have the same content type"
            }

            favoriteDao.getByGroup(sourceGroupId).first().forEach { favorite ->
                val targetFavorite = favoriteDao.get(
                    providerId = favorite.providerId,
                    contentId = favorite.contentId,
                    contentType = favorite.contentType.name,
                    groupId = targetGroupId
                )
                if (targetFavorite != null) {
                    favoriteDao.delete(favorite.providerId, favorite.contentId, favorite.contentType.name, sourceGroupId)
                } else {
                    favoriteDao.updateGroup(favorite.id, targetGroupId)
                }
            }

            virtualGroupDao.delete(sourceGroupId)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to merge groups: ${e.message}", e)
    }

    override suspend fun reorderFavorites(favorites: List<Favorite>): Result<Unit> = try {
        validateReorderPartition(favorites)
        val updates = buildReorderUpdates(favorites)
        if (updates.isNotEmpty()) {
            favoriteDao.updateAll(updates.map(Favorite::toEntity))
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reorder favorites: ${e.message}", e)
    }

    override suspend fun reorderFavoritesIfUnchanged(providerId: Long, contentType: ContentType, groupId: Long?,
        expected: List<Favorite>, orderedIds: List<Long>): FavoriteOrderSaveResult {
        if (providerId <= 0 || groupId != null && groupId <= 0 ||
            expected.any { it.providerId != providerId || it.contentType != contentType || it.groupId != groupId } ||
            expected.map { it.id }.distinct().size != expected.size ||
            orderedIds.size != expected.size || orderedIds.distinct().size != orderedIds.size ||
            orderedIds.toSet() != expected.map { it.id }.toSet()) return FavoriteOrderSaveResult.Invalid
        return transactionRunner.inTransaction {
            val current = favoriteDao.getPartitionSnapshot(providerId, contentType.name, groupId).map { it.toDomain() }
            // Remote backup identities are not stored in this table and are irrelevant to ordering.
            fun signature(items: List<Favorite>) = items.map { listOf(it.id, it.providerId, it.contentId, it.position.toLong(), it.groupId, it.addedAt) }
            if (signature(current) != signature(expected.sortedWith(compareBy(Favorite::position, Favorite::id)))) {
                FavoriteOrderSaveResult.Conflict
            } else {
                val byId = current.associateBy { it.id }
                val updates = buildReorderUpdates(orderedIds.map(byId::getValue))
                if (updates.isNotEmpty()) favoriteDao.updateAll(updates.map(Favorite::toEntity))
                FavoriteOrderSaveResult.Saved
            }
        }
    }

    // Checks if content is in Global Favorites (groupId = null)
    override suspend fun isFavorite(providerId: Long, contentId: Long, contentType: ContentType): Boolean =
        favoriteDao.get(providerId, contentId, contentType.name, null) != null

    override suspend fun getGroupMemberships(providerId: Long, contentId: Long, contentType: ContentType): List<Long> =
        favoriteDao.getGroupMemberships(providerId, contentId, contentType.name)

    override suspend fun createGroup(providerId: Long, name: String, iconEmoji: String?, contentType: ContentType): Result<VirtualGroup> = try {
        val position = (virtualGroupDao.getMaxPosition(providerId, contentType.name) ?: -POSITION_STEP) + POSITION_STEP
        val identityKey = java.util.UUID.randomUUID().toString()
        val id = virtualGroupDao.insert(
            com.streamvault.data.local.entity.VirtualGroupEntity(
                providerId = providerId,
                name = name,
                iconEmoji = iconEmoji,
                position = position,
                contentType = contentType,
                identityKey = identityKey
            )
        )
        Result.success(
            VirtualGroup(
                id = id,
                providerId = providerId,
                name = name,
                iconEmoji = iconEmoji,
                position = position,
                contentType = contentType,
                identityKey = identityKey
            )
        )
    } catch (e: Exception) {
        Result.error("Failed to create group: ${e.message}", e)
    }

    override suspend fun deleteGroup(groupId: Long): Result<Unit> = try {
        transactionRunner.inTransaction {
            favoriteDao.getByGroup(groupId).first().forEach { favorite ->
                val globalFavorite = favoriteDao.get(
                    providerId = favorite.providerId,
                    contentId = favorite.contentId,
                    contentType = favorite.contentType.name,
                    groupId = null
                )
                if (globalFavorite != null) {
                    favoriteDao.delete(favorite.providerId, favorite.contentId, favorite.contentType.name, groupId)
                } else {
                    favoriteDao.updateGroup(favorite.id, null)
                }
            }
            virtualGroupDao.delete(groupId)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to delete group: ${e.message}", e)
    }

    override suspend fun renameGroup(groupId: Long, newName: String): Result<Unit> = try {
        virtualGroupDao.rename(groupId, newName)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to rename group: ${e.message}", e)
    }

    private fun buildReorderUpdates(favorites: List<Favorite>): List<Favorite> {
        if (favorites.size < 2) return emptyList()

        val currentById = favorites.associateBy(Favorite::id)
        val oldOrder = favorites.sortedBy(Favorite::position).map(Favorite::id)
        val newOrder = favorites.map(Favorite::id)
        if (oldOrder == newOrder) return emptyList()

        val firstMismatch = newOrder.indices.first { index -> newOrder[index] != oldOrder[index] }
        val lastMismatch = newOrder.indices.last { index -> newOrder[index] != oldOrder[index] }

        val localReposition = assignSparsePositions(
            items = favorites.subList(firstMismatch, lastMismatch + 1),
            leftBound = favorites.getOrNull(firstMismatch - 1)?.position?.toLong(),
            rightBound = favorites.getOrNull(lastMismatch + 1)?.position?.toLong()
        )

        val reassigned = localReposition ?: favorites.mapIndexed { index, favorite ->
            favorite.copy(position = index * POSITION_STEP)
        }

        return reassigned.filter { updated ->
            updated.position != currentById.getValue(updated.id).position
        }
    }

    private fun validateReorderPartition(favorites: List<Favorite>) {
        if (favorites.isEmpty()) return

        require(favorites.map(Favorite::id).distinct().size == favorites.size) {
            "Favorite reorder request contains duplicate items"
        }

        val partitions = favorites
            .map { favorite -> favorite.providerId to favorite.groupId }
            .distinct()
        require(partitions.size == 1) {
            "Favorite reorder request must stay within one provider and group partition"
        }
    }

    private suspend fun validateGroupAssignment(
        providerId: Long,
        contentType: ContentType,
        groupId: Long?
    ) {
        val targetGroupId = groupId ?: return
        val group = virtualGroupDao.getById(targetGroupId)
            ?: throw IllegalArgumentException("Favorite group $targetGroupId does not exist")

        require(group.providerId == providerId) {
            "Favorite group $targetGroupId belongs to provider ${group.providerId}, not $providerId"
        }
        require(group.contentType == contentType) {
            "Favorite group $targetGroupId accepts ${group.contentType}, not $contentType"
        }
    }

    private fun assignSparsePositions(
        items: List<Favorite>,
        leftBound: Long?,
        rightBound: Long?
    ): List<Favorite>? {
        if (items.isEmpty()) return emptyList()

        val positions = when {
            leftBound == null && rightBound == null -> {
                items.indices.map { index -> index.toLong() * POSITION_STEP }
            }

            leftBound == null -> {
                val right = rightBound ?: return null
                if (right <= items.size.toLong()) return null
                val step = right / (items.size + 1L)
                if (step <= 0L) return null
                List(items.size) { index -> step * (index + 1L) }
            }

            rightBound == null -> {
                List(items.size) { index -> leftBound + ((index + 1L) * POSITION_STEP) }
            }

            else -> {
                val gap = rightBound - leftBound - 1L
                if (gap < items.size.toLong()) return null
                val step = gap / (items.size + 1L)
                if (step <= 0L) return null
                List(items.size) { index -> leftBound + (step * (index + 1L)) }
            }
        }

        if (positions.any { it !in 0..Int.MAX_VALUE.toLong() }) return null
        if (positions.zipWithNext().any { (first, second) -> first >= second }) return null
        if (rightBound != null && positions.last() >= rightBound) return null

        return items.zip(positions).map { (favorite, position) ->
            favorite.copy(position = position.toInt())
        }
    }
}
