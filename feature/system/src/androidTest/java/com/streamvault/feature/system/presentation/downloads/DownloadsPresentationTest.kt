package com.streamvault.feature.system.presentation.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.feature.system.api.SystemScaffoldContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateRendersAndFolderActionIsForwarded() {
        var folderClicks = 0

        composeRule.setContent {
            StreamVaultTheme {
                DownloadsContent(
                    uiState = DownloadsUiState(isLoading = false),
                    scaffold = testScaffold,
                    onChangeFolder = { folderClicks++ },
                    onOpen = {},
                    onResume = {},
                    onDelete = {},
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("No downloads yet").assertIsDisplayed()
        composeRule.onNodeWithText("Change download folder").performClick()

        assertThat(folderClicks).isEqualTo(1)
    }

    @Test
    fun downloadingAndFailedStatesRetainLabelsAndActions() {
        var resumedId: String? = null
        var deletedId: String? = null
        val downloading = downloadFixture("downloading").copy(
            status = DownloadStatus.DOWNLOADING,
            bytesWritten = 50,
            totalBytes = 100,
        )
        val failed = downloadFixture("failed").copy(status = DownloadStatus.FAILED)

        composeRule.setContent {
            StreamVaultTheme {
                DownloadsContent(
                    uiState = DownloadsUiState(
                        isLoading = false,
                        downloads = listOf(downloading, failed),
                    ),
                    scaffold = testScaffold,
                    onChangeFolder = {},
                    onOpen = {},
                    onResume = { resumedId = it.id },
                    onDelete = { deletedId = it.id },
                    onConfirmDelete = {},
                    onDismissDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Downloading").assertIsDisplayed()
        composeRule.onNodeWithText("50%").assertIsDisplayed()
        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("Resume").performClick()
        composeRule.onAllNodesWithText("Delete")[1].performClick()

        assertThat(resumedId).isEqualTo("failed")
        assertThat(deletedId).isEqualTo("failed")
    }

    @Test
    fun completedStateAndDeleteConfirmationRetainCallbacks() {
        var openedId: String? = null
        var confirmed = 0
        var dismissed = 0
        val completed = downloadFixture("completed").copy(status = DownloadStatus.COMPLETED)

        composeRule.setContent {
            StreamVaultTheme {
                DownloadsContent(
                    uiState = DownloadsUiState(
                        isLoading = false,
                        downloads = listOf(completed),
                        deleteConfirmItem = completed,
                    ),
                    scaffold = testScaffold,
                    onChangeFolder = {},
                    onOpen = { openedId = it.id },
                    onResume = {},
                    onDelete = {},
                    onConfirmDelete = { confirmed++ },
                    onDismissDelete = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Completed").assertIsDisplayed()
        composeRule.onNodeWithText("completed").performTouchInput { click() }
        composeRule.onNodeWithText("Delete download").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete")[1].performClick()
        assertThat(confirmed).isEqualTo(1)
        assertThat(openedId).isEqualTo("completed")

        composeRule.onNodeWithText("Delete download").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(dismissed).isEqualTo(1)
    }

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column { content() }
    }

    private fun downloadFixture(id: String) = DownloadItem(
        id = id,
        providerId = 7L,
        contentType = DownloadContentType.MOVIE,
        contentId = 42L,
        contentName = id,
        streamUrl = "https://example.test/movie.mp4",
    )
}
