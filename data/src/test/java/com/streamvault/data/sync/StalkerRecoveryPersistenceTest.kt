package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.MovieCategoryHydrationDao
import com.streamvault.data.local.dao.SeriesCategoryHydrationDao
import com.streamvault.data.local.entity.MovieCategoryHydrationEntity
import com.streamvault.data.local.entity.SeriesCategoryHydrationEntity
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerNetworkPriority
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/** Characterizes production checkpoint and policy behavior using restart-persistent DAO fakes. */
class StalkerRecoveryPersistenceTest {
    @Test
    fun `exhausted transient checkpoint restores budget and backs off without losing progress`() = runTest {
        val movies = MemoryMovieHydrationDao()
        val store = StalkerIndexCheckpointStore(movies, MemorySeriesHydrationDao())
        store.markAttemptSucceeded(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, null, 1, 1_000L,
            10, 3, 10, 30, 3, false, false, null, "page-1")
        var now = 1_000L
        repeat(3) {
            val previous = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
            store.markAttemptFailed(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, previous, 2, now,
                "HTTP 503", true, null)
            val saved = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
            now = maxOf(now, saved.retryAfterMs)
        }
        val exhausted = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(exhausted.lastStatus).isEqualTo("FAILED_BUDGET_EXHAUSTED")
        assertThat(StalkerIndexPolicy.canAttempt(exhausted, now - 1)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(exhausted, now)).isTrue()
        store.markAttemptFailed(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, exhausted, 2, now,
            "HTTP 503", true, null)
        val recovered = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(recovered.lastStatus).isEqualTo("FAILED_RETRYABLE")
        assertThat(recovered.retryBudgetRemaining).isEqualTo(2)
        assertThat(recovered.lastSuccessfulPage).isEqualTo(1)
        assertThat(recovered.lastAttemptedPage).isEqualTo(2)
        assertThat(recovered.retryAfterMs).isGreaterThan(now)
    }

    @Test
    fun `local rate limit defers category without spending retry budget`() = runTest {
        val movieDao = MemoryMovieHydrationDao()
        val seriesDao = MemorySeriesHydrationDao()
        val store = StalkerIndexCheckpointStore(movieDao, seriesDao)
        var now = 1_000L
        val coordinator = StalkerRequestCoordinator(null, true, { now }, 1)
        coordinator.recordFailure(PROVIDER_ID, StalkerApiError.RateLimited())
        val cooldownEndsAt = now + 15 * 60_000L

        repeat(3) {
            // No HTTP request is sent: the coordinator rejects admission during cooldown.
            val rejection = try {
                coordinator.acquireNetworkPermit(PROVIDER_ID, StalkerNetworkPriority.INDEX)
                error("Provider cooldown must reject admission")
            } catch (error: StalkerApiError.RateLimited) {
                error
            }
            assertThat(rejection.retryAfterMillis).isEqualTo(cooldownEndsAt - now)
            val classification = StalkerIndexRecoveryPolicy.failureState(rejection) { it?.message.orEmpty() }
            assertThat(classification).isEqualTo("FAILED_RETRYABLE")
            val previous = store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID)
            store.markAttemptFailed(
                PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, previous, 1, now,
                rejection.message.orEmpty(), classification == "FAILED_RETRYABLE", null,
                deferredUntilMs = now + requireNotNull(rejection.retryAfterMillis)
            )
            val saved = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
            assertThat(saved.retryBudgetRemaining).isEqualTo(3)
            assertThat(saved.retryAfterMs).isEqualTo(cooldownEndsAt)
            assertThat(StalkerIndexPolicy.canAttempt(saved, now)).isFalse()
        }

        val deferred = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(now).isLessThan(cooldownEndsAt)
        assertThat(deferred.lastStatus).isEqualTo("COOLDOWN")
        assertThat(deferred.retryBudgetRemaining).isEqualTo(3)

