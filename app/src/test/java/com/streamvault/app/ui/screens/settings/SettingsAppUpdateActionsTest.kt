package com.streamvault.app.ui.screens.settings

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.feature.settings.presentation.SettingsUiState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAppUpdateActionsTest {
    @Test
    fun `failed check records failure without replacing cached release`() = runTest(StandardTestDispatcher()) {
        val application = mock<Application>()
        val preferences = mock<PreferencesRepository>()
        val checker = mock<GitHubReleaseChecker>()
        val installer = mock<AppUpdateInstaller>()
        val uiState = MutableStateFlow(SettingsUiState())
        whenever(checker.fetchLatestRelease()).thenReturn(Result.error("HTTP 500"))

        val actions = SettingsAppUpdateActions(
            appContext = application,
            preferencesRepository = preferences,
            gitHubReleaseChecker = checker,
            appUpdateInstaller = installer,
            uiState = uiState
        )

        actions.checkForAppUpdates(
            scope = this,
            manual = false,
            isRemoteVersionNewer = { _, _, _ -> false }
        )
        advanceUntilIdle()

        assertThat(uiState.value.appUpdate.errorMessage).isEqualTo("HTTP 500")
        verify(preferences).setLastAppUpdateAttemptTimestamp(any())
        verify(preferences).setLastAppUpdateFailureTimestamp(any())
        verify(preferences).setLastAppUpdateOutcome("FAILURE: HTTP 500")
        verify(preferences, never()).setCachedAppUpdateRelease(
            versionName = anyOrNull(),
            versionCode = anyOrNull(),
            releaseUrl = anyOrNull(),
            downloadUrl = anyOrNull(),
            downloadSha256 = anyOrNull(),
            releaseNotes = anyOrNull(),
            publishedAt = anyOrNull()
        )
    }
}
