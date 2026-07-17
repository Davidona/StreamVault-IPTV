package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.PlaybackHistoryDao
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.XtreamContentIndexDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.http.toGenericRequestProfile
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerTrafficCoordinator
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.data.sync.ContentCachePolicy
import com.streamvault.data.sync.SyncManager
import com.streamvault.data.util.MoviePresentationSettings
import com.streamvault.data.util.buildPresentedMovies
import com.streamvault.data.util.rankSearchResults
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PagedResult
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Result.Success
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.VodDuplicateConfidence
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodMovieVariant
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MovieRepositoryImpl @Inject constructor(
    private val movieDao: MovieDao,
    private val categoryDao: CategoryDao,
    private val providerDao: ProviderDao,
    private val stalkerApiService: StalkerApiService,
    private val xtreamApiService: XtreamApiService,
    private val credentialCrypto: CredentialCrypto,
    private val preferencesRepository: PreferencesRepository,
    private val favoriteDao: FavoriteDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val movieCategoryHydrationDao: MovieCategoryHydrationDao,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val xtreamContentIndexDao: XtreamContentIndexDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val syncManager: SyncManager,
    private val transactionRunner: DatabaseTransactionRunner
) : MovieRepository {
    private companion object {
        const val TAG = "MovieRepository"
        const val SEARCH_RESULT_LIMIT = 200
        const val SEARCH_OVERSAMPLE_LIMIT = 500
        const val MIN_SEARCH_QUERY_LENGTH = 2
        const val BROWSE_WINDOW_BUFFER = 80
        const val XTREAM_CATEGORY_HYDRATION_CONCURRENCY = 1
        const val XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS = 30_000L
        const val CURSOR_BATCH_SIZE = 40
        const val STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD = 24
        const val STALKER_PREVIEW_MAX_REMOTE_PAGES = 2
        const val DETAIL_REFRESH_TTL_MILLIS = 14L * 24L * 60L * 60L * 1000L
        const val CACHE_STATE_SUMMARY_ONLY = "SUMMARY_ONLY"
        const val CACHE_STATE_DETAIL_HYDRATED = "DETAIL_HYDRATED"
        val DETAIL_YEAR_REGEX = Regex("""(19|20)\\d{2}""")
    }

    private data class CachedXtreamProvider(
        val signature: String,
        val provider: XtreamProvider
    )

    private data class NameCursor(
        val name: String,
        val id: Long
    )

    private data class RatingCursor(
        val rating: Float,
        val name: String,
        val id: Long
    )

    private data class FreshCursor(
        val addedAt: Long,
        val name: String,
        val id: Long
    )

    private data class ReleasedCursor(
        val year: String?,
        val addedAt: Long,
        val name: String,
        val id: Long
    )

    private val xtreamProviderCache = ConcurrentHashMap<Long, CachedXtreamProvider>()
    private val xtreamCategoryLoadLocks = ConcurrentHashMap<String, Mutex>()
    private val freshXtreamCategories = ConcurrentHashMap.newKeySet<String>()
    private var yearsCleared = false
    private val backgroundRefreshes = ConcurrentHashMap.newKeySet<String>()
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(XTREAM_CATEGORY_HYDRATION_CONCURRENCY)
    )
    private val moviePresentationSettingsFlow: Flow<MoviePresentationSettings> = combine(
        preferencesRepository.vodDuplicateHandlingMode,
        preferencesRepository.vodVariantPreferenceMode,
        preferencesRepository.vodVariantSelections,
        preferencesRepository.vodVariantObservations
    ) { duplicateHandlingMode, preferenceMode, preferredVariants, observations ->
        MoviePresentationSettings(
            duplicateHandlingMode = duplicateHandlingMode,
            preferenceMode = preferenceMode,
            preferredVariants = preferredVariants,
            observations = observations
        )
    }

    override fun getMovies(providerId: Long): Flow<List<Movie>> =
        preferencesRepository.parentalControlLevel.flatMapLatest { level ->
            if (level >= 3) movieDao.getByProviderUnprotected(providerId)
            else movieDao.getByProvider(providerId)
        }.combine(moviePresentationSettingsFlow) { list, settings ->
            buildPresentedMovies(list.map { it.toDomain() }, settings)
        }

    override fun getMoviesByCategory(providerId: Long, categoryId: Long): Flow<List<Movie>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true)
            emitAll(
                preferencesRepository.parentalControlLevel.flatMapLatest { level ->
                    if (level >= 3) movieDao.getByCategoryUnprotected(providerId, categoryId)
                    else movieDao.getByCategory(providerId, categoryId)
                }.combine(moviePresentationSettingsFlow) { list, settings ->
                    buildPresentedMovies(list.map { it.toDomain() }, settings)
                }
            )
        }

    override fun getMoviesByCategoryPage(
        providerId: Long,
        categoryId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<Movie>> = flow {
        ensureXtreamCategoryLoaded(providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true)
        emitAll(
            combine(
                movieDao.getByCategoryPage(providerId, categoryId, limit, offset),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list -> list.map { it.toDomain() } }
                .combine(moviePresentationSettingsFlow) { movies, settings ->
                    buildPresentedMovies(movies, settings)
                }
        )
    }

    override fun getMoviesByCategoryPreview(providerId: Long, categoryId: Long, limit: Int): Flow<List<Movie>> =
        flow {
            ensureXtreamCategoryLoaded(providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true)
            emitAll(
                combine(
                    movieDao.getByCategoryPreview(providerId, categoryId, limit),
                    preferencesRepository.parentalControlLevel
                ) { entities, level: Int ->
                    if (level >= 3) {
                        entities.filter { !it.isUserProtected }
                    } else {
                        entities
                    }
                }.map { list -> list.map { it.toDomain() } }
                    .combine(moviePresentationSettingsFlow) { movies, settings ->
                        buildPresentedMovies(movies, settings).take(limit)
                    }
            )
        }

    override fun getCategoryPreviewRows(providerId: Long, categoryIds: List<Long>, limitPerCategory: Int): Flow<Map<Long?, List<Movie>>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
            preferencesRepository.parentalControlLevel
        ) { categories, level ->
            val requestedIds = categoryIds.toSet()
            val filtered = if (level >= 3) categories.filter { !it.isAdult && !it.isUserProtected } else categories
            filtered.filter { it.categoryId in requestedIds } to level
        }.flatMapLatest { (filteredCategories, level) ->
            if (filteredCategories.isEmpty()) {
                flowOf(emptyMap())
            } else channelFlow {
                val provider = providerDao.getById(providerId)
                val previewCategories = if (provider?.type == ProviderType.STALKER_PORTAL) {
                    val stalkerProvider = createStalkerProvider(providerId, provider)
                    filteredCategories.filterNot { category ->
                        stalkerProvider.isWildcardCategory(ContentType.MOVIE, category.categoryId)
                    }
                } else {
                    filteredCategories
                }
                if (previewCategories.isEmpty()) {
                    send(emptyMap())
                    return@channelFlow
                }
                previewCategories.forEach { category ->
                    launch(Dispatchers.IO) {
                        ensureXtreamCategoryLoaded(
                            providerId = providerId,
                            categoryId = category.categoryId,
                            fetchIfMissing = true,
                            refreshStaleInBackground = false,
                            requiredCount = limitPerCategory,
                            allowStalkerWildcard = false
                        )
                    }
                }
                // SQL LIMIT applied per-category — avoids loading the full catalog into memory
                val categoryGroupFlows: List<Flow<Pair<Long?, List<Movie>>>> = previewCategories.map { cat ->
                    movieDao.getByCategoryPreview(providerId, cat.categoryId, limitPerCategory)
                        .map { entities ->
                            val items = if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            (cat.categoryId as Long?) to items.map { it.toDomain() }
                        }
                }
                combine(
                    combine(categoryGroupFlows) { pairs ->
                        pairs.associate { it.first to it.second }
                    },
                    moviePresentationSettingsFlow
                ) { previews, settings ->
                    previews.mapValues { (_, movies) -> buildPresentedMovies(movies, settings).take(limitPerCategory) }
                }.collect { previews ->
                    send(previews)
                }
            }
        }

    override fun getTopRatedPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getTopRatedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(moviePresentationSettingsFlow) { movies, settings ->
                buildPresentedMovies(movies, settings).take(limit)
            }

    override fun getFreshPreview(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getFreshPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(moviePresentationSettingsFlow) { movies, settings ->
                buildPresentedMovies(movies, settings).take(limit)
            }

    override fun getByReleaseDate(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            movieDao.getReleasedPreview(providerId, limit),
            preferencesRepository.parentalControlLevel
        ) { entities, level: Int ->
            if (level >= 3) {
                entities.filter { !it.isUserProtected }
            } else {
                entities
            }
        }.map { list -> list.map { it.toDomain() } }
            .combine(moviePresentationSettingsFlow) { movies, settings ->
                buildPresentedMovies(movies, settings).take(limit)
            }

    override fun getRecommendations(providerId: Long, limit: Int): Flow<List<Movie>> =
        combine(
            getTopRatedPreview(providerId, limit = maxOf(limit * 6, 48)),
            getFreshPreview(providerId, limit = maxOf(limit * 6, 48)),
            favoriteDao.getAllByType(providerId, ContentType.MOVIE.name),
            playbackHistoryDao.getRecentlyWatchedByProvider(providerId, limit = maxOf(limit * 4, 24))
        ) { topRated, fresh, favorites, history ->
            buildRecommendations(
                movies = (topRated + fresh).distinctBy { it.id },
                favoriteIds = favorites.map { it.contentId }.toSet(),
                recentlyWatchedIds = history
                    .asSequence()
                    .filter { it.contentType == ContentType.MOVIE }
                    .map { it.contentId }
                    .toSet(),
                limit = limit
            )
        }

    override fun getRelatedContent(providerId: Long, movieId: Long, limit: Int): Flow<List<Movie>> =
        flow {
            val targetMovie = getMovie(movieId) ?: run {
                emit(emptyList())
                return@flow
            }
            val categoryFlow = targetMovie.categoryId?.let { categoryId ->
                getMoviesByCategoryPreview(providerId, categoryId, limit = maxOf(limit * 6, 48))
            } ?: flowOf(emptyList())
            emitAll(
                combine(
                    categoryFlow,
                    getTopRatedPreview(providerId, limit = maxOf(limit * 4, 32))
                ) { categoryItems, topRated ->
                    buildRelatedContent(
                        target = targetMovie,
                        candidates = (categoryItems + topRated).distinctBy { it.id }.filterNot { it.id == movieId },
                        limit = limit
                    )
                }
            )
        }

    override fun getMoviesByIds(ids: List<Long>): Flow<List<Movie>> =
        movieDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.MOVIE.name),
            preferencesRepository.parentalControlLevel
        ) { entities: List<CategoryEntity>, level: Int ->
            val mapped = entities.map { it.toDomain() }
            if (level >= 3) {
                mapped.filter { !it.isAdult && !it.isUserProtected }
            } else {
                mapped
            }
        }

    override fun getCategoryItemCounts(providerId: Long): Flow<Map<Long, Int>> =
        movieDao.getCategoryCounts(providerId).map { counts ->
            counts.associate { it.categoryId to it.item_count }
        }

    override fun getLibraryCount(providerId: Long): Flow<Int> =
        movieDao.getCount(providerId)

    override fun browseMovies(query: LibraryBrowseQuery): Flow<PagedResult<Movie>> {
        return flow {
            val normalizedSearch = query.searchQuery.trim()
            query.categoryId?.takeIf { normalizedSearch.length < MIN_SEARCH_QUERY_LENGTH }?.let {
                ensureXtreamCategoryLoaded(
                    query.providerId,
                    it,
                    fetchIfMissing = true,
                    refreshStaleInBackground = true,
                    requiredCount = browseFetchLimit(query)
                )
            }
            emit(fetchMovieBrowseResult(query))
        }.flowOn(Dispatchers.IO)
    }

    override fun searchMovies(providerId: Long, query: String): Flow<List<Movie>> =
        query.trim().takeIf { it.length >= MIN_SEARCH_QUERY_LENGTH }?.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isNullOrBlank()) {
            flowOf(emptyList())
            } else combine(
                safeMovieSearchFlow(
                    source = movieDao.search(providerId, ftsQuery, SEARCH_RESULT_LIMIT),
                    fallback = {
                        movieDao.searchFallback(providerId, query.trim().toSqlLikePattern(), SEARCH_RESULT_LIMIT)
                    },
                    rawQuery = query.trim()
                ),
                preferencesRepository.parentalControlLevel
            ) { entities, level: Int ->
                if (level >= 3) {
                    entities.filter { !it.isUserProtected }
                } else {
                    entities
                }
            }.map { list ->
                list.map { it.toDomain() }
                    .rankSearchResults(query) { it.name }
            }.combine(favoriteDao.getAllByType(providerId, ContentType.MOVIE.name)) { movies, favorites ->
                val favoriteIds = favorites.map { it.contentId }.toSet()
                movies.map { if (it.id in favoriteIds) it.copy(isFavorite = true) else it }
            }.combine(moviePresentationSettingsFlow) { movies, settings ->
                buildPresentedMovies(movies, settings)
            }
        }

    override suspend fun getMovie(movieId: Long): Movie? =
        movieDao.getById(movieId)?.toDomain()

    override suspend fun getMovieVariants(movieId: Long): List<VodMovieVariant> =
        movieDao.getById(movieId)
            ?.toDomain()
            ?.let { movie -> attachMoviePresentation(movie).variants }
            .orEmpty()

    override suspend fun getMovieDetails(
        providerId: Long,
        movieId: Long,
        knownPresentation: MovieDetailPresentationHint?
    ): Result<Movie> {
        val movieEntity = movieDao.getById(movieId)
            ?: return Result.error("Movie not found")

        val provider = providerDao.getById(providerId)
            ?: return Result.error("Provider not found")

        if (provider.type == ProviderType.XTREAM_CODES && movieEntity.hasFreshXtreamDetails()) {
            return Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
        }

        val remoteMovieResult = try {
            when (provider.type) {
                ProviderType.XTREAM_CODES -> getOrCreateXtreamProvider(providerId, provider).getVodInfo(movieEntity.streamId)
                ProviderType.STALKER_PORTAL -> return Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
                ProviderType.M3U -> return Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
                ProviderType.JELLYFIN -> return Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
            }
        } catch (e: Exception) {
            if (provider.type == ProviderType.XTREAM_CODES) {
                xtreamContentIndexDao.markDetailHydrationError(
                    providerId = providerId,
                    contentType = ContentType.MOVIE.name,
                    remoteId = movieEntity.streamId.toString(),
                    errorState = "DETAIL_FAILED_RETRYABLE"
                )
            }
            return Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
        }

        return when (val remoteResult = remoteMovieResult) {
            is Result.Success -> {
                val remoteMovie = remoteResult.data
                val updatedMovie = movieEntity.copy(
                    name = remoteMovie.name.ifBlank { movieEntity.name },
                    posterUrl = remoteMovie.posterUrl ?: movieEntity.posterUrl,
                    backdropUrl = remoteMovie.backdropUrl ?: movieEntity.backdropUrl,
                    categoryId = remoteMovie.categoryId ?: movieEntity.categoryId,
                    categoryName = remoteMovie.categoryName ?: movieEntity.categoryName,
                    plot = remoteMovie.plot ?: movieEntity.plot,
                    cast = remoteMovie.cast ?: movieEntity.cast,
                    director = remoteMovie.director ?: movieEntity.director,
                    genre = remoteMovie.genre ?: movieEntity.genre,
                    releaseDate = remoteMovie.releaseDate ?: movieEntity.releaseDate,
                    duration = remoteMovie.duration ?: movieEntity.duration,
                    durationSeconds = remoteMovie.durationSeconds.takeIf { it > 0 } ?: movieEntity.durationSeconds,
                    rating = if (remoteMovie.rating > 0f) remoteMovie.rating else movieEntity.rating,
                    year = remoteMovie.year ?: movieEntity.year,
                    tmdbId = remoteMovie.tmdbId ?: movieEntity.tmdbId,
                    youtubeTrailer = remoteMovie.youtubeTrailer ?: movieEntity.youtubeTrailer,
                    cacheState = CACHE_STATE_DETAIL_HYDRATED,
                    detailHydratedAt = System.currentTimeMillis()
                )
                movieDao.update(updatedMovie)
                xtreamContentIndexDao.markDetailHydrated(
                    providerId = providerId,
                    contentType = ContentType.MOVIE.name,
                    remoteId = movieEntity.streamId.toString(),
                    localContentId = movieEntity.id,
                    imageUrl = updatedMovie.posterUrl,
                    detailHydratedAt = updatedMovie.detailHydratedAt
                )
                Result.success(attachMoviePresentation((movieDao.getById(movieEntity.id) ?: updatedMovie).toDomain(), knownPresentation))
            }
            is Result.Error -> {
                if (provider.type == ProviderType.XTREAM_CODES) {
                    xtreamContentIndexDao.markDetailHydrationError(
                        providerId = providerId,
                        contentType = ContentType.MOVIE.name,
                        remoteId = movieEntity.streamId.toString(),
                        errorState = "DETAIL_FAILED_RETRYABLE"
                    )
                }
                Result.success(attachMoviePresentation(movieEntity.toDomain(), knownPresentation))
            }
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getStreamInfo(movie: Movie): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = movie.streamUrl,
            fallbackProviderId = movie.providerId,
            fallbackStreamId = movie.streamId,
            fallbackContentType = ContentType.MOVIE,
            fallbackContainerExtension = movie.containerExtension
        )?.let { resolvedStream ->
            val ext = resolvedStream.containerExtension ?: movie.containerExtension
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = movie.name,
                    headers = resolvedStream.headers,
                    userAgent = resolvedStream.userAgent,
                    allowInvalidSsl = resolvedStream.allowInvalidSsl,
                    proxyHost = resolvedStream.proxyHost,
                    proxyPort = resolvedStream.proxyPort,
                    streamType = StreamType.fromContainerExtension(ext),
                    containerExtension = ext,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for movie: ${movie.name}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for movie: ${movie.name}", e)
    }

    override suspend fun refreshMovies(providerId: Long): Result<Unit> =
        Result.success(Unit) // Handled by ProviderRepository

    private suspend fun attachMoviePresentation(
        movie: Movie,
        knownPresentation: MovieDetailPresentationHint? = null
    ): Movie {
        val settings = moviePresentationSettingsFlow.first()
        if (settings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL) {
            return movie
        }
        knownPresentation
            ?.takeIf { hint ->
                hint.providerId == movie.providerId && hint.variants.any { variant -> variant.rawMovieId == movie.id }
            }
            ?.let { hint ->
                return attachPresentedMovie(
                    movie = movie,
                    logicalGroupId = hint.logicalGroupId,
                    variants = hint.variants,
                    duplicateConfidence = hint.duplicateConfidence
                )
            }
        val groupedMovie = buildPresentedMovies(
            movies = loadPresentationCandidates(movie).map { it.toDomain() },
            settings = settings
        ).firstOrNull { presented ->
            presented.id == movie.id || presented.variants.any { variant -> variant.rawMovieId == movie.id }
        } ?: return movie
        return attachPresentedMovie(
            movie = movie,
            logicalGroupId = groupedMovie.logicalGroupId,
            variants = groupedMovie.variants,
            duplicateConfidence = groupedMovie.duplicateConfidence,
            fallbackVariantLabel = groupedMovie.variantLabel
        )
    }

    private suspend fun loadPresentationCandidates(movie: Movie): List<com.streamvault.data.local.entity.MovieEntity> {
        movie.tmdbId?.takeIf { it > 0L }?.let { tmdbId ->
            val tmdbMatches = movieDao.getByProviderAndTmdbIdSync(movie.providerId, tmdbId)
            if (tmdbMatches.isNotEmpty()) {
                return tmdbMatches
            }
        }

        val displayYear = movieDisplayYearForDetail(movie)
        if (displayYear != null) {
            val yearMatches = movieDao.getByProviderAndYearSync(movie.providerId, displayYear.toString())
            val releaseMatches = movieDao.getByProviderAndReleaseYearPrefixSync(movie.providerId, "$displayYear%")
            val narrowed = (yearMatches + releaseMatches + listOfNotNull(movieDao.getById(movie.id))).distinctBy { it.id }
            if (narrowed.isNotEmpty()) {
                return narrowed
            }
        }

        return listOfNotNull(movieDao.getById(movie.id))
    }

    private fun attachPresentedMovie(
        movie: Movie,
        logicalGroupId: String?,
        variants: List<VodMovieVariant>,
        duplicateConfidence: VodDuplicateConfidence,
        fallbackVariantLabel: String? = null
    ): Movie {
        val selectedVariant = variants.firstOrNull { it.rawMovieId == movie.id }
        return movie.copy(
            logicalGroupId = logicalGroupId,
            selectedVariantId = movie.id,
            variants = variants,
            duplicateConfidence = duplicateConfidence,
            variantLabel = selectedVariant?.label ?: fallbackVariantLabel
        )
    }

    private fun movieDisplayYearForDetail(movie: Movie): Int? =
        movie.year?.trim()?.toIntOrNull()
            ?: movie.releaseDate?.filter(Char::isDigit)?.take(4)?.toIntOrNull()
            ?: DETAIL_YEAR_REGEX.find(movie.name)?.value?.toIntOrNull()

    private fun buildRecommendations(
        movies: List<Movie>,
        favoriteIds: Set<Long>,
        recentlyWatchedIds: Set<Long>,
        limit: Int
    ): List<Movie> {
        if (movies.isEmpty()) return emptyList()

        val seedMovies = movies.filter { movie -> movie.id in favoriteIds || movie.id in recentlyWatchedIds }
        val excludedIds = favoriteIds + recentlyWatchedIds

        if (seedMovies.isEmpty()) {
            return movies
                .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending(::movieReleaseScore).thenBy { it.name.lowercase() })
                .take(limit)
        }

        return movies
            .asSequence()
            .filterNot { movie -> movie.id in excludedIds }
            .map { movie -> movie to recommendationScore(movie, seedMovies, favoriteIds) }
            .filter { (_, score) -> score > 0f }
            .sortedWith(
                compareByDescending<Pair<Movie, Float>> { it.second }
                    .thenByDescending { it.first.rating }
                    .thenByDescending { movieReleaseScore(it.first) ?: Long.MIN_VALUE }
                    .thenBy { it.first.name.lowercase() }
            )
            .map { it.first }
            .take(limit)
            .toList()
            .ifEmpty {
                movies
                    .filterNot { movie -> movie.id in excludedIds }
                .sortedWith(compareByDescending<Movie> { it.rating }.thenByDescending { movieReleaseScore(it) ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
                    .take(limit)
            }
    }

    private fun buildRelatedContent(
        target: Movie,
        candidates: List<Movie>,
        limit: Int
    ): List<Movie> {
        return candidates
            .asSequence()
            .map { movie -> movie to relatedScore(target, movie) }
            .filter { (_, score) -> score > 0f }
            .sortedWith(
                compareByDescending<Pair<Movie, Float>> { it.second }
                    .thenByDescending { it.first.rating }
                    .thenByDescending { movieReleaseScore(it.first) ?: Long.MIN_VALUE }
                    .thenBy { it.first.name.lowercase() }
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    private fun recommendationScore(candidate: Movie, seedMovies: List<Movie>, favoriteIds: Set<Long>): Float {
        val candidateGenres = metadataTokens(candidate.genre)
        val candidateCast = metadataTokens(candidate.cast)
        val candidateDirector = metadataTokens(candidate.director)
        var score = if (candidate.id in favoriteIds) -2f else 0f

        seedMovies.forEach { seed ->
            score += tokenOverlap(candidateGenres, metadataTokens(seed.genre)) * 4f
            score += tokenOverlap(candidateCast, metadataTokens(seed.cast)) * 2.5f
            score += tokenOverlap(candidateDirector, metadataTokens(seed.director)) * 2f
            if (candidate.categoryId != null && candidate.categoryId == seed.categoryId) {
                score += 1.5f
            }
        }

        score += candidate.rating * 0.35f
        score += (movieReleaseScore(candidate) ?: 0L) * 0.0001f
        return score
    }

    private fun relatedScore(target: Movie, candidate: Movie): Float {
        val genreScore = tokenOverlap(metadataTokens(target.genre), metadataTokens(candidate.genre)) * 5f
        val castScore = tokenOverlap(metadataTokens(target.cast), metadataTokens(candidate.cast)) * 3f
        val directorScore = tokenOverlap(metadataTokens(target.director), metadataTokens(candidate.director)) * 2f
        val categoryScore = if (target.categoryId != null && target.categoryId == candidate.categoryId) 1.5f else 0f
        val yearScore = if (target.year != null && target.year == candidate.year) 0.75f else 0f

        return genreScore + castScore + directorScore + categoryScore + yearScore + (candidate.rating * 0.25f)
    }

    private fun metadataTokens(value: String?): Set<String> =
        value.orEmpty()
            .lowercase()
            .split(',', '/', '|')
            .flatMap { chunk -> chunk.split(' ') }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

    private fun tokenOverlap(left: Set<String>, right: Set<String>): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat()
    }

    private fun movieBrowseSource(query: LibraryBrowseQuery): Flow<List<Movie>> {
        val normalizedSearch = query.searchQuery.trim()
        val fetchLimit = browseFetchLimit(query)
        val fastFlow: Flow<List<Movie>>? = when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> {
                val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return flowOf(emptyList())
                query.categoryId?.let { categoryId ->
                    combine(
                        safeMovieSearchFlow(
                            source = movieDao.searchByCategory(query.providerId, categoryId, ftsQuery, SEARCH_OVERSAMPLE_LIMIT),
                            fallback = {
                                movieDao.searchByCategoryFallback(
                                    query.providerId,
                                    categoryId,
                                    normalizedSearch.toSqlLikePattern(),
                                    SEARCH_OVERSAMPLE_LIMIT
                                )
                            },
                            rawQuery = normalizedSearch
                        ),
                        preferencesRepository.parentalControlLevel
                    ) { entities, level ->
                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                    }.map { entities ->
                        entities.map { it.toDomain() }
                            .rankSearchResults(normalizedSearch) { it.name }
                    }
                } ?: combine(
                    safeMovieSearchFlow(
                        source = movieDao.search(query.providerId, ftsQuery, SEARCH_RESULT_LIMIT),
                        fallback = {
                            movieDao.searchFallback(
                                query.providerId,
                                normalizedSearch.toSqlLikePattern(),
                                SEARCH_RESULT_LIMIT
                            )
                        },
                        rawQuery = normalizedSearch
                    ),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities ->
                    entities.map { it.toDomain() }
                        .rankSearchResults(normalizedSearch) { it.name }
                }
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                when {
                    query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                                emitAll(
                                    combine(
                                        movieDao.getTopRatedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            movieDao.getTopRatedPreview(query.providerId, fetchLimit),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                    query.sortBy == LibrarySortBy.RELEASE -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                                emitAll(
                                    combine(
                                        movieDao.getReleasedByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            movieDao.getReleasedPreview(query.providerId, fetchLimit),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                    query.sortBy == LibrarySortBy.UPDATED || query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                                emitAll(
                                    combine(
                                        movieDao.getFreshByCategoryPreview(query.providerId, categoryId, fetchLimit),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            movieDao.getFreshPreview(query.providerId, fetchLimit),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                    else -> {
                        query.categoryId?.let { categoryId ->
                            flow {
                                ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                                emitAll(
                                    combine(
                                        movieDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                        preferencesRepository.parentalControlLevel
                                    ) { entities, level ->
                                        if (level >= 3) entities.filter { !it.isUserProtected } else entities
                                    }.map { entities -> entities.map { it.toDomain() } }
                                )
                            }
                        } ?: combine(
                            movieDao.getByProviderPage(query.providerId, fetchLimit, 0),
                            preferencesRepository.parentalControlLevel
                        ) { entities, level ->
                            if (level >= 3) entities.filter { !it.isUserProtected } else entities
                        }.map { entities -> entities.map { it.toDomain() } }
                    }
                }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                        emitAll(
                            combine(
                                movieDao.getFavoritesByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getFavoritesByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                        emitAll(
                            combine(
                                movieDao.getInProgressByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getInProgressByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                        emitAll(
                            combine(
                                movieDao.getUnwatchedByCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getUnwatchedByProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    flow {
                        ensureXtreamCategoryLoaded(query.providerId, categoryId, fetchIfMissing = true, refreshStaleInBackground = true, requiredCount = fetchLimit)
                        emitAll(
                            combine(
                                movieDao.getByWatchCountCategoryPage(query.providerId, categoryId, fetchLimit, 0),
                                preferencesRepository.parentalControlLevel
                            ) { entities, level ->
                                if (level >= 3) entities.filter { !it.isUserProtected } else entities
                            }.map { entities -> entities.map { it.toDomain() } }
                        )
                    }
                } ?: combine(
                    movieDao.getByWatchCountProviderPage(query.providerId, fetchLimit, 0),
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            }
            else -> null
        }

        val categoryId = query.categoryId
        return fastFlow ?: flow {
            categoryId?.let {
                ensureXtreamCategoryLoaded(
                    query.providerId,
                    it,
                    fetchIfMissing = true,
                    refreshStaleInBackground = true,
                    requiredCount = fetchLimit
                )
            }
            val source = if (categoryId == null) {
                movieDao.getByProviderPage(query.providerId, fetchLimit, 0)
            } else {
                movieDao.getByCategoryPage(query.providerId, categoryId, fetchLimit, 0)
            }
            emitAll(
                combine(
                    source,
                    preferencesRepository.parentalControlLevel
                ) { entities, level ->
                    if (level >= 3) entities.filter { !it.isUserProtected } else entities
                }.map { entities -> entities.map { it.toDomain() } }
            )
        }
    }

    private fun movieBrowseTotalCount(query: LibraryBrowseQuery): Flow<Int> {
        val normalizedSearch = query.searchQuery.trim()
        return when {
            normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH -> movieBrowseSource(query).map { it.size }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.FAVORITES &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getFavoriteCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getFavoriteCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.IN_PROGRESS &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getInProgressCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getInProgressCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.UNWATCHED &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getUnwatchedCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getUnwatchedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() &&
                query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.WATCH_COUNT -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getCount(query.providerId)
            }
            normalizedSearch.isBlank() && query.filterBy.type == LibraryFilterType.TOP_RATED -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getTopRatedCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getTopRatedCountByProvider(query.providerId)
            }
            normalizedSearch.isBlank() && query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getFreshCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getFreshCountByProvider(query.providerId)
            }
            query.filterBy.type in setOf(LibraryFilterType.ALL, LibraryFilterType.TOP_RATED, LibraryFilterType.RECENTLY_UPDATED) &&
                query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE, LibrarySortBy.RELEASE, LibrarySortBy.UPDATED, LibrarySortBy.RATING) -> {
                query.categoryId?.let { categoryId ->
                    movieDao.getCountByCategory(query.providerId, categoryId)
                } ?: movieDao.getCount(query.providerId)
            }
            else -> movieBrowseSource(query).map { it.size }
        }
    }

    private fun browseFetchLimit(query: LibraryBrowseQuery): Int =
        (query.offset + query.limit + BROWSE_WINDOW_BUFFER).coerceAtMost(SEARCH_RESULT_LIMIT)

    private suspend fun fetchMovieBrowseResult(query: LibraryBrowseQuery): PagedResult<Movie> {
        // One-time repair: old extractYearFromName extracted false positives
        // from titles (e.g. year='2049' for 'Blade Runner 2049'). First restore
        // any years from parenthetical (YYYY) patterns, then clear bad ones.
        if (!yearsCleared) {
            yearsCleared = true
            movieDao.restoreYearsFromName()
            movieDao.clearInvalidYears()
        }
        val normalizedSearch = query.searchQuery.trim()
        val presentationSettings = moviePresentationSettingsFlow.first()
        if (normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH && supportsFastMovieSearchBrowse(query)) {
            return fetchFastMovieSearchBrowseResult(query, normalizedSearch, presentationSettings)
        }

        if (normalizedSearch.length >= MIN_SEARCH_QUERY_LENGTH) {
            val searchResults = movieBrowseSource(query).first()
            val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.MOVIE.name)
                .first()
                .asSequence()
                .filter { it.groupId == null }
                .map { it.contentId }
                .toSet()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .filter { it.resumePositionMs > 0L && (it.totalDurationMs <= 0L || !moviePlaybackComplete(it.resumePositionMs, it.totalDurationMs)) }
                .map { it.contentId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .associate { it.contentId to it.watchCount }
            val filteredResults = applyMovieBrowseQuery(
                movies = searchResults,
                query = query,
                favoriteIds = favoriteIds,
                inProgressIds = inProgressIds,
                watchCounts = watchCounts
            ).let { results -> buildPresentedMovies(results, presentationSettings) }
            val items = filteredResults.drop(query.offset).take(query.limit)

            return PagedResult(
                items = items,
                totalCount = filteredResults.size,
                offset = query.offset,
                limit = query.limit,
                hasMoreRemote = false
            )
        }

        val rawTotalCount = movieBrowseTotalCount(query).first()
        val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.MOVIE.name)
            .first()
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val canUseCursorWindow = presentationSettings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL && supportsCursorBrowse(query)
        val items = if (canUseCursorWindow) {
            fetchMovieCursorWindow(query, favoriteIds)
        } else {
            val movies = movieBrowseSource(query).first()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .filter { it.resumePositionMs > 0L && (it.totalDurationMs <= 0L || !moviePlaybackComplete(it.resumePositionMs, it.totalDurationMs)) }
                .map { it.contentId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .associate { it.contentId to it.watchCount }

            applyMovieBrowseQuery(
                movies = movies,
                query = query,
                favoriteIds = favoriteIds,
                inProgressIds = inProgressIds,
                watchCounts = watchCounts
            ).let { results -> buildPresentedMovies(results, presentationSettings) }
                .drop(query.offset)
                .take(query.limit)
        }

        val totalCount = if (presentationSettings.duplicateHandlingMode == VodDuplicateHandlingMode.SHOW_ALL) {
            rawTotalCount
        } else {
            val movies = movieBrowseSource(query).first()
            val history = playbackHistoryDao.getByProvider(query.providerId).first()
            val inProgressIds = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .filter { it.resumePositionMs > 0L && (it.totalDurationMs <= 0L || !moviePlaybackComplete(it.resumePositionMs, it.totalDurationMs)) }
                .map { it.contentId }
                .toSet()
            val watchCounts = history
                .asSequence()
                .filter { it.contentType == ContentType.MOVIE }
                .associate { it.contentId to it.watchCount }
            buildPresentedMovies(
                applyMovieBrowseQuery(
                    movies = movies,
                    query = query,
                    favoriteIds = favoriteIds,
                    inProgressIds = inProgressIds,
                    watchCounts = watchCounts
                ),
                presentationSettings
            ).size
        }

        val hasMoreRemote = query.categoryId?.let { categoryId ->
            val provider = providerDao.getById(query.providerId)
            if (provider?.type == ProviderType.STALKER_PORTAL) {
                movieCategoryHydrationDao.get(query.providerId, categoryId)?.let { !it.isComplete } ?: false
            } else {
                false
            }
        } ?: false

        return PagedResult(
            items = items,
            totalCount = totalCount,
            offset = query.offset,
            limit = query.limit,
            hasMoreRemote = hasMoreRemote
        )
    }

    private suspend fun fetchFastMovieSearchBrowseResult(
        query: LibraryBrowseQuery,
        normalizedSearch: String,
        presentationSettings: MoviePresentationSettings
    ): PagedResult<Movie> {
        val ftsQuery = normalizedSearch.toFtsPrefixQuery() ?: return PagedResult(
            items = emptyList(),
            totalCount = 0,
            offset = query.offset,
            limit = query.limit
        )
        val includeProtected = if (preferencesRepository.parentalControlLevel.first() >= 3) 0 else 1
        val pageLimit = query.limit + 1
        val rows = query.categoryId?.let { categoryId ->
            movieDao.searchByCategoryPage(
                providerId = query.providerId,
                categoryId = categoryId,
                query = ftsQuery,
                rawQuery = normalizedSearch,
                prefixLike = normalizedSearch.toSqlPrefixLikePattern(),
                includeProtected = includeProtected,
                limit = pageLimit,
                offset = query.offset
            )
        } ?: movieDao.searchPage(
            providerId = query.providerId,
            query = ftsQuery,
            rawQuery = normalizedSearch,
            prefixLike = normalizedSearch.toSqlPrefixLikePattern(),
            includeProtected = includeProtected,
            limit = pageLimit,
            offset = query.offset
        )
        val hasMore = rows.size > query.limit
        val favoriteIds = favoriteDao.getAllByType(query.providerId, ContentType.MOVIE.name)
            .first()
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()
        val rawItems = rows
            .take(query.limit)
            .map { it.toDomain() }
            .map { movie -> if (movie.id in favoriteIds) movie.copy(isFavorite = true) else movie }
        val items = buildPresentedMovies(rawItems, presentationSettings)

        return PagedResult(
            items = items,
            totalCount = query.offset + items.size + if (hasMore) 1 else 0,
            offset = query.offset,
            limit = query.limit,
            hasMoreRemote = hasMore
        )
    }

    private suspend fun fetchMovieCursorWindow(
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>
    ): List<Movie> {
        val parentalLevel = preferencesRepository.parentalControlLevel.first()
        val targetVisibleCount = (query.offset + query.limit).coerceAtLeast(query.limit)
        val collected = ArrayList<Movie>(targetVisibleCount)

        when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.LIBRARY -> {
                collectMoviePages<FreshCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { FreshCursor(it.addedAt, it.name, it.id) }
                ) { limit, cursor ->
                    loadMovieFreshPage(query, limit, cursor)
                }
            }
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy == LibrarySortBy.TITLE -> {
                collectMoviePages<NameCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { NameCursor(it.name, it.id) }
                ) { limit, cursor ->
                    loadMovieNamePage(query, limit, cursor)
                }
            }
            (query.sortBy == LibrarySortBy.RATING || query.filterBy.type == LibraryFilterType.TOP_RATED) -> {
                collectMoviePages<RatingCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { RatingCursor(it.rating, it.name, it.id) }
                ) { limit, cursor ->
                    loadMovieRatingPage(query, limit, cursor)
                }
            }
            query.sortBy == LibrarySortBy.UPDATED ||
                query.filterBy.type == LibraryFilterType.RECENTLY_UPDATED -> {
                collectMoviePages<FreshCursor>(query, parentalLevel, collected, favoriteIds,
                    extractCursor = { FreshCursor(it.addedAt, it.name, it.id) }
                ) { limit, cursor ->
                    loadMovieFreshPage(query, limit, cursor)
                }
            }
        }

        return collected.drop(query.offset).take(query.limit)
    }

    private suspend fun <C> collectMoviePages(
        query: LibraryBrowseQuery,
        parentalLevel: Int,
        collected: MutableList<Movie>,
        favoriteIds: Set<Long>,
        extractCursor: (MovieBrowseEntity) -> C,
        loadPage: suspend (limit: Int, cursor: C?) -> List<MovieBrowseEntity>
    ) {
        var cursor: C? = null
        val targetVisibleCount = query.offset + query.limit
        while (collected.size < targetVisibleCount) {
            val batch = loadPage(CURSOR_BATCH_SIZE, cursor)
            if (batch.isEmpty()) {
                return
            }
            val visibleBatch = if (parentalLevel >= 3) {
                batch.filterNot { it.isUserProtected }
            } else {
                batch
            }
            collected += visibleBatch.map { entity ->
                val movie = entity.toDomain()
                if (movie.id in favoriteIds) movie.copy(isFavorite = true) else movie
            }
            if (batch.size < CURSOR_BATCH_SIZE) {
                return
            }
            cursor = extractCursor(batch.last())
        }
    }

    private suspend fun loadMovieNamePage(query: LibraryBrowseQuery, limit: Int, cursor: NameCursor?): List<MovieBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) movieDao.getByProviderCursorPage(query.providerId, limit)
            else movieDao.getByProviderCursorPageAfter(query.providerId, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) movieDao.getByCategoryCursorPage(query.providerId, categoryId, limit)
            else movieDao.getByCategoryCursorPageAfter(query.providerId, categoryId, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadMovieRatingPage(query: LibraryBrowseQuery, limit: Int, cursor: RatingCursor?): List<MovieBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) movieDao.getTopRatedCursorPage(query.providerId, limit)
            else movieDao.getTopRatedCursorPageAfter(query.providerId, cursor.rating, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) movieDao.getTopRatedByCategoryCursorPage(query.providerId, categoryId, limit)
            else movieDao.getTopRatedByCategoryCursorPageAfter(query.providerId, categoryId, cursor.rating, cursor.name, cursor.id, limit)
        }
    }

    private suspend fun loadMovieFreshPage(query: LibraryBrowseQuery, limit: Int, cursor: FreshCursor?): List<MovieBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) movieDao.getFreshCursorPage(query.providerId, limit)
            else movieDao.getFreshCursorPageAfter(query.providerId, cursor.addedAt, cursor.name, cursor.id, limit)
        } else {
            if (cursor == null) movieDao.getFreshByCategoryCursorPage(query.providerId, categoryId, limit)
            else movieDao.getFreshByCategoryCursorPageAfter(
                query.providerId,
                categoryId,
                cursor.addedAt,
                cursor.name,
                cursor.id,
                limit
            )
        }
    }

    private suspend fun loadMovieReleasedPage(query: LibraryBrowseQuery, limit: Int, cursor: ReleasedCursor?): List<MovieBrowseEntity> {
        val categoryId = query.categoryId
        return if (categoryId == null) {
            if (cursor == null) movieDao.getReleasedCursorPage(query.providerId, limit)
            else movieDao.getReleasedCursorPageAfter(
                query.providerId, cursor.year, cursor.addedAt, cursor.name, cursor.id, limit
            )
        } else {
            if (cursor == null) movieDao.getReleasedByCategoryCursorPage(query.providerId, categoryId, limit)
            else movieDao.getReleasedByCategoryCursorPageAfter(
                query.providerId, categoryId, cursor.year, cursor.addedAt, cursor.name, cursor.id, limit
            )
        }
    }

    private fun supportsCursorBrowse(query: LibraryBrowseQuery): Boolean {
        if (query.searchQuery.isNotBlank()) return false
        return when {
            query.filterBy.type == LibraryFilterType.ALL &&
                query.sortBy in setOf(
                    LibrarySortBy.TITLE,
                    LibrarySortBy.UPDATED,
                    LibrarySortBy.RATING
                ) -> true
            else -> false
        }
    }

    private fun applyMovieBrowseQuery(
        movies: List<Movie>,
        query: LibraryBrowseQuery,
        favoriteIds: Set<Long>,
        inProgressIds: Set<Long>,
        watchCounts: Map<Long, Int>
    ): List<Movie> {
        val withFavoriteState = movies.map { movie ->
            movie.copy(isFavorite = movie.id in favoriteIds)
        }
        val filtered = withFavoriteState.filter { movie ->
            movieMatchesFilter(movie, query.filterBy.type, inProgressIds) && movieMatchesSearch(movie, query.searchQuery)
        }

        val sorted = when (query.sortBy) {
            // LIBRARY: sort by effective year descending. Extracts year from
            // releaseDate, DB year column, or parenthetical (YYYY) in name.
            // Items with no year info sort last (Long.MIN_VALUE).
            LibrarySortBy.LIBRARY -> filtered.sortedWith(
                compareByDescending<Movie> {
                    movieReleaseScore(it) ?: Long.MIN_VALUE
                }
            )
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE -> filtered.sortedWith(
                compareByDescending<Movie> { movieReleaseScore(it) ?: Long.MIN_VALUE }
            )
            LibrarySortBy.UPDATED -> filtered.sortedByDescending(::movieAddedScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { watchCounts[it.id] ?: 0 }
        }

        return if (query.searchQuery.isBlank() || query.sortBy != LibrarySortBy.LIBRARY) {
            sorted
        } else {
            sorted.rankSearchResults(query.searchQuery) { it.name }
        }
    }

    private fun supportsFastMovieSearchBrowse(query: LibraryBrowseQuery): Boolean =
        query.filterBy.type == LibraryFilterType.ALL &&
            query.sortBy in setOf(LibrarySortBy.LIBRARY, LibrarySortBy.TITLE)

    private fun movieMatchesFilter(movie: Movie, filterType: LibraryFilterType, inProgressIds: Set<Long>): Boolean = when (filterType) {
        LibraryFilterType.ALL -> true
        LibraryFilterType.FAVORITES -> movie.isFavorite
        LibraryFilterType.IN_PROGRESS -> movie.id in inProgressIds || movieIsInProgress(movie)
        LibraryFilterType.UNWATCHED -> movie.id !in inProgressIds && movie.watchProgress <= 0L
        LibraryFilterType.TOP_RATED -> movie.rating > 0f
        LibraryFilterType.RECENTLY_UPDATED -> movieAddedScore(movie) > 0L
    }

    private fun movieMatchesSearch(movie: Movie, searchQuery: String): Boolean {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return sequenceOf(movie.name, movie.genre, movie.categoryName, movie.year)
            .filterNotNull()
            .any { value -> value.lowercase().contains(normalizedQuery) }
    }

    private fun safeMovieSearchFlow(
        source: Flow<List<MovieBrowseEntity>>,
        fallback: () -> Flow<List<MovieBrowseEntity>>,
        rawQuery: String
    ): Flow<List<MovieBrowseEntity>> = flow {
        try {
            source.collect { ftsRows ->
                if (ftsRows.isEmpty()) {
                    emit(emptyList())
                } else {
                    emit(ftsRows)
                }
            }
        } catch (error: SQLiteException) {
            Log.w(TAG, "FTS movie search failed for queryLength=${rawQuery.length}; using LIKE-only search", error)
            emitAll(fallback())
        }
    }

    private fun String.toSqlLikePattern(): String {
        val escaped = buildString(length) {
            this@toSqlLikePattern.forEach { char ->
                when (char) {
                    '%', '_', '\\' -> append('\\')
                }
                append(char)
            }
        }
        return "%$escaped%"
    }

    private fun String.toSqlPrefixLikePattern(): String {
        val escaped = buildString(length) {
            this@toSqlPrefixLikePattern.forEach { char ->
                when (char) {
                    '%', '_', '\\' -> append('\\')
                }
                append(char)
            }
        }
        return "$escaped%"
    }

    private suspend fun ensureXtreamCategoryLoaded(
        providerId: Long,
        categoryId: Long,
        fetchIfMissing: Boolean = true,
        refreshStaleInBackground: Boolean = false,
        forceRefresh: Boolean = false,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        allowStalkerWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        val provider = providerDao.getById(providerId) ?: return
        if (provider.type != ProviderType.XTREAM_CODES && provider.type != ProviderType.STALKER_PORTAL) return

        val localCount = movieDao.getCountByCategory(providerId, categoryId).first()
        val hydration = movieCategoryHydrationDao.get(providerId, categoryId)
        if (provider.type == ProviderType.XTREAM_CODES) {
            if (localCount > 0) {
                freshXtreamCategories.add(key)
            } else {
                syncManager.prioritizeXtreamIndexCategory(providerId, ContentType.MOVIE, categoryId)
            }
            return
        }
        if (provider.type == ProviderType.STALKER_PORTAL) {
            if (localCount <= 0) {
                syncManager.prioritizeStalkerIndexCategory(providerId, ContentType.MOVIE, categoryId)
            }
            if (!shouldUseStalkerLazyFallback(providerId, hydration, localCount)) {
                return
            }
            hydrateStalkerMovieCategoryToCount(
                providerId = providerId,
                categoryId = categoryId,
                provider = provider,
                requiredCount = requiredCount,
                localCount = localCount,
                hydration = hydration,
                allowWildcard = allowStalkerWildcard
            )
            return
        }
    }

    private fun triggerXtreamCategoryHydration(
        providerId: Long,
        categoryId: Long,
        fetchIfMissing: Boolean,
        refreshStaleInBackground: Boolean,
        requiredCount: Int = SEARCH_RESULT_LIMIT,
        allowStalkerWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        if (!backgroundRefreshes.add(key)) return
        repositoryScope.launch {
            try {
                val provider = providerDao.getById(providerId) ?: return@launch
                if (provider.type != ProviderType.XTREAM_CODES && provider.type != ProviderType.STALKER_PORTAL) {
                    return@launch
                }
                if (provider.type == ProviderType.XTREAM_CODES) {
                    return@launch
                }
                val localCount = movieDao.getCountByCategory(providerId, categoryId).first()
                val hydration = movieCategoryHydrationDao.get(providerId, categoryId)
                if (provider.type == ProviderType.STALKER_PORTAL) {
                    if (localCount <= 0) {
                        syncManager.prioritizeStalkerIndexCategory(providerId, ContentType.MOVIE, categoryId)
                    }
                    if (!shouldUseStalkerLazyFallback(providerId, hydration, localCount)) {
                        return@launch
                    }
                    hydrateStalkerMovieCategoryToCount(
                        providerId = providerId,
                        categoryId = categoryId,
                        provider = provider,
                        requiredCount = requiredCount,
                        localCount = localCount,
                        hydration = hydration,
                        allowWildcard = allowStalkerWildcard
                    )
                    return@launch
                }
            } finally {
                backgroundRefreshes.remove(key)
            }
        }
    }

    private suspend fun shouldUseStalkerLazyFallback(
        providerId: Long,
        hydration: MovieCategoryHydrationEntity?,
        localCount: Int
    ): Boolean {
        val now = System.currentTimeMillis()
        if (localCount > 0) return false
        if (hydration?.isComplete == true) return false
        if (hydration?.lastStatus in setOf("FAILED_PERMANENT", "FAILED_BUDGET_EXHAUSTED")) return true
        if (hydration?.retryBudgetRemaining == 0 && hydration.retryAfterMs <= now) return true
        return when (xtreamIndexJobDao.get(providerId, ContentType.MOVIE.name)?.state) {
            null, "FAILED_PERMANENT" -> true
            else -> false
        }
    }

    private suspend fun hydrateStalkerMovieCategoryToCount(
        providerId: Long,
        categoryId: Long,
        provider: ProviderEntity,
        requiredCount: Int,
        localCount: Int? = null,
        hydration: MovieCategoryHydrationEntity? = null,
        allowWildcard: Boolean = true
    ) {
        val key = "$providerId:$categoryId"
        val lock = xtreamCategoryLoadLocks.getOrPut(key) { Mutex() }
        lock.withLock {
            val stalkerProvider = createStalkerProvider(providerId, provider)
            if (!allowWildcard && stalkerProvider.isWildcardCategory(ContentType.MOVIE, categoryId)) return
            var currentCount = localCount ?: movieDao.getCountByCategory(providerId, categoryId).first()
            var currentHydration = hydration ?: movieCategoryHydrationDao.get(providerId, categoryId)
            if (currentHydration?.isComplete == true || currentCount >= requiredCount) return
            if (currentCount == 0 && currentHydration?.isEmptyRetryCoolingDown() == true) return

            val isPreviewLoad = requiredCount <= STALKER_PREVIEW_REQUIRED_COUNT_THRESHOLD
            var nextPage = when (currentHydration?.lastStatus) {
                "FAILED_RETRYABLE", "COOLDOWN", "ANOMALY" -> currentHydration?.lastAttemptedPage?.coerceAtLeast(1) ?: 1
                else -> ((currentHydration?.lastSuccessfulPage ?: currentHydration?.lastLoadedPage ?: 0) + 1).coerceAtLeast(1)
            }
            // The cached totalPages can under-report when the preview hydrate stored
            // it from a partial response. Skip the pre-fetch guard on the first
            // iteration so we always perform at least one real fetch that refreshes
            // totalPages; subsequent iterations use the in-loop updated value.
            var firstIteration = true
            while (currentCount < requiredCount) {
                if (StalkerTrafficCoordinator.shouldDeferCatalogFetch(providerId)) break
                val attemptStartedAt = System.currentTimeMillis()
                if (isPreviewLoad && nextPage > STALKER_PREVIEW_MAX_REMOTE_PAGES) break
                val totalPages = currentHydration?.totalPages ?: 0
                if (!firstIteration && totalPages > 0 && nextPage > totalPages) break
                firstIteration = false
                when (val result = stalkerProvider.getVodStreamsPage(categoryId, nextPage)) {
                    is Success -> {
                        val entities = result.data.items.map { movie -> movie.toEntity() }
                        val pageComplete = result.data.isComplete || entities.isEmpty()
                        transactionRunner.inTransaction {
                            movieDao.upsertCategoryPage(providerId, entities)
                            val updatedCount = movieDao.getCountByCategory(providerId, categoryId).first()
                            currentCount = updatedCount
                            currentHydration = MovieCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = categoryId,
                                lastHydratedAt = attemptStartedAt,
                                itemCount = updatedCount,
                                lastStatus = "SUCCESS",
                                lastError = null,
                                lastLoadedPage = result.data.page,
                                lastAttemptedPage = result.data.page,
                                lastSuccessfulPage = result.data.page,
                                totalPages = result.data.totalPages,
                                isComplete = pageComplete,
                                pageSize = result.data.pageSize,
                                retryAfterMs = 0L,
                                failureCount = 0,
                                retryBudgetRemaining = 3,
                                lastPageFingerprint = null
                            )
                            movieCategoryHydrationDao.upsert(currentHydration!!)
                        }
                        if (pageComplete) break
                        nextPage = result.data.page + 1
                    }
                    is Result.Error -> {
                        val priorFailureCount = currentHydration?.failureCount ?: 0
                        val remainingBudget = ((currentHydration?.retryBudgetRemaining ?: 3) - 1).coerceAtLeast(0)
                        val nextStatus = when {
                            remainingBudget <= 0 -> "FAILED_BUDGET_EXHAUSTED"
                            else -> "FAILED_RETRYABLE"
                        }
                        movieCategoryHydrationDao.upsert(
                            MovieCategoryHydrationEntity(
                                providerId = providerId,
                                categoryId = categoryId,
                                lastHydratedAt = currentHydration?.lastHydratedAt ?: 0L,
                                itemCount = currentCount,
                                lastStatus = nextStatus,
                                lastError = result.message,
                                lastLoadedPage = currentHydration?.lastLoadedPage ?: 0,
                                lastAttemptedPage = nextPage,
                                lastSuccessfulPage = currentHydration?.lastSuccessfulPage ?: currentHydration?.lastLoadedPage ?: 0,
                                totalPages = currentHydration?.totalPages ?: 0,
                                isComplete = currentHydration?.isComplete ?: false,
                                pageSize = currentHydration?.pageSize ?: 0,
                                retryAfterMs = if (nextStatus == "FAILED_RETRYABLE") {
                                    attemptStartedAt + XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS * (priorFailureCount + 1)
                                } else {
                                    0L
                                },
                                failureCount = priorFailureCount + 1,
                                retryBudgetRemaining = remainingBudget,
                                lastPageFingerprint = currentHydration?.lastPageFingerprint
                            )
                        )
                        break
                    }
                    is Result.Loading -> break
                }
            }
        }
    }

    private fun com.streamvault.data.local.entity.MovieEntity.hasFreshXtreamDetails(now: Long = System.currentTimeMillis()): Boolean {
        if (cacheState == CACHE_STATE_SUMMARY_ONLY) return false
        if (detailHydratedAt <= 0L) return false
        if (remoteStaleAt > 0L) return false
        if (ContentCachePolicy.shouldRefresh(detailHydratedAt, DETAIL_REFRESH_TTL_MILLIS, now)) return false
        return true
    }

    private fun MovieCategoryHydrationEntity.isFresh(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "SUCCESS") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, ContentCachePolicy.CATALOG_TTL_MILLIS, now)
    }

    private fun MovieCategoryHydrationEntity.isEmptyRetryCoolingDown(now: Long = System.currentTimeMillis()): Boolean {
        if (lastStatus != "EMPTY_RETRY") return false
        return !ContentCachePolicy.shouldRefresh(lastHydratedAt, XTREAM_EMPTY_CATEGORY_RETRY_COOLDOWN_MILLIS, now)
    }

    private fun movieIsInProgress(movie: Movie): Boolean {
        if (movie.watchProgress <= 0L) return false
        val totalDurationMs = movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
        return !moviePlaybackComplete(movie.watchProgress, totalDurationMs)
    }

    /**
     * Extracts a 4-digit year from a movie name as fallback.
     * Handles "Movie Name (YYYY)" and standalone years.
     */
    private fun extractYearFromName(name: String): Long? {
        val parenYear = Regex("""\((\d{4})\)\s*$""").find(name)
        return parenYear?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Returns the effective release year (4 digits) or null if no year info exists.
     * Items with null score sort last in descending order (below all dated items).
     */
    private fun movieReleaseScore(movie: Movie): Long? =
        movie.releaseDate?.take(4)?.toLongOrNull()
            ?: movie.year?.toLongOrNull()
            ?: extractYearFromName(movie.name)

    private fun movieAddedScore(movie: Movie): Long =
        movie.addedAt.takeIf { it > 0L } ?: 0L

    private suspend fun getOrCreateXtreamProvider(providerId: Long, provider: ProviderEntity): XtreamProvider {
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val signature = listOf(
            provider.serverUrl,
            provider.username,
            provider.password,
            provider.httpUserAgent,
            provider.httpHeaders,
            provider.allowedOutputFormatsJson,
            enableBase64TextCompatibility.toString()
        ).joinToString("\u0000")
        return requireNotNull(
            xtreamProviderCache.compute(providerId) { _, cached ->
                if (cached != null && cached.signature == signature) {
                    cached
                } else {
                    val decryptedPassword = credentialCrypto.decryptIfNeeded(provider.password)
                    CachedXtreamProvider(
                        signature = signature,
                        provider = XtreamProvider(
                            providerId = providerId,
                            api = xtreamApiService,
                            serverUrl = provider.serverUrl,
                            username = provider.username,
                            password = decryptedPassword,
                            allowedOutputFormats = provider.toDomain().allowedOutputFormats,
                            enableBase64TextCompatibility = enableBase64TextCompatibility,
                            requestProfile = provider.toGenericRequestProfile(ownerTag = "provider:$providerId/xtream")
                        )
                    )
                }
            }
        ) { "Provider cache compute returned null for providerId=$providerId" }.provider
    }

    private fun createStalkerProvider(providerId: Long, provider: ProviderEntity): StalkerProvider {
        return StalkerProvider(
            providerId = providerId,
            api = stalkerApiService,
            portalUrl = provider.serverUrl,
            macAddress = provider.stalkerMacAddress,
            authMode = provider.stalkerAuthMode,
            username = provider.username,
            password = credentialCrypto.decryptIfNeeded(provider.password),
            httpUserAgent = provider.httpUserAgent,
            httpHeaders = provider.httpHeaders,
            portalFingerprintHint = provider.stalkerPortalFingerprint,
            magPresetHint = provider.stalkerMagPreset,
            bootstrapRecipeHint = provider.stalkerLastBootstrapRecipe,
            endpointPreferenceHint = provider.stalkerEndpointPreference,
            cookieModeHint = provider.stalkerCookieMode,
            playbackBackendHint = provider.stalkerPlaybackBackendHint,
            portalProfileHint = provider.stalkerPortalProfile,
            preferredPlaybackMode = provider.stalkerLastPlaybackMode
                ?.let { value -> runCatching { StalkerPlaybackMode.valueOf(value) }.getOrNull() },
            deviceProfile = provider.stalkerDeviceProfile,
            timezone = provider.stalkerDeviceTimezone,
            locale = provider.stalkerDeviceLocale,
            serialNumber = provider.stalkerSerialNumber,
            deviceId = provider.stalkerDeviceId,
            deviceId2 = provider.stalkerDeviceId2,
            signature = provider.stalkerSignature,
            stalkerAdvancedOptionsJson = provider.stalkerAdvancedOptionsJson
        )
    }

    private fun moviePlaybackComplete(progressMs: Long, totalDurationMs: Long): Boolean {
        if (progressMs <= 0L || totalDurationMs <= 0L) return false
        return progressMs >= (totalDurationMs * 0.95f).toLong()
    }
}

