package com.streamvault.feature.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
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
        repeat(2) {
            if (!isFocused("Delete")) press(Key.DirectionRight)
        }

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

    private fun showDownloads(vararg downloads: DownloadItem) {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    DownloadsContent(
                        uiState = DownloadsUiState(isLoading = false, downloads = downloads.toList()),
                        scaffold = testScaffold,
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

    private fun focus(text: String) {
        composeRule.onNode(hasText(text) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
    }

    private fun press(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    private fun isFocused(text: String): Boolean =
        runCatching { composeRule.onNode(hasText(text) and hasClickAction()).assertIsFocused() }.isSuccess

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }

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
