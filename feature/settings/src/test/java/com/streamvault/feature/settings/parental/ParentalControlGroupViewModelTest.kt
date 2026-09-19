package com.streamvault.feature.settings.parental

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.CategoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ParentalControlGroupViewModelTest {

    private val categoryRepository: CategoryRepository = mock()
    private val preferencesRepository: SettingsPreferences = mock()

    @Test
    fun adultCategoryRemainsProtectedWhenProtectionIsToggled() = runTest {
        val adult = Category(
            id = 9L,
            name = "Adult",
            type = ContentType.VOD,
            isAdult = true,
            isUserProtected = true
        )
        whenever(categoryRepository.getCategories(7L)).thenReturn(flowOf(listOf(adult)))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.LIVE))
            .thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.MOVIE))
            .thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.SERIES))
            .thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenCategoryIds(7L, ContentType.VOD))
            .thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.hasParentalPin).thenReturn(flowOf(true))

        val viewModel = ParentalControlGroupViewModel(
            categoryRepository = categoryRepository,
            preferencesRepository = preferencesRepository,
            savedStateHandle = SavedStateHandle(mapOf("providerId" to 7L))
        )

        val initial = viewModel.uiState.first { !it.isLoading }
        viewModel.toggleCategoryProtection(adult)
        val afterToggle = viewModel.uiState.first { !it.isLoading }

        assertThat(initial.categories.single().isProtected).isTrue()
        assertThat(afterToggle.categories.single().isProtected).isTrue()
        assertThat(afterToggle.hasPendingProtectionChanges).isFalse()
    }
}
