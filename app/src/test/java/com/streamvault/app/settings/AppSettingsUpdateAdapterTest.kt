package com.streamvault.app.settings

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.BuildConfig
import com.streamvault.app.update.AppUpdateDownloadState
import com.streamvault.app.update.AppUpdateChannel
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.GitHubReleaseChecker
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppSettingsUpdateAdapterTest {

    @Test
    fun isRemoteVersionNewer_delegatesToCurrentBuildPolicy() {
        val releaseChecker = mock<GitHubReleaseChecker>()
        val updateInstaller = mock<AppUpdateInstaller>()
        whenever(updateInstaller.downloadState).thenReturn(MutableStateFlow(AppUpdateDownloadState()))
        val adapter = AppSettingsUpdateAdapter(releaseChecker, updateInstaller)
        val remoteVersionName = if (AppUpdateChannel.fromBuildConfig(
                BuildConfig.APP_UPDATE_CHANNEL,
                BuildConfig.VERSION_NAME
            ) == AppUpdateChannel.Beta
        ) {
            "999.0.0-beta"
        } else {
            "999.0.0"
        }

        assertThat(
            adapter.isRemoteVersionNewer(
                remoteVersionCode = Int.MAX_VALUE,
                remoteVersionName = remoteVersionName,
                remotePublishedAt = null
            )
        ).isTrue()
    }
}
