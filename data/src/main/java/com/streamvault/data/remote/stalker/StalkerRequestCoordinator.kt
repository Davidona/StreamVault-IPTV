package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.StalkerRequestPriority
import com.streamvault.domain.model.Result
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class StalkerRequestSnapshot(
    val active: Int,
    val queued: Int,
    val concurrencyLimit: Int,
    val stressCooldownUntil: Long
)

class StalkerNetworkPermit internal constructor(
    internal val providerId: Long,
    internal val halfOpenProbe: Boolean,
    internal val priority: StalkerNetworkPriority
)

enum class StalkerNetworkPriority { INTERACTIVE, FOREGROUND, PREFETCH, BACKGROUND }

internal class StalkerRequestPriorityContext(
    val priority: StalkerRequestPriority
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StalkerRequestPriorityContext>
}

data class StalkerRequestDescriptor(
    val contentType: String,
    val action: String,
    val categoryKey: String? = null,
    val itemKey: String? = null,
    val page: Int? = null,
    val workId: String? = null
) {
    internal fun dedupeKey(): String = listOf(
        contentType,
        action,
        categoryKey.orEmpty(),
        itemKey.orEmpty(),
        page?.toString().orEmpty()
    ).joinToString("\u001f")
}

data class StalkerResponseMetrics(
    val items: Int? = null,
    val pages: Int? = null,
    val advertisedTotal: Int? = null,
    val truncated: Boolean? = null,
    val terminationReason: String? = null
)

