package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenContractTest {
    @Test
    fun settingsScreenEntryPointIsOwnedByFeature() {
        val entryPoint: @Composable () -> Unit = {
            SettingsScreen(
                onNavigate = {},
                currentRoute = "settings",
                platformHost = error("platform host is supplied by app composition"),
            )
        }

        assertThat(entryPoint).isNotNull()
    }
}
