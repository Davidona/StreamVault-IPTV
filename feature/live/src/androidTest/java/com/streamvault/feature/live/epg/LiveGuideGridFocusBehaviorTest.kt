package com.streamvault.feature.live.epg

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.presentation.epg.GuideDensity
import com.streamvault.feature.live.presentation.epg.LiveGuideGrid
import com.streamvault.feature.live.presentation.epg.LiveGuideGridLabels
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RunWith(AndroidJUnit4::class)
class LiveGuideGridFocusBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    private val allChannels = (1L..70L).map { Channel(id = it, name = "Channel $it") }

    @Test fun loadingAnotherPageDoesNotStealFocus() {
        val channels = mutableStateOf(allChannels.take(10))
        var focusedId: Long? = null
        composeRule.setContent {
            StreamVaultTheme {
                grid(channels.value, initialId = null, hasMore = false, onFocus = { focusedId = it })
            }
        }
        composeRule.waitUntil(5_000) { focusedId == 1L }
        composeRule.onNodeWithText("Channel 4").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.runOnIdle { channels.value = allChannels }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(4L, focusedId) }
    }

    @Test fun restoresChannelOnLaterPage() {
        val channels = mutableStateOf(allChannels.take(60))
        var focusedId: Long? = null
        composeRule.setContent {
            StreamVaultTheme {
                grid(channels.value, initialId = 65L, hasMore = channels.value.size < 70,
                    onFocus = { focusedId = it }, onMore = { channels.value = allChannels })
            }
        }
        composeRule.waitUntil(5_000) { focusedId == 65L }
        composeRule.runOnIdle { assertEquals(65L, focusedId) }
    }

    @Test fun changingCategoryFocusesItsFirstChannel() {
        val category = mutableStateOf(0)
        var focusedId: Long? = null
        composeRule.setContent {
            StreamVaultTheme {
                key(category.value) {
                    val channels = if (category.value == 0) allChannels.take(10) else allChannels.drop(10).take(10)
                    grid(channels, initialId = null, hasMore = false, onFocus = { focusedId = it })
                }
            }
        }
        composeRule.waitUntil(5_000) { focusedId == 1L }
        composeRule.runOnIdle { category.value = 1 }
        composeRule.waitUntil(5_000) { focusedId == 11L }
    }

    @Test fun restoresChannelPastPageWithNoDisplayedRows() {
        val channels = mutableStateOf(allChannels.take(60))
        val loadedCount = mutableStateOf(60)
        val loading = mutableStateOf(false)
        var requests = 0
        var focusedId: Long? = null
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            StreamVaultTheme {
                grid(channels.value, initialId = 125L, hasMore = loadedCount.value < 180,
                    loadedCount = loadedCount.value, loading = loading.value, onFocus = { focusedId = it },
                    onMore = {
                        requests++
                        loading.value = true
                        scope.launch {
                            delay(60)
                            loadedCount.value += 60
                            if (loadedCount.value == 180) {
                                channels.value += Channel(id = 125L, name = "Channel 125")
                            }
                            loading.value = false
                        }
                    })
            }
        }
        composeRule.waitUntil(5_000) { focusedId == 125L }
        composeRule.runOnIdle { assertEquals(2, requests) }
    }

    @androidx.compose.runtime.Composable
    private fun grid(
        channels: List<Channel>, initialId: Long?, hasMore: Boolean,
        onFocus: (Long) -> Unit, onMore: () -> Unit = {},
        loadedCount: Int = channels.size, loading: Boolean = false
    ) {
        LiveGuideGrid(
            channels = channels, favoriteChannelIds = emptySet(), programsByChannel = emptyMap(),
            guideWindowStart = 0L, guideWindowEnd = 3_600_000L, density = GuideDensity.COMPACT,
            labels = LiveGuideGridLabels("No schedule", "Archive", "Favorite"),
            initialFocusedChannelId = initialId, hasMoreChannels = hasMore,
            loadedChannelCount = loadedCount, isLoadingMoreChannels = loading,
            onChannelClick = {}, onProgramClick = { _, _ -> },
            onChannelFocused = { channel, _, _ -> onFocus(channel.id) },
            onProgramFocused = { channel, _, _ -> onFocus(channel.id) },
            onRequestMoreChannels = onMore
        )
    }
}
