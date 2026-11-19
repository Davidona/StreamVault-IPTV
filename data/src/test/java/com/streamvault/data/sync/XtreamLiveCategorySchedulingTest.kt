package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamNetworkException
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class XtreamLiveCategorySchedulingTest {
    private val provider = LegacyProvider(id = 7, name = "Fixture", type = ProviderType.XTREAM_CODES, serverUrl = "https://example.test")
    private val categories = (1..6).map { XtreamCategory(categoryId = "$it", categoryName = "Category $it") }
    private fun support() = SyncManagerXtreamSupport(XtreamAdaptiveSyncPolicy(), { it is java.io.IOException },
        { it?.message.orEmpty() }, { _, _, _ -> }, 60_000, 60_000, "aborted")

    @Test
    fun `freed workers refill before the slow category finishes and preserve result order`() = runTest {
        val events = mutableListOf<String>()
        var active = 0
        var peak = 0
        val plan = support().executeCategoryRecoveryPlan(provider, categories, 2, "Live", "sequential", null,
            fetch = { category ->
                active++
                peak = maxOf(peak, active)
                events += "start${category.categoryId}"
                delay(if (category.categoryId == "1") 1000 else 10)
                events += "end${category.categoryId}"
                active--
                TimedCategoryOutcome<String>(category, CategoryFetchOutcome.Empty(category.categoryName), 10)
            }, refillWorkers = true)
        assertThat(events.indexOf("start3")).isLessThan(events.indexOf("end1"))
        assertThat(peak).isEqualTo(2)
        assertThat(plan.outcomes.map { it.category.categoryId }).containsExactly("1", "2", "3", "4", "5", "6").inOrder()
    }

    @Test
    fun `authentication failure stops admission and drains already running categories`() = runTest {
        val started = mutableListOf<String>()
        val plan = support().executeCategoryRecoveryPlan(provider, categories, 2, "Live", "sequential", null,
            fetch = { category ->
                started += category.categoryId
                delay(if (category.categoryId == "1") 1 else 20)
                TimedCategoryOutcome<String>(category,
                    CategoryFetchOutcome.Failure(category.categoryName, XtreamAuthenticationException(401, "authentication")), 1)
            }, refillWorkers = true)
        assertThat(started).containsExactly("1", "2")
        assertThat(plan.outcomes).hasSize(2)
        assertThat(plan.stopReason).isEqualTo(CategoryExecutionStopReason.AUTHENTICATION)
        var retries = 0
        support().continueFailedCategoryOutcomes(provider, plan.outcomes, { retries++; it.let { category ->
            TimedCategoryOutcome<String>(category, CategoryFetchOutcome.Empty(category.categoryName), 0)
        } })
        assertThat(retries).isEqualTo(0)
    }

    @Test
    fun `healthy live categories have no pacing floor while provider retry after survives successes`() = runTest {
        val policy = XtreamAdaptiveSyncPolicy { testScheduler.currentTime }
        repeat(3) { policy.awaitTurn(7, XtreamAdaptiveSyncPolicy.Stage.LIVE_CATEGORY) }
        assertThat(testScheduler.currentTime).isEqualTo(0L)
        policy.recordFailure(7, XtreamNetworkException("HTTP 429", statusCode = 429, retryAfterMs = 10_000))
        repeat(4) { policy.recordSuccess(7) }
        policy.awaitTurn(7, XtreamAdaptiveSyncPolicy.Stage.LIVE_CATEGORY)
        assertThat(testScheduler.currentTime).isEqualTo(10_000L)
    }

    @Test
    fun `a new adaptive policy respects saved provider deadline`() = runTest {
        val saved = mutableMapOf<Long, Long>()
        val deadlines = object : XtreamRetryDeadlines {
            override fun deadline(providerId: Long) = saved[providerId] ?: 0L
            override fun extend(providerId: Long, deadline: Long) {
                saved[providerId] = maxOf(saved[providerId] ?: 0L, deadline)
            }
            override fun clear(providerId: Long) { saved.remove(providerId) }
        }
        val policy = XtreamAdaptiveSyncPolicy(deadlines) { testScheduler.currentTime }
        policy.recordFailure(7, XtreamNetworkException("HTTP 429", statusCode = 429, retryAfterMs = 600_000))
        val reconstructed = XtreamAdaptiveSyncPolicy(deadlines) { testScheduler.currentTime }
        reconstructed.awaitTurn(8, XtreamAdaptiveSyncPolicy.Stage.HEAVY)
        assertThat(testScheduler.currentTime).isEqualTo(0L)
        reconstructed.awaitTurn(7, XtreamAdaptiveSyncPolicy.Stage.HEAVY)
        assertThat(testScheduler.currentTime).isEqualTo(600_000L)
        reconstructed.forgetProvider(7)
        assertThat(saved).isEmpty()
    }

    @Test
    fun `retry after extension while waiting delays the next request to the new deadline`() = runTest {
        val policy = XtreamAdaptiveSyncPolicy { testScheduler.currentTime }
        policy.recordFailure(7, XtreamNetworkException("HTTP 429", statusCode = 429, retryAfterMs = 10_000))
        val waiting = async { policy.awaitTurn(7, XtreamAdaptiveSyncPolicy.Stage.LIVE_CATEGORY) }
        delay(1000)
        policy.recordFailure(7, XtreamNetworkException("HTTP 429", statusCode = 429, retryAfterMs = 20_000))
        repeat(6) { policy.recordSuccess(7) }
        waiting.await()
        assertThat(testScheduler.currentTime).isEqualTo(21_000L)
    }

    @Test
    fun `repeated stress drains active requests and stops before fetching every category`() = runTest {
        var started = 0
        val plan = support().executeCategoryRecoveryPlan(provider, categories, 2, "Live", "sequential", null,
            fetch = { category ->
                started++
                delay(10)
                TimedCategoryOutcome<String>(category, CategoryFetchOutcome.Failure(category.categoryName, java.io.IOException("timeout")), 10)
            }, refillWorkers = true)
        assertThat(started).isLessThan(categories.size)
        assertThat(plan.outcomes).hasSize(started)
        assertThat(plan.warnings).containsExactly("sequential", "Live aborted")
        assertThat(plan.stopReason).isEqualTo(CategoryExecutionStopReason.PROVIDER_STRESS)
        val beforeContinuation = started
        support().continueFailedCategoryOutcomes(provider, plan.outcomes, fetchSequentially = { category ->
            started++
            TimedCategoryOutcome(category, CategoryFetchOutcome.Empty(category.categoryName), 0)
        }, stopReason = plan.stopReason)
        assertThat(started).isEqualTo(beforeContinuation)
    }

    @Test
    fun `stress on the final category remains a terminal continuation stop`() = runTest {
        val plan = support().executeCategoryRecoveryPlan(provider, categories.take(4), 2,
            "Live", "sequential", null, fetch = { category ->
                TimedCategoryOutcome<String>(category,
                    CategoryFetchOutcome.Failure(category.categoryName, java.io.IOException("timeout")), 0)
            }, refillWorkers = true)
        assertThat(plan.outcomes).hasSize(4)
        assertThat(plan.stopReason).isEqualTo(CategoryExecutionStopReason.PROVIDER_STRESS)
    }

    @Test
    fun `worker exception cancels and joins its sibling`() = runTest {
        var siblingJoined = false
        val failure = runCatching {
            support().executeCategoryRecoveryPlan<String>(provider, categories, 2, "Live", "sequential", null,
                fetch = { category ->
                    if (category.categoryId == "1") { delay(10); error("writer failure") }
                    try { awaitCancellation() } finally { siblingJoined = true }
                }, refillWorkers = true)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(siblingJoined).isTrue()
    }
}
