package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPreferencesContractTest {
    @Test
    fun `update actions accept preferences contract and persist failed outcome`() = runTest {
        val fake = FakeSettingsPreferences()
        val preferences: SettingsPreferences = fake
        val updatePort = mock<SettingsAppUpdatePort>()
        whenever(updatePort.fetchLatestRelease()).thenReturn(Result.error("HTTP 503"))
        val state = MutableStateFlow(SettingsUiState())
        val actions = SettingsAppUpdateActions(mock(), preferences, updatePort, state)

        actions.checkForAppUpdates(
            scope = backgroundScope,
            manual = false,
            isRemoteVersionNewer = { _, _, _ -> false }
        )
        testScheduler.runCurrent()

        assertThat(state.value.appUpdate.errorMessage).isEqualTo("HTTP 503")
        assertThat(state.value.isCheckingForUpdates).isFalse()
        assertThat(fake.outcomes).containsExactly("ATTEMPTED", "FAILURE: HTTP 503").inOrder()
        assertThat(fake.attemptTimestamp).isNotNull()
        assertThat(fake.failureTimestamp).isEqualTo(fake.attemptTimestamp)
    }

    @Test
    fun `category observer accepts preferences contract and filters hidden categories`() = runTest {
        val preferences: SettingsPreferences = FakeSettingsPreferences()
        val categories = mock<CategoryRepository>()
        val hidden = Category(id = 11L, name = "Hidden", type = ContentType.LIVE)
        whenever(categories.getCategories(7L)).thenReturn(
            flowOf(listOf(hidden, Category(id = 12L, name = "Visible")))
        )

        val snapshot = observeCategoryManagement(flowOf(7L), preferences, categories).first()

        assertThat(snapshot.hiddenCategories).containsExactly(hidden)
        assertThat(snapshot.categorySortModes).containsExactly(
            ContentType.LIVE, CategorySortMode.DEFAULT,
            ContentType.MOVIE, CategorySortMode.DEFAULT,
            ContentType.SERIES, CategorySortMode.DEFAULT
        )
    }

    // Only the exercised preferences need state; unused contract members delegate to a mock.
    private class FakeSettingsPreferences : SettingsPreferences by mock<SettingsPreferences>() {
        var attemptTimestamp: Long? = null
        var failureTimestamp: Long? = null
        val outcomes = mutableListOf<String?>()

        override suspend fun setLastAppUpdateAttemptTimestamp(timestampMs: Long?) {
            attemptTimestamp = timestampMs
        }

        override suspend fun setLastAppUpdateFailureTimestamp(timestampMs: Long?) {
            failureTimestamp = timestampMs
        }

        override suspend fun setLastAppUpdateOutcome(outcome: String?) {
            outcomes += outcome
        }

        override fun getCategorySortMode(providerId: Long, type: ContentType): Flow<CategorySortMode> =
            flowOf(CategorySortMode.DEFAULT)

        override fun getHiddenCategoryIds(providerId: Long, type: ContentType): Flow<Set<Long>> =
            flowOf(if (providerId == 7L && type == ContentType.LIVE) setOf(11L) else emptySet())
    }
}
