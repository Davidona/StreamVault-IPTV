package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.VodSyncMode
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.flow.first

/** Owns Xtream category-shell and movie/series repair orchestration. */
internal class XtreamCatalogSectionExecutor(
    private val preferencesRepository: PreferencesRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val syncCatalogStore: SyncCatalogStore,
    private val createProvider: suspend (Provider, Boolean, Boolean) -> XtreamProvider,
    private val markIndexRunning: suspend (Long, ContentType, Long) -> Unit,
    private val markIndexQueued: suspend (Long, ContentType, Long, Int) -> Unit,
    private val markIndexFailure: suspend (Long, ContentType, Long, Throwable) -> Unit,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable) -> String,
    private val userMessage: (Throwable, String) -> String,
    private val hasPendingIndex: suspend (Long, ContentType) -> Boolean = { _, _ -> false }
) {
    suspend fun syncCategoryShell(
        provider: Provider,
        api: XtreamProvider,
        contentType: ContentType,
        label: String,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): kotlin.Result<Int> = runCatching {
        progress(provider.id, onProgress, "Loading $label categories...")
        markIndexRunning(provider.id, contentType, now)
        val categories = when (contentType) {
            ContentType.MOVIE -> requireResult(api.getVodCategories(), "Failed to load VOD categories")
            ContentType.SERIES -> requireResult(api.getSeriesCategories(), "Failed to load series categories")
            else -> throw IllegalArgumentException("Unsupported Xtream category shell: $contentType")
        }
        syncCatalogStore.upsertCategories(
            providerId = provider.id,
            type = contentType.name,
            categories = categories.map { category -> category.toEntity(provider.id) }
        )
        markIndexQueued(provider.id, contentType, now, categories.size)
        categories.size
    }

    suspend fun syncMovies(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        preservePendingIndex: Boolean = false
    ): SyncOutcome {
        if (preservePendingIndex && hasPendingIndex(provider.id, ContentType.MOVIE)) {
            progress(provider.id, onProgress, "Resuming Movies index...")
            return queuedOutcome(ContentType.MOVIE)
        }
        val now = System.currentTimeMillis()
        progress(provider.id, onProgress, "Queueing Movies index...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val api = createProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val currentMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        val categoryCount = syncCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.MOVIE,
            label = "Movies",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            markIndexFailure(provider.id, ContentType.MOVIE, now, error)
            throw IllegalStateException(
                userMessage(error, "Failed to queue movie index"),
                error
            )
        }
        syncMetadataRepository.updateMetadata(
            currentMetadata.copy(
                lastMovieAttempt = now,
                movieCatalogStale = true,
                movieSyncMode = VodSyncMode.UNKNOWN
            )
        )
        Log.i(TAG, "Queued Xtream movie index for provider ${provider.id}: $categoryCount categories.")
        return SyncOutcome(
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = ContentType.MOVIE,
                    reason = "movie category shell is committed; durable item indexing is queued"
                )
            ),
            activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
        )
    }

    suspend fun syncSeries(
        provider: Provider,
        onProgress: ((String) -> Unit)?,
        preservePendingIndex: Boolean = false
    ): SyncOutcome {
        if (preservePendingIndex && hasPendingIndex(provider.id, ContentType.SERIES)) {
            progress(provider.id, onProgress, "Resuming Series index...")
            return queuedOutcome(ContentType.SERIES)
        }
        val now = System.currentTimeMillis()
        progress(provider.id, onProgress, "Queueing Series index...")
        val useTextClassification = preferencesRepository.useXtreamTextClassification.first()
        val enableBase64TextCompatibility = preferencesRepository.xtreamBase64TextCompatibility.first()
        val api = createProvider(provider, useTextClassification, enableBase64TextCompatibility)
        val categoryCount = syncCategoryShell(
            provider = provider,
            api = api,
            contentType = ContentType.SERIES,
            label = "Series",
            now = now,
            onProgress = onProgress
        ).getOrElse { error ->
            markIndexFailure(provider.id, ContentType.SERIES, now, error)
            throw IllegalStateException(
                userMessage(error, "Failed to queue series index"),
                error
            )
        }
        // A category shell is an attempt, not a completed series import.
        Log.i(TAG, "Queued Xtream series index for provider ${provider.id}: $categoryCount categories.")
        return SyncOutcome(
            continuationWork = listOf(
                SyncContinuation(
                    operation = SyncContinuationOperation.INDEX_CATALOG,
                    section = ContentType.SERIES,
                    reason = "series category shell is committed; durable item indexing is queued"
                )
            ),
            activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
        )
    }

    private fun <T> requireResult(result: Result<T>, fallbackMessage: String): T = when (result) {
        is Result.Success -> result.data
        is Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
        is Result.Loading -> throw IllegalStateException("Unexpected loading state")
    }

    private fun queuedOutcome(section: ContentType) = SyncOutcome(
        continuationWork = listOf(SyncContinuation(
            operation = SyncContinuationOperation.INDEX_CATALOG,
            section = section,
            reason = "resume the existing durable index without resetting its cursor"
        )),
        activation = SyncActivation.DEFERRED_TO_FOLLOW_UP
    )

    private companion object {
        const val TAG = "XtreamCatalogSection"
    }
}
