package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.domain.model.VodCategoryHydration
import com.streamvault.domain.model.VodCategoryHydrationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface MovieRepository {
    fun getMovies(providerId: Long): Flow<List<Movie>>
    /** Loads only active-provider rows with the requested Trakt/TMDB identities. */
    suspend fun getMoviesByTmdbIds(providerId: Long, tmdbIds: List<Long>): List<Movie> = emptyList()
    fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>>
    fun getMoviesByCategoryPage(providerId: Long, categoryId: Long, limit: Int, offset: Int): Flow<List<Movie>>
    fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>>
    fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>>
    fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>>
    fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>>
    fun getRecommendations(providerId: Long, limit: Int): Flow<List<Movie>>
    fun getRelatedContent(providerId: Long, movieId: Long, limit: Int): Flow<List<Movie>>
    fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>>
    fun getLibraryCount(providerId: Long): Flow<Int>
    fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>>
    fun observeCategoryHydration(providerId: Long, categoryId: Long): Flow<VodCategoryHydration?> = flowOf(null)
    suspend fun requestCategoryHydration(
        providerId: Long,
        categoryId: Long,
        request: VodCategoryHydrationRequest
    ): Result<Unit> = Result.success(Unit)
    fun searchMovies(providerId: Long, query: String): Flow<List<Movie>>
    suspend fun getMovie(movieId: Long): Movie?
    suspend fun getMovieVariants(movieId: Long): List<VodMovieVariant> = emptyList()
    /** True when the viewer explicitly pinned a provider variant for this movie group. */
    suspend fun hasExplicitMovieVariantSelection(movieId: Long): Boolean = false
    suspend fun getMovieDetails(
        providerId: Long,
        movieId: Long,
        knownPresentation: MovieDetailPresentationHint? = null
    ): Result<Movie>
    suspend fun getStreamInfo(movie: Movie): Result<StreamInfo>
    suspend fun refreshMovies(providerId: Long): Result<Unit>
    suspend fun getWatchProgress(movieId: Long): Long? = null
}