/** Provider-scoped priority, deduplication, and adaptive metadata admission. */
@Singleton
class StalkerRequestCoordinator @Inject constructor(
    private val portalStateStore: StalkerPortalStateStore?
) {
    /** Isolated constructor for pure unit tests; production uses the injected state store. */
    constructor() : this(null)

    private data class Waiter(
        val ticket: Long,
        val priority: StalkerRequestPriority
    )

    private class ProviderState {
        val mutex = Mutex()
        val waiters = mutableListOf<Waiter>()
        val inFlight = mutableMapOf<String, CompletableDeferred<Any?>>()
        var active = 0
        var activeBackground = 0
        var safeMetadataConcurrency = NORMAL_METADATA_CONCURRENCY
        var stressCooldownUntil = 0L
        var rateLimitCooldownUntil = 0L
        var rateLimitStrikes = 0
        var halfOpenProbeInFlight = false
        var networkTokens = NETWORK_TOKEN_CAPACITY
        var lastNetworkRefillAt = System.currentTimeMillis()
        var nextBackgroundRequestAt = 0L
        var interactiveWaiters = 0
        var interactiveInFlight = 0
        var persistedStateLoaded = false
    }

    private val states = ConcurrentHashMap<Long, ProviderState>()
    private val tickets = AtomicLong()

    /** Drops idle/adaptive state after the owning provider has been deleted. */
    fun forgetProvider(providerId: Long) {
        if (providerId > 0L) states.remove(providerId)
    }

    suspend fun <T> execute(
        providerId: Long,
        priority: StalkerRequestPriority,
        descriptor: StalkerRequestDescriptor,
        metricsOf: (T) -> StalkerResponseMetrics = { StalkerResponseMetrics() },
        block: suspend () -> T
    ): T {
        require(providerId > 0L)
        val state = states.computeIfAbsent(providerId) { ProviderState() }
        hydratePersistedState(providerId, state)
        val requestKey = descriptor.dedupeKey()
        val deferred = CompletableDeferred<Any?>()
        var owner = false
        val shared = state.mutex.withLock {
            state.inFlight[requestKey]?.also { return@withLock it }
            state.inFlight[requestKey] = deferred
            owner = true
            deferred
        }
        if (!owner) {
            @Suppress("UNCHECKED_CAST")
            return shared.await() as T
        }

        val waiter = Waiter(tickets.incrementAndGet(), priority)
        var acquired = false
        var responseMetrics = StalkerResponseMetrics()
        val startedAt = System.currentTimeMillis()
        var outcome = "success"
        try {
            state.mutex.withLock { state.waiters += waiter }
            while (!acquired) {
                acquired = state.mutex.withLock {
                    val now = System.currentTimeMillis()
                    val limit = state.currentLimit(now)
                    val first = state.waiters.minWithOrNull(
                        compareBy<Waiter>({ it.priority.ordinal }, { it.ticket })
                    )
                    val backgroundAllowed = priority != StalkerRequestPriority.BACKGROUND_INDEX ||
                        state.activeBackground == 0
                    if (first == waiter && state.active < limit && backgroundAllowed) {
                        state.waiters.remove(waiter)
                        state.active += 1
                        if (priority == StalkerRequestPriority.BACKGROUND_INDEX) state.activeBackground += 1
                        true
                    } else {
                        false
                    }
                }
                if (!acquired) delay(ADMISSION_POLL_MILLIS)
            }
            val result = withContext(StalkerRequestPriorityContext(priority)) { block() }
            if (result is Result.Error) {
                // Catalog loaders return domain Result values rather than throwing. Keep the
                // coordinator telemetry honest so an authentication/catalog failure cannot look
                // like a successful page with zero items.
                outcome = "error_${result.exception?.telemetryOutcome() ?: "unknown"}"
            }
            responseMetrics = metricsOf(result)
            val probeNow = System.currentTimeMillis()
            val restored = state.mutex.withLock {
                if (state.safeMetadataConcurrency == 1 && state.stressCooldownUntil in 1..probeNow) {
                    state.safeMetadataConcurrency = NORMAL_METADATA_CONCURRENCY
                    state.stressCooldownUntil = 0L
                    true
                } else {
                    false
                }
            }
            if (restored) portalStateStore?.recordHealthyMetadataProbe(providerId, probeNow)
            deferred.complete(result)
            return result
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            deferred.cancel(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            outcome = error.telemetryOutcome()
            if (error.findRateLimit() != null) {
                recordRateLimit(providerId, error.findRateLimit()?.retryAfterMillis)
            } else if (error.isProviderStressSignal()) {
                val cooldownUntil = System.currentTimeMillis() + STRESS_COOLDOWN_MILLIS
                state.mutex.withLock {
                    state.safeMetadataConcurrency = 1
                    state.stressCooldownUntil = cooldownUntil
                }
                portalStateStore?.recordStressCooldown(providerId, cooldownUntil)
            }
            deferred.completeExceptionally(error)
            throw error
        } finally {
            val finalSnapshot = state.mutex.withLock {
                state.waiters.remove(waiter)
                if (acquired) {
                    state.active = (state.active - 1).coerceAtLeast(0)
                    if (priority == StalkerRequestPriority.BACKGROUND_INDEX) {
                        state.activeBackground = (state.activeBackground - 1).coerceAtLeast(0)
                    }
                }
                if (state.inFlight[requestKey] === deferred) state.inFlight.remove(requestKey)
                val limit = state.currentLimit(System.currentTimeMillis())
                StalkerRequestSnapshot(state.active, state.waiters.size, limit, state.stressCooldownUntil)
            }
            StalkerTelemetry.requestCompleted(
                providerId = providerId,
                priority = priority,
                descriptor = descriptor,
                responseMetrics = responseMetrics,
                durationMillis = System.currentTimeMillis() - startedAt,
                active = finalSnapshot.active,
                queued = finalSnapshot.queued,
                concurrencyLimit = finalSnapshot.concurrencyLimit,
                stressCooldownUntil = finalSnapshot.stressCooldownUntil,
                outcome = outcome
            )
        }
    }

    suspend fun snapshot(providerId: Long): StalkerRequestSnapshot {
        val state = states[providerId] ?: return StalkerRequestSnapshot(0, 0, 2, 0L)
        return state.mutex.withLock {
            val limit = state.currentLimit(System.currentTimeMillis())
            StalkerRequestSnapshot(state.active, state.waiters.size, limit, state.stressCooldownUntil)
        }
    }

    /** Returns the currently admitted metadata limit after loading persisted portal health. */
    suspend fun effectiveConcurrencyLimit(providerId: Long): Int {
        if (providerId <= 0L) return NORMAL_METADATA_CONCURRENCY
        val state = states.computeIfAbsent(providerId) { ProviderState() }
        hydratePersistedState(providerId, state)
        return state.mutex.withLock { state.currentLimit(System.currentTimeMillis()) }
    }

    suspend fun recordFailure(providerId: Long, error: Throwable?) {
        if (providerId <= 0L) return
        error?.findRateLimit()?.let { rateLimit ->
            recordRateLimit(providerId, rateLimit.retryAfterMillis)
            return
        }
        if (error?.isProviderStressSignal() != true) return
        val state = states.computeIfAbsent(providerId) { ProviderState() }
        hydratePersistedState(providerId, state)
        val cooldownUntil = System.currentTimeMillis() + STRESS_COOLDOWN_MILLIS
        state.mutex.withLock {
            state.safeMetadataConcurrency = 1
            state.stressCooldownUntil = cooldownUntil
        }
        portalStateStore?.recordStressCooldown(providerId, cooldownUntil)
    }

    /**
     * Gates every Stalker HTTP call, including authentication and playback. After a 429,
     * calls fail locally until the provider-wide cooldown expires. Exactly one half-open
     * request is then allowed to verify recovery.
     */
    suspend fun acquireNetworkPermit(
        providerId: Long,
        priority: StalkerNetworkPriority = StalkerNetworkPriority.FOREGROUND
    ): StalkerNetworkPermit {
        if (providerId <= 0L) return StalkerNetworkPermit(providerId, halfOpenProbe = false, priority)
        val state = states.computeIfAbsent(providerId) { ProviderState() }
        hydratePersistedState(providerId, state)
        val interactive = priority == StalkerNetworkPriority.INTERACTIVE
        if (interactive) state.mutex.withLock { state.interactiveWaiters += 1 }
        try {
            while (true) {
                var waitMillis = NETWORK_ADMISSION_POLL_MILLIS
                val permit = state.mutex.withLock {
                    val now = System.currentTimeMillis()
                    if (state.rateLimitCooldownUntil > now) {
                        throw StalkerApiError.RateLimited(
                            retryAfterMillis = state.rateLimitCooldownUntil - now
                        )
                    }
                    if (state.rateLimitCooldownUntil > 0L && state.halfOpenProbeInFlight) {
                        throw StalkerApiError.RateLimited(retryAfterMillis = HALF_OPEN_POLL_MILLIS)
                    }

                    state.refillNetworkTokens(now)
                    val lowPriority = priority == StalkerNetworkPriority.PREFETCH ||
                        priority == StalkerNetworkPriority.BACKGROUND
                    val backgroundBlocked = lowPriority &&
                        (state.interactiveWaiters > 0 || state.interactiveInFlight > 0)
                    val backgroundWait = if (lowPriority) {
                        (state.nextBackgroundRequestAt - now).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    val canBorrow = interactive && state.networkTokens > INTERACTIVE_TOKEN_FLOOR
                    val tokenWait = if (state.networkTokens >= 1.0 || canBorrow) {
                        0L
                    } else {
                        (((1.0 - state.networkTokens) * NETWORK_TOKEN_REFILL_MILLIS).toLong())
                            .coerceAtLeast(1L)
                    }
                    waitMillis = maxOf(
                        NETWORK_ADMISSION_POLL_MILLIS.takeIf { backgroundBlocked } ?: 0L,
                        backgroundWait,
                        tokenWait
                    )
                    if (waitMillis > 0L) return@withLock null

                    state.networkTokens -= 1.0
                    if (lowPriority) {
                        val interval = if (priority == StalkerNetworkPriority.BACKGROUND) {
                            BACKGROUND_REQUEST_INTERVAL_MILLIS
                        } else {
                            PREFETCH_REQUEST_INTERVAL_MILLIS
                        }
                        state.nextBackgroundRequestAt = now + interval
                    }
                    val halfOpen = state.rateLimitCooldownUntil > 0L
                    if (halfOpen) state.halfOpenProbeInFlight = true
                    if (interactive) state.interactiveInFlight += 1
                    StalkerNetworkPermit(providerId, halfOpen, priority)
                }
                if (permit != null) return permit
                delay(waitMillis.coerceAtMost(MAX_NETWORK_ADMISSION_SLEEP_MILLIS))
            }
        } finally {
            if (interactive) {
                state.mutex.withLock {
                    state.interactiveWaiters = (state.interactiveWaiters - 1).coerceAtLeast(0)
                }
            }
        }
    }

    suspend fun releaseNetworkPermit(permit: StalkerNetworkPermit) {
        if (permit.providerId <= 0L || permit.priority != StalkerNetworkPriority.INTERACTIVE) return
        val state = states[permit.providerId] ?: return
        state.mutex.withLock {
            state.interactiveInFlight = (state.interactiveInFlight - 1).coerceAtLeast(0)
        }
    }

    suspend fun recordNetworkSuccess(permit: StalkerNetworkPermit) {
        if (permit.providerId <= 0L || !permit.halfOpenProbe) return
        val state = states[permit.providerId] ?: return
        state.mutex.withLock {
            state.rateLimitCooldownUntil = 0L
            state.rateLimitStrikes = 0
            state.halfOpenProbeInFlight = false
        }
        portalStateStore?.clearRateLimitCooldown(permit.providerId)
    }

    suspend fun recordNetworkFailure(permit: StalkerNetworkPermit, error: Throwable) {
        if (permit.providerId <= 0L) return
        val rateLimit = error.findRateLimit()
        if (rateLimit != null) {
            recordRateLimit(permit.providerId, rateLimit.retryAfterMillis)
            return
        }
        if (!permit.halfOpenProbe) return
        val state = states[permit.providerId] ?: return
        val retryAt = System.currentTimeMillis() + HALF_OPEN_FAILURE_COOLDOWN_MILLIS
        state.mutex.withLock {
            state.rateLimitCooldownUntil = retryAt
            state.halfOpenProbeInFlight = false
        }
        portalStateStore?.recordRateLimitCooldown(permit.providerId, retryAt)
    }

    private suspend fun recordRateLimit(providerId: Long, retryAfterMillis: Long?) {
        if (providerId <= 0L) return
        val state = states.computeIfAbsent(providerId) { ProviderState() }
        hydratePersistedState(providerId, state)
        val now = System.currentTimeMillis()
        val cooldownUntil = state.mutex.withLock {
            if (state.rateLimitCooldownUntil > now) {
                val explicitUntil = retryAfterMillis
                    ?.takeIf { it > 0L }
                    ?.let { now + it.coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MILLIS) }
                    ?: state.rateLimitCooldownUntil
                state.rateLimitCooldownUntil = maxOf(state.rateLimitCooldownUntil, explicitUntil)
                state.halfOpenProbeInFlight = false
                return@withLock state.rateLimitCooldownUntil
            }
            state.rateLimitStrikes = (state.rateLimitStrikes + 1).coerceAtMost(MAX_RATE_LIMIT_STRIKES)
            val exponential = DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS shl (state.rateLimitStrikes - 1)
            val requested = retryAfterMillis?.takeIf { it > 0L } ?: exponential
            val until = now + requested.coerceIn(
                MIN_RATE_LIMIT_COOLDOWN_MILLIS,
                MAX_RATE_LIMIT_COOLDOWN_MILLIS
            )
            state.rateLimitCooldownUntil = maxOf(state.rateLimitCooldownUntil, until)
            state.halfOpenProbeInFlight = false
            state.rateLimitCooldownUntil
        }
        portalStateStore?.recordRateLimitCooldown(providerId, cooldownUntil, now)
    }

    private suspend fun hydratePersistedState(providerId: Long, state: ProviderState) {
        if (state.mutex.withLock { state.persistedStateLoaded }) return
        val persisted = portalStateStore?.get(providerId)
        state.mutex.withLock {
            if (!state.persistedStateLoaded) {
                state.safeMetadataConcurrency = persisted?.safeMetadataConcurrency
                    ?.coerceIn(1, NORMAL_METADATA_CONCURRENCY)
                    ?: NORMAL_METADATA_CONCURRENCY
                state.stressCooldownUntil = persisted?.stressCooldownUntil ?: 0L
                state.rateLimitCooldownUntil = persisted
                    ?.let { portalStateStore?.rateLimitCooldownUntil(it) }
                    ?: 0L
                state.persistedStateLoaded = true
            }
        }
    }

    private fun ProviderState.currentLimit(now: Long): Int =
        if (safeMetadataConcurrency <= 1 || stressCooldownUntil > now) 1 else NORMAL_METADATA_CONCURRENCY

    private fun ProviderState.refillNetworkTokens(now: Long) {
        val elapsed = (now - lastNetworkRefillAt).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        networkTokens = minOf(
            NETWORK_TOKEN_CAPACITY,
            networkTokens + elapsed.toDouble() / NETWORK_TOKEN_REFILL_MILLIS.toDouble()
        )
        lastNetworkRefillAt = now
    }

    private fun Throwable.isProviderStressSignal(): Boolean = when (this) {
        is StalkerApiError.RateLimited -> true
        is StalkerApiError.Server -> httpStatus == 503
        is StalkerApiError.Transport -> cause is SocketTimeoutException ||
            message.orEmpty().contains("timeout", ignoreCase = true) ||
            message.orEmpty().contains("reset", ignoreCase = true)
        is SocketTimeoutException -> true
        else -> cause?.isProviderStressSignal() == true
    }

    private fun Throwable.findRateLimit(): StalkerApiError.RateLimited? =
        generateSequence(this) { it.cause }
            .filterIsInstance<StalkerApiError.RateLimited>()
            .firstOrNull()

    private fun Throwable.telemetryOutcome(): String = when (this) {
        is StalkerApiError.Authorization -> "authorization"
        is StalkerApiError.RateLimited -> "rate_limited"
        is StalkerApiError.Server -> "server"
        is StalkerApiError.Transport -> "transport"
        is StalkerApiError.TransportConsentRequired -> "transport_consent"
        is StalkerApiError.Malformed -> "malformed"
        is StalkerApiError.EmptyBody -> "empty_body"
        is StalkerApiError.ResponseTooLarge -> "oversized"
        is StalkerApiError.ContentUnavailable -> "unavailable"
        is StalkerApiError.UnsupportedProtocol -> "unsupported"
        is StalkerApiError.CatalogTruncated -> "truncated"
        is StalkerApiError.DiscoveryBudgetExceeded -> "budget"
        is StalkerApiError.BlockedOrConfiguration -> "blocked_configuration"
        else -> "failed"
    }

    private companion object {
        const val NORMAL_METADATA_CONCURRENCY = 2
        const val ADMISSION_POLL_MILLIS = 20L
        const val STRESS_COOLDOWN_MILLIS = 5L * 60L * 1000L
        const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 15L * 60L * 1000L
        const val MIN_RATE_LIMIT_COOLDOWN_MILLIS = 60L * 1000L
        const val MAX_RATE_LIMIT_COOLDOWN_MILLIS = 2L * 60L * 60L * 1000L
        const val HALF_OPEN_FAILURE_COOLDOWN_MILLIS = 5L * 60L * 1000L
        const val HALF_OPEN_POLL_MILLIS = 1_000L
        const val MAX_RATE_LIMIT_STRIKES = 4
        const val NETWORK_TOKEN_CAPACITY = 8.0
        const val NETWORK_TOKEN_REFILL_MILLIS = 1_000L
        const val INTERACTIVE_TOKEN_FLOOR = -2.0
        const val BACKGROUND_REQUEST_INTERVAL_MILLIS = 5_000L
        const val PREFETCH_REQUEST_INTERVAL_MILLIS = 500L
        const val NETWORK_ADMISSION_POLL_MILLIS = 100L
        const val MAX_NETWORK_ADMISSION_SLEEP_MILLIS = 1_000L
    }
}
