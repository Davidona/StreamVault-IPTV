package com.streamvault.domain.model

/**
 * One page of portal-backed VOD search results. [totalCount] is the portal's advertised
 * match count for the query, so the UI can keep paging while `items.size < totalCount`.
 */
data class VodSearchResult(
    val items: List<VodCatalogItem>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int
)