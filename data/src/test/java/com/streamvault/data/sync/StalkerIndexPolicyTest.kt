package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerPagedResult
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import org.junit.Test

class StalkerIndexPolicyTest {
    @Test
    fun `split catalogs finish series before derived movie supplementation`() {
        val decision = StalkerIndexPolicy.chooseNextSection(
            layout = CatalogLayout.SPLIT,
            requestedSection = null,
            movie = section(ContentType.MOVIE, updatedAt = 1L),
            series = section(ContentType.SERIES, updatedAt = 100L)
        )

        assertThat(decision.contentType).isEqualTo(ContentType.SERIES)
    }

    @Test
    fun `unified catalogs choose the oldest runnable section`() {
        val decision = StalkerIndexPolicy.chooseNextSection(
            layout = CatalogLayout.UNIFIED_VOD,
            requestedSection = null,
            movie = section(ContentType.MOVIE, updatedAt = 200L),
            series = section(ContentType.SERIES, updatedAt = 100L)
        )

        assertThat(decision.contentType).isEqualTo(ContentType.SERIES)
    }

    @Test
    fun `retry delay uses the earliest eligible hydration deadline`() {
        val now = 10_000L
        val delay = StalkerIndexPolicy.nextRetryDelaySeconds(
            hydrations = listOf(
                snapshot(retryAfterMs = now + 4_001L),
                snapshot(retryAfterMs = now + 1_001L)
            ),
            now = now
        )

        assertThat(delay).isEqualTo(2L)
    }

    @Test
    fun `continuation retries the failed page and advances after success`() {
        assertThat(
            StalkerIndexPolicy.nextAttemptPage(
                snapshot(lastAttemptedPage = 7, lastSuccessfulPage = 6, lastStatus = "FAILED_RETRYABLE")
            )
        ).isEqualTo(7)
        assertThat(
            StalkerIndexPolicy.nextAttemptPage(
                snapshot(lastAttemptedPage = 7, lastSuccessfulPage = 7, lastStatus = "SUCCESS")
            )
        ).isEqualTo(8)
    }

    @Test
    fun `anomaly detection rejects wrong page and repeated payload`() {
        val wrongPage = StalkerIndexPolicy.detectPageAnomaly(
            hydration = null,
            requestedPage = 3,
            pagedResult = page(page = 2),
            pageFingerprint = null
        )
        val repeatedPage = StalkerIndexPolicy.detectPageAnomaly(
            hydration = snapshot(
                lastAttemptedPage = 3,
                lastSuccessfulPage = 2,
                totalPages = 5,
                lastStatus = "FAILED_RETRYABLE",
                lastPageFingerprint = "same"
            ),
            requestedPage = 3,
            pagedResult = page(page = 3, totalPages = 5),
            pageFingerprint = "same"
        )

        assertThat(wrongPage).contains("while page 3 was requested")
        assertThat(repeatedPage).contains("repeated the same page payload")
    }

    @Test
    fun `completed, truncated, and exhausted categories are not attemptable`() {
        val now = 100L
        assertThat(StalkerIndexPolicy.canAttempt(snapshot(isComplete = true), now)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(snapshot(lastStatus = "TRUNCATED"), now)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(snapshot(retryBudgetRemaining = 0), now)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(snapshot(retryAfterMs = now + 1), now)).isFalse()
        assertThat(StalkerIndexPolicy.canAttempt(snapshot(retryAfterMs = now), now)).isTrue()
        assertThat(StalkerIndexPolicy.canAttempt(null, now)).isTrue()
    }

    @Test
    fun `wildcard backfill targets categories without local rows`() {
        val covered = category(1L, "Action")
        val excluded = category(2L, "Adult")
        val empty = category(3L, "Empty")

        val backfill = StalkerIndexPolicy.wildcardBackfillCategories(
            normalCategories = listOf(covered, excluded, empty),
            localRowCounts = mapOf(1L to 25, 2L to 0)
        )

        assertThat(backfill.map { it.categoryId }).containsExactly(2L, 3L)
    }

    private fun category(categoryId: Long, name: String) = CategoryEntity(
        categoryId = categoryId,
        name = name,
        type = ContentType.MOVIE,
        providerId = 1L
    )

    private fun section(
        contentType: ContentType,
        updatedAt: Long,
        runnable: Boolean = true,
        pending: Boolean = true,
        retryDelaySeconds: Long = 0L
    ) = StalkerCatalogSectionState(
        contentType = contentType,
        runnable = runnable,
        retryDelaySeconds = retryDelaySeconds,
        pending = pending,
        jobState = "QUEUED",
        updatedAt = updatedAt
    )

    private fun snapshot(
        lastStatus: String = "SUCCESS",
        lastAttemptedPage: Int = 1,
        lastSuccessfulPage: Int = 1,
        totalPages: Int = 1,
        retryAfterMs: Long = 0L,
        retryBudgetRemaining: Int = 3,
        isComplete: Boolean = false,
        lastPageFingerprint: String? = null
    ) = StalkerHydrationSnapshot(
        lastHydratedAt = 0L,
        itemCount = 0,
        lastStatus = lastStatus,
        lastError = null,
        lastLoadedPage = lastSuccessfulPage,
        lastAttemptedPage = lastAttemptedPage,
        lastSuccessfulPage = lastSuccessfulPage,
        totalPages = totalPages,
        advertisedTotalItems = null,
        advertisedTotalPages = null,
        isComplete = isComplete,
        pageSize = 100,
        retryAfterMs = retryAfterMs,
        failureCount = 0,
        retryBudgetRemaining = retryBudgetRemaining,
        lastPageFingerprint = lastPageFingerprint
    )

    private fun page(page: Int, totalPages: Int = 1) = StalkerPagedResult(
        items = emptyList<String>(),
        page = page,
        totalPages = totalPages,
        pageSize = 100
    )
}
