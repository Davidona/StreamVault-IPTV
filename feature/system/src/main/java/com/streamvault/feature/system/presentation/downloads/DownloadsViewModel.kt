package com.streamvault.feature.system.presentation.downloads

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.feature.system.R
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.repository.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * ViewModel for the Downloads screen.
 *
 * Manages download state, playback intents, deletion, and storage folder configuration.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    @ApplicationContext private val application: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadManager.observeAllDownloads().collect { downloads ->
                _uiState.update {
                    it.copy(downloads = downloads, isLoading = false)
                }
            }
        }

        viewModelScope.launch {
            downloadManager.observeStorageState().collect { storageConfig ->
                _uiState.update { it.copy(storageConfig = storageConfig) }
            }
        }
    }

    /**
     * Returns an [Intent] for playing the downloaded file at [item]'s [DownloadItem.outputUri],
     * or null if no activity can handle playback.
     */
    fun playDownload(item: DownloadItem): Intent? {
        val uri = item.outputUri ?: return null
        downloadManager.onPlaybackStopped()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), "video/*")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        val pm = application.packageManager
        return if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            intent
        } else {
            null
        }
    }

    /**
     * Deletes the download [item] and sets a user message confirming deletion.
     */
    fun deleteDownload(item: DownloadItem) {
        viewModelScope.launch {
            downloadManager.deleteDownload(item.id)
            val message = application.getString(R.string.downloads_deleted)
            _uiState.update { it.copy(userMessage = message) }
        }
    }

    fun resumeDownload(item: DownloadItem) {
        viewModelScope.launch {
            downloadManager.resumeDownload(item.id)
            val message = application.getString(R.string.downloads_resumed)
            _uiState.update { it.copy(userMessage = message) }
        }
    }

    fun showDeleteConfirm(item: DownloadItem) {
        _uiState.update { it.copy(deleteConfirmItem = item) }
    }

    fun dismissDeleteConfirm() {
        _uiState.update { it.copy(deleteConfirmItem = null) }
    }

    fun confirmDelete() {
        val item = _uiState.value.deleteConfirmItem ?: return
        _uiState.update { it.copy(deleteConfirmItem = null) }
        deleteDownload(item)
    }

    /**
     * Returns an [Intent] with [Intent.ACTION_OPEN_DOCUMENT_TREE] to let the user pick
     * a download folder.
     */
    fun changeDownloadFolder(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    }

    /**
     * Handles the result from [changeDownloadFolder] after the user selects a folder.
     * Persists the [treeUri] and its display name via [DownloadManager.updateStorageConfig].
     */
    fun onFolderSelected(treeUri: Uri) {
        viewModelScope.launch {
            runCatching {
                application.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val displayName = getDisplayName(treeUri)
            downloadManager.updateStorageConfig(treeUri.toString(), displayName)
        }
    }

    /**
     * Resets the user-facing message to null.
     */
    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val displayNameColumn = "DISPLAY_NAME"
            application.contentResolver.query(
                uri,
                arrayOf(displayNameColumn),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(displayNameColumn)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
    }
}
