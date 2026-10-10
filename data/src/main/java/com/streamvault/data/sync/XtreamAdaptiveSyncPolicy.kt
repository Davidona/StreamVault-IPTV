package com.streamvault.data.sync

import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.data.remote.xtream.XtreamRequestException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class XtreamAdaptiveSyncPolicy(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private companion object {
        const val MAX_SEGMENTED_CONCURRENCY = 4
    }

    enum class Stage(val timeoutMs: Long?) {
        LIGHTWEIGHT(15_000L),
        CATEGORY(35_000L),
        LIVE_CATEGORY(35_000L),
        HEAVY(null)
    }

    private data class ProviderHealth(
        val stressLevel: Int = 0,
        val backoffUntilMs: Long = 0L,
        val retryAfterUntilMs: Long = 0L,
        val successStreak: Int = 0,
        val timeoutStreak: Int = 0,
        val resetStreak: Int = 0,
        val connectionFailureStreak: Int = 0
    )

    private class ProviderPacingState {
        val mutex = Mutex()
        var nextSegmentedRequestStartMs: Long = 0L
    }

    private val providerHealth = ConcurrentHashMap<Long, ProviderHealth>()
    private val providerPacing = ConcurrentHashMap<Long, ProviderPacingState>()
    // This policy is shared by SyncManager. Do not multiply queued bulk batches
    // when different providers import simultaneously.
    private val livePipelineSlot = Semaphore(1)

    suspend fun <T> withLivePipelinePermit(block: suspend () -> T): T =
        livePipelineSlot.withPermit { block() }

    fun liveRequestConcurrencyFor(providerId: Long): Int =
        if ((providerHealth[providerId]?.stressLevel ?: 0) > 0) 1 else 2

    suspend fun awaitTurn(providerId: Long, stage: Stage) {
        val health = providerHealth[providerId]
        val waitMs = maxOf(health?.backoffUntilMs ?: 0L, health?.retryAfterUntilMs ?: 0L) - nowMillis()
        if (waitMs > 0L) {
            delay(waitMs)
        }
        val requestSpacingMs = segmentedRequestSpacingFor(providerId, stage)
        if (requestSpacingMs <= 0L) {
            return
        }
        val pacingState = providerPacing.computeIfAbsent(providerId) { ProviderPacingState() }
        pacingState.mutex.withLock {
            val now = nowMillis()
            val pacingWaitMs = (pacingState.nextSegmentedRequestStartMs - now).coerceAtLeast(0L)
            if (pacingWaitMs > 0L) {
                delay(pacingWaitMs)
            }
            pacingState.nextSegmentedRequestStartMs = nowMillis() + requestSpacingMs
        }
    }

    fun timeoutFor(providerId: Long, stage: Stage): Long? = stage.timeoutMs

    fun concurrencyFor(
        providerId: Long,
        workloadSize: Int,
        preferSequential: Boolean,
        stage: Stage
    ): Int {
        if (preferSequential || workloadSize <= 1) {
            return 1
        }
        val health = providerHealth[providerId] ?: ProviderHealth()
        val base = when (stage) {
            Stage.LIGHTWEIGHT -> 1
            Stage.CATEGORY, Stage.LIVE_CATEGORY -> when {
                workloadSize <= 3 -> 1
                workloadSize <= 8 -> 2
                workloadSize <= 18 -> 2
                workloadSize <= 36 -> 3
                else -> 4
            }
            Stage.HEAVY -> 1
        }
        val adjusted = when {
            health.stressLevel >= 5 -> 1
            health.stressLevel >= 4 -> (base / 3).coerceAtLeast(1)
            health.stressLevel >= 3 -> (base / 2).coerceAtLeast(1)
            health.stressLevel >= 2 -> (base - 2).coerceAtLeast(1)
            health.stressLevel >= 1 -> (base - 1).coerceAtLeast(1)
            else -> base
        }
        val boosted = if (health.stressLevel == 0 && health.successStreak >= 4 && stage != Stage.HEAVY) {
            adjusted + 1
        } else {
            adjusted
        }
        return boosted.coerceIn(1, MAX_SEGMENTED_CONCURRENCY).coerceAtMost(workloadSize)
    }

    fun recordSuccess(providerId: Long) {
        providerHealth.compute(providerId) { _, current ->
            val state = current ?: ProviderHealth()
            val nextSuccessStreak = (state.successStreak + 1).coerceAtMost(6)
            val recoveredStress = if (nextSuccessStreak >= 2) {
                (state.stressLevel - 1).coerceAtLeast(0)
            } else {
                state.stressLevel
            }
            state.copy(
                stressLevel = recoveredStress,
                backoffUntilMs = if (recoveredStress == 0) 0L else state.backoffUntilMs,
                successStreak = nextSuccessStreak,
                timeoutStreak = 0,
                resetStreak = 0,
                connectionFailureStreak = 0
            )
        }
    }

    fun recordFailure(providerId: Long, error: Throwable) {
        providerHealth.compute(providerId) { _, current ->
            val state = current ?: ProviderHealth()
            val signal = classify(error)
            val nextStress = when (signal) {
                StressSignal.THROTTLED -> (state.stressLevel + 2).coerceAtMost(5)
                StressSignal.TIMEOUT,
                StressSignal.CONNECTION_RESET,
                StressSignal.CONNECTION_FAILURE -> (state.stressLevel + 1).coerceAtMost(5)
                StressSignal.NONE -> state.stressLevel
            }
            val nextTimeoutStreak = if (signal == StressSignal.TIMEOUT) state.timeoutStreak + 1 else 0
            val nextResetStreak = if (signal == StressSignal.CONNECTION_RESET) state.resetStreak + 1 else 0
            val nextConnectionFailureStreak = if (signal == StressSignal.CONNECTION_FAILURE) state.connectionFailureStreak + 1 else 0
            val extraStress = when {
                nextTimeoutStreak >= 2 || nextResetStreak >= 2 || nextConnectionFailureStreak >= 2 -> 1
                else -> 0
            }
            val finalStress = (nextStress + extraStress).coerceAtMost(5)
            val backoffMs = when {
                signal == StressSignal.THROTTLED -> 900L + (1_100L * finalStress)
                signal == StressSignal.TIMEOUT -> 350L * finalStress
                signal == StressSignal.CONNECTION_RESET || signal == StressSignal.CONNECTION_FAILURE -> 250L * finalStress
                else -> 0L
            }
            val now = nowMillis()
            val retryAfterMs = (error as? XtreamNetworkException)?.retryAfterMs ?: 0L
            val retryAfterUntil = now + retryAfterMs.coerceIn(0L, Long.MAX_VALUE - now)
            state.copy(
                stressLevel = finalStress,
                backoffUntilMs = maxOf(state.backoffUntilMs, now + backoffMs),
                retryAfterUntilMs = maxOf(state.retryAfterUntilMs, retryAfterUntil),
                successStreak = 0,
                timeoutStreak = nextTimeoutStreak,
                resetStreak = nextResetStreak,
                connectionFailureStreak = nextConnectionFailureStreak
            )
        }
    }

    fun retryDelayFor(providerId: Long, attempt: Int): Long {
        val health = providerHealth[providerId] ?: ProviderHealth()
        val base = when (attempt) {
            1 -> 150L
            2 -> 500L
            else -> 1_200L
        }
        val stressExtra = when {
            health.stressLevel >= 4 -> 1_600L
            health.stressLevel >= 3 -> 900L
            health.stressLevel >= 2 -> 450L
            health.stressLevel >= 1 -> 150L
            else -> 0L
        }
        return base + stressExtra
    }

    fun forgetProvider(providerId: Long) {
        providerHealth.remove(providerId)
        providerPacing.remove(providerId)
    }

    fun isProviderStress(error: Throwable): Boolean = classify(error) != StressSignal.NONE

    private enum class StressSignal {
        NONE,
        THROTTLED,
        TIMEOUT,
        CONNECTION_RESET,
        CONNECTION_FAILURE
    }

    private fun classify(error: Throwable): StressSignal {
        return when {
            error is XtreamAuthenticationException -> StressSignal.NONE
            error is XtreamNetworkException && error.statusCode == 429 -> StressSignal.THROTTLED
            error is XtreamRequestException && error.statusCode in setOf(403, 429) -> StressSignal.THROTTLED
            messageContains(error, "timeout") || messageContains(error, "timed out") -> StressSignal.TIMEOUT
            messageContains(error, "connection reset") || messageContains(error, "reset by peer") -> StressSignal.CONNECTION_RESET
            messageContains(error, "connection refused") ||
                messageContains(error, "failed to connect") ||
                messageContains(error, "unable to resolve host") ||
                messageContains(error, "broken pipe") -> StressSignal.CONNECTION_FAILURE
            error is XtreamNetworkException || error is IOException -> StressSignal.TIMEOUT
            else -> StressSignal.NONE
        }
    }

    private fun segmentedRequestSpacingFor(providerId: Long, stage: Stage): Long {
        if (stage != Stage.CATEGORY && stage != Stage.LIVE_CATEGORY) {
            return 0L
        }
        val health = providerHealth[providerId] ?: ProviderHealth()
        val baseSpacingMs = when (stage) {
            Stage.CATEGORY -> 250L
            Stage.LIVE_CATEGORY -> if (health.stressLevel > 0) 250L else 0L
            else -> 0L
        }
        val stressSpacingMs = when {
            health.stressLevel >= 4 -> 900L
            health.stressLevel >= 3 -> 650L
            health.stressLevel >= 2 -> 400L
            health.stressLevel >= 1 -> 200L
            else -> 0L
        }
        return baseSpacingMs + stressSpacingMs
    }

    private fun messageContains(error: Throwable, needle: String): Boolean =
        error.message.orEmpty().contains(needle, ignoreCase = true)
}
