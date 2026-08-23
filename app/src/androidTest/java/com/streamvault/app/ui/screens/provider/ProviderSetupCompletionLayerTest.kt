package com.streamvault.app.ui.screens.provider

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.theme.StreamVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderSetupCompletionLayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedResumingWarning_completesImmediatelyWithoutBlockingDialog() {
        var uiState by mutableStateOf(
            ProviderSetupState(
                onboardingCompletion = ProviderSetupViewModel.OnboardingCompletion.SAVED_RESUMING,
                completionWarning = "Playlist saved, but initial sync failed. Resume has been queued."
            )
        )
        var providerAddedCount by mutableIntStateOf(0)
        var cleanupCallCount by mutableIntStateOf(0)

        composeRule.setContent {
            StreamVaultTheme {
                ProviderSetupCompletionLayer(
                    uiState = uiState,
                    knownLocalM3uUrls = emptySet(),
                    selectedM3uUrl = "",
                    filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    onProviderAdded = { providerAddedCount++ },
                    onAttachCreatedProvider = {},
                    onSkipCreatedProviderCombinedAttach = {},
                    cleanupImportedFiles = { _, _, _ -> cleanupCallCount++ }
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Provider Saved, Sync Resuming").assertDoesNotExist()
        assertEquals(1, providerAddedCount)
        assertEquals(1, cleanupCallCount)
    }
}
