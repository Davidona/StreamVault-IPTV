package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.device.isTelevisionDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LiveGuideSearchNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun downLeavesEditingAndFocusesCategory() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.isTelevisionDevice())
        val requester = FocusRequester()
        rule.setContent {
            var query by remember { mutableStateOf("") }
            Column {
                LiveGuideSearchField(query, { query = it }, "Search categories", focusRequester = requester)
                Box(Modifier.size(100.dp).testTag("category").focusable())
            }
        }
        rule.runOnIdle { requester.requestFocus() }
        rule.onNodeWithText("Search categories").performClick()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("category").assertIsFocused()
    }

    @Test fun rightAtEndFocusesClearControl() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.isTelevisionDevice())
        val requester = FocusRequester()
        rule.setContent {
            var query by remember { mutableStateOf("news") }
            LiveGuideProgramSearchRow(
                query = query,
                onQueryChange = { query = it },
                onClear = { query = "" },
                searchLabel = "Search",
                searchPlaceholder = "Search categories",
                clearLabel = "Clear",
                focusRequester = requester
            )
        }
        rule.runOnIdle { requester.requestFocus() }
        rule.onNodeWithText("news").performClick()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithText("Clear").assertIsFocused()
    }
}
