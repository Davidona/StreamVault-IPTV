package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.test.assertAgainstGolden
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun browseHeroPanel_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    BrowseHeroPanel(
                        title = "Late Night Premiere",
                        subtitle = "Premium hero layout for large-screen browse surfaces.",
                        eyebrow = "Movies",
                        metadata = listOf("2026", "RTG 8.8"),
                        actionLabel = "Play",
                        onClick = {},
                        imageUrl = null
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("browse_hero_panel")
    }

    @Test
    fun liveChannelRowSurface_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    LiveChannelRowSurface(
                        channel = Channel(
                            id = 7L,
                            name = "World Sports HD",
                            streamUrl = "https://example.com/live",
                            logoUrl = null,
                            isFavorite = true,
                            catchUpSupported = true
                        ),
                        nowMs = 0L,
                        onClick = {},
                        onLongClick = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("live_channel_row_surface")
    }

    @Test
    fun appScreenScaffold_rtl_matchesGolden() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StreamVaultTheme {
                    AppScreenScaffold(
                        currentRoute = Routes.EPG,
                        onNavigate = {},
                        title = "Guide",
                        subtitle = "RTL shell validation for premium TV surfaces."
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("golden")
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("app_screen_scaffold_rtl")
    }
}
