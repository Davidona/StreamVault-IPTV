package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SyncManagerXtreamSupportTest {

    private val provider = Provider(id = 7, name = "Test Xtream", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://example.test")

    private fun failure(id: String, error: Exception): TimedCategoryOutcome<String> {
        val category = XtreamCategory(id, "Category $id")
        return TimedCategoryOutcome(category, CategoryFetchOutcome.Failure(category.categoryName, error), 0)
    }

    private val adaptiveSyncPolicy = XtreamAdaptiveSyncPolicy()

    private val support = SyncManagerXtreamSupport(
        adaptiveSyncPolicy = adaptiveSyncPolicy,
        shouldRememberSequentialPreference = { false },
        sanitizeThrowableMessage = { it?.message.orEmpty() },
        progress = { _, _, _ -> },
        movieRequestTimeoutMillis = 60_000L,
        seriesRequestTimeoutMillis = 60_000L,
        recoveryAbortWarningSuffix = "aborted"
    )

    @Test
    fun `executeXtreamRequest converts timeout cancellations into io failures`() = runTest {
        val providerId = 7L

        val failure = runCatching {
            support.executeXtreamRequest(providerId, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                withTimeout(1) {
                    delay(10)
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("35 seconds")
        assertThat(
            adaptiveSyncPolicy.concurrencyFor(
                providerId = providerId,
                workloadSize = 10,
                preferSequential = false,
                stage = XtreamAdaptiveSyncPolicy.Stage.CATEGORY
            )
        ).isEqualTo(1)
    }

    @Test
    fun `continueFailedCategoryOutcomes reports sequential retry progress`() = runTest {
        val provider = Provider(
            id = 7L,
            name = "Test Xtream",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.test"
        )
        val failedCategory = XtreamCategory(categoryId = "13", categoryName = "Sports")
        val progress = mutableListOf<String>()

        val retried = support.continueFailedCategoryOutcomes(
            provider = provider,
            timedOutcomes = listOf(
                TimedCategoryOutcome(
                    category = XtreamCategory(categoryId = "12", categoryName = "News"),
                    outcome = CategoryFetchOutcome.Success("News", emptyList()),
                    elapsedMs = 25L
                ),
                TimedCategoryOutcome(
                    category = failedCategory,
                    outcome = CategoryFetchOutcome.Failure("Sports", IOException("boom")),
                    elapsedMs = 50L
                )
            ),
            fetchSequentially = { category ->
                TimedCategoryOutcome(
                    category = category,
                    outcome = CategoryFetchOutcome.Success(category.categoryName, emptyList()),
                    elapsedMs = 30L
                )
            },
            onCategoryRetried = { completed, total, currentLabel ->
                progress += "$completed/$total:$currentLabel"
            }
        )

        assertThat(progress).containsExactly("1/1:Sports")
        assertThat(retried.last().outcome).isInstanceOf(CategoryFetchOutcome.Success::class.java)
    }

    @Test
    fun `authentication in mixed outcomes prevents all continuation requests`() = runTest {
        val original = listOf(failure("1", IOException("timeout")),
            failure("2", XtreamAuthenticationException(401, "authentication")))
        var requests = 0
        var progress = 0
        val result = support.continueFailedCategoryOutcomes(provider, original, fetchSequentially = { category ->
            requests++
            TimedCategoryOutcome(category, CategoryFetchOutcome.Empty(category.categoryName), 0)
        }, onCategoryRetried = { _, _, _ -> progress++ })
        assertThat(requests).isEqualTo(0)
        assertThat(progress).isEqualTo(0)
        assertThat(result).isSameInstanceAs(original)
    }

    @Test
    fun `authentication during continuation stops admissions and keeps completed and untouched outcomes`() = runTest {
        val completed = TimedCategoryOutcome(XtreamCategory("0", "Completed"),
            CategoryFetchOutcome.Success("Completed", listOf("retained")), 0)
        val original = listOf(completed, failure("1", IOException("timeout")),
            failure("2", IOException("timeout")), failure("3", IOException("timeout")))
        val requests = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val authentication = XtreamAuthenticationException(401, "authentication")
        val result = support.continueFailedCategoryOutcomes(provider, original, fetchSequentially = { category ->
            requests += category.categoryId
            if (category.categoryId == "1") {
                TimedCategoryOutcome(category, CategoryFetchOutcome.Success(category.categoryName, listOf("retried")), 0)
            } else failure(category.categoryId, authentication)
        }, onCategoryRetried = { current, _, _ -> progress += current })
        assertThat(requests).containsExactly("1", "2").inOrder()
        assertThat(progress).containsExactly(1, 2).inOrder()
        assertThat(result[0]).isSameInstanceAs(completed)
        assertThat(result[1].outcome).isInstanceOf(CategoryFetchOutcome.Success::class.java)
        assertThat((result[2].outcome as CategoryFetchOutcome.Failure).error).isSameInstanceAs(authentication)
        assertThat(result[3]).isSameInstanceAs(original[3])
    }
}
