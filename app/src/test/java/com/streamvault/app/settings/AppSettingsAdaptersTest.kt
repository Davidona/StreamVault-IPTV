package com.streamvault.app.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsBackupFileCandidate
import com.streamvault.feature.settings.api.SettingsBackupFileHost
import com.streamvault.feature.settings.api.SettingsRecordingPlaybackRequest
import java.io.File
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppSettingsAdaptersTest {
    @Test
    fun recordingPlaybackAdapterPreservesRequestFields() {
        val requests = mutableListOf<SettingsRecordingPlaybackRequest>()
        val platformHost = AppSettingsPlatformHost(
            context = RuntimeEnvironment.getApplication(),
            backupFiles = fakeBackupFiles(),
            playRecordingOperation = requests::add,
        )
        val request = SettingsRecordingPlaybackRequest(
            streamUrl = "content://recording/7",
            title = "News",
            internalId = 7L,
            providerId = 3L,
            contentType = "MOVIE",
        )

        platformHost.playRecording(request)

        assertThat(requests).containsExactly(request)
    }

    @Test
    fun backupShareAdapterDelegatesUriWithoutChangingIt() {
        val sharedUris = mutableListOf<Uri>()
        val platformHost = AppSettingsPlatformHost(
            context = RuntimeEnvironment.getApplication(),
            backupFiles = fakeBackupFiles(),
            shareBackupOperation = { uri ->
                sharedUris += uri
                Result.success(Unit)
            },
        )
        val uri = Uri.parse("content://backup/42")

        val result = platformHost.shareBackup(uri)

        assertThat(result).isEqualTo(Result.success(Unit))
        assertThat(sharedUris).containsExactly(uri)
    }

    private fun fakeBackupFiles(): SettingsBackupFileHost = object : SettingsBackupFileHost {
        override val jsonMimeType: String = "application/json"
        override fun rememberManagedExport(uri: Uri) = Unit
        override fun listManagedBackups(): List<SettingsBackupFileCandidate> = emptyList()
        override fun listPickerFreeBackups(): List<SettingsBackupFileCandidate> = emptyList()
        override fun listBackups(directory: File): List<SettingsBackupFileCandidate> = emptyList()
        override fun createShareExportFile(): File = File("backup.json")
        override fun createPickerFreeExportUri(): Uri? = null
        override fun finishPickerFreeExport(uri: Uri, success: Boolean): Boolean = success
        override fun createExportFile(directory: File): File = File(directory, "backup.json")
        override fun delete(candidate: SettingsBackupFileCandidate): Boolean = true
        override fun providerUri(file: File): Uri = Uri.fromFile(file)
    }
}
