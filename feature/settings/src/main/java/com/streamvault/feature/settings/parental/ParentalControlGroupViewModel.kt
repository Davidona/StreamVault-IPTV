package com.streamvault.feature.settings.parental

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryControlItem(
    val category: Category,
    val isProtected: Boolean,
    val isHidden: Boolean,
    val isInitiallyProtected: Boolean
) {
    val key: String = categoryControlKey(category)
}

data class ParentalControlGroupUiState(
    val providerId: Long = -1L,
    val categories: List<CategoryControlItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val hasParentalPin: Boolean = false,
    val hasPendingProtectionChanges: Boolean = false,
    val pendingProtectionChangeCount: Int = 0,
    val hiddenCategoryCount: Int = 0,
    val visibleCategoryCount: Int = 0,
    val userMessage: String? = null
)

@HiltViewModel
class ParentalControlGroupViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val preferencesRepository: PreferencesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val providerId: Long = checkNotNull(savedStateHandle["providerId"])

    private val _searchQuery = MutableStateFlow("")
    private val _pendingProtection = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _userMessage = MutableStateFlow<String?>(null)

    private val hiddenCategoriesByType = combine(
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.MOVIE),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.SERIES),
        preferencesRepository.getHiddenCategoryIds(providerId, ContentType.VOD)
    ) { hiddenLive: Set<Long>, hiddenMovies: Set<Long>, hiddenSeries: Set<Long>, hiddenVod: Set<Long> ->
        mapOf(
            ContentType.LIVE to hiddenLive,
            ContentType.MOVIE to hiddenMovies,
            ContentType.SERIES to hiddenSeries,
            ContentType.VOD to hiddenVod
        )
    }

    val uiState: StateFlow<ParentalControlGroupUiState> = categoryRepository.getCategories(providerId)
        .combine(hiddenCategoriesByType) { categories, hiddenByType ->
            categories to hiddenByType
        }
        .combine(preferencesRepository.hasParentalPin) { (categories, hiddenByType), hasParentalPin ->
            Triple(categories, hiddenByType, hasParentalPin)
        }
        .combine(_searchQuery) { (categories, hiddenByType, hasParentalPin), query ->
            CategoryUiInputs(
                categories = categories,
                hiddenByType = hiddenByType,
                hasParentalPin = hasParentalPin,
                query = query,
                pendingProtection = _pendingProtection.value,
                userMessage = _userMessage.value
            )
        }
        .combine(_pendingProtection) { inputs, pendingProtection ->
            inputs.copy(pendingProtection = pendingProtection)
        }
        .combine(_userMessage) { inputs, userMessage ->
            inputs.copy(userMessage = userMessage)
        }
        .combine(MutableStateFlow(false)) { inputs, isLoading ->
            buildUiState(
                providerId = providerId,
                inputs = inputs,
                isLoading = isLoading
            )
        }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ParentalControlGroupUiState(providerId = providerId, isLoading = true)
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleCategoryProtection(category: Category) {
        if (category.isAdult) return
        val key = categoryControlKey(category)
        val original = category.isUserProtected
        val current = _pendingProtection.value[key] ?: original
        val updated = !current
        _pendingProtection.update { pending ->
            if (updated == original) pending - key else pending + (key to updated)
        }
    }

    fun resetProtectionChanges() {
        _pendingProtection.value = emptyMap()
    }

    fun toggleCategoryHidden(item: CategoryControlItem) {
        viewModelScope.launch {
            preferencesRepository.setCategoryHidden(
                providerId = providerId,
                type = item.category.type,
                categoryId = item.category.id,
                hidden = !item.isHidden
            )
            _userMessage.value = if (item.isHidden) {
                "Restored ${item.category.name}"
            } else {
                "Hidden ${item.category.name}"
            }
        }
    }

    fun hideAllCategories(type: ContentType) {
        viewModelScope.launch {
            val categoryIds = categoryRepository.getCategories(providerId)
                .first()
                .asSequence()
                .filter { it.type == type }
                .map(Category::id)
                .toSet()
            preferencesRepository.setHiddenCategoryIds(
                providerId = providerId,
                type = type,
                categoryIds = categoryIds
            )
            _userMessage.value = "${contentTypeMessageLabel(type)} categories hidden."
        }
    }

    fun unhideAllCategories(type: ContentType) {
        viewModelScope.launch {
            preferencesRepository.setHiddenCategoryIds(
                providerId = providerId,
                type = type,
                categoryIds = emptySet()
            )
            _userMessage.value = "${contentTypeMessageLabel(type)} categories restored."
        }
    }

    suspend fun verifyPin(pin: String): Boolean = preferencesRepository.verifyParentalPin(pin)

    fun setParentalPin(pin: String) {
        viewModelScope.launch {
            preferencesRepository.setParentalPin(pin)
            _userMessage.value = "Parental PIN saved."
        }
    }

    fun saveProtectionChanges() {
        val currentItems = uiState.value.categories
        if (_pendingProtection.value.isEmpty()) return
        viewModelScope.launch {
            var failed = false
            currentItems
                .filter { item ->
                    !item.category.isAdult && item.isProtected != item.category.isUserProtected
                }
                .forEach { item ->
                    when (
                        categoryRepository.setCategoryProtection(
                            providerId = providerId,
                            categoryId = item.category.id,
                            type = item.category.type,
                            isProtected = item.isProtected
                        )
                    ) {
                        is Result.Success -> Unit
                        is Result.Error -> failed = true
                        Result.Loading -> Unit
                    }
                }
            if (failed) {
                _userMessage.value = "Some category lock changes could not be saved."
            } else {
                _pendingProtection.value = emptyMap()
                _userMessage.value = "Category lock changes saved."
            }
        }
    }

    fun userMessageShown() {
        _userMessage.value = null
    }
}

