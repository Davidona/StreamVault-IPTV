package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupResult
import com.streamvault.domain.usecase.InspectBackupResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBackupActionsTest {

    private val exportBackup: ExportBackup = mock()
    private val importBackup: ImportBackup = mock()

    @Test
    fun partialImport_retainsSourceAndPlanForCheckpointRetry() = runTest(StandardTestDispatcher()) {
        val plan = BackupImportPlan(importPreferences = true, importProviders = false)
        val uiState = MutableStateFlow(
            SettingsUiState(
                pendingBackupUri = "content://backup",
                backupImportPlan = plan
            )
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(
            ImportBackupResult.Success(
                BackupImportResult(
                    outcome = BackupRestoreOutcome.PARTIAL,
                    importedSections = listOf("Providers"),
                    failedSections = listOf("Preferences: unavailable")
                )
            )
        )
        var onSuccessCalled = false

        actions.confirmBackupImport(this, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertThat(uiState.value.pendingBackupUri).isEqualTo("content://backup")
        assertThat(uiState.value.backupImportPlan).isEqualTo(plan)
        assertThat(uiState.value.isImportingBackup).isFalse()
        assertThat(onSuccessCalled).isFalse()
    }

    @Test
    fun completeImport_clearsSourceAndRunsFollowUp() = runTest(StandardTestDispatcher()) {
        val uiState = MutableStateFlow(
            SettingsUiState(pendingBackupUri = "content://backup")
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.confirm(org.mockito.kotlin.any())).thenReturn(
            ImportBackupResult.Success(BackupImportResult(outcome = BackupRestoreOutcome.COMPLETE))
        )
        var onSuccessCalled = false

        actions.confirmBackupImport(this, onSuccess = { onSuccessCalled = true })
        advanceUntilIdle()

        assertThat(uiState.value.pendingBackupUri).isNull()
        assertThat(uiState.value.backupImportPlan).isEqualTo(BackupImportPlan())
        assertThat(onSuccessCalled).isTrue()
    }

    @Test
    fun normalInspect_clearsCredentialsPendingFromAnEarlierDrivePull() = runTest(StandardTestDispatcher()) {
        val uiState = MutableStateFlow(
            SettingsUiState(
                pendingDriveCredentials = listOf(
                    com.streamvault.domain.manager.ProviderCredentials(
                        serverUrl = "https://example.com",
                        username = "alice",
                        password = "secret"
                    )
                )
            )
        )
        val actions = SettingsBackupActions(exportBackup, importBackup, uiState)
        whenever(importBackup.inspect(org.mockito.kotlin.any())).thenReturn(
            InspectBackupResult.Success(
                uriString = "content://local-backup",
                preview = BackupPreview(
                    version = 12,
                    providerCount = 0,
                    favoriteCount = 0,
                    groupCount = 0,
                    playbackHistoryCount = 0,
                    multiViewPresetCount = 0,
                    preferenceCount = 0,
                    protectedCategoryCount = 0,
                    scheduledRecordingCount = 0,
                    providerConflicts = 0,
                    favoriteConflicts = 0,
                    groupConflicts = 0,
                    historyConflicts = 0,
                    protectedCategoryConflicts = 0,
                    recordingConflicts = 0
                )
            )
        )

        actions.inspectBackup(this, "content://local-backup")
        advanceUntilIdle()

        assertThat(uiState.value.pendingDriveCredentials).isNull()
    }
}
