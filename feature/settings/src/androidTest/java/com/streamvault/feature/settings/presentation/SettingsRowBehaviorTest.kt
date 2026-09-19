package com.streamvault.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.theme.StreamVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsRowBehaviorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchHasOneActivationTargetAndChangesExactlyOnce() {
        var checked by mutableStateOf(false)
        var changes = 0
        rule.setContent {
            StreamVaultTheme {
                SwitchSettingsRow("Keep screen awake", "During playback", checked, {
                    checked = it
                    changes++
                })
            }
        }
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
        rule.onNode(isToggleable()).assertIsOff()
            .performSemanticsAction(SemanticsActions.OnClick)
            .assertIsOn()
        rule.runOnIdle { assertEquals(1, changes) }
        rule.onNode(isToggleable()).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
            .assertIsOff()
        rule.runOnIdle { assertEquals(2, changes) }
    }

    @Test fun disabledChoiceCannotInvokeItsCallback() {
        var changes = 0
        rule.setContent {
            StreamVaultTheme {
                ClickableSettingsRow("Preferred variant", "Enable grouping first", { changes++ }, enabled = false)
            }
        }
        rule.onNodeWithText("Preferred variant").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun longChoiceValuesRemainReadableInANarrowColumn() {
        rule.setContent {
            StreamVaultTheme {
                Column(Modifier.width(300.dp)) {
                    ClickableSettingsRow("Preferred audio language",
                        "Use the original audio language when the preferred language is unavailable", {})
                    SwitchSettingsRow("Keep the screen awake while watching", "Applies during playback", false, {})
                }
            }
        }
        rule.onNodeWithText("Use the original audio language when the preferred language is unavailable").assertIsDisplayed()
        rule.onNodeWithText("Keep the screen awake while watching").assertIsDisplayed()
    }

    @Test fun constrainedSettingsWidthKeepsTheContentVisible() {
        rule.setContent {
            StreamVaultTheme {
                Box(Modifier.width(590.dp).height(480.dp)) {
                    SettingsAdaptiveLayout(navigation = { compact ->
                        SettingsNavigationRail(
                            selectedCategory = 0,
                            focusRequester = remember { FocusRequester() },
                            onCategorySelected = {},
                            compact = compact,
                        )
                    }) { compact ->
                        androidx.tv.material3.Text(if (compact) "Compact settings content" else "Wide settings content")
                    }
                }
            }
        }
        rule.onNodeWithText("Compact settings content").assertIsDisplayed()
    }
}
