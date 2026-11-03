package com.streamvault.feature.catalog.presentation.vod

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.theme.StreamVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class VodSelectedCategoryFocusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun searchAndSubsequentLoadingKeepEditingFocus() {
        val requester = FocusRequester()
        val query = mutableStateOf("news")
        val ready = mutableStateOf(true)
        rule.setContent {
            StreamVaultTheme {
                VodSelectedCategoryFocus("Saved", ready.value, requester)
                SearchInput(query.value, { query.value = it }, "Search", focusRequester = requester)
            }
        }
        waitForSearchFocus()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("news") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("news", useUnmergedTree = true).assertIsFocused().performTextInput("x")
        rule.runOnIdle { ready.value = false }
        rule.waitForIdle()
        rule.runOnIdle { ready.value = true }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("newsx", useUnmergedTree = true).assertIsFocused()
    }

    @Test fun categoryEntryWaitsForContentAndRestoresAgainForAnotherCategory() {
        val requester = FocusRequester()
        val otherRequester = FocusRequester()
        val category = mutableStateOf("Saved")
        val ready = mutableStateOf(false)
        rule.setContent {
            StreamVaultTheme {
                VodSelectedCategoryFocus(category.value, ready.value, requester)
                Column {
                    SearchInput("news", {}, "Search", focusRequester = requester)
                    Box(Modifier.size(50.dp).testTag("other").focusRequester(otherRequester).focusable())
                }
            }
        }
        rule.runOnIdle { otherRequester.requestFocus() }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("other").assertIsFocused()
        rule.runOnIdle { ready.value = true }
        waitForSearchFocus()
        rule.runOnIdle {
            otherRequester.requestFocus()
            category.value = "Full library"
        }
        waitForSearchFocus()
    }

    private fun waitForSearchFocus() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasContentDescription("Search") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
