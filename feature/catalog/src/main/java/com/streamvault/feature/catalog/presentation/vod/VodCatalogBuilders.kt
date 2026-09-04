package com.streamvault.feature.catalog.presentation.vod

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Favorite
import com.streamvault.feature.catalog.presentation.vod.matchesVodGroupMembership

data class VodCatalogSnapshot<Item>(
    val grouped: Map<String, List<Item>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

suspend fun <Item> buildVodPreviewCatalog(
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    providerCategories: List<Category>,
    providerCategoryCounts: Map<Long, Int>,
    libraryCount: Int,
    hiddenProviderCategoryIds: Set<Long>,
    loadItemsByIds: suspend (List<Long>) -> List<Item>,
    providerPreviews: Map<Long?, List<Item>>,
    itemIds: (Item) -> List<Long>,
    itemCategoryId: (Item) -> Long?,
    copyWithFavorite: (Item, Boolean) -> Item
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val previewRows = linkedMapOf<String, List<Item>>()
    val countMap = linkedMapOf<String, Int>()

    val favoritesIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .sortedBy(Favorite::position)
        .map(Favorite::contentId)
        .toList()
    if (favoritesIds.isNotEmpty()) {
        val previewIds = favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
        val preview = loadItemsByIds(previewIds)
            .orderByRequestedIds(previewIds, itemIds)
            .filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds }
            .let { items -> markVodFavorites(items, globalFavoriteIds, itemIds, copyWithFavorite) }
        if (preview.isNotEmpty()) {
            previewRows[VodBrowseDefaults.FAVORITES_CATEGORY] = preview
            countMap[VodBrowseDefaults.FAVORITES_CATEGORY] = favoritesIds.size
        }
    }

    val customCategoryPreviewIds = customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .associateWith { category ->
            allFavorites
                .asSequence()
                .filter { matchesVodGroupMembership(it.groupId, category.id) }
                .sortedBy(Favorite::position)
                .map(Favorite::contentId)
                .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                .toList()
        }

    val idsToPreload = buildSet {
        addAll(favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT))
        customCategoryPreviewIds.values.forEach(::addAll)
    }
    val preloadedById = if (idsToPreload.isEmpty()) {
        emptyMap()
    } else {
        loadItemsByIds(idsToPreload.toList())
            .flatMap { item -> itemIds(item).map { rawItemId -> rawItemId to item } }
            .toMap()
    }

    customCategoryPreviewIds.forEach { (category, previewIds) ->
        if (previewIds.isNotEmpty()) {
            val preview = previewIds.mapNotNull(preloadedById::get)
                .filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds }
                .let { items -> markVodFavorites(items, globalFavoriteIds, itemIds, copyWithFavorite) }
            if (preview.isNotEmpty()) {
                previewRows[category.name] = preview
                countMap[category.name] = allFavorites.count { favorite -> matchesVodGroupMembership(favorite.groupId, category.id) }
            }
        }
    }

    val requestedProviderCategoryIds = providerPreviews.keys.filterNotNull().toSet()
    providerCategories.forEach { category ->
        countMap[category.name] = providerCategoryCounts[category.id] ?: providerPreviews[category.id]?.size ?: 0
    }
    providerCategories
        .filter { category -> category.id in requestedProviderCategoryIds }
        .forEach { category ->
            val preview = providerPreviews[category.id].orEmpty()
                .let { items -> markVodFavorites(items, globalFavoriteIds, itemIds, copyWithFavorite) }
            previewRows[category.name] = preview
        }

    return VodCatalogSnapshot(
        grouped = previewRows,
        categoryNames = previewRows.keys.toList(),
        categoryCounts = countMap,
        libraryCount = libraryCount
    )
}

private fun <Item> List<Item>.orderByRequestedIds(
    requestedIds: List<Long>,
    itemIds: (Item) -> List<Long>
): List<Item> {
    val positions = requestedIds.withIndex().associate { it.value to it.index }
    return sortedBy { item ->
        itemIds(item).mapNotNull(positions::get).minOrNull() ?: Int.MAX_VALUE
    }
}

fun <Item> buildVodSearchCatalog(
    items: List<Item>,
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    providerCategories: List<Category>,
    hiddenProviderCategoryIds: Set<Long>,
    itemIds: (Item) -> List<Long>,
    itemCategoryId: (Item) -> Long?,
    itemCategoryName: (Item) -> String?,
    copyWithFavorite: (Item, Boolean) -> Item,
    uncategorizedName: String
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val enrichedItems = markVodFavorites(
        items.filterNot { item -> itemCategoryId(item) in hiddenProviderCategoryIds },
        globalFavoriteIds,
        itemIds,
        copyWithFavorite
    )
    val grouped = enrichedItems
        .groupBy { itemCategoryName(it) ?: uncategorizedName }
        .toMutableMap()

    val favoriteMatches = enrichedItems.filter { item -> itemIds(item).any { rawItemId -> rawItemId in globalFavoriteIds } }
    if (favoriteMatches.isNotEmpty()) {
        grouped[VodBrowseDefaults.FAVORITES_CATEGORY] = favoriteMatches
    }

    customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .forEach { customCategory ->
            val itemIdsInGroup = allFavorites
                .asSequence()
                .filter { matchesVodGroupMembership(it.groupId, customCategory.id) }
                .map(Favorite::contentId)
                .toSet()
            grouped[customCategory.name] = enrichedItems.filter { item ->
                itemIds(item).any { rawItemId -> rawItemId in itemIdsInGroup }
            }
        }

    val customNames = customCategories.map(Category::name).toSet()
    val preferredProviderNames = providerCategories.map(Category::name)
    val orderedNames = buildList {
        if (grouped.containsKey(VodBrowseDefaults.FAVORITES_CATEGORY)) {
            add(VodBrowseDefaults.FAVORITES_CATEGORY)
        }
        customCategories.forEach { category ->
            if (grouped.containsKey(category.name)) {
                add(category.name)
            }
        }
        preferredProviderNames.forEach { categoryName ->
            if (grouped.containsKey(categoryName)) {
                add(categoryName)
            }
        }
        grouped.keys
            .filterNot { it == VodBrowseDefaults.FAVORITES_CATEGORY || it in customNames || it in preferredProviderNames }
            .sortedBy { it.lowercase() }
            .forEach(::add)
    }

    return VodCatalogSnapshot(
        grouped = grouped,
        categoryNames = orderedNames,
        categoryCounts = orderedNames.associateWith { name -> grouped[name]?.size ?: 0 },
        libraryCount = items.size
    )
}

fun <Item> markVodFavorites(
    items: List<Item>,
    globalFavoriteIds: Set<Long>,
    itemIds: (Item) -> List<Long>,
    copyWithFavorite: (Item, Boolean) -> Item
): List<Item> = items.map { item ->
    copyWithFavorite(item, itemIds(item).any { rawItemId -> rawItemId in globalFavoriteIds })
}

