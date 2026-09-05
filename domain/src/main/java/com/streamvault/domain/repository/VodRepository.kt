package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import com.streamvault.domain.model.VodSearchResult
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface VodRepository {
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun getCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<VodCatalogItem>>
    fun getCategoryItems(providerId: Long, categoryId: Long): Flow<List<VodCatalogItem>>
    fun observeHydration(providerId: Long, categoryId: Long): Flow<VodCategoryHydration?>
    suspend fun ensurePreview(providerId: Long, categoryId: Long): Result<Unit>
    suspend fun requestCategoryHydration(providerId: Long, categoryId: Long, request: VodCategoryHydrationRequest): Result<Unit>
    suspend fun hydrateCompletely(providerId: Long, categoryId: Long): Result<Unit>

    /**
     * Searches the provider's full VOD catalog on the portal side (STB-style `get_ordered_list`
     * with a `search` parameter). Only supported for Stalker portals; other providers return an
     * error result.
     */
    suspend fun searchVod(providerId: Long, query: String, page: Int): Result<VodSearchResult>
}
