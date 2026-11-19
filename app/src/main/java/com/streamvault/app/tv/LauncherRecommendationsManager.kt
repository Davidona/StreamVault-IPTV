package com.streamvault.app.tv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.navigation.ExternalItemLinkCodec
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.GetRecommendations
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.RecommendationsResult
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest

@Singleton
class LauncherRecommendationsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRepository: ProviderRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val favoritesSource: Lazy<LauncherFavoritesSource>
) {
    private val getRecommendations = GetRecommendations(movieRepository)
    private val getContinueWatching = GetContinueWatching(playbackHistoryRepository)
    private val refreshMutex = Mutex()
    private var favoritesLogoChannelId: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(FlowPreview::class)
    private val favoritesObservation by lazy {
        scope.launch {
            while (true) {
                try {
                    favoritesSource.get().observe().debounce(300).collectLatest(::syncFavorites)
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    // Fail closed: stale public metadata must not survive unreadable privacy state.
                    try { syncFavorites(LauncherFavoritesSnapshot(null, emptyList())) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Retry the provider write with the next observation. */ }
                    Log.w(TAG, "Favorites observation failed: ${cause.javaClass.simpleName}")
                    delay(5_000)
                }
            }
        }
    }

    fun startObservingFavorites() {
        if (context.isTelevisionDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            favoritesObservation.start()
        }
    }

    suspend fun refreshFavorites() = withContext(Dispatchers.IO) {
        if (!context.isTelevisionDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext
        startObservingFavorites()
        syncFavorites(favoritesSource.get().observe().first())
    }

    private suspend fun syncFavorites(snapshot: LauncherFavoritesSnapshot) = refreshMutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withLock
        // Source snapshots can be queued while a provider switch is being processed.
        val activeId = providerRepository.getActiveProvider().first()?.id
        if (snapshot.providerId != null && snapshot.providerId != activeId) return@withLock
        val existing = loadExistingChannels(setOf(CHANNEL_FAVORITES))[CHANNEL_FAVORITES]
        if (snapshot.items.isEmpty() && existing == null) return@withLock
        val spec = RecommendationChannelSpec(CHANNEL_FAVORITES,
            context.getString(R.string.tv_channel_favorites_title),
            context.getString(R.string.tv_channel_favorites_description),
            snapshot.items.mapIndexed { index, item -> RecommendationProgramSpec(
                key = "favorite:${item.link.providerId}:${item.link.type.path}:${item.link.id}",
                title = item.title,
                description = context.getString(R.string.tv_channel_favorites_description),
                posterArtUri = artworkUri(item.posterUrl),
                intentUri = ExternalItemLinkCodec.intent(context, item.link).toUri(Intent.URI_INTENT_SCHEME),
                weight = LauncherFavoritesSource.MAX_ITEMS - index,
                durationMillis = item.durationMs,
                playbackPositionMillis = item.positionMs,
                contentType = when (item.link.type) {
                    ExternalItemType.MOVIE -> ContentType.MOVIE
                    ExternalItemType.SERIES -> ContentType.SERIES
                    else -> ContentType.LIVE
                }
            ) })
        val channelId = existing ?: insertChannel(spec) ?: return@withLock
        if (favoritesLogoChannelId != channelId && updateChannelLogo(channelId)) favoritesLogoChannelId = channelId
        updateChannel(channelId, spec)
        syncPrograms(channelId, spec)
        // Additional channels are enabled by the viewer in their launcher's channel controls.
        if (providerRepository.getActiveProvider().first()?.id != snapshot.providerId) {
            syncPrograms(channelId, spec.copy(programs = emptyList()))
        }
    }
    @Volatile
    private var lastRefreshAtMs: Long = 0L

    suspend fun refreshRecommendations(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!context.isTelevisionDevice()) return@withContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext

        try { refreshFavorites() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { Log.w(TAG, "Favorites refresh failed: ${cause.javaClass.simpleName}") }
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && lastRefreshAtMs > 0L && now - lastRefreshAtMs < MIN_REFRESH_INTERVAL_MS) {
                return@withLock
            }

            val provider = resolveActiveProvider()
            if (provider == null) {
                deleteAllManagedChannels()
                lastRefreshAtMs = now
                return@withLock
            }

            runCatching {
                val specs = buildChannelSpecs(provider)
                val existingChannels = loadExistingChannels()
                val activeKeys = specs.mapTo(mutableSetOf()) { it.key }

                existingChannels
                    .filterKeys { it !in activeKeys }
                    .values
                    .forEach(::deleteChannel)

                specs.forEach { spec ->
                    if (spec.isDegraded) {
                        // Transient failure — preserve existing channel and programs unchanged.
                        return@forEach
                    }
                    if (spec.programs.isEmpty()) {
                        existingChannels[spec.key]?.let(::deleteChannel)
                        return@forEach
                    }

                    val channelId = existingChannels[spec.key] ?: insertChannel(spec) ?: return@forEach
                    updateChannel(channelId, spec)
                    syncPrograms(channelId, spec)
                    requestChannelBrowsable(channelId)
                }
                lastRefreshAtMs = now
            }.onFailure { throwable ->
                Log.w(TAG, "Launcher recommendation sync failed", throwable)
            }
        }
    }

    /**
     * Resolves the provider that should drive launcher recommendation channels.
     * In single-provider mode, returns the active provider directly.
     * In combined-live mode, prefers the globally active provider only when it is a
     * member of the active combined profile; otherwise falls back to the first enabled member.
     */
    private suspend fun resolveActiveProvider(): Provider? {
        val activeSource = combinedM3uRepository.getActiveLiveSource().first()
        return when (activeSource) {
            is ActiveLiveSource.CombinedM3uSource -> {
                val memberIds = combinedM3uRepository.getProfile(activeSource.profileId)
                    ?.members.orEmpty()
                    .filter { it.enabled }
                    .map { it.providerId }
                val activeProvider = providerRepository.getActiveProvider().first()
                activeProvider?.takeIf { it.id in memberIds }
                    ?: memberIds.firstOrNull()?.let { providerRepository.getProvider(it) }
            }
            is ActiveLiveSource.ProviderSource -> {
                providerRepository.getActiveProvider().first()
                    ?.takeIf { it.id == activeSource.providerId }
                    ?: providerRepository.getProvider(activeSource.providerId)
            }
            null -> providerRepository.getActiveProvider().first()
        }
    }

    private suspend fun buildChannelSpecs(provider: Provider): List<RecommendationChannelSpec> {
        val continueWatching = when (val result = getContinueWatching(provider.id, limit = 12, requireResumePosition = true).first()) {
            is ContinueWatchingResult.Items -> result.items
            ContinueWatchingResult.Degraded -> emptyList()
        }.mapNotNull { history ->
                val link = ExternalItemLinkCodec.historyLink(history) ?: return@mapNotNull null
                RecommendationProgramSpec(
                    key = "cw:${history.contentType.name}:${history.contentId}",
                    title = history.title,
                    description = context.getString(R.string.saved_preset_watch_next),
                    posterArtUri = artworkUri(history.posterUrl),
                    intentUri = ExternalItemLinkCodec.intent(context, link).toUri(Intent.URI_INTENT_SCHEME),
                    weight = history.lastWatchedAt.toInt().coerceAtLeast(0),
                    durationMillis = history.totalDurationMs,
                    playbackPositionMillis = history.resumePositionMs,
                    contentType = history.contentType
                )
            }

        val recommendationsResult = getRecommendations(provider.id, limit = 12).first()
        val recommendedMovies: List<RecommendationProgramSpec>?
        val recommendationsDegraded: Boolean
        when (recommendationsResult) {
            is RecommendationsResult.Success -> {
                recommendedMovies = recommendationsResult.movies.mapIndexed { index, movie ->
                    RecommendationProgramSpec(
                        key = "movie:${movie.id}",
                        title = movie.name,
                        description = movie.plot ?: movie.genre ?: provider.name,
                        posterArtUri = artworkUri(movie.posterUrl ?: movie.backdropUrl),
                        intentUri = ExternalItemLinkCodec.intent(context, ExternalItemLink(
                            ExternalItemType.MOVIE, movie.id, movie.providerId, ExternalItemAction.PLAY
                        )).toUri(Intent.URI_INTENT_SCHEME),
                        weight = (TOP_MOVIE_WEIGHT_BASE - index).coerceAtLeast(0),
                        durationMillis = movie.durationSeconds.toLong() * 1000L,
                        playbackPositionMillis = movie.watchProgress,
                        contentType = ContentType.MOVIE
                    )
                }
                recommendationsDegraded = false
            }
            RecommendationsResult.Degraded -> {
                recommendedMovies = emptyList()
                recommendationsDegraded = true
            }
        }

        val freshSeries = seriesRepository.getFreshPreview(provider.id, limit = 12)
            .first()
            .mapIndexed { index, series ->
                RecommendationProgramSpec(
                    key = "series:${series.id}",
                    title = series.name,
                    description = series.plot ?: series.genre ?: provider.name,
                    posterArtUri = artworkUri(series.posterUrl ?: series.backdropUrl),
                    intentUri = ExternalItemLinkCodec.intent(context, ExternalItemLink(
                        ExternalItemType.SERIES, series.id, series.providerId, ExternalItemAction.DETAILS
                    )).toUri(Intent.URI_INTENT_SCHEME),
                    weight = (FRESH_SERIES_WEIGHT_BASE - index).coerceAtLeast(0),
                    durationMillis = 0L,
                    playbackPositionMillis = 0L,
                    contentType = ContentType.SERIES
                )
            }

        return listOf(
            RecommendationChannelSpec(
                key = CHANNEL_CONTINUE_WATCHING,
                title = context.getString(R.string.tv_channel_continue_watching_title),
                description = context.getString(R.string.tv_channel_continue_watching_description),
                programs = continueWatching
            ),
            RecommendationChannelSpec(
                key = CHANNEL_TOP_MOVIES,
                title = context.getString(R.string.tv_channel_recommended_movies_title),
                description = context.getString(R.string.tv_channel_recommended_movies_description, provider.name),
                programs = recommendedMovies,
                isDegraded = recommendationsDegraded
            ),
            RecommendationChannelSpec(
                key = CHANNEL_FRESH_SERIES,
                title = context.getString(R.string.tv_channel_fresh_series_title),
                description = context.getString(R.string.tv_channel_fresh_series_description, provider.name),
                programs = freshSeries
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadExistingChannels(managedKeys: Set<String> = MANAGED_CHANNEL_KEYS): Map<String, Long> {
        val projection = arrayOf(
            BaseColumns._ID,
            CHANNEL_COLUMN_TYPE,
            CHANNEL_COLUMN_INTERNAL_PROVIDER_ID
        )
        return context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val typeIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_TYPE)
            val keyIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID)
            buildMap {
                while (cursor.moveToNext()) {
                    val type = cursor.getString(typeIndex)
                    val key = cursor.getString(keyIndex)
                    if (type == TvContract.Channels.TYPE_PREVIEW && key in managedKeys) {
                        put(key, cursor.getLong(idIndex))
                    }
                }
            }
        }.orEmpty()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun insertChannel(spec: RecommendationChannelSpec): Long? {
        val uri = context.contentResolver.insert(TvContract.Channels.CONTENT_URI, buildChannelValues(spec)) ?: return null
        val channelId = ContentUris.parseId(uri)
        updateChannelLogo(channelId)
        return channelId
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateChannel(channelId: Long, spec: RecommendationChannelSpec) {
        context.contentResolver.update(
            ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId),
            buildChannelValues(spec),
            null,
            null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun syncPrograms(channelId: Long, spec: RecommendationChannelSpec) {
        val existingPrograms = loadExistingPrograms(channelId)
        val activeKeys = spec.programs.mapTo(mutableSetOf()) { it.key }

        existingPrograms
            .filterKeys { it !in activeKeys }
            .values
            .forEach { programId ->
                context.contentResolver.delete(
                    ContentUris.withAppendedId(TvContract.PreviewPrograms.CONTENT_URI, programId),
                    null,
                    null
                )
            }

        spec.programs.forEach { program ->
            val values = buildProgramValues(channelId, program)
            val existingId = existingPrograms[program.key]
            if (existingId == null) {
                context.contentResolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)
            } else {
                context.contentResolver.update(
                    ContentUris.withAppendedId(TvContract.PreviewPrograms.CONTENT_URI, existingId),
                    values,
                    null,
                    null
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadExistingPrograms(channelId: Long): Map<String, Long> {
        val projection = arrayOf(BaseColumns._ID, PREVIEW_COLUMN_INTERNAL_PROVIDER_ID)
        return context.contentResolver.query(
            TvContract.buildPreviewProgramsUriForChannel(channelId),
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val keyIndex = cursor.getColumnIndexOrThrow(PREVIEW_COLUMN_INTERNAL_PROVIDER_ID)
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(keyIndex), cursor.getLong(idIndex))
                }
            }
        }.orEmpty()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildChannelValues(spec: RecommendationChannelSpec): ContentValues = ContentValues().apply {
        put(CHANNEL_COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
        put(CHANNEL_COLUMN_DISPLAY_NAME, spec.title)
        put(CHANNEL_COLUMN_DESCRIPTION, spec.description)
        put(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID, spec.key)
        put(CHANNEL_COLUMN_APP_LINK_INTENT_URI, buildBrowseIntent().toUri(Intent.URI_INTENT_SCHEME))
        put("searchable", 1)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildProgramValues(channelId: Long, spec: RecommendationProgramSpec): ContentValues = ContentValues().apply {
        put(PREVIEW_COLUMN_CHANNEL_ID, channelId)
        put(PREVIEW_COLUMN_TYPE, previewProgramType(spec.contentType))
        put(PREVIEW_COLUMN_TITLE, spec.title)
        put(PREVIEW_COLUMN_DESCRIPTION, spec.description)
        put(PREVIEW_COLUMN_POSTER_ART_URI, spec.posterArtUri.toString())
        put(PREVIEW_COLUMN_INTENT_URI, spec.intentUri)
        put("searchable", 1)
        put(PREVIEW_COLUMN_WEIGHT, spec.weight)
        put(PREVIEW_COLUMN_INTERNAL_PROVIDER_ID, spec.key)
        put(PREVIEW_COLUMN_LAST_PLAYBACK_POSITION_MILLIS, spec.playbackPositionMillis)
        put(PREVIEW_COLUMN_DURATION_MILLIS, spec.durationMillis)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun previewProgramType(contentType: ContentType): Int = when (contentType) {
        ContentType.MOVIE,
        ContentType.VOD -> TvContract.PreviewPrograms.TYPE_MOVIE
        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
        ContentType.LIVE -> TvContract.PreviewPrograms.TYPE_CLIP
    }

    private fun buildBrowseIntent(): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "home")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestChannelBrowsable(channelId: Long) {
        runCatching {
            TvContract.requestChannelBrowsable(context, channelId)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to request browsable state for channel $channelId", throwable)
        }
    }

    private fun updateChannelLogo(channelId: Long): Boolean =
        runCatching {
            val output = context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(channelId), "w")
                ?: return@runCatching false
            output.use(::writeDefaultLogo)
            true
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to write channel logo for $channelId", throwable)
        }.getOrDefault(false)

    private fun writeDefaultLogo(stream: OutputStream) {
        val bitmap = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)).toBitmap()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun deleteAllManagedChannels() {
        loadExistingChannels().values.forEach(::deleteChannel)
    }

    private fun deleteChannel(channelId: Long) {
        context.contentResolver.delete(
            ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId),
            null,
            null
        )
    }

    private fun artworkUri(url: String?): Uri {
        val remoteArtwork = url?.takeIf { it.isNotBlank() }
        if (remoteArtwork != null) {
            return Uri.parse(remoteArtwork)
        }
        return Uri.parse("android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
    }

    private data class RecommendationChannelSpec(
        val key: String,
        val title: String,
        val description: String,
        val programs: List<RecommendationProgramSpec>,
        val isDegraded: Boolean = false
    )

    private data class RecommendationProgramSpec(
        val key: String,
        val title: String,
        val description: String,
        val posterArtUri: Uri,
        val intentUri: String,
        val weight: Int,
        val durationMillis: Long,
        val playbackPositionMillis: Long,
        val contentType: ContentType
    )

    private companion object {
        const val TAG = "LauncherRecommendations"
        const val MIN_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
        const val CHANNEL_CONTINUE_WATCHING = "streamvault_continue_watching"
        const val CHANNEL_TOP_MOVIES = "streamvault_top_movies"
        const val CHANNEL_FRESH_SERIES = "streamvault_fresh_series"
        const val CHANNEL_FAVORITES = "streamvault_favorites"
        val MANAGED_CHANNEL_KEYS = setOf(
            CHANNEL_CONTINUE_WATCHING,
            CHANNEL_TOP_MOVIES,
            CHANNEL_FRESH_SERIES
        )
        const val TOP_MOVIE_WEIGHT_BASE = 10_000
        const val FRESH_SERIES_WEIGHT_BASE = 9_000
        const val CHANNEL_COLUMN_TYPE = "type"
        const val CHANNEL_COLUMN_DISPLAY_NAME = "display_name"
        const val CHANNEL_COLUMN_DESCRIPTION = "description"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val CHANNEL_COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri"
        const val PREVIEW_COLUMN_CHANNEL_ID = "channel_id"
        const val PREVIEW_COLUMN_TYPE = "type"
        const val PREVIEW_COLUMN_TITLE = "title"
        const val PREVIEW_COLUMN_DESCRIPTION = "description"
        const val PREVIEW_COLUMN_POSTER_ART_URI = "poster_art_uri"
        const val PREVIEW_COLUMN_INTENT_URI = "intent_uri"
        const val PREVIEW_COLUMN_WEIGHT = "weight"
        const val PREVIEW_COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val PREVIEW_COLUMN_LAST_PLAYBACK_POSITION_MILLIS = "last_playback_position_millis"
        const val PREVIEW_COLUMN_DURATION_MILLIS = "duration_millis"
    }
}
