package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamParsingException
import com.streamvault.data.remote.xtream.XtreamRequestException
import com.streamvault.data.remote.xtream.XtreamResponseTooLargeException
import com.streamvault.domain.model.LegacyProvider as Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.random.Random

private const val XTREAM_SUPPORT_TAG = "SyncManager"

internal class SyncManagerXtreamSupport(
    private val adaptiveSyncPolicy: XtreamAdaptiveSyncPolicy,
    private val shouldRememberSequentialPreference: (Throwable) -> Boolean,
    private val sanitizeThrowableMessage: (Throwable?) -> String,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val movieRequestTimeoutMillis: Long,
    private val seriesRequestTimeoutMillis: Long,
    private val recoveryAbortWarningSuffix: String
) {
    suspend fun <T> executeCategoryRecoveryPlan(
        provider: Provider,
        categories: List<XtreamCategory>,
        initialConcurrency: Int,
        sectionLabel: String,
        sequentialModeWarning: String,
        onProgress: ((String) -> Unit)?,
        fetch: suspend (XtreamCategory) -> TimedCategoryOutcome<T>,
        refillWorkers: Boolean = false,
        onCategoryCompleted: ((completed: Int, total: Int, currentLabel: String) -> Unit)? = null,
        shouldStopOnFailure: (Throwable) -> Boolean = { false }
    ): CategoryExecutionPlan<T> {
        if (categories.isEmpty()) {
            return CategoryExecutionPlan(emptyList())
        }
        if (refillWorkers) return executeRefilledCategoryPlan(provider, categories, initialConcurrency,
            sectionLabel, sequentialModeWarning, onProgress, fetch, onCategoryCompleted, shouldStopOnFailure)

        val outcomes = mutableListOf<TimedCategoryOutcome<T>>()
        val warnings = mutableListOf<String>()
        var nextIndex = 0
        var forceSequential = initialConcurrency <= 1
        var consecutiveSequentialStressFailures = 0
        var stoppedEarly = false
        var stopReason: CategoryExecutionStopReason? = null

        while (nextIndex < categories.size && !stoppedEarly) {
            val windowConcurrency = if (forceSequential) 1 else initialConcurrency
            val window = categories.subList(nextIndex, minOf(nextIndex + windowConcurrency, categories.size))
            val startingCategoryNumber = nextIndex + 1
            val endingCategoryNumber = nextIndex + window.size
            val windowLabel = if (startingCategoryNumber == endingCategoryNumber) {
                startingCategoryNumber.toString()
            } else {
                "$startingCategoryNumber-$endingCategoryNumber"
            }
            progress(provider.id, onProgress, "Downloading $sectionLabel by category $windowLabel/${categories.size}...")
            val windowOutcomes = coroutineScope {
                window.map { category ->
                    async { fetch(category) }
                }.awaitAll()
            }
            outcomes += windowOutcomes
            nextIndex += window.size

            if (windowOutcomes.any { timed ->
                    val error = (timed.outcome as? CategoryFetchOutcome.Failure)?.error
                    error != null && shouldStopOnFailure(error)
                }
            ) {
                stoppedEarly = true
                stopReason = CategoryExecutionStopReason.AUTHENTICATION
            }

            val completed = outcomes.size
            progress(provider.id, onProgress, "Downloading $sectionLabel by category $completed/${categories.size}...")
            // Hook d'emission optionnel (M1 — T4) : permet aux strategies d'emettre un
            // SyncProgress structure apres chaque fenetre traitee. Le label correspond a
            // la derniere categorie de la fenetre (pertinent en mode sequentiel ; en mode
            // concurrent, la fenetre est petite donc le label reste representatif).
            onCategoryCompleted?.invoke(completed, categories.size, window.last().categoryName)

            if (!stoppedEarly && !forceSequential && shouldRecoverRemainingCategoryRequests(categories.size, completed, outcomes.map { it.outcome })) {
                forceSequential = true
                warnings += sequentialModeWarning
                Log.w(
                    XTREAM_SUPPORT_TAG,
                    "Xtream $sectionLabel category sync is switching remaining categories to sequential mode for provider ${provider.id} after $completed/${categories.size} categories."
                )
            }

            if (forceSequential) {
                windowOutcomes.forEach { timedOutcome ->
                    val isStressFailure = (timedOutcome.outcome as? CategoryFetchOutcome.Failure)
                        ?.error
                        ?.let(shouldRememberSequentialPreference)
                        ?: false
                    consecutiveSequentialStressFailures = if (isStressFailure) {
                        consecutiveSequentialStressFailures + 1
                    } else {
                        0
                    }
                }
                if (consecutiveSequentialStressFailures >= 3 && nextIndex < categories.size) {
                    warnings += "$sectionLabel $recoveryAbortWarningSuffix"
                    Log.w(
                        XTREAM_SUPPORT_TAG,
                        "Xtream $sectionLabel category sync stopped early for provider ${provider.id} after repeated sequential stress failures. completed=$completed total=${categories.size}"
                    )
                    stoppedEarly = true
                }
            }
        }

        return CategoryExecutionPlan(
            outcomes = outcomes,
            warnings = warnings.distinct(),
            stopReason = stopReason ?: if (stoppedEarly) CategoryExecutionStopReason.PROVIDER_STRESS else null
        )
    }

    private suspend fun <T> executeRefilledCategoryPlan(
        provider: Provider,
        categories: List<XtreamCategory>,
        initialConcurrency: Int,
        sectionLabel: String,
        sequentialModeWarning: String,
        onProgress: ((String) -> Unit)?,
        fetch: suspend (XtreamCategory) -> TimedCategoryOutcome<T>,
        onCategoryCompleted: ((Int, Int, String) -> Unit)?,
        shouldStopOnFailure: (Throwable) -> Boolean
    ): CategoryExecutionPlan<T> = coroutineScope {
        val limit = initialConcurrency.coerceIn(1, categories.size)
        val completed = Channel<Pair<Int, TimedCategoryOutcome<T>>>(limit)
        val results = mutableListOf<Pair<Int, TimedCategoryOutcome<T>>>()
        val warnings = mutableListOf<String>()
        var nextIndex = 0
        var active = 0
        var sequential = limit == 1
        var stopReason: CategoryExecutionStopReason? = null
        var stressFailures = 0
        fun fillSlots() {
            val desired = if (sequential) 1 else limit
            while (stopReason == null && active < desired && nextIndex < categories.size) {
                val index = nextIndex++
                active++
                launch {
                    try {
                        completed.send(index to fetch(categories[index]))
                    } catch (error: CancellationException) {
                        this@coroutineScope.cancel(error)
                        throw error
                    }
                }
            }
        }
        try {
            fillSlots()
            while (active > 0) {
                val result = completed.receive()
                active--
                results += result
                val outcome = result.second.outcome
                val error = (outcome as? CategoryFetchOutcome.Failure)?.error
                progress(provider.id, onProgress, "Downloading $sectionLabel by category ${results.size}/${categories.size}...")
                onCategoryCompleted?.invoke(results.size, categories.size, result.second.category.categoryName)
                if (error is XtreamAuthenticationException || (error != null && shouldStopOnFailure(error))) {
                    stopReason = CategoryExecutionStopReason.AUTHENTICATION
                }
                if (!sequential && shouldRecoverRemainingCategoryRequests(categories.size, results.size, results.map { it.second.outcome })) {
                    sequential = true
                    warnings += sequentialModeWarning
                }
                if (sequential && stopReason == null) {
                    stressFailures = if (error != null && shouldRememberSequentialPreference(error)) stressFailures + 1 else 0
                    if (stressFailures >= 3) {
                        stopReason = CategoryExecutionStopReason.PROVIDER_STRESS
                        if (nextIndex < categories.size) warnings += "$sectionLabel $recoveryAbortWarningSuffix"
                    }
                }
                // Already running attempts drain before a downgrade admits another.
                fillSlots()
            }
        } finally {
            completed.cancel()
        }
        CategoryExecutionPlan(results.sortedBy { it.first }.map { it.second }, warnings.distinct(), stopReason)
    }

    suspend fun <T> retryTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 700L,
        block: suspend () -> T
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Exception? = null

        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                lastError = t
                attempt++
                if (attempt >= maxAttempts || !isRetryable(t)) {
                    throw t
                }
                delay(delayMs)
                delayMs *= 2
            }
        }

        throw lastError ?: IllegalStateException("Unknown sync retry failure")
    }

    suspend fun <T> attemptNonCancellation(block: suspend () -> T): Attempt<T> {
        return try {
            Attempt.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Attempt.Failure(e)
        }
    }

    suspend fun <T> executeXtreamRequest(
        providerId: Long,
        stage: XtreamAdaptiveSyncPolicy.Stage,
        block: suspend () -> T
    ): T {
        adaptiveSyncPolicy.awaitTurn(providerId, stage)
        val timeoutMs = adaptiveSyncPolicy.timeoutFor(providerId, stage)
        return try {
            val result = if (timeoutMs != null) {
                withTimeout(timeoutMs) {
                    block()
                }
            } else {
                block()
            }
            adaptiveSyncPolicy.recordSuccess(providerId)
            result
        } catch (e: TimeoutCancellationException) {
            adaptiveSyncPolicy.recordFailure(providerId, e)
            throw IOException(
                "Timed out after ${formatTimeout(timeoutMs)} during ${stage.name.lowercase()} Xtream request",
                e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: XtreamLiveWriterException) {
            // Local persistence is not a provider-health signal.
            throw e
        } catch (e: Exception) {
            adaptiveSyncPolicy.recordFailure(providerId, e)
            throw e
        }
    }

    suspend fun <T> withMovieRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T {
        return withRequestTimeout(movieRequestTimeoutMillis, requestLabel, block)
    }

    suspend fun <T> withSeriesRequestTimeout(
        requestLabel: String,
        block: suspend () -> T
    ): T {
        return withRequestTimeout(seriesRequestTimeoutMillis, requestLabel, block)
    }

    suspend fun <T> retryXtreamCatalogTransient(providerId: Long, block: suspend () -> T): T {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt < 3) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                lastError = t
                attempt++
                if (attempt >= 3 || !isXtreamCatalogRetryable(t, attempt)) {
                    throw t
                }
                val delayMs = adaptiveSyncPolicy.retryDelayFor(providerId, attempt)
                val jitterMs = Random.nextLong(0L, (delayMs / 3L).coerceAtLeast(1L) + 1L)
                delay(delayMs + jitterMs)
            }
        }

        throw lastError ?: IllegalStateException("Unknown Xtream catalog retry failure")
    }

    fun logXtreamCatalogFallback(
        provider: Provider,
        section: String,
        stage: String,
        elapsedMs: Long,
        itemCount: Int?,
        error: Throwable?,
        nextStep: String
    ) {
        val reason = when {
            error != null -> "${error::class.java.simpleName}: ${sanitizeThrowableMessage(error)}"
            itemCount == 0 -> "empty result"
            else -> "no usable data"
        }
        Log.w(
            XTREAM_SUPPORT_TAG,
            "Xtream $section $stage failed for provider ${provider.id} after ${elapsedMs}ms ($reason). Switching to $nextStep."
        )
    }

    suspend fun <T> continueFailedCategoryOutcomes(
        provider: Provider,
        timedOutcomes: List<TimedCategoryOutcome<T>>,
        fetchSequentially: suspend (XtreamCategory) -> TimedCategoryOutcome<T>,
        onCategoryRetried: ((completed: Int, total: Int, currentLabel: String) -> Unit)? = null,
        stopReason: CategoryExecutionStopReason? = null
    ): List<TimedCategoryOutcome<T>> {
        if (stopReason != null || timedOutcomes.any {
                (it.outcome as? CategoryFetchOutcome.Failure)?.error is XtreamAuthenticationException
            }) {
            return timedOutcomes
        }
        val failedCategories = timedOutcomes
            .filter { it.outcome is CategoryFetchOutcome.Failure }
            .map { it.category }
        if (failedCategories.isEmpty()) {
            return timedOutcomes
        }
        val replacements = LinkedHashMap<String, TimedCategoryOutcome<T>>()
        for ((index, category) in failedCategories.withIndex()) {
            val result = fetchSequentially(category)
            replacements[category.categoryId] = result
            onCategoryRetried?.invoke(index + 1, failedCategories.size, category.categoryName)
            if ((result.outcome as? CategoryFetchOutcome.Failure)?.error is XtreamAuthenticationException) break
        }
        return timedOutcomes.map { existing ->
            replacements[existing.category.categoryId] ?: existing
        }.also {
            Log.i(
                XTREAM_SUPPORT_TAG,
                "Xtream continuation fallback kept ${timedOutcomes.count { it.outcome !is CategoryFetchOutcome.Failure }} successful category results for provider ${provider.id} and retried ${replacements.size} failed categories."
            )
        }
    }

    private fun <T> shouldRecoverRemainingCategoryRequests(
        totalCategories: Int,
        processedCategories: Int,
        outcomes: List<CategoryFetchOutcome<T>>
    ): Boolean {
        if (processedCategories >= totalCategories || processedCategories <= 1) {
            return false
        }
        val processedOutcomes = outcomes.take(processedCategories)
        val failures = processedOutcomes.count { it is CategoryFetchOutcome.Failure }
        val stressFailures = processedOutcomes.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        val recentWindow = processedOutcomes.takeLast(minOf(4, processedOutcomes.size))
        val recentStressFailures = recentWindow.count { outcome ->
            outcome is CategoryFetchOutcome.Failure && shouldRememberSequentialPreference(outcome.error)
        }
        val failureRatio = failures.toFloat() / processedCategories.toFloat()
        return recentStressFailures >= minOf(2, recentWindow.size) ||
            stressFailures >= minOf(3, processedCategories) ||
            (processedCategories >= 6 && failureRatio >= 0.34f)
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is XtreamAuthenticationException) return false
        if (error is XtreamParsingException) return false
        if (error is XtreamRequestException) return false
        if (error is XtreamNetworkException) return true
        if (error is IOException) return true

        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("unable to resolve host") ||
            message.contains("connection reset") ||
            message.contains("connect") ||
            message.contains("network")
    }

    private fun isXtreamCatalogRetryable(error: Throwable, attempt: Int): Boolean {
        return when (error) {
            is XtreamLiveWriterException -> false
            is XtreamAuthenticationException -> false
            is XtreamResponseTooLargeException -> false
            is XtreamParsingException -> attempt == 1
            is XtreamRequestException ->
                error.statusCode in setOf(408, 409, 429) || error.statusCode in 500..599
            is XtreamNetworkException -> true
            is IOException -> true
            else -> isRetryable(error)
        }
    }

    private suspend fun <T> withRequestTimeout(
        timeoutMillis: Long,
        requestLabel: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMillis) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Timed out after ${formatTimeout(timeoutMillis)} while loading $requestLabel", e)
        }
    }

    private fun formatTimeout(timeoutMillis: Long?): String {
        if (timeoutMillis == null) {
            return "the configured timeout"
        }
        return if (timeoutMillis % 1_000L == 0L) {
            "${timeoutMillis / 1_000L} seconds"
        } else {
            "$timeoutMillis ms"
        }
    }
}

internal fun <T> com.streamvault.domain.model.Result<T>.getOrThrow(resourceName: String): T {
    return when (this) {
        is com.streamvault.domain.model.Result.Success -> data
        is com.streamvault.domain.model.Result.Error ->
            throw exception ?: IllegalStateException("Failed to fetch $resourceName: $message")
        is com.streamvault.domain.model.Result.Loading ->
            throw Exception("Unexpected loading state for $resourceName")
    }
}
