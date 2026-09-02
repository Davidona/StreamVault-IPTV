package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.first

suspend fun <Item> loadVodReorderItems(
    providerId: Long,
    category: Category,
    contentType: ContentType,
    favoriteRepository: FavoriteRepository,
    loadByIds: suspend (List<Long>) -> List<Item>,
    itemId: (Item) -> Long
): List<Item> {
    if (!category.isVirtual) return emptyList()

    val groupId = category.reorderGroupId()
    val favorites = if (groupId == null) {
        favoriteRepository.getFavorites(providerId, contentType).first()
    } else {
        favoriteRepository.getFavoritesByGroup(groupId).first()
    }

    val orderedIds = favorites
        .sortedBy(Favorite::position)
        .map(Favorite::contentId)

    if (orderedIds.isEmpty()) return emptyList()
    return loadByIds(orderedIds).orderByIds(orderedIds, itemId)
}

fun <Item> moveVodItemUp(items: List<Item>, item: Item): List<Item> {
    val list = items.toMutableList()
    val index = list.indexOf(item)
    if (index <= 0) return items

    list.removeAt(index)
    list.add(index - 1, item)
    return list
}

fun <Item> moveVodItemDown(items: List<Item>, item: Item): List<Item> {
    val list = items.toMutableList()
    val index = list.indexOf(item)
    if (index < 0 || index >= list.lastIndex) return items

    list.removeAt(index)
    list.add(index + 1, item)
    return list
}

suspend fun <Item> saveVodReorder(
    providerId: Long,
    category: Category,
    currentItems: List<Item>,
    contentType: ContentType,
    favoriteRepository: FavoriteRepository,
    itemId: (Item) -> Long
) {
    val groupId = category.reorderGroupId()
    val favorites = if (groupId == null) {
        favoriteRepository.getFavorites(providerId, contentType).first()
    } else {
        favoriteRepository.getFavoritesByGroup(groupId).first()
    }

    val favoriteMap = favorites.associateBy(Favorite::contentId)
    val reorderedFavorites = currentItems.mapNotNull { item ->
        favoriteMap[itemId(item)]
    }

    favoriteRepository.reorderFavorites(reorderedFavorites)
}

private fun Category.reorderGroupId(): Long? =
    if (id == VodBrowseDefaults.FAVORITES_SENTINEL_ID) null else -id

private fun <Item> List<Item>.orderByIds(
    ids: List<Long>,
    itemId: (Item) -> Long
): List<Item> {
    val positions = ids.withIndex().associate { it.value to it.index }
    return sortedBy { item -> positions[itemId(item)] ?: Int.MAX_VALUE }
}

