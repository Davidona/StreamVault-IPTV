package com.streamvault.feature.settings.presentation

import androidx.activity.result.ActivityResultLauncher
import android.content.Intent
import android.util.Log
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSyncManager
import com.streamvault.domain.manager.DriveSyncStatus
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.InspectBackupCommand
import com.streamvault.domain.usecase.InspectBackupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drive backup orchestration helper. Mirrors the [SettingsBackupActions] pattern:
 * internal class, [MutableStateFlow] uiState injected, methods take the caller
 * [CoroutineScope] and run their body in `scope.launch { uiState.update { ... } }`.
 *
 * Pull deliberately goes through the [ImportBackup.inspect] usecase rather than
 * calling [com.streamvault.domain.manager.BackupManager.inspectBackup] directly,
 * so the existing David SAF preview dialog is reused unchanged (decision D2).
 */
class SettingsDriveBackupActions(
    private val driveManager: DriveBackupSyncManager,
    private val importBackup: ImportBackup,
    private val providerRepository: ProviderRepository,
    private val uiState: MutableStateFlow<SettingsUiState>
) {

    /**
     * Collects [DriveBackupSyncManager.authState] into [SettingsUiState.driveAuthState].
     * Call once from the owning ViewModel `init` block.
     */
    fun observeAuthState(scope: CoroutineScope) {
        scope.launch {
            driveManager.authState.collect { state ->
                uiState.update { it.copy(driveAuthState = state) }
            }
        }
        scope.launch {
            driveManager.syncStatus.collect { status ->
                uiState.update {
                    it.copy(
                        driveSyncStatus = status,
                        driveLastPushAt = status.lastPushAtMs ?: it.driveLastPushAt,
                        driveLastPullAt = status.lastPullAtMs ?: it.driveLastPullAt
                    )
                }
            }
        }
    }

    /**
     * Requests a Sign-In intent from the manager then forwards it to the
     * Activity-bound [launcher]. The result is sent back via [completeSignIn].
     */
    fun beginSignIn(scope: CoroutineScope, launcher: ActivityResultLauncher<Intent>) {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true) }
            val result = driveManager.beginSignIn()
            uiState.update { state ->
                when (result) {
                    is Result.Success -> {
                        val intent = result.data.intent as? Intent
                        if (intent != null) {
                            runCatching { launcher.launch(intent) }
                                .onFailure {
                                    return@update state.copy(
                                        driveIsBusy = false,
                                        userMessage = "Drive sign-in failed: ${it.message ?: "unable to launch"}"
                                    )
                                }
                            state.copy(driveIsBusy = false, drivePendingSignIn = result.data)
                        } else {
                            state.copy(
                                driveIsBusy = false,
                                userMessage = "Drive sign-in failed: missing intent"
                            )
                        }
                    }
                    is Result.Error -> state.copy(
                        driveIsBusy = false,
                        userMessage = "Drive sign-in failed: ${result.message}"
                    )
                    is Result.Loading -> state.copy(driveIsBusy = false)
                }
            }
        }
    }

    fun completeSignIn(scope: CoroutineScope, intentData: Intent?) {
        scope.launch {
            uiState.update { it.copy(driveIsBusy = true) }
            val result = driveManager.completeSignIn(intentData)
            uiState.update { state ->
                when (result) {
                    is Result.Success -> state.copy(
                        driveIsBusy = false,
                        drivePendingSignIn = null,
                        userMessage = result.data.email?.let { "Signed in as $it" }
                            ?: "Signed in to Google Drive"
                    )
                    is Result.Error -> state.copy(
                        driveIsBusy = false,
                        drivePendingSignIn = null,
                        userMessage = "Drive sign-in failed: ${result.message}"
                    )
                    is Result.Loading -> state.copy(driveIsBusy = false)
                }
            }
        }
    }

    fun signOut(scope: CoroutineScope) {
        scope.launch {
            uiState.update {
                it.copy(
                    driveIsBusy = true,
                    driveBackupOptions = emptyList(),
                    driveBackupManagementOptions = emptyList(),
                    pendingDriveCredentials = null,
                )
            }
            val result = driveManager.signOut()
            uiState.update { state ->
                when (result) {
                    is Result.Success -> state.copy(
                        driveIsBusy = false,
                        driveSyncStatus = DriveSyncStatus(),
                        driveLastPushAt = null,
                        driveLastPullAt = null,
                        driveBackupOptions = emptyList(),
                        driveBackupManagementOptions = emptyList(),
                        pendingDriveCredentials = null,
                        userMessage = "Signed out of Google Drive"
                    )
                    is Result.Error -> state.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        driveBackupManagementOptions = emptyList(),
                        pendingDriveCredentials = null,
                        userMessage = "Drive sign-out failed: ${result.message}"
                    )
                    is Result.Loading -> state.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        driveBackupManagementOptions = emptyList(),
                        pendingDriveCredentials = null,
                    )
                }
            }
        }
    }

    fun pushBackup(scope: CoroutineScope) {
        scope.launch {
            if (uiState.value.driveAuthState !is DriveAuthState.SignedIn) {
                uiState.update { it.copy(userMessage = "Sign in to Google Drive first") }
                return@launch
            }
            var started = false
            uiState.update {
                if (it.driveIsBusy) {
                    it
                } else {
                    started = true
                    it.copy(
                        driveIsBusy = true,
                        driveBackupManagementOptions = emptyList(),
                        pendingDriveCredentials = null,
                    )
                }
            }
            if (!started) return@launch
            // Read credentials before starting the export so the Drive manager
            // can commit one matching backup+credentials bundle atomically.
            val credentials = runCatching {
                providerRepository.getAllProviderCredentials()
            }.getOrElse { error ->
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        userMessage = "Drive push failed: ${error.message ?: "credentials unavailable"}"
                    )
                }
                return@launch
            }
            val backupResult = driveManager.pushBackup(credentials)
            if (backupResult is Result.Error) {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        userMessage = "Drive push failed: ${backupResult.message}"
                    )
                }
                return@launch
            }
            uiState.update {
                it.copy(
                    driveIsBusy = false,
                    userMessage = "Backup uploaded to Google Drive"
                )
            }
        }
    }

    fun pullBackup(scope: CoroutineScope) {
        scope.launch {
            if (uiState.value.driveAuthState !is DriveAuthState.SignedIn) {
                uiState.update { it.copy(userMessage = "Sign in to Google Drive first") }
                return@launch
            }
            var started = false
            uiState.update {
                if (it.driveIsBusy) {
                    it
                } else {
                    started = true
                    it.copy(
                        driveIsBusy = true,
                        driveBackupOptions = emptyList(),
                        pendingDriveCredentials = null,
                    )
                }
            }
            if (!started) return@launch
            val listResult = driveManager.listBackups()
            if (listResult !is Result.Success) {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        pendingDriveCredentials = null,
                        userMessage = "Drive pull failed: ${resultMessage(listResult)}"
                    )
                }
                return@launch
            }
            val snapshots = listResult.data
            if (snapshots.isEmpty()) {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        pendingDriveCredentials = null,
                        userMessage = "Drive pull failed: no backups found"
                    )
                }
                return@launch
            }
            if (snapshots.size > 1) {
                uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        driveBackupOptions = snapshots,
                        pendingDriveCredentials = null,
                    )
                }
                return@launch
            }
            pullSelectedBackup(snapshots.single().id)
        }
    }

    fun selectBackup(scope: CoroutineScope, snapshotId: String) {
        scope.launch {
            var started = false
            uiState.update {
                if (it.driveIsBusy) {
                    it
                } else {
                    started = true
                    it.copy(
                        driveIsBusy = true,
                        driveBackupOptions = emptyList(),
                        pendingDriveCredentials = null,
                    )
                }
            }
            if (!started) return@launch
            pullSelectedBackup(snapshotId)
        }
    }

    fun dismissBackupOptions() {
        uiState.update { it.copy(driveBackupOptions = emptyList()) }
    }

    fun manageBackups(scope: CoroutineScope) {
        scope.launch {
            if (uiState.value.driveAuthState !is DriveAuthState.SignedIn) {
                uiState.update { it.copy(userMessage = "Sign in to Google Drive first") }
                return@launch
            }
            var started = false
            uiState.update {
                if (it.driveIsBusy) {
                    it
                } else {
                    started = true
                    it.copy(driveIsBusy = true, driveBackupManagementOptions = emptyList())
                }
            }
            if (!started) return@launch
            when (val result = driveManager.listBackups()) {
                is Result.Success -> uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        driveBackupManagementOptions = result.data,
                        userMessage = if (result.data.isEmpty()) "No Google Drive backups found" else null,
                    )
                }
                is Result.Error -> uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        driveBackupManagementOptions = emptyList(),
                        userMessage = "Drive backup management failed: ${result.message}",
                    )
                }
                is Result.Loading -> uiState.update { it.copy(driveIsBusy = false) }
            }
        }
    }

    fun dismissBackupManagement() {
        uiState.update { it.copy(driveBackupManagementOptions = emptyList()) }
    }

    fun deleteBackup(scope: CoroutineScope, snapshotId: String) {
        scope.launch {
            var started = false
            uiState.update {
                if (it.driveIsBusy) {
                    it
                } else {
                    started = true
                    it.copy(driveIsBusy = true)
                }
            }
            if (!started) return@launch
            when (val result = driveManager.deleteBackup(snapshotId)) {
                is Result.Success -> {
                    val refreshed = driveManager.listBackups()
                    uiState.update {
                        when (refreshed) {
                            is Result.Success -> it.copy(
                                driveIsBusy = false,
                                driveBackupManagementOptions = refreshed.data,
                                userMessage = "Drive backup deleted",
                            )
                            is Result.Error -> it.copy(
                                driveIsBusy = false,
                                driveBackupManagementOptions = emptyList(),
                                userMessage = "Drive backup deleted",
                            )
                            is Result.Loading -> it.copy(
                                driveIsBusy = false,
                                driveBackupManagementOptions = emptyList(),
                                userMessage = "Drive backup deleted",
                            )
                        }
                    }
                }
                is Result.Error -> uiState.update {
                    it.copy(
                        driveIsBusy = false,
                        userMessage = "Drive backup deletion failed: ${result.message}",
                    )
                }
                is Result.Loading -> uiState.update { it.copy(driveIsBusy = false) }
            }
        }
    }

    private suspend fun pullSelectedBackup(snapshotId: String) {
        val pullResult = driveManager.pullBackup(snapshotId)
        if (pullResult is Result.Error) {
            uiState.update {
                it.copy(
                    driveIsBusy = false,
                    driveBackupOptions = emptyList(),
                    pendingDriveCredentials = null,
                    userMessage = "Drive pull failed: ${pullResult.message}"
                )
            }
            return
        }
        val artifact = (pullResult as Result.Success).data
            // New bundles carry credentials with the exact backup snapshot.
            // Legacy standalone backups still use the old companion file.
            val pendingCredentials = artifact.credentials ?: run {
                val credentialsResult = driveManager.pullCredentials()
                if (credentialsResult is Result.Error) {
                    Log.w("DriveSync", "pullCredentials failed (non-fatal): ${credentialsResult.message}")
                }
                (credentialsResult as? Result.Success)?.data
            }
            val inspectResult = importBackup.inspect(InspectBackupCommand(artifact.localUriString))
            uiState.update { state ->
                when (inspectResult) {
                    is InspectBackupResult.Success -> state.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        pendingBackupUri = inspectResult.uriString,
                        backupPreview = inspectResult.preview,
                        backupImportPlan = inspectResult.defaultPlan,
                        pendingDriveCredentials = pendingCredentials,
                    )
                    is InspectBackupResult.Error -> state.copy(
                        driveIsBusy = false,
                        driveBackupOptions = emptyList(),
                        pendingDriveCredentials = null,
                        userMessage = "Drive import failed: ${inspectResult.message}"
                    )
                }
            }
    }

    private fun resultMessage(result: Result<*>): String = when (result) {
        is Result.Error -> result.message
        is Result.Loading -> "still loading"
        is Result.Success -> "unknown error"
    }

    /**
     * Applies the credentials downloaded by [pullBackup] to the providers that
     * were just restored by the SAF import dialog. Match key is
     * `(serverUrl, username)` so the row ids reshuffled by import don't matter.
     * Re-encrypts via [CredentialCrypto] before writing to the local DB.
     *
     * Call this from the ViewModel **after** the import confirm succeeds.
     */
    fun applyPendingCredentials(scope: CoroutineScope) {
        scope.launch {
            val pending = uiState.value.pendingDriveCredentials.orEmpty()
            if (pending.isEmpty()) return@launch
            var applied = 0
            pending.forEach { cred ->
                if (providerRepository.updateProviderPassword(
                        serverUrl = cred.serverUrl,
                        username = cred.username,
                        cleartextPassword = cred.password,
                    )
                ) {
                    applied++
                }
            }
            Log.d("DriveSync", "applyPendingCredentials: $applied / ${pending.size} matched")
            uiState.update { it.copy(pendingDriveCredentials = null) }
        }
    }
}
