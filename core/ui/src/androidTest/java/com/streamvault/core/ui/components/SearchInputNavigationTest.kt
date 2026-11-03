package com.streamvault.core.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.device.isTelevisionDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.google.common.truth.Truth.assertThat

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchInputNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun downLeavesEditingAndFocusesControlBelow() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.isTelevisionDevice())
        val requester = FocusRequester()
        rule.setContent {
            var query by androidx.compose.runtime.remember { mutableStateOf("news") }
            Column {
                SearchInput(query, { query = it }, "Search", focusRequester = requester)
                Box(Modifier.size(100.dp).testTag("result").focusable())
            }
        }
        rule.runOnIdle { requester.requestFocus() }
        rule.onNodeWithContentDescription("Search").performClick()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("result").assertIsFocused()
    }

    @Test fun horizontalKeysMoveCursorThenNavigateAtBoundaries() {
        var value = TextFieldValue("abc", selection = TextRange(2))
        var direction: FocusDirection? = null
        var exited = false
        fun key(code: Int, canMove: Boolean = true) = handleTvSearchKey(
            KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)), value,
            { value = it }, { direction = it; canMove }, { exited = true }
        )
        assertThat(key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)).isTrue()
        assertThat(value.selection).isEqualTo(TextRange(3))
        assertThat(direction).isNull()
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, canMove = false)
        assertThat(direction).isEqualTo(FocusDirection.Right)
        assertThat(exited).isFalse()
        key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(exited).isTrue()
        value = value.copy(selection = TextRange(0, 2))
        key(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertThat(value.selection).isEqualTo(TextRange(0))
    }
}
