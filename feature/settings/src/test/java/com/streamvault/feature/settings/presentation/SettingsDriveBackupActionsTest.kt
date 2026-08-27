package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveAccount
import com.streamvault.domain.manager.DriveBackupArtifact
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.DriveSyncStatus
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.InspectBackupResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDriveBackupActionsTest {

    private val driveManager: DriveBackupSyncManager = mock()
    private val importBackup: ImportBackup = mock()
    private val providerRepository: ProviderRepository = mock()

    @Test
    fun pushBackup_passesOneCredentialSnapshotAndDoesNotUseLegacySecondUpload() =
        runTest(StandardTestDispatcher()) {
            val credentials = listOf(
                ProviderCredentials("https://example.com", "alice", "secret")
            )
            whenever(providerRepository.getAllProviderCredentials()).thenReturn(credentials)
            whenever(driveManager.pushBackup(eq(credentials))).thenReturn(
                Result.Success(DriveSyncStatus(lastPushAtMs = 123L))
            )
            val uiState = MutableStateFlow(
                SettingsUiState(driveAuthState = DriveAuthState.SignedIn(DriveAccount(null, null)))
            )
            val actions = SettingsDriveBackupActions(
                driveManager = driveManager,
                importBackup = importBackup,
                providerRepository = providerRepository,
                uiState = uiState,
            )

            actions.pushBackup(this)
            advanceUntilIdle()

            verify(driveManager).pushBackup(eq(credentials))
            verify(driveManager, never()).pushCredentials(any())
            assertThat(uiState.value.userMessage).isEqualTo("Backup uploaded to Google Drive")
            assertThat(uiState.value.driveIsBusy).isFalse()
        }

    @Test
    fun pullBackup_usesBundleCredentialsWithoutFetchingLegacyCompanionFile() =
        runTest(StandardTestDispatcher()) {
            val credentials = listOf(
                ProviderCredentials("https://example.com", "alice", "secret")
            )
            whenever(driveManager.listBackups()).thenReturn(
                Result.Success(listOf(DriveBackupSnapshot("bundle-id", "backup.json")))
            )
            whenever(driveManager.pullBackup(eq("bundle-id"))).thenReturn(
                Result.Success(
                    DriveBackupArtifact(
                        localUriString = "file:///cache/bundle-backup.json",
                        sizeBytes = 256L,
                        credentials = credentials,
                    )
                )
            )
            whenever(importBackup.inspect(any())).thenReturn(
                InspectBackupResult.Success(
                    uriString = "file:///cache/bundle-backup.json",
                    preview = emptyPreview(),
                )
            )
            val uiState = MutableStateFlow(
                SettingsUiState(driveAuthState = DriveAuthState.SignedIn(DriveAccount(null, null)))
            )
            val actions = SettingsDriveBackupActions(
                driveManager = driveManager,
                importBackup = importBackup,
                providerRepository = providerRepository,
                uiState = uiState,
            )

            actions.pullBackup(this)
            advanceUntilIdle()

            verify(driveManager).listBackups()
            verify(driveManager).pullBackup(eq("bundle-id"))
            verify(driveManager, never()).pullCredentials()
            assertThat(uiState.value.pendingDriveCredentials).isEqualTo(credentials)
            assertThat(uiState.value.pendingBackupUri).isEqualTo("file:///cache/bundle-backup.json")
        }

    @Test
    fun busyDriveOperation_isIgnored() = runTest(StandardTestDispatcher()) {
        val uiState = MutableStateFlow(
            SettingsUiState(
                driveAuthState = DriveAuthState.SignedIn(DriveAccount(null, null)),
                driveIsBusy = true,
            )
        )
        val actions = SettingsDriveBackupActions(
            driveManager = driveManager,
            importBackup = importBackup,
            providerRepository = providerRepository,
            uiState = uiState,
        )

        actions.pushBackup(this)
        actions.pullBackup(this)
        advanceUntilIdle()

        verify(driveManager, never()).pushBackup(any())
        verify(driveManager, never()).listBackups()
        verify(driveManager, never()).pullBackup(any())
        assertThat(uiState.value.driveIsBusy).isTrue()
    }

    @Test
    fun pullBackup_withMultipleSnapshots_waitsForUserSelection() =
        runTest(StandardTestDispatcher()) {
            val snapshots = listOf(
                DriveBackupSnapshot("new", "streamvault_backup_bundle_new.json"),
                DriveBackupSnapshot("old", "streamvault_backup_bundle_old.json"),
            )
            whenever(driveManager.listBackups()).thenReturn(Result.Success(snapshots))
            val uiState = MutableStateFlow(
                SettingsUiState(driveAuthState = DriveAuthState.SignedIn(DriveAccount(null, null)))
            )
            val actions = SettingsDriveBackupActions(
                driveManager = driveManager,
                importBackup = importBackup,
                providerRepository = providerRepository,
                uiState = uiState,
            )

            actions.pullBackup(this)
            advanceUntilIdle()

            assertThat(uiState.value.driveBackupOptions).isEqualTo(snapshots)
            assertThat(uiState.value.driveIsBusy).isFalse()
            verify(driveManager, never()).pullBackup(any())
        }

    @Test
    fun manageBackups_listsSnapshotsAndRefreshesAfterDelete() =
        runTest(StandardTestDispatcher()) {
            val snapshot = DriveBackupSnapshot("snapshot-id", "streamvault_backup_bundle_test.json")
            whenever(driveManager.listBackups()).thenReturn(
                Result.Success(listOf(snapshot)),
                Result.Success(emptyList()),
            )
            whenever(driveManager.deleteBackup(eq(snapshot.id))).thenReturn(Result.Success(Unit))
            val uiState = MutableStateFlow(
                SettingsUiState(driveAuthState = DriveAuthState.SignedIn(DriveAccount(null, null)))
            )
            val actions = SettingsDriveBackupActions(
                driveManager = driveManager,
                importBackup = importBackup,
                providerRepository = providerRepository,
                uiState = uiState,
            )

            actions.manageBackups(this)
            advanceUntilIdle()
            assertThat(uiState.value.driveBackupManagementOptions).containsExactly(snapshot)

            actions.deleteBackup(this, snapshot.id)
            advanceUntilIdle()

            verify(driveManager).deleteBackup(eq(snapshot.id))
            assertThat(uiState.value.driveBackupManagementOptions).isEmpty()
            assertThat(uiState.value.userMessage).isEqualTo("Drive backup deleted")
        }

    private fun emptyPreview() = BackupPreview(
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
        recordingConflicts = 0,
    )
}
