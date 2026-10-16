package com.streamvault.feature.stremio.presentation

import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.util.AdultContentNameClassifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

data class StremioCatalogParentalState(
    val isReady: Boolean = false,
    val providerId: Long? = null,
    val categories: List<Category> = emptyList(),
    val hiddenMovieCategoryIds: Set<Long> = emptySet(),
    val hiddenSeriesCategoryIds: Set<Long> = emptySet(),
    val hiddenVodCategoryIds: Set<Long> = emptySet(),
    val parentalControlLevel: Int = 1,
    val unlockedCategoryIds: Set<Long> = emptySet()
) {
    fun accessFor(catalog: StremioCatalogRef): StremioCatalogCategoryAccess =
        accessFor(catalog.type, listOfNotNull(catalog.name, catalog.id))

    fun accessForGenre(catalog: StremioCatalogRef, genre: String): StremioCatalogCategoryAccess =
        accessFor(catalog.type, listOf(genre))

    private fun accessFor(type: StremioMediaType, names: List<String>): StremioCatalogCategoryAccess {
        val contentType = when (type) {
            StremioMediaType.MOVIE -> ContentType.MOVIE
            StremioMediaType.SERIES -> ContentType.SERIES
            else -> return StremioCatalogCategoryAccess()
        }
        val categoryNames = names.map(::normalizeCategoryName)
            .filter(String::isNotBlank)
            .toSet()
        val matches = categories.filter { category ->
            (category.type == contentType || category.type == ContentType.VOD) &&
                normalizeCategoryName(category.name) in categoryNames
        }
        val hiddenIds = when (contentType) {
            ContentType.MOVIE -> hiddenMovieCategoryIds + hiddenVodCategoryIds
            ContentType.SERIES -> hiddenSeriesCategoryIds + hiddenVodCategoryIds
            else -> emptySet()
        }
        val hiddenBySettings = matches.any { it.id in hiddenIds }
        val protectedIds = matches.asSequence()
            .filter {
                it.isAdult || it.isUserProtected || AdultContentNameClassifier.isAdultCategoryName(it.name)
            }
            .map { kotlin.math.abs(it.id) }
            .toSet()
        val protectedByName = names.any(AdultContentNameClassifier::isAdultCategoryName) ||
            matches.any { AdultContentNameClassifier.isAdultCategoryName(it.name) }
        val unlockedIds = unlockedCategoryIds.mapTo(mutableSetOf()) { kotlin.math.abs(it) }
        val protectedCategoryLocked = protectedIds.any { it !in unlockedIds }
        val nameOnlyProtected = protectedByName && protectedIds.isEmpty()
        val hiddenByParentalLevel = parentalControlLevel >= 3 &&
            (protectedCategoryLocked || nameOnlyProtected)
        return StremioCatalogCategoryAccess(
            hidden = hiddenBySettings || hiddenByParentalLevel,
            locked = parentalControlLevel in 1..2 && (protectedCategoryLocked || nameOnlyProtected),
            protectedCategoryIds = protectedIds,
            providerId = providerId
        )
    }
}

data class StremioCatalogCategoryAccess(
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val protectedCategoryIds: Set<Long> = emptySet(),
    val providerId: Long? = null
)

interface StremioCatalogParentalAccess {
    val state: StateFlow<StremioCatalogParentalState>
    suspend fun verifyPin(pin: String): Boolean
    fun unlockCategories(providerId: Long, categoryIds: Set<Long>)
}

object NoopStremioCatalogParentalAccess : StremioCatalogParentalAccess {
    private val emptyState = MutableStateFlow(
        StremioCatalogParentalState(isReady = true, parentalControlLevel = 0)
    )
    override val state: StateFlow<StremioCatalogParentalState> = emptyState.asStateFlow()
    override suspend fun verifyPin(pin: String): Boolean = false
    override fun unlockCategories(providerId: Long, categoryIds: Set<Long>) = Unit
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class StremioCatalogParentalAccessManager @Inject constructor(
    providerRepository: ProviderRepository,
    categoryRepository: CategoryRepository,
    private val preferences: SettingsPreferences,
    private val parentalControlManager: ParentalControlManager
) : StremioCatalogParentalAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(StremioCatalogParentalState())
    override val state: StateFlow<StremioCatalogParentalState> = _state.asStateFlow()

    init {
        scope.launch {
            providerRepository.getActiveProvider().flatMapLatest { provider ->
                if (provider == null) {
                    preferences.parentalControlLevel.map { level ->
                        StremioCatalogParentalState(
                            isReady = true,
                            parentalControlLevel = level
                        )
                    }
                } else {
                    flow {
                        emit(StremioCatalogParentalState(providerId = provider.id))
                        val hiddenCategoryIds = combine(
                            preferences.getHiddenCategoryIds(provider.id, ContentType.MOVIE),
                            preferences.getHiddenCategoryIds(provider.id, ContentType.SERIES),
                            preferences.getHiddenCategoryIds(provider.id, ContentType.VOD)
                        ) { hiddenMovies, hiddenSeries, hiddenVod ->
                            Triple(hiddenMovies, hiddenSeries, hiddenVod)
                        }
                        emitAll(
                            combine(
                                categoryRepository.getCategories(provider.id),
                                hiddenCategoryIds,
                                preferences.parentalControlLevel,
                                parentalControlManager.unlockedCategoriesForProvider(provider.id)
                            ) { categories, hidden, level, unlocked ->
                                StremioCatalogParentalState(
                                    isReady = true,
                                    providerId = provider.id,
                                    categories = categories,
                                    hiddenMovieCategoryIds = hidden.first,
                                    hiddenSeriesCategoryIds = hidden.second,
                                    hiddenVodCategoryIds = hidden.third,
                                    parentalControlLevel = level,
                                    unlockedCategoryIds = unlocked
                                )
                            }
                        )
                    }
                }
            }.collect { _state.value = it }
        }
    }

    override suspend fun verifyPin(pin: String): Boolean = preferences.verifyParentalPin(pin)

    override fun unlockCategories(providerId: Long, categoryIds: Set<Long>) {
        categoryIds.forEach { categoryId ->
            parentalControlManager.unlockCategory(providerId, kotlin.math.abs(categoryId))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StremioCatalogParentalAccessModule {
    @Binds
    @Singleton
    abstract fun bindStremioCatalogParentalAccess(
        implementation: StremioCatalogParentalAccessManager
    ): StremioCatalogParentalAccess
}

internal fun normalizeCategoryName(name: String): String =
    name.trim().replace(Regex("\\s+"), " ").lowercase()
