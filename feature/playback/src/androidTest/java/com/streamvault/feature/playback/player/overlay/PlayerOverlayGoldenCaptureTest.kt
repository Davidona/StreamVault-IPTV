package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerOverlayGoldenCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingGoldenIsRejectedInsteadOfRecorded() {
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .testTag("golden")
            )
        }

        assertThrows(AssertionError::class.java) {
            composeRule.onNodeWithTag("golden")
                .assertPlayerOverlayGolden("missing_baseline")
        }
    }
}
