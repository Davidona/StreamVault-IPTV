package com.streamvault.feature.playback.player

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsStateCollectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hiddenControlsStopObservingAndReopeningReadsLatestState() {
        val source = MutableStateFlow(0)
        val visible = mutableStateOf(true)
        var observed = -1
        var compositions = 0
        compose.setContent {
            val value = source.collectAsControlsState(visible.value).value
            SideEffect { observed = value; compositions++ }
        }
        compose.waitUntil { source.subscriptionCount.value == 1 }
        compose.runOnIdle { source.value = 1 }
        compose.waitUntil { observed == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(source.subscriptionCount.value).isEqualTo(0) }
        val hiddenCompositions = compositions
        compose.runOnIdle { repeat(100) { source.value = it + 2 } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(observed).isEqualTo(1)
            assertThat(compositions).isEqualTo(hiddenCompositions)
            visible.value = true
        }
        compose.waitUntil { observed == 101 && source.subscriptionCount.value == 1 }
    }

    @Test fun openSheetKeepsControlsStateCurrentWhileChromeIsHidden() {
        val source = MutableStateFlow(0)
        val sheetOpen = mutableStateOf(true)
        var observed = -1
        compose.setContent {
            val value = source.collectAsControlsState(active = sheetOpen.value).value
            SideEffect { observed = value }
        }
        compose.waitUntil { source.subscriptionCount.value == 1 }
        compose.runOnIdle { source.value = 23 }
        compose.waitUntil { observed == 23 }
        compose.runOnIdle { sheetOpen.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(source.subscriptionCount.value).isEqualTo(0) }
    }
}
