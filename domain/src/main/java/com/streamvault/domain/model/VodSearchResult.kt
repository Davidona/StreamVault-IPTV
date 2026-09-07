package com.streamvault.domain.model

data class VodSearchResult(
    val items: List<VodCatalogItem>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean
)
