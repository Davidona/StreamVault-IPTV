package com.streamvault.app.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.app.update.AppUpdateChannel
import com.streamvault.app.update.AppUpdateDownloadStatus
import com.streamvault.app.update.isRemoteVersionNewerForBuild
import com.streamvault.feature.settings.api.SettingsUpdateActionState
import com.streamvault.feature.settings.api.SettingsUpdateDownloadStatus
import com.streamvault.feature.settings.presentation.latestActionState
import org.junit.Test

class SettingsAppUpdateModelsTest {

    @Test
    fun stableBuildIgnoresBetaRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Stable
        )

        assertThat(result).isFalse()
    }

    @Test
    fun betaBuildIgnoresStableRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isFalse()
    }

    @Test
    fun betaBuildAcceptsNewerBaseVersionBetaRelease() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 13,
            remoteVersionName = "1.0.12-beta-cafebad",
            remotePublishedAt = "2026-05-14T10:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isTrue()
    }

    @Test
    fun betaBuildAcceptsSameVersionBetaReleaseWhenPublishedLater() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T12:30:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 0L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isTrue()
    }

    @Test
    fun betaBuildRejectsSameVersionBetaReleaseWhenNotPublishedLater() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = "2026-05-14T08:00:00Z",
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = Long.MAX_VALUE,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isFalse()
    }

    @Test
    fun betaBuildRejectsSameVersionBetaReleaseWhenPublishedAtMissing() {
        val result = isRemoteVersionNewerForBuild(
            remoteVersionCode = 12,
            remoteVersionName = "1.0.11-beta-deadbee",
            remotePublishedAt = null,
            currentVersionCode = 12,
            currentVersionName = "1.0.11-beta",
            currentBuildTimestampUtc = 1_747_216_000_000L,
            currentChannel = AppUpdateChannel.Beta
        )

        assertThat(result).isFalse()
    }

    @Test
    fun downloadedLatestShowsInstallAction() {
        val update = AppUpdateUiModel(
            latestVersionName = "1.0.12",
            downloadUrl = "https://example.com/StreamVault.apk",
            isUpdateAvailable = true,
            downloadStatus = SettingsUpdateDownloadStatus.DOWNLOADED,
            downloadedVersionName = "1.0.12"
        )

        assertThat(update.latestActionState()).isEqualTo(SettingsUpdateActionState.INSTALL_LATEST)
    }

    @Test
    fun staleDownloadedUpdateShowsDownloadAction() {
        val update = AppUpdateUiModel(
            latestVersionName = "1.0.13",
            downloadUrl = "https://example.com/StreamVault.apk",
            isUpdateAvailable = true,
            downloadStatus = SettingsUpdateDownloadStatus.DOWNLOADED,
            downloadedVersionName = "1.0.12"
        )

        assertThat(update.latestActionState()).isEqualTo(SettingsUpdateActionState.DOWNLOAD_LATEST)
    }

    @Test
    fun downloadedLatestWithMissingInstallPermissionShowsPermissionAction() {
        val update = AppUpdateUiModel(
            latestVersionName = "1.0.12",
            downloadUrl = "https://example.com/StreamVault.apk",
            isUpdateAvailable = true,
            downloadStatus = SettingsUpdateDownloadStatus.DOWNLOADED,
            downloadedVersionName = "1.0.12",
            installPermissionRequired = true
        )

        assertThat(update.latestActionState()).isEqualTo(SettingsUpdateActionState.INSTALL_PERMISSION_REQUIRED)
    }

    @Test
    fun downloadingLatestShowsDownloadingAction() {
        val update = AppUpdateUiModel(
            latestVersionName = "1.0.12",
            downloadUrl = "https://example.com/StreamVault.apk",
            isUpdateAvailable = true,
            downloadStatus = SettingsUpdateDownloadStatus.DOWNLOADING,
            downloadedVersionName = "1.0.12"
        )

        assertThat(update.latestActionState()).isEqualTo(SettingsUpdateActionState.DOWNLOADING)
    }
}
