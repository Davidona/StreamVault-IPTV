package com.streamvault.core.ui.components.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.MaterialTheme
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppPalette
import com.streamvault.core.ui.design.AppShapes
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseVisualStyle
import com.streamvault.core.ui.design.LocalAppShapes
import com.streamvault.core.ui.design.LocalBrowseVisuals
import com.streamvault.core.ui.theme.StreamVaultTheme
import org.junit.Rule
import org.junit.Test

class BrowseVisualStyleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun leavingBrowseRestoresProtectedThemeWithoutRecreatingContent() {
        val enabled = mutableStateOf(true)
        var remembered: Any? = null
        var observed: Any? = null
        var modern = false
        var shapes = AppShapes()
        var surface = AppPalette.classicBlue().surface
        composeRule.setContent {
            StreamVaultTheme {
                BrowseVisualStyle(enabled = enabled.value) {
                    observed = remember { Any() }
                    modern = LocalBrowseVisuals.current
                    shapes = LocalAppShapes.current
                    surface = MaterialTheme.colorScheme.surface
                }
            }
        }
        composeRule.runOnIdle {
            remembered = observed
            assertThat(modern).isTrue()
            assertThat(surface).isEqualTo(BrowseColors.Surface)
            enabled.value = false
        }
        composeRule.runOnIdle {
            assertThat(observed).isSameInstanceAs(remembered)
            assertThat(modern).isFalse()
            assertThat(shapes).isEqualTo(AppShapes())
            assertThat(surface).isEqualTo(AppColors.Surface)
            assertThat(AppColors.current).isEqualTo(AppPalette.classicBlue())
        }
    }
}