        // Recovery reopens the original category without resetting its checkpoint.
        now = cooldownEndsAt
        val probe = coordinator.acquireNetworkPermit(PROVIDER_ID, StalkerNetworkPriority.INDEX)
        assertThat(probe.halfOpenProbe).isTrue()
        coordinator.recordNetworkSuccess(probe)
        coordinator.releaseNetworkPermit(probe)
        assertThat(StalkerIndexPolicy.canAttempt(deferred, now)).isTrue()
    }

    @Test
    fun `session expiry defers and preserves the saved page across store reconstruction`() = runTest {
        val movieDao = MemoryMovieHydrationDao()
        val seriesDao = MemorySeriesHydrationDao()
        val initialStore = StalkerIndexCheckpointStore(movieDao, seriesDao)
        val failure = StalkerApiError.SessionExpired(httpStatus = 401)
        val classification = StalkerIndexRecoveryPolicy.failureState(failure) { it?.message.orEmpty() }

        initialStore.markAttemptSucceeded(
            providerId = PROVIDER_ID,
            contentType = ContentType.MOVIE,
            categoryId = CATEGORY_ID,
            hydration = null,
            attemptedPage = 1,
            now = 500L,
            itemCount = 10,
            totalPages = 3,
            pageSize = 10,
            advertisedTotalItems = 30,
            advertisedTotalPages = 3,
            pageComplete = false,
            truncated = false,
            terminationReason = null,
            pageFingerprint = "page-1"
        )

        initialStore.markAttemptFailed(
            providerId = PROVIDER_ID,
            contentType = ContentType.MOVIE,
            categoryId = CATEGORY_ID,
            hydration = initialStore.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID),
            attemptedPage = 2,
            now = 1_000L,
            message = "The portal session expired; authentication is required again.",
            retryable = classification == "FAILED_RETRYABLE",
            pageFingerprint = null,
            deferredUntilMs = 901_000L
        )

        // Recreate the store over the same DAO rows, as a process restart would.
        val restoredStore = StalkerIndexCheckpointStore(movieDao, seriesDao)
        val persisted = requireNotNull(restoredStore.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(classification).isEqualTo("FAILED_RETRYABLE")
        assertThat(persisted.lastStatus).isEqualTo("COOLDOWN")
        assertThat(persisted.lastAttemptedPage).isEqualTo(2)
        assertThat(persisted.lastSuccessfulPage).isEqualTo(1)
        assertThat(persisted.retryBudgetRemaining).isEqualTo(3)

        assertThat(StalkerIndexPolicy.canAttempt(persisted, 900_999L)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(persisted, 901_000L)).isTrue()
        assertThat(StalkerIndexPolicy.nextAttemptPage(persisted)).isEqualTo(2)
    }

    @Test
    fun `explicit device rejection remains permanent`() {
        listOf(StalkerApiError.DeviceConflict("device mismatch"), StalkerApiError.AccountBlocked("blocked"))
            .forEach { error ->
                assertThat(StalkerIndexRecoveryPolicy.failureState(error) { it?.message.orEmpty() })
                    .isEqualTo("FAILED_PERMANENT")
            }
    }

    @Test
    fun `retry budget exhaustion remains terminal after store reconstruction`() = runTest {
        val movieDao = MemoryMovieHydrationDao()
        val seriesDao = MemorySeriesHydrationDao()
        val store = StalkerIndexCheckpointStore(movieDao, seriesDao)
        store.markAttemptSucceeded(
            providerId = PROVIDER_ID,
            contentType = ContentType.MOVIE,
            categoryId = CATEGORY_ID,
            hydration = null,
            attemptedPage = 1,
            now = 1_000L,
            itemCount = 10,
            totalPages = 3,
            pageSize = 10,
            advertisedTotalItems = 30,
            advertisedTotalPages = 3,
            pageComplete = false,
            truncated = false,
            terminationReason = null,
            pageFingerprint = "page-1"
        )

        var simulatedNow = 1_000L
        repeat(3) { failureIndex ->
            val previous = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
            val failure = IOException("Temporary portal outage")
            val classification = StalkerIndexRecoveryPolicy.failureState(failure) { it?.message.orEmpty() }
            store.markAttemptFailed(
                providerId = PROVIDER_ID,
                contentType = ContentType.MOVIE,
                categoryId = CATEGORY_ID,
                hydration = previous,
                attemptedPage = 2,
                now = simulatedNow,
                message = failure.message.orEmpty(),
                retryable = classification == "FAILED_RETRYABLE",
                pageFingerprint = null
            )
            val saved = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
            if (failureIndex < 2) {
                assertThat(saved.lastStatus).isEqualTo("FAILED_RETRYABLE")
                assertThat(saved.retryBudgetRemaining).isEqualTo(2 - failureIndex)
                assertThat(StalkerIndexPolicy.canAttempt(saved, saved.retryAfterMs)).isTrue()
                if (failureIndex == 1) {
                    assertThat(saved.retryAfterMs).isEqualTo(simulatedNow + 600_000L)
                    assertThat(StalkerIndexPolicy.canAttempt(saved, saved.retryAfterMs - 1L)).isFalse()
                }
                simulatedNow = maxOf(simulatedNow, saved.retryAfterMs)
            } else {
                assertThat(saved.lastStatus).isEqualTo("FAILED_BUDGET_EXHAUSTED")
                assertThat(saved.retryBudgetRemaining).isEqualTo(0)
            }
        }

        // A new policy read after time advances still honors the exhausted checkpoint absent reset.
        val restoredStore = StalkerIndexCheckpointStore(movieDao, seriesDao)
        val persisted = requireNotNull(restoredStore.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(StalkerIndexPolicy.canAttempt(persisted, Long.MAX_VALUE)).isFalse()
        assertThat(persisted.lastSuccessfulPage).isEqualTo(1)
        assertThat(persisted.lastAttemptedPage).isEqualTo(2)
    }

    @Test
    fun `a successful resumed page resets persisted failure count and retry budget`() = runTest {
        val movieDao = MemoryMovieHydrationDao()
        val seriesDao = MemorySeriesHydrationDao()
        val store = StalkerIndexCheckpointStore(movieDao, seriesDao)
        store.markAttemptSucceeded(
            PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, null, 1, 1_000L, 10, 2, 10,
            20, 2, false, false, null, "page-1"
        )
        val beforeFailure = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        store.markAttemptFailed(
            PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, beforeFailure, 2, 2_000L,
            "Temporary portal outage", true, null
        )
        val failed = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        store.markAttemptSucceeded(
            PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID, failed, 2, 3_000L, 20, 2, 10,
            20, 2, true, false, null, "page-2"
        )

        val recovered = requireNotNull(store.getHydrationSnapshot(PROVIDER_ID, ContentType.MOVIE, CATEGORY_ID))
        assertThat(recovered.lastStatus).isEqualTo("SUCCESS")
        assertThat(recovered.failureCount).isEqualTo(0)
        assertThat(recovered.retryBudgetRemaining).isEqualTo(StalkerIndexCheckpointStore.STALKER_CATEGORY_RETRY_BUDGET)
        assertThat(recovered.lastSuccessfulPage).isEqualTo(2)
    }

    private class MemoryMovieHydrationDao : MovieCategoryHydrationDao {
        private val rows = mutableMapOf<Pair<Long, Long>, MovieCategoryHydrationEntity>()
        override suspend fun get(providerId: Long, categoryId: Long) = rows[providerId to categoryId]
        override fun observe(providerId: Long, categoryId: Long): Flow<MovieCategoryHydrationEntity?> =
            flowOf(rows[providerId to categoryId])
        override suspend fun upsert(metadata: MovieCategoryHydrationEntity) {
            rows[metadata.providerId to metadata.categoryId] = metadata
        }
        override suspend fun delete(providerId: Long, categoryId: Long) { rows.remove(providerId to categoryId) }
        override suspend fun deleteByProvider(providerId: Long) { rows.keys.removeAll { it.first == providerId } }
    }

    private class MemorySeriesHydrationDao : SeriesCategoryHydrationDao {
        private val rows = mutableMapOf<Pair<Long, Long>, SeriesCategoryHydrationEntity>()
        override suspend fun get(providerId: Long, categoryId: Long) = rows[providerId to categoryId]
        override fun observe(providerId: Long, categoryId: Long): Flow<SeriesCategoryHydrationEntity?> =
            flowOf(rows[providerId to categoryId])
        override suspend fun upsert(metadata: SeriesCategoryHydrationEntity) {
            rows[metadata.providerId to metadata.categoryId] = metadata
        }
        override suspend fun delete(providerId: Long, categoryId: Long) { rows.remove(providerId to categoryId) }
        override suspend fun deleteByProvider(providerId: Long) { rows.keys.removeAll { it.first == providerId } }
    }

    private companion object {
        const val PROVIDER_ID = 91L
        const val CATEGORY_ID = 17L
    }
}
