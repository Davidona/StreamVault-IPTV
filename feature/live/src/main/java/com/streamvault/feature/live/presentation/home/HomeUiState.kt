package com.streamvault.feature.live.presentation.home

import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Channel

/** State rendered by the Home Live TV surface. Kept in the Live feature with its presentation state. */
data class HomeUiState(
    val provider: Provider? = null,
    val activeLiveSource: ActiveLiveSource? = null,
    val activeLiveSourceTitle: String = "",
    val isCombinedLiveSource: Boolean = false,
    val liveSourceOptions: List<ActiveLiveSourceOption> = emptyList(),
    val currentCombinedProfileMembers: List<CombinedM3uProfileMember> = emptyList(),
    val selectedCombinedSourceProviderId: Long? = null,
    val showLiveSourceSwitcher: Boolean = false,
    val allProviders: List<Provider> = emptyList(),
    val categories: List<Category> = emptyList(),
    val recentChannels: List<Channel> = emptyList(),
    val lastVisitedCategory: Category? = null,
    val selectedCategory: Category? = null,
    val filteredChannels: List<Channel> = emptyList(),
    val hasChannels: Boolean = false,
    val hasMoreChannels: Boolean = false,
    val isLoading: Boolean = true,
    val isCategoriesLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val categorySearchQuery: String = "",
    val savedCategoryFilters: List<String> = emptyList(),
    val activeCategoryFilter: String? = null,
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
    val channelSearchQuery: String = "",
    val showDialog: Boolean = false,
    val selectedChannelForDialog: Channel? = null,
    val dialogGroupMemberships: List<Long> = emptyList(),
    val showHiddenChannelsDialog: Boolean = false,
    val userMessage: String? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val parentalControlLevel: Int = 0,
    val unlockedCategoryIds: Set<Long> = emptySet(),
    val pinnedCategoryIds: Set<Long> = emptySet(),
    val hiddenLiveCategories: List<Category> = emptyList(),
    val shouldAutoFocusFirstChannelOnEntry: Boolean = false,
    val selectedCategoryForOptions: Category? = null,
    val isChannelReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.PRO,
    val liveTvAutoHideCategories: Boolean = false,
    val errorMessage: String? = null,
    val multiviewChannelCount: Int = 0,
    val multiviewSlotCapacity: Int = 4
)
