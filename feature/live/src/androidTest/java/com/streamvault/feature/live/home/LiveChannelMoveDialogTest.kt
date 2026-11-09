package com.streamvault.feature.live.home

import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.core.ui.components.dialogs.AppDialog
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import com.streamvault.feature.live.presentation.dialogs.LiveChannelMoveDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.tv.material3.Text
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveCategoryOrder
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.SaveLiveCategoryOrderResult
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.repository.*
import com.streamvault.domain.manager.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.UnlockParentalCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LiveChannelMoveDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun categoryEditorKeepsPinSectionAndSavesRemoteCategoryOrder() {
        val rows = listOf(Category(1, name = "News", providerOrder = 1), Category(2, name = "Sports", providerOrder = 2),
            Category(3, name = "Kids", providerOrder = 3))
        val stored = MutableStateFlow(LiveCategoryOrder(pinnedCategoryIds = setOf(2)))
        val preferences = stub(SettingsPreferences::class.java) { method, args ->
            when (method) {
                "getLiveCategoryOrder" -> stored
                "getHiddenCategoryIds" -> flowOf(emptySet<Long>())
                "saveLiveCategoryOrder" -> {
                    stored.value = (args!![2] as LiveCategoryOrder).copy(revision = 1)
                    SaveLiveCategoryOrderResult.Saved(stored.value)
                }
                else -> flowOf(true)
            }
        }
        val custom = GetCustomCategories(stub(FavoriteRepository::class.java) { _, _ -> flowOf(emptyList<Any>()) },
            stub(ChannelRepository::class.java) { _, _ -> flowOf(emptyList<Channel>()) })
        val model = ProviderCategoryOrderViewModel(
            stub(CategoryRepository::class.java) { _, _ -> flowOf(rows) },
            stub(CombinedM3uRepository::class.java) { _, _ -> flowOf(emptyList<CombinedCategory>()) },
            preferences,
            custom
        )
        var dismissed = false
        compose.setContent { StreamVaultTheme { ProviderCategoryOrderEditor(7, onDismiss = { dismissed = true }, viewModel = model) } }
        val button = { text: String -> compose.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)) }
        compose.waitUntil(10_000) { model.uiState.value.drafts.isNotEmpty() }
        button(context.getString(R.string.live_category_order_pinned)).activateWithRemote()
        compose.onNodeWithText("Sports").assertIsDisplayed()
        button(context.getString(R.string.live_category_order_other)).activateWithRemote()
        capture("streamvault-category-order-editor.png")
        compose.onNodeWithText("News").activateWithRemote()
        compose.onNodeWithText("News").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        button(context.getString(R.string.action_save_order)).activateWithRemote()
        compose.waitUntil(10_000) { dismissed }
        compose.runOnIdle {
            assertThat(stored.value.sortMode).isEqualTo(CategorySortMode.MANUAL)
            assertThat(stored.value.normalIds).containsExactly(3L, 2L, 1L).inOrder()
            assertThat(stored.value.pinnedCategoryIds).containsExactly(2L)
        }
    }

    @Test fun sharedEditorSupportsRemotePickupUndoRedoAndSave() {
        val category = Category(id = 12, name = "Sports", type = ContentType.LIVE)
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val rows = (1..500).map { Channel(id = it.toLong(), providerId = 7, streamId = it.toLong(), name = "Channel $it") }
        val stored = MutableStateFlow(LiveChannelOrder())
        val parental = ParentalControlManager(object : ParentalControlSessionStore {
            override fun readSessionState() = ParentalControlSessionState()
            override fun writeSessionState(state: ParentalControlSessionState) = Unit
        })
        val model = ProviderChannelOrderViewModel(
            stub(ProviderRepository::class.java) { _, _ -> provider },
            stub(CategoryRepository::class.java) { _, _ -> flowOf(listOf(category)) },
            stub(ChannelRepository::class.java) { _, _ -> flowOf(rows) },
            stub(FavoriteRepository::class.java) { _, _ -> flowOf(emptyList<Favorite>()) },
            stub(LiveChannelOrderRepository::class.java) { method, args ->
                if (method == "observe") stored else {
                    stored.value = (args!![2] as LiveChannelOrder).copy(revision = 1)
                    LiveChannelOrderSaveResult.Saved(stored.value)
                }
            },
            stub(SettingsPreferences::class.java) { method, _ ->
                if (method == "getDefaultLiveChannelOrder") flowOf(com.streamvault.domain.model.LiveChannelOrderMode.PROVIDER) else flowOf(0)
            }, parental,
            UnlockParentalCategory(object : ParentalPinVerifier { override suspend fun verifyParentalPin(pin: String) = false }, parental)
        )
        var dismissed = false
        compose.setContent {
            StreamVaultTheme { ProviderChannelOrderEditor(7, category, onDismiss = { dismissed = true }, viewModel = model) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Channel 1").fetchSemanticsNodes().isNotEmpty() }
        capture("streamvault-ordering-editor.png")
        compose.onNodeWithText("Channel 1").activateWithRemote()
        compose.onNodeWithText("Channel 1").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertThat(model.uiState.value.channels.first().id).isEqualTo(2L) }
        compose.onNodeWithText(context.getString(R.string.live_order_undo)).activateWithRemote()
        compose.runOnIdle { assertThat(model.uiState.value.channels.first().id).isEqualTo(1L) }
        compose.onNodeWithText(context.getString(R.string.live_order_redo)).activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.action_save_order)).activateWithRemote()
        compose.waitUntil(10_000) { dismissed }
        compose.runOnIdle { assertThat(stored.value.manualKeys.take(2)).containsExactly("stream:2", "stream:1").inOrder() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, invoke: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method.name, args) } as T

    @Test fun modePreviewIsTransactionalAndCancelKeepsTheUnsavedDraft() {
        val category = Category(12, name = "Sports")
        val provider = LegacyProvider(7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val rows = listOf(Channel(1, "Alpha", providerId = 7, streamId = 1),
            Channel(2, "Zulu", providerId = 7, streamId = 2), Channel(3, "Middle", providerId = 7, streamId = 3))
        val stored = MutableStateFlow(LiveChannelOrder(com.streamvault.domain.model.LiveChannelOrderMode.MANUAL,
            listOf("stream:2", "stream:1", "stream:3"), 3))
        val initial = stored.value
        val parental = ParentalControlManager(object : ParentalControlSessionStore {
            override fun readSessionState() = ParentalControlSessionState()
            override fun writeSessionState(state: ParentalControlSessionState) = Unit
        })
        val model = ProviderChannelOrderViewModel(
            stub(ProviderRepository::class.java) { _, _ -> provider },
            stub(CategoryRepository::class.java) { _, _ -> flowOf(listOf(category)) },
            stub(ChannelRepository::class.java) { _, _ -> flowOf(rows) },
            stub(FavoriteRepository::class.java) { _, _ -> flowOf(emptyList<Favorite>()) },
            stub(LiveChannelOrderRepository::class.java) { method, args ->
                if (method == "observe") stored else {
                    stored.value = (args!![2] as LiveChannelOrder).copy(revision = 4)
                    LiveChannelOrderSaveResult.Saved(stored.value)
                }
            },
            stub(SettingsPreferences::class.java) { method, _ ->
                if (method == "getDefaultLiveChannelOrder") flowOf(com.streamvault.domain.model.LiveChannelOrderMode.PROVIDER) else flowOf(0)
            }, parental, UnlockParentalCategory(object : ParentalPinVerifier {
                override suspend fun verifyParentalPin(pin: String) = false
            }, parental)
        )
        var dismissed = false
        compose.setContent { StreamVaultTheme { ProviderChannelOrderEditor(7, category, onDismiss = { dismissed = true }, viewModel = model) } }
        compose.waitUntil(10_000) { model.uiState.value.draft != null }
        val buttons = { text: String -> compose.onAllNodes(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)) }
        buttons(context.getString(R.string.live_order_mode)).onFirst().activateWithRemote()
        buttons(context.getString(R.string.live_order_title_asc)).onLast().activateWithRemote()
        compose.runOnIdle {
            assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
            assertThat(stored.value).isEqualTo(initial)
        }
        buttons(context.getString(R.string.action_cancel)).onFirst().activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.live_order_discard_title)).assertIsDisplayed()
        buttons(context.getString(R.string.action_cancel)).onLast().activateWithRemote()
        capture("streamvault-ordering-mode-preview.png")
        buttons(context.getString(R.string.action_save_order)).onFirst().activateWithRemote()
        compose.waitUntil(10_000) { dismissed }
        compose.runOnIdle {
            assertThat(stored.value.mode).isEqualTo(com.streamvault.domain.model.LiveChannelOrderMode.TITLE_ASC)
            assertThat(stored.value.manualKeys).isEqualTo(initial.manualKeys)
        }
    }

    @Test fun searchFindsLateChannelAndMoveUsesFullListPosition() {
        val channels = (1..500).map { Channel(id = it.toLong(), name = "Channel $it") }
        var moved: Pair<Long, Int>? = null
        compose.setContent {
            StreamVaultTheme {
                LiveChannelMoveDialog(channels, onMove = { channel, position -> moved = channel.id to position }, onDismiss = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.home_search_channels)).onParent().activateWithRemote()
        capture("streamvault-ordering-activated.png")
        compose.onNode(hasSetTextAction()).performTextInput("Channel 500")
        compose.waitForIdle()
        compose.waitForIdle()
        compose.onNode(hasText("Channel 500") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).activateWithRemote()
        capture("streamvault-ordering-selected.png")
        compose.onNodeWithText(context.getString(R.string.live_order_position, 500)).assertIsDisplayed()
        capture("streamvault-ordering-position.png")
        compose.onNodeWithText(context.getString(R.string.live_order_top)).activateWithRemote()
        compose.runOnIdle { assertThat(moved).isEqualTo(500L to 1) }
    }

    @Test fun directPositionValidatesAgainstWholeCategory() {
        val channels = (1..500).map { Channel(id = it.toLong(), name = "Channel $it") }
        var moved: Pair<Long, Int>? = null
        compose.setContent {
            StreamVaultTheme {
                LiveChannelMoveDialog(channels, onMove = { channel, position -> moved = channel.id to position }, onDismiss = {})
            }
        }
        capture("streamvault-ordering-search.png")
        compose.onNodeWithText("Channel 1", substring = false).activateWithRemote()
        capture("streamvault-ordering-selected.png")
        compose.onNodeWithText(context.getString(R.string.live_order_position, 500)).onParent().activateWithRemote()
        compose.onNode(hasSetTextAction()).performTextReplacement("501")
        compose.onNodeWithText(context.getString(R.string.live_order_move), substring = false).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("500")
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.live_order_move), substring = false).activateWithRemote()
        compose.runOnIdle { assertThat(moved).isEqualTo(1L to 500) }
    }

    @Test fun bulkSelectionSurvivesSearchAndMovesAfterUnselectedDestination() {
        val channels = (1..2000).map { Channel(id = it.toLong(), name = "Channel $it") }
        var moved: Pair<List<Long>, Int>? = null
        compose.setContent {
            StreamVaultTheme {
                LiveChannelMoveDialog(channels, onMove = { _, _ -> error("Expected bulk move") }, onDismiss = {},
                    onMoveMany = { selected, position -> moved = selected.map { it.id } to position })
            }
        }
        val button = { text: String -> compose.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)) }
        button(context.getString(R.string.live_order_select_multiple)).activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.home_search_channels)).onParent().activateWithRemote()
        compose.onNode(hasSetTextAction()).performTextReplacement("Channel 2000")
        button("Channel 2000").activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.home_search_channels)).onParent().activateWithRemote()
        compose.onNode(hasSetTextAction()).performTextReplacement("Channel 1500")
        button("Channel 1500").activateWithRemote()
        button(context.getString(R.string.live_order_move_selected, 2)).activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.live_order_position, 1999)).assertExists()
        button(context.getString(R.string.live_order_after)).activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.home_search_channels)).onParent().activateWithRemote()
        compose.onNode(hasSetTextAction()).performTextReplacement("Channel 1600")
        capture("streamvault-ordering-bulk-destination.png")
        button("Channel 1600").activateWithRemote()
        compose.runOnIdle { assertThat(moved).isEqualTo(listOf(1500L, 2000L) to 1600) }
    }

    @Test fun rtlMoveDialogKeepsRemoteMoveActionsUsable() {
        val channels = listOf(
            Channel(id = 1, name = "قناة الأخبار"),
            Channel(id = 2, name = "قناة الرياضة")
        )
        var moved: Pair<Long, Int>? = null
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StreamVaultTheme {
                    LiveChannelMoveDialog(channels, onMove = { channel, position -> moved = channel.id to position }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText("قناة الرياضة").assertIsDisplayed()
        capture("streamvault-ordering-rtl.png")
        compose.onNodeWithText("قناة الرياضة").activateWithRemote()
        compose.onNodeWithText(context.getString(R.string.live_order_top)).activateWithRemote()
        compose.runOnIdle { assertThat(moved).isEqualTo(2L to 1) }
    }

    @Test fun appDialogPreservesTheParentLayoutDirection() {
        var dialogDirection = LayoutDirection.Ltr
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppDialog(onDismissRequest = {}) {
                    val currentDirection = LocalLayoutDirection.current
                    SideEffect { dialogDirection = currentDirection }
                    Text("RTL direction probe")
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(dialogDirection).isEqualTo(LayoutDirection.Rtl) }
    }

    private fun SemanticsNodeInteraction.activateWithRemote() {
        performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }
}
