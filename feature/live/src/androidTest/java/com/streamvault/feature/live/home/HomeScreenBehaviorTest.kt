package com.streamvault.feature.live.home

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun channelContentHost_rendersLoadingState() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelContentHost(
                    isLoading = true,
                    errorMessage = null,
                    hasChannels = false,
                    isBlockedCategorySearch = false,
                    loadingLabel = "Loading channels",
                    lockedLabel = "Locked",
                    noChannelsLabel = "No channels",
                    noChannelsSubtitle = "Try another category",
                    channelContent = { Text("Channel content") }
                )
            }
        }
        composeRule.onNodeWithText("Loading channels").assertIsDisplayed()
        composeRule.onNodeWithText("Channel content").assertDoesNotExist()
    }

    @Test
    fun channelContentHost_rendersErrorState() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelContentHost(
                    isLoading = false,
                    errorMessage = "Channel request failed",
                    hasChannels = false,
                    isBlockedCategorySearch = false,
                    loadingLabel = "Loading channels",
                    lockedLabel = "Locked",
                    noChannelsLabel = "No channels",
                    noChannelsSubtitle = "Try another category",
                    channelContent = { Text("Channel content") }
                )
            }
        }
        composeRule.onNodeWithText("Channel request failed").assertIsDisplayed()
    }

    @Test
    fun channelContentHost_rendersLockedEmptyStateWithoutEmptyHints() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelContentHost(
                    isLoading = false,
                    errorMessage = null,
                    hasChannels = false,
                    isBlockedCategorySearch = true,
                    loadingLabel = "Loading channels",
                    lockedLabel = "Locked",
                    noChannelsLabel = "No channels",
                    noChannelsSubtitle = "Try another category",
                    additionalEmptyHint = "Add a favorite",
                    channelContent = { Text("Channel content") }
                )
            }
        }
        composeRule.onNodeWithText("Locked").assertIsDisplayed()
        composeRule.onNodeWithText("No channels").assertDoesNotExist()
        composeRule.onNodeWithText("Add a favorite").assertDoesNotExist()
    }

    @Test
    fun channelContentHost_rendersChannelContentWhenChannelsExist() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelContentHost(
                    isLoading = false,
                    errorMessage = null,
                    hasChannels = true,
                    isBlockedCategorySearch = false,
                    loadingLabel = "Loading channels",
                    lockedLabel = "Locked",
                    noChannelsLabel = "No channels",
                    noChannelsSubtitle = "Try another category",
                    channelContent = { Text("Channel content") }
                )
            }
        }
        composeRule.onNodeWithText("Channel content").assertIsDisplayed()
    }

    @Test
    fun categorySidebarHeader_quickFilterSelectionReachesHomeCallback() {
        val selectedFilters = mutableListOf<String>()
        val categorySearchFocusRequester = FocusRequester()

        composeRule.setContent {
            StreamVaultTheme {
                LiveCategorySidebarHeader(
                    title = "Live TV",
                    currentSource = ActiveLiveSource.ProviderSource(1L),
                    sourceOptions = listOf(
                        ActiveLiveSourceOption(
                            source = ActiveLiveSource.ProviderSource(1L),
                            title = "Fixture provider"
                        )
                    ),
                    showSourceSwitcher = false,
                    onSourceSelected = {},
                    categorySearchQuery = "",
                    onCategorySearchQueryChanged = {},
                    categorySearchFocusRequester = categorySearchFocusRequester,
                    categorySearchPlaceholder = "Search categories",
                    quickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
                    savedCategoryFilters = listOf("Movies"),
                    activeCategoryFilter = null,
                    hiddenCategoriesButtonLabel = null,
                    hiddenChannelsButtonLabel = null,
                    quickFiltersButtonTitle = "Quick filters",
                    quickFiltersTitle = "Saved filters",
                    quickFiltersShowLabel = "Show",
                    quickFiltersHideLabel = "Hide",
                    quickFiltersShowingAllLabel = "Showing all categories",
                    quickFiltersActiveLabel = "Filter active",
                    quickFiltersManualSearchLabel = "Manual search",
                    quickFiltersAllLabel = "All categories",
                    quickFiltersEmptyLabel = "No saved filters",
                    quickFiltersAddChipLabel = "Add quick filter",
                    noSourceLabel = "No provider",
                    selectedLabel = "Selected",
                    unavailableLabel = "Unavailable",
                    isReorderMode = false,
                    onShowHiddenCategories = {},
                    onShowHiddenChannels = {},
                    onAddFilter = {},
                    onAllSelected = {},
                    onSavedFilterSelected = selectedFilters::add
                )
            }
        }

        composeRule.onNodeWithText("Quick filters")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Movies")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertThat(selectedFilters).containsExactly("Movies")
    }

    @Test
    fun categorySidebarHeader_titleUsesLightOnSurfaceText() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0xFF162338))
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides androidx.compose.ui.graphics.Color.Black
                    ) {
                        LiveCategorySidebarHeader(
                            title = "Categories",
                            currentSource = null,
                            sourceOptions = emptyList(),
                            showSourceSwitcher = false,
                            onSourceSelected = {},
                            categorySearchQuery = "",
                            onCategorySearchQueryChanged = {},
                            categorySearchFocusRequester = remember { FocusRequester() },
                            categorySearchPlaceholder = "Search categories",
                            quickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.HIDE,
                            savedCategoryFilters = emptyList(),
                            activeCategoryFilter = null,
                            hiddenCategoriesButtonLabel = null,
                            hiddenChannelsButtonLabel = null,
                            quickFiltersButtonTitle = "Quick filters",
                            quickFiltersTitle = "Saved filters",
                            quickFiltersShowLabel = "Show",
                            quickFiltersHideLabel = "Hide",
                            quickFiltersShowingAllLabel = "Showing all categories",
                            quickFiltersActiveLabel = "Filter active",
                            quickFiltersManualSearchLabel = "Manual search",
                            quickFiltersAllLabel = "All categories",
                            quickFiltersEmptyLabel = "No saved filters",
                            quickFiltersAddChipLabel = "Add quick filter",
                            noSourceLabel = "No provider",
                            selectedLabel = "Selected",
                            unavailableLabel = "Unavailable",
                            isReorderMode = false,
                            onShowHiddenCategories = {},
                            onShowHiddenChannels = {},
                            onAddFilter = {},
                            onAllSelected = {},
                            onSavedFilterSelected = {}
                        )
                    }
                }
            }
        }

        val titleNode = composeRule
            .onNodeWithText("Categories")
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val bounds = titleNode.boundsInRoot
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val brightestPixel = (bounds.left.toInt() until bounds.right.toInt()).maxOf { x ->
            (bounds.top.toInt() until bounds.bottom.toInt()).maxOf { y ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
            }
        }

        assertThat(brightestPixel).isGreaterThan(700)
    }

    @Test
    fun channelListHost_rendersStableRowsAndReportsVisibleWindow() {
        val visibleWindows = mutableListOf<List<Long>>()
        val channels = listOf(
            testChannel(11L, "News"),
            testChannel(12L, "Sports")
        )

        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelListHost(
                    channels = channels,
                    hasMoreChannels = false,
                    isReorderMode = false,
                    draggingChannel = null,
                    focusedChannelId = null,
                    channelFocusRequesters = mutableMapOf(),
                    contentPaddingBottom = 8.dp,
                    channelListSpacing = 4.dp,
                    onDraggingChannelChange = {},
                    onExitReorderMode = {},
                    onMoveChannelUp = {},
                    onMoveChannelDown = {},
                    onVisibleChannelWindowChanged = { ids, _ -> visibleWindows += ids },
                    onLoadMore = {},
                    itemContent = { channel, _, _ -> Text("${channel.id}:${channel.name}") }
                )
            }
        }

        composeRule.onNodeWithText("11:News").assertIsDisplayed()
        composeRule.onNodeWithText("12:Sports").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) { visibleWindows.any { it.containsAll(listOf(11L, 12L)) } }
    }
}

private fun testChannel(id: Long, name: String) = com.streamvault.domain.model.Channel(
    id = id,
    name = name,
    number = id.toInt(),
    streamUrl = "https://example.test/$id.m3u8"
)
