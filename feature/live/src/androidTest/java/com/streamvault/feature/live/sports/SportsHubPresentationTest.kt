package com.streamvault.feature.live.sports

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.design.BrowseVisualStyle
import com.streamvault.core.ui.design.browseBackdrop
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SportsHubPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val state = mutableStateOf(SportsHubUiState(isLoading = false, events = listOf(event), nowMillis = NOW))
    private var played: SportsBroadcast? = null

    @Test fun quickFilters_exposeSelection_andCanBeActivatedWithRemote() {
        showHub()
        composeRule.onNodeWithText(label(R.string.home_quick_filters_all)).assertIsSelected()
        composeRule.onNodeWithText(label(R.string.live_sports_filter_football))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
            .assertIsSelected()
        composeRule.onNodeWithText(label(R.string.home_quick_filters_all)).assertIsNotSelected()
        capture("filters")
    }

    @Test fun search_expandsOnDemand_andClearRestoresFixtures() {
        showHub()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithText(label(R.string.live_sports_search_events)).activateWithRemote()
        composeRule.onNode(hasSetTextAction()).performTextInput("No matching fixture")
        composeRule.onNodeWithText(label(R.string.live_sports_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.epg_clear_search)).activateWithRemote()
        composeRule.onNodeWithText(label(R.string.epg_program_details_show)).assertExists()
        capture("search")
    }

    @Test fun details_prioritizeChannels_andRemoteActivationPlaysTheSelectedChannel() {
        showHub()
        capture("overview")
        composeRule.onNodeWithText(label(R.string.epg_program_details_show)).activateWithRemote()
        val channel = composeRule.onNode(hasContentDescription(playLabel(event.broadcasts.first().channel)) and hasAnyAncestor(isDialog()))
        channel.assertIsDisplayed().assertIsFocused()
        val channelBounds = channel.fetchSemanticsNode().boundsInRoot
        val standingsBounds = composeRule.onNodeWithText(label(R.string.live_sports_standings)).fetchSemanticsNode().boundsInRoot
        assertTrue("Channel choices must precede standings", channelBounds.top < standingsBounds.top)
        composeRule.onNodeWithText(label(R.string.hidden_channels_dialog_close)).assertIsDisplayed()
        capture("details")
        channel.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertEquals(event.broadcasts.first(), played) }
    }

    @Test fun inclusions_keepFooterVisible_andToggleSportsWithRemote() {
        showHub()
        composeRule.onNodeWithText(label(R.string.live_sports_inclusions)).activateWithRemote()
        composeRule.onNodeWithText(label(R.string.hidden_categories_dialog_close)).assertIsDisplayed()
        composeRule.onNode(hasText(label(R.string.live_sports_filter_football)) and hasAnyAncestor(isDialog()))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle { assertTrue("FOOTBALL" in state.value.excludedSports) }
        capture("inclusions")
    }

    private fun showHub() {
        composeRule.setContent {
            StreamVaultTheme {
                BrowseVisualStyle {
                    Box(Modifier.fillMaxSize().browseBackdrop()) {
                        SportsHubContent(
                            state = state.value,
                            onRetry = {},
                            onSearchQueryChanged = { state.value = state.value.copy(searchQuery = it) },
                            onSetSportIncluded = { sport, included ->
                                state.value = state.value.copy(excludedSports = if (included) state.value.excludedSports - sport.discoveryKey
                                    else state.value.excludedSports + sport.discoveryKey)
                            },
                            onSetLeagueIncluded = { _, _ -> }, onResetInclusions = {},
                            onSelectTimeRange = { state.value = state.value.copy(selectedTimeRange = it) },
                            onSelectSportFilter = { state.value = state.value.copy(selectedSportFilter = it) },
                            onSelectLeagueFilter = { state.value = state.value.copy(selectedLeagueFilter = it) },
                            onSearchChannels = { _, _ -> emptyList() },
                            onRememberChannelForEvent = { _, _ -> true }, onForgetChannelForEvent = {},
                            onLoadStandings = { null }, onLoadExtraDetails = { null },
                            onPlayBroadcast = { played = it },
                        )
                    }
                }
            }
        }
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun SemanticsNodeInteraction.activateWithRemote() =
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
    private fun playLabel(channel: Channel) = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.live_sports_play_channel_content_description, channel.name)

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("recordSportsReview") != "true") return
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.externalCacheDir, "sports-$name.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /sdcard/Download/streamvault-sports-$name.png",
        )).use { it.readBytes() }
    }

    companion object {
        private const val NOW = 1_792_344_600_000L
        private val event = SportsEvent(
            id = "fixture", title = "Liverpool vs Manchester City", league = "Premier League", categoryLabel = null,
            description = "Match coverage\\r\\nPremier League", sport = SportType.FOOTBALL,
            startTime = NOW - 1_800_000L, endTime = NOW + 5_400_000L,
            broadcasts = listOf("Sports One HD", "Sports Two HD").mapIndexed { index, name ->
                SportsBroadcast(Channel(id = index.toLong(), name = name, providerId = 1L), combinedProfileId = null)
            },
            confidence = 1f, competitors = listOf(SportsCompetitor("Liverpool"), SportsCompetitor("Manchester City")),
            session = null, eventType = SportsEventType.LIVE_EVENT, rawProgrammeRefs = emptyList(),
        )
    }
}
