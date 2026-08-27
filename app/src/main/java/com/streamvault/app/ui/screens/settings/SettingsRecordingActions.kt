package com.streamvault.app.ui.screens.settings

import com.streamvault.feature.settings.presentation.SettingsUiState

import android.app.Application
import com.streamvault.app.R
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.RecordingStorageConfig
import com.streamvault.domain.model.RecordingReconciliationResult
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SettingsRecordingActions(
    private val appContext: Application,
    private val recordingManager: RecordingManager,
    private val uiState: MutableStateFlow<SettingsUiState>
) {
    // Serializes all storage-config updates so that a concurrent read-modify-write
    // (e.g. folder + retention changed close together) always builds from the latest
    // committed RecordingStorageState rather than two stale copies of the same snapshot.
    private val storageConfigMutex = Mutex()

    fun stopRecording(scope: CoroutineScope, recordingId: String) {
        scope.launch {
            val result = recordingManager.stopRecording(recordingId)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_stop_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_stopped)
                    }
                )
            }
        }
    }

    fun cancelRecording(scope: CoroutineScope, recordingId: String) {
        scope.launch {
            val result = recordingManager.cancelRecording(recordingId)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_cancel_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_cancelled)
                    }
                )
            }
        }
    }

    fun skipOccurrence(scope: CoroutineScope, recordingId: String) {
        scope.launch {
            val result = recordingManager.skipOccurrence(recordingId)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_skip_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_skipped)
                    }
                )
            }
        }
    }

    fun deleteRecording(scope: CoroutineScope, recordingId: String) {
        scope.launch {
            val result = recordingManager.deleteRecording(recordingId)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_delete_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_deleted)
                    }
                )
            }
        }
    }

    fun retryRecording(scope: CoroutineScope, recordingId: String) {
        scope.launch {
            val result = recordingManager.retryRecording(recordingId)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_retry_failed, result.message)
                    } else {
                        appContext.getString(R.string.settings_recording_retry_started)
                    }
                )
            }
        }
    }

    fun setRecordingScheduleEnabled(scope: CoroutineScope, recordingId: String, enabled: Boolean) {
        scope.launch {
            val result = recordingManager.setScheduleEnabled(recordingId, enabled)
            uiState.update {
                it.copy(
                    userMessage = if (result is Result.Error) {
                        appContext.getString(R.string.settings_recording_schedule_toggle_failed, result.message)
                    } else {
                        appContext.getString(
                            if (enabled) R.string.settings_recording_schedule_enabled
                            else R.string.settings_recording_schedule_disabled
                        )
                    }
                )
            }
        }
    }

    fun reconcileRecordings(scope: CoroutineScope) {
        scope.launch {
            val result = recordingManager.reconcileRecordingState()
            uiState.update {
                it.copy(
                    userMessage = when (result) {
                        is RecordingReconciliationResult.TransientFailure ->
                            appContext.getString(R.string.settings_recording_reconcile_failed, result.message)
                        is RecordingReconciliationResult.PermanentFailure ->
                            appContext.getString(R.string.settings_recording_reconcile_failed, result.message)
                        is RecordingReconciliationResult.Complete,
                        is RecordingReconciliationResult.Partial ->
                            appContext.getString(R.string.settings_recording_reconcile_complete)
                    }
                )
            }
        }
    }

    fun updateRecordingFolder(scope: CoroutineScope, treeUri: String?, displayName: String?) {
        scope.launch {
            storageConfigMutex.withLock {
                val current = uiState.value.recordingStorageState
                val result = recordingManager.updateStorageConfig(
                    RecordingStorageConfig(
                        treeUri = treeUri,
                        displayName = displayName,
                        fileNamePattern = current.fileNamePattern,
                        retentionDays = current.retentionDays,
                        maxSimultaneousRecordings = current.maxSimultaneousRecordings
                    )
                )
                uiState.update { state ->
                    state.copy(
                        recordingStorageState = if (result is Result.Success) result.data else state.recordingStorageState,
                        userMessage = if (result is Result.Error) {
                            appContext.getString(R.string.settings_recording_storage_update_failed, result.message)
                        } else {
                            appContext.getString(R.string.settings_recording_storage_updated)
                        }
                    )
                }
            }
        }
    }

    fun updateRecordingLocalDirectory(scope: CoroutineScope, localDirectory: String) {
        scope.launch {
            storageConfigMutex.withLock {
                val current = uiState.value.recordingStorageState
                val result = recordingManager.updateStorageConfig(
                    RecordingStorageConfig(
                        treeUri = null,
                        displayName = null,
                        localDirectory = localDirectory,
                        fileNamePattern = current.fileNamePattern,
                        retentionDays = current.retentionDays,
                        maxSimultaneousRecordings = current.maxSimultaneousRecordings
                    )
                )
                uiState.update { state ->
                    state.copy(
                        recordingStorageState = if (result is Result.Success) result.data else state.recordingStorageState,
                        userMessage = if (result is Result.Error) {
                            appContext.getString(R.string.settings_recording_storage_update_failed, result.message)
                        } else {
                            appContext.getString(R.string.settings_recording_storage_updated)
                        }
                    )
                }
            }
        }
    }

    fun updateRecordingFileNamePattern(scope: CoroutineScope, pattern: String) {
        scope.launch {
            storageConfigMutex.withLock {
                val current = uiState.value.recordingStorageState
                val result = recordingManager.updateStorageConfig(
                    RecordingStorageConfig(
                        treeUri = current.treeUri,
                        displayName = current.displayName,
                        localDirectory = current.localDirectory,
                        fileNamePattern = pattern,
                        retentionDays = current.retentionDays,
                        maxSimultaneousRecordings = current.maxSimultaneousRecordings
                    )
                )
                uiState.update { state ->
                    state.copy(
                        recordingStorageState = if (result is Result.Success) result.data else state.recordingStorageState,
                        userMessage = if (result is Result.Error) {
                            appContext.getString(R.string.settings_recording_pattern_failed, result.message)
                        } else {
                            appContext.getString(R.string.settings_recording_pattern_saved)
                        }
                    )
                }
            }
        }
    }

    fun updateRecordingRetentionDays(scope: CoroutineScope, retentionDays: Int?) {
        scope.launch {
            storageConfigMutex.withLock {
                val current = uiState.value.recordingStorageState
                val result = recordingManager.updateStorageConfig(
                    RecordingStorageConfig(
                        treeUri = current.treeUri,
                        displayName = current.displayName,
                        localDirectory = current.localDirectory,
                        fileNamePattern = current.fileNamePattern,
                        retentionDays = retentionDays,
                        maxSimultaneousRecordings = current.maxSimultaneousRecordings
                    )
                )
                uiState.update { state ->
                    state.copy(
                        recordingStorageState = if (result is Result.Success) result.data else state.recordingStorageState,
                        userMessage = if (result is Result.Error) {
                            appContext.getString(R.string.settings_recording_retention_failed, result.message)
                        } else {
                            appContext.getString(R.string.settings_recording_retention_saved)
                        }
                    )
                }
            }
        }
    }

    fun updateRecordingMaxSimultaneous(scope: CoroutineScope, maxSimultaneousRecordings: Int) {
        scope.launch {
            storageConfigMutex.withLock {
                val current = uiState.value.recordingStorageState
                val result = recordingManager.updateStorageConfig(
                    RecordingStorageConfig(
                        treeUri = current.treeUri,
                        displayName = current.displayName,
                        localDirectory = current.localDirectory,
                        fileNamePattern = current.fileNamePattern,
                        retentionDays = current.retentionDays,
                        maxSimultaneousRecordings = maxSimultaneousRecordings
                    )
                )
                uiState.update { state ->
                    state.copy(
                        recordingStorageState = if (result is Result.Success) result.data else state.recordingStorageState,
                        userMessage = if (result is Result.Error) {
                            appContext.getString(R.string.settings_recording_concurrency_failed, result.message)
                        } else {
                            appContext.getString(R.string.settings_recording_concurrency_saved)
                        }
                    )
                }
            }
        }
    }
}
