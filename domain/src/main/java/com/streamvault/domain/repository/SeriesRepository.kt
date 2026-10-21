package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VodSeriesVariant
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SeriesRepository {
    fun getSeries(providerId: Long): Flow<List<Series>>
    /** Loads only active-provider rows with the requested Trakt/TMDB identities. */
    suspend fun getSeriesByTmdbIds(providerId: Long, tmdbIds: List<Long>): List<Series> = emptyList()
    fun getSeriesByCategory(providerId: Long, categoryId: Long): Flow<List<Series>>
    fun getSeriesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Series>>
    fun getSeriesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Series>>
    fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Series>>>
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Series>>
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Series>>
    fun getSeriesByIds(ids: List<Long>): Flow<List<Series>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>>
    fun getLibraryCount(providerId: Long): Flow<Int>
    fun browseSeries(query: LibraryBrowseQuery): Flow<PagedResult<Series>>
    fun observeCategoryHydration(providerId: Long, categoryId: Long): Flow<VodCategoryHydration?> = flowOf(null)
    suspend fun requestCategoryHydration(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> = Result.success(Unit)
    fun searchSeries(providerId: Long, query: String): Flow<List<Series>>
    suspend fun getSeriesById(seriesId: Long): Series?
    suspend fun getSeriesVariants(seriesId: Long): List<VodSeriesVariant> = emptyList()
    suspend fun hasExplicitSeriesVariantSelection(seriesId: Long): Boolean = true
    suspend fun getEpisodeById(episodeId: Long): Episode?
    suspend fun getSeriesDetails(
        providerId: Long,
        seriesId: Long,
        knownPresentation: SeriesDetailPresentationHint? = null
    ): Result<Series>
    suspend fun getEpisodeStreamInfo(episode: Episode): Result<StreamInfo>
    suspend fun refreshSeries(providerId: Long): Result<Unit>
}

class SeriesDetailsUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
