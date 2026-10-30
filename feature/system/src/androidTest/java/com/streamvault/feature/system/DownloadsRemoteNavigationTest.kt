package com.streamvault.feature.system

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.TorrentCacheStatus
import com.streamvault.feature.system.api.SystemScaffoldContent
import com.streamvault.feature.system.presentation.downloads.DownloadsContent
import com.streamvault.feature.system.presentation.downloads.DownloadsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Downloads screen driven with a TV remote: every action on a download must be reachable with the D-pad. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DownloadsRemoteNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<DownloadItem>()
    private val deleted = mutableListOf<DownloadItem>()

    @Test
    fun deleteOnCompletedDownloadIsReachableWithDpad() {
        showDownloads(completedDownload())

        focus("Change download folder")
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        if (!isFocused("Delete")) press(Key.DirectionRight)

        composeRule.onNode(hasText("Delete") and hasClickAction()).assertIsFocused()
        press(Key.Enter)
        assertThat(deleted.map { it.id }).containsExactly("completed")
        assertThat(opened).isEmpty()
    }

    @Test
    fun playOnCompletedDownloadOpensIt() {
        showDownloads(completedDownload())

        focus("Play")
        press(Key.Enter)

        assertThat(opened.map { it.id }).containsExactly("completed")
        assertThat(deleted).isEmpty()
    }

    @Test
    fun focusedDeleteDialogButtonLooksDifferentFromUnfocused() {
        showDownloadsWithDialog(completedDownload())
        openDeleteDialog()

        val confirm = composeRule.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog()))
        val unfocused = confirm.captureToImage().asAndroidBitmap()
        press(Key.DirectionRight)
        confirm.assertIsFocused()
        val focused = confirm.captureToImage().asAndroidBitmap()

        val share = clearlyDifferentPixelShare(unfocused, focused)
        android.util.Log.i("PROBE", "clearly different pixel share = $share")
        assertThat(share).isGreaterThan(0.05)
    }

    @Test
    fun cancellingDeleteReturnsFocusToTheDeleteButton() {
        showDownloadsWithDialog(completedDownload())
        openDeleteDialog()

        press(Key.Enter) // focus starts on Cancel
        composeRule.waitUntil(2_000) { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }

        composeRule.onNode(hasText("Delete") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun confirmingDeleteKeepsFocusOnTheDownloadsScreen() {
        showDownloadsWithDialog(completedDownload())
        openDeleteDialog()

        press(Key.DirectionRight)
        press(Key.Enter)
        composeRule.waitUntil(2_000) { downloads.isEmpty() }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Change download folder") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun rightFromACompletedCardReachesTheNextCardsButtons() {
        showDownloads(completedDownload(), failedDownload())

        focus("Delete", index = 0)
        press(Key.DirectionRight)

        composeRule.onNode(hasText("Resume") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun buttonsOfCardsInARowLineUp() {
        showDownloads(completedDownload(), failedDownload())

        val deletes = composeRule.onAllNodes(hasText("Delete") and hasClickAction()).fetchSemanticsNodes()

        assertThat(deletes.map { it.boundsInRoot.top }.distinct()).hasSize(1)
    }

    @Test
    fun downReachesTheNextRowOfCards() {
        showDownloads(*Array(4) { completedDownload().copy(id = "completed-$it", contentName = "Movie $it") })

        focus("Delete", index = 0)
        press(Key.DirectionDown)

        val focused = composeRule.onAllNodes(hasText("Delete") and hasClickAction()).fetchSemanticsNodes()
            .indexOfFirst { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } }
        assertThat(focused).isEqualTo(3)
    }

    @Test
    fun downFromClearCacheReachesACard() {
        showDownloads(completedDownload(), failedDownload(), cacheStatus = TorrentCacheStatus(0, 8_000_000_000, 20_000_000_000, 0))

        focus("Clear cache")
        press(Key.DirectionDown)

        composeRule.onNode(hasText("Delete") and hasClickAction() and isFocused()).assertExists()
    }

    @Test
    fun clearCacheKeepsFocusWhileClearing() {
        var clearing by mutableStateOf(false)
        var clears = 0
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    DownloadsContent(
                        uiState = DownloadsUiState(
                            isLoading = false,
                            torrentCacheStatus = TorrentCacheStatus(0, 8_000_000_000, 20_000_000_000, 0),
                            isClearingTorrentCache = clearing
                        ),
                        scaffold = scaffoldWithMenu,
                        onChangeFolder = {},
                        onOpen = {},
                        onResume = {},
                        onDelete = {},
                        onConfirmDelete = {},
                        onDismissDelete = {},
                        onClearTorrentCache = { clears++ },
                    )
                }
            }
        }
        focus("Clear cache")

        clearing = true
        composeRule.waitForIdle()
        press(Key.Enter) // ignored while clearing
        clearing = false
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Clear cache") and hasClickAction()).assertIsFocused()
        assertThat(clears).isEqualTo(0)
    }

    private var downloads by mutableStateOf(emptyList<DownloadItem>())
    private var confirmItem by mutableStateOf<DownloadItem?>(null)

    /** Like the screen with its view model: Delete opens the dialog, confirming removes the download. */
    private fun showDownloadsWithDialog(vararg items: DownloadItem) {
        downloads = items.toList()
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    DownloadsContent(
                        uiState = DownloadsUiState(isLoading = false, downloads = downloads, deleteConfirmItem = confirmItem),
                        scaffold = scaffoldWithMenu,
                        onChangeFolder = {},
                        onOpen = { opened += it },
                        onResume = {},
                        onDelete = { confirmItem = it },
                        onConfirmDelete = {
                            val item = confirmItem
                            confirmItem = null
                            downloads = downloads - listOfNotNull(item).toSet()
                        },
                        onDismissDelete = { confirmItem = null },
                    )
                }
            }
        }
    }

    private fun openDeleteDialog() {
        focus("Delete")
        press(Key.Enter)
        composeRule.waitUntil(2_000) { composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        // The dialog ignores presses for a moment after opening, so the OK that opened it can't confirm it.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    /** Share of pixels whose colour changed visibly (some channel by more than 40 of 255), not just a faint tint. */
    private fun clearlyDifferentPixelShare(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        var different = 0
        for (x in 0 until w) for (y in 0 until h) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val delta = maxOf(
                kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                kotlin.math.abs((p and 0xFF) - (q and 0xFF)),
            )
            if (delta > 40) different++
        }
        return different.toDouble() / (w * h)
    }

    private fun showDownloads(vararg downloads: DownloadItem, cacheStatus: TorrentCacheStatus? = null) {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    DownloadsContent(
                        uiState = DownloadsUiState(
                            isLoading = false,
                            downloads = downloads.toList(),
                            torrentCacheStatus = cacheStatus
                        ),
                        scaffold = scaffoldWithMenu,
                        onChangeFolder = {},
                        onOpen = { opened += it },
                        onResume = {},
                        onDelete = { deleted += it },
                        onConfirmDelete = {},
                        onDismissDelete = {},
                    )
                }
            }
        }
    }

    private fun focus(text: String, index: Int? = null) {
        val matcher = hasText(text) and hasClickAction()
        val target = if (index == null) composeRule.onNode(matcher) else composeRule.onAllNodes(matcher)[index]
        if (runCatching { target.assertIsDisplayed() }.isFailure) target.performScrollTo()
        // Exit Android touch mode as a physical TV remote does before requesting focus.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        target.assertIsFocused()
    }

    private fun press(key: Key) {
        val dialogs = composeRule.onAllNodes(isDialog()).fetchSemanticsNodes()
        val target = if (dialogs.isNotEmpty()) composeRule.onNode(isDialog()) else composeRule.onRoot()
        target.performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    private fun isFocused(text: String): Boolean =
        runCatching { composeRule.onNode(hasText(text) and hasClickAction()).assertIsFocused() }.isSuccess

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }

    /** Like the app: a top menu above the screen, where lost focus ends up. */
    private val scaffoldWithMenu: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text("Menu Home") }
            content()
        }
    }

    private fun failedDownload() = DownloadItem(
        id = "failed",
        providerId = 7L,
        contentType = DownloadContentType.MOVIE,
        contentId = 43L,
        contentName = "Always Fails",
        streamUrl = "https://example.test/failed.mp4",
        status = DownloadStatus.FAILED,
        failureReason = "HTTP 503",
    )

    private fun completedDownload() = DownloadItem(
        id = "completed",
        providerId = 7L,
        contentType = DownloadContentType.MOVIE,
        contentId = 42L,
        contentName = "The Night Shift",
        streamUrl = "https://example.test/movie.mp4",
        outputUri = "content://example.test/movie.mp4",
        outputDisplayPath = "/storage/emulated/0/Download/The Night Shift.mp4",
        status = DownloadStatus.COMPLETED,
        bytesWritten = 123_456_789L,
        totalBytes = 123_456_789L,
        completedAt = 1L,
    )
}