private fun categoryControlKey(category: Category): String = "${category.type.name}:${category.id}"

private fun contentTypeMessageLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> "Live TV"
    ContentType.MOVIE -> "Movie"
    ContentType.VOD -> "VOD"
    ContentType.SERIES,
    ContentType.SERIES_EPISODE -> "Series"
}

private data class CategoryUiInputs(
    val categories: List<Category>,
    val hiddenByType: Map<ContentType, Set<Long>>,
    val hasParentalPin: Boolean,
    val query: String,
    val pendingProtection: Map<String, Boolean>,
    val userMessage: String?
)

private fun buildUiState(
    providerId: Long,
    inputs: CategoryUiInputs,
    isLoading: Boolean
): ParentalControlGroupUiState {
    val items = inputs.categories.map { category ->
        val isInitiallyProtected = category.isAdult || category.isUserProtected
        val isProtected = if (category.isAdult) {
            true
        } else {
            inputs.pendingProtection[categoryControlKey(category)] ?: category.isUserProtected
        }
        CategoryControlItem(
            category = category,
            isProtected = isProtected,
            isHidden = category.id in inputs.hiddenByType[category.type].orEmpty(),
            isInitiallyProtected = isInitiallyProtected
        )
    }
    return ParentalControlGroupUiState(
        providerId = providerId,
        categories = items,
        searchQuery = inputs.query,
        isLoading = isLoading,
        hasParentalPin = inputs.hasParentalPin,
        hasPendingProtectionChanges = inputs.pendingProtection.isNotEmpty(),
        pendingProtectionChangeCount = inputs.pendingProtection.size,
        hiddenCategoryCount = inputs.categories.count { category ->
            category.id in inputs.hiddenByType[category.type].orEmpty()
        },
        visibleCategoryCount = inputs.categories.count { category ->
            category.id !in inputs.hiddenByType[category.type].orEmpty()
        },
        userMessage = inputs.userMessage
    )
}
