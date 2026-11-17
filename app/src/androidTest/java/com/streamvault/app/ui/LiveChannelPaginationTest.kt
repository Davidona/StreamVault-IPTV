package com.streamvault.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.home.LiveChannelListHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.tv.material3.Text

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LiveChannelPaginationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollLoadsPastBothPageBoundariesAndUsesUpdatedAvailabilityAndCallback() {
        val allChannels = (1L..525L).map { Channel(id = it, name = "Channel $it") }
        val loaded = mutableStateOf(200)
        // Reproduce availability changing while the same list is composed.
        val more = mutableStateOf(false)
        val callbackVersion = mutableStateOf(0)
        val requests = mutableListOf<Int>()
        val requesters = mutableMapOf<Long, FocusRequester>()
        compose.setContent {
            val version = callbackVersion.value
            StreamVaultTheme {
                LiveChannelListHost(
                    channels = allChannels.take(loaded.value), hasMoreChannels = more.value,
                    isReorderMode = false, draggingChannel = null, focusedChannelId = null,
                    channelFocusRequesters = requesters, contentPaddingBottom = 0.dp,
                    channelListSpacing = 0.dp, onDraggingChannelChange = {}, onExitReorderMode = {},
                    onMoveChannelUp = {}, onMoveChannelDown = {}, onVisibleChannelWindowChanged = { _, _ -> },
                    onLoadMore = {
                        requests += version
                        loaded.value = minOf(loaded.value + 200, allChannels.size)
                        more.value = loaded.value < allChannels.size
                    },
                    itemContent = { channel, requester, _ ->
                        Text(channel.name, Modifier.height(48.dp).focusRequester(requester).focusable())
                    },
                    modifier = Modifier.testTag("channels")
                )
            }
        }
        compose.runOnIdle { checkNotNull(requesters[1L]).requestFocus() }
        compose.onNodeWithTag("channels").performScrollToIndex(199)
        compose.runOnIdle {
            assertThat(requests).isEmpty()
            callbackVersion.value = 1
            more.value = true
        }
        compose.waitUntil(10_000) { loaded.value == 400 }
        compose.onNodeWithText("Channel 200").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Channel 200").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Channel 201").assertIsFocused()
        compose.onNodeWithTag("channels").performScrollToIndex(399)
        compose.waitUntil(10_000) { loaded.value == 525 }
        compose.onNodeWithText("Channel 400").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Channel 400").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Channel 401").assertIsFocused()
        compose.onNodeWithTag("channels").performScrollToIndex(524)
        compose.onNodeWithText("Channel 525").assertIsDisplayed()
        compose.runOnIdle {
            assertThat(requests).containsExactly(1, 1)
            assertThat(more.value).isFalse()
        }
    }
}
