package com.streamvault.feature.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.components.LiveCategoryRow
import com.streamvault.feature.live.presentation.components.LiveChannelCard
import com.streamvault.feature.live.presentation.components.LiveChannelRowSurface
import com.streamvault.feature.live.presentation.components.LiveReorderTopBar
import com.streamvault.feature.live.presentation.components.LiveSelectionChip
import com.streamvault.feature.live.presentation.components.LiveSelectionChipRow
import com.streamvault.feature.live.presentation.components.LiveSourceSwitcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePresentationGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionChips_renderSelectedAndUnselectedStates() {
        capture("live_selection_chips") {
            LiveSelectionChipRow(
                title = "Quick filters",
                subtitle = "Saved live category searches",
                chips = listOf(
                    LiveSelectionChip("all", "All", "4820 channels"),
                    LiveSelectionChip("sports", "Sports", "326 channels"),
                    LiveSelectionChip("news", "News", "118 channels")
                ),
                selectedKey = "sports",
                onChipSelected = {}
            )
        }
    }

    @Test
    fun categoryRow_renderedWithLockedAndUnlockedRows() {
        capture("live_category_row") {
            LiveCategoryRow(
                title = "Live groups",
                items = listOf(
                    Category(id = 1L, name = "Sports", type = ContentType.LIVE, count = 326),
                    Category(id = 2L, name = "Locked News", type = ContentType.LIVE, count = 118, isUserProtected = true)
                ),
                keySelector = { it.id },
                onSeeAll = {},
                onPinToggle = {},
                isPinned = true
            ) { category ->
                Text(
                    text = "${category.name} ${category.count}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }

    @Test
    fun channelSurfaces_renderProgressAndLockedStates() {
        capture("live_channel_surfaces") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LiveChannelCard(
                    channel = liveChannel.copy(isFavorite = true),
                    nowMs = PROGRAM_START + 1_800_000L,
                    onClick = {},
                    modifier = Modifier.width(300.dp),
                    isRecording = true
                )
                LiveChannelCard(
                    channel = liveChannel.copy(id = 8L, name = "Locked News"),
                    nowMs = PROGRAM_START,
                    onClick = {},
                    modifier = Modifier.width(300.dp),
                    isLocked = true
                )
                LiveChannelRowSurface(
                    channel = liveChannel,
                    nowMs = PROGRAM_START + 1_800_000L,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    @Test
    fun channelRowSurface_matchesAppGolden() {
        capture("live_channel_row_surface") {
            LiveChannelRowSurface(
                channel = Channel(
                    id = 7L,
                    name = "World Sports HD",
                    streamUrl = "https://example.com/live",
                    logoUrl = null,
                    isFavorite = true,
                    catchUpSupported = true
                ),
                nowMs = 0L,
                onClick = {},
                onLongClick = {},
                noScheduleLabel = "No schedule information",
                savedLabel = "SAVED"
            )
        }
    }

    @Test
    fun sourceSwitcher_renderedWithSelectedAndUnavailableSources() {
        capture("live_source_switcher") {
            LiveSourceSwitcher(
                currentSource = ActiveLiveSource.ProviderSource(7L),
                options = listOf(
                    ActiveLiveSourceOption(
                        source = ActiveLiveSource.ProviderSource(7L),
                        title = "Primary Provider",
                        subtitle = "1467 channels"
                    ),
                    ActiveLiveSourceOption(
                        source = ActiveLiveSource.CombinedM3uSource(12L),
                        title = "Combined playlist",
                        subtitle = "Unavailable",
                        isEnabled = false
                    )
                ),
                onSourceSelected = {},
                initiallyExpanded = true,
                modifier = Modifier.testTag("source-switcher")
            )
        }
    }

    @Test
    fun reorderBar_renderedWithSaveAndCancelActions() {
        capture("live_reorder_top_bar") {
            LiveReorderTopBar(
                categoryName = "Sports",
                subtitle = "D-pad moves the selected channel. Save the order when you are done.",
                onSave = {},
                onCancel = {}
            )
        }
    }

    private fun capture(
        name: String,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            StreamVaultTheme {
                Box(modifier = Modifier.fillMaxSize().testTag("golden")) {
                    content()
                }
            }
        }
        composeRule.onNodeWithTag("golden").assertAgainstGolden(name)
    }

    private companion object {
        const val PROGRAM_START = 1_710_000_000_000L
        val liveChannel = Channel(
            id = 7L,
            name = "World Sports HD",
            streamUrl = "https://example.com/live.m3u8",
            number = 105,
            isFavorite = true,
            catchUpSupported = true,
            catchUpDays = 7,
            categoryName = "Sports",
            currentProgram = Program(
                id = 42L,
                channelId = "7",
                title = "World Cup Qualifiers",
                startTime = PROGRAM_START,
                endTime = PROGRAM_START + 3_600_000L,
                hasArchive = true,
                isNowPlaying = true
            )
        )
    }
}
