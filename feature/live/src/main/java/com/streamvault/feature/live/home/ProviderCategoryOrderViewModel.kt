package com.streamvault.feature.live.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.*
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.feature.live.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class CategoryOrderSection { PERSONAL, PINNED, PROVIDER }

data class ProviderCategoryOrderUiState(
    val categories: Map<Long, Category> = emptyMap(),
    val drafts: Map<CategoryOrderSection, LiveOrderDraft> = emptyMap(),
    val hiddenIds: Set<Long> = emptySet(),
    val showPersonalSection: Boolean = true,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val closed: Boolean = false,
    val message: Int? = null
)

@HiltViewModel
class ProviderCategoryOrderViewModel @Inject constructor(
    private val categories: CategoryRepository,
    private val combinedM3uRepository: CombinedM3uRepository,
    private val preferences: SettingsPreferences,
    private val customCategories: GetCustomCategories
) : ViewModel() {
    private val state = MutableStateFlow(ProviderCategoryOrderUiState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var providerId = 0L
    private var combinedProfileId: Long? = null
    private var combinedProfileIdentity: String? = null
    private var saved = LiveCategoryOrder()
    private var baseNormalIds = emptyList<Long>()
    private var pickup: Pair<CategoryOrderSection, LiveOrderDraft>? = null

    fun open(providerId: Long, favoritesLabel: String, recentLabel: String, allLabel: String) {
        reset()
        this.providerId = providerId
        load(favoritesLabel, recentLabel, allLabel)
    }

    fun openCombined(profileId: Long, favoritesLabel: String, recentLabel: String, allLabel: String) {
        reset()
        combinedProfileId = profileId
        load(favoritesLabel, recentLabel, allLabel)
    }

    private fun load(favoritesLabel: String, recentLabel: String, allLabel: String) {
        job = viewModelScope.launch {
            try {
                val combinedId = combinedProfileId
                val custom: List<Category>
                val providerCategories: List<Category>
                if (combinedId != null) {
                    val profile = checkNotNull(combinedM3uRepository.getProfile(combinedId))
                    combinedProfileIdentity = profile.identityKey
                    val providerIds = profile.members.filter { it.enabled }.map { it.providerId }.distinct()
                    providerCategories = combinedM3uRepository.getCombinedCategories(combinedId).first().map { it.category }
                    custom = customCategories(providerIds, ContentType.LIVE).first()
                    saved = preferences.getCombinedLiveCategoryOrder(profile.identityKey).first()
                } else {
                    require(providerId > 0)
                    providerCategories = categories.getCategories(providerId).first()
                        .filter { it.type == ContentType.LIVE && !it.isVirtual && it.id != ChannelRepository.ALL_CHANNELS_ID }
                        .sortedBy { it.providerOrder }
                    custom = customCategories(providerId, ContentType.LIVE).first()
                    saved = preferences.getLiveCategoryOrder(providerId).first()
                }
                val personal = buildList {
                    add(custom.firstOrNull { it.id == VirtualCategoryIds.FAVORITES }
                        ?.copy(name = favoritesLabel) ?: Category(VirtualCategoryIds.FAVORITES, name = favoritesLabel, isVirtual = true))
                    add(Category(VirtualCategoryIds.RECENT, name = recentLabel, isVirtual = true))
                    addAll(custom.filterNot { it.id == VirtualCategoryIds.FAVORITES })
                    add(Category(ChannelRepository.ALL_CHANNELS_ID, name = allLabel))
                }
                val ordered = applyLiveCategorySections(personal + providerCategories, saved.pinnedCategoryIds, saved)
                baseNormalIds = (saved.normalIds + providerCategories.map { it.id }).distinct()
                val hidden = if (combinedId != null) mutableSetOf() else
                    preferences.getHiddenCategoryIds(providerId, ContentType.LIVE).first().toMutableSet()
                if (!preferences.showFavoritesCategory.first()) hidden += VirtualCategoryIds.FAVORITES
                if (!preferences.showRecentChannelsCategory.first()) hidden += VirtualCategoryIds.RECENT
                if (!preferences.showAllChannelsCategory.first()) hidden += ChannelRepository.ALL_CHANNELS_ID
                fun draft(section: CategoryOrderSection) = LiveOrderDraft.start(ordered.filter { category ->
                    when (section) {
                        CategoryOrderSection.PERSONAL -> category.isVirtual || category.id == ChannelRepository.ALL_CHANNELS_ID
                        CategoryOrderSection.PINNED -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id in saved.pinnedCategoryIds
                        CategoryOrderSection.PROVIDER -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id !in saved.pinnedCategoryIds
                    }
                }.map { it.id.toString() })
                state.value = ProviderCategoryOrderUiState(categories = ordered.associateBy { it.id },
                    drafts = CategoryOrderSection.entries.associateWith(::draft), hiddenIds = hidden,
                    showPersonalSection = combinedId == null, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = ProviderCategoryOrderUiState(loading = false, message = R.string.live_category_order_load_failed) }
        }
    }

    fun move(section: CategoryOrderSection, id: Long, position: Int) = moveMany(section, setOf(id), position)

    fun moveMany(section: CategoryOrderSection, ids: Set<Long>, position: Int) {
        val draft = state.value.drafts[section] ?: return
        val keys = ids.map(Long::toString).toSet()
        if (state.value.saving || keys.isEmpty() || !draft.keys.containsAll(keys) ||
            position !in 1..(draft.keys.size - keys.size + 1)) return
        publish(section, draft.moveToPosition(keys, position))
    }
    fun undo(section: CategoryOrderSection) { if (!state.value.saving) state.value.drafts[section]?.undo()?.let { publish(section, it) } }
    fun redo(section: CategoryOrderSection) { if (!state.value.saving) state.value.drafts[section]?.redo()?.let { publish(section, it) } }
    fun beginMove(section: CategoryOrderSection) { if (!state.value.saving) state.value.drafts[section]?.let { pickup = section to it } }
    fun finishMove() { pickup = null }
    fun cancelMove() { if (!state.value.saving) { pickup?.let { publish(it.first, it.second) }; pickup = null } }
    private fun publish(section: CategoryOrderSection, draft: LiveOrderDraft) { state.update { it.copy(drafts = it.drafts + (section to draft)) } }

    fun save(providerOrder: Boolean = false) {
        val current = state.value
        if (current.loading || current.saving || current.drafts.isEmpty()) return
        if (current.drafts.values.none { it.hasChanges } && (!providerOrder || saved.sortMode == CategorySortMode.DEFAULT)) {
            state.update { it.copy(closed = true) }; return
        }
        fun ids(section: CategoryOrderSection) = current.drafts.getValue(section).keys.map(String::toLong)
        val requested = saved.copy(
            sortMode = if (providerOrder) CategorySortMode.DEFAULT else if (current.drafts.getValue(CategoryOrderSection.PROVIDER).hasChanges) CategorySortMode.MANUAL else saved.sortMode,
            normalIds = if (!providerOrder && current.drafts.getValue(CategoryOrderSection.PROVIDER).hasChanges)
                mergeLiveCategoryPositions(baseNormalIds, ids(CategoryOrderSection.PROVIDER)) else saved.normalIds,
            pinnedIds = mergeLiveCategoryPositions(saved.pinnedIds, ids(CategoryOrderSection.PINNED)),
            personalIds = if (combinedProfileIdentity == null)
                mergeLiveCategoryPositions(saved.personalIds, ids(CategoryOrderSection.PERSONAL)) else saved.personalIds
        )
        state.update { it.copy(saving = true, message = null) }
        job = viewModelScope.launch {
            try {
                val saveResult = combinedProfileIdentity?.let { identity ->
                    preferences.saveCombinedLiveCategoryOrder(identity, saved.revision, requested)
                } ?: preferences.saveLiveCategoryOrder(providerId, saved.revision, requested)
                when (saveResult) {
                    is SaveLiveCategoryOrderResult.Saved -> state.update { it.copy(saving = false, closed = true) }
                    is SaveLiveCategoryOrderResult.Conflict -> state.update { it.copy(saving = false, message = R.string.live_order_conflict) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(saving = false, message = R.string.live_category_order_save_failed) } }
        }
    }

    fun reset() {
        job?.cancel(); pickup = null; baseNormalIds = emptyList(); providerId = 0L
        combinedProfileId = null; combinedProfileIdentity = null
        state.value = ProviderCategoryOrderUiState()
    }
}
