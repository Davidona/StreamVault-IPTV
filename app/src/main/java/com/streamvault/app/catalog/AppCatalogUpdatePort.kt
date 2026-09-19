package com.streamvault.app.catalog

import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.app.update.AppUpdateInstaller
import com.streamvault.app.update.isRemoteVersionNewer
import com.streamvault.app.update.latestAppUpdateAction
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import com.streamvault.feature.catalog.api.CatalogAppUpdatePort
import com.streamvault.feature.catalog.api.CatalogUpdateAction
import com.streamvault.feature.catalog.api.CatalogUpdateNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCatalogUpdatePort internal constructor(
    override val notice: Flow<CatalogUpdateNotice?>,
    private val install: suspend (String?) -> Result<Unit>,
) : CatalogAppUpdatePort {
    @Inject
    constructor(
        preferencesRepository: PreferencesRepository,
        updateInstaller: AppUpdateInstaller,
    ) : this(
        notice = observeCatalogUpdateNotice(preferencesRepository, updateInstaller),
        install = updateInstaller::installDownloadedUpdate,
    )

    override suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit> =
        install(expectedSha256)

    companion object {
        internal fun mapAction(action: AppUpdateActionState): CatalogUpdateAction = when (action) {
            AppUpdateActionState.None -> CatalogUpdateAction.None
            AppUpdateActionState.DownloadLatest -> CatalogUpdateAction.DownloadLatest
            AppUpdateActionState.Downloading -> CatalogUpdateAction.Downloading
            AppUpdateActionState.InstallLatest -> CatalogUpdateAction.InstallLatest
            AppUpdateActionState.InstallPermissionRequired ->
                CatalogUpdateAction.InstallPermissionRequired
        }
    }
}

private fun observeCatalogUpdateNotice(
    preferencesRepository: PreferencesRepository,
    updateInstaller: AppUpdateInstaller,
): Flow<CatalogUpdateNotice?> {
    val cachedRelease = combine(
        preferencesRepository.cachedAppUpdateVersionName,
        preferencesRepository.cachedAppUpdateVersionCode,
        preferencesRepository.cachedAppUpdatePublishedAt,
        preferencesRepository.cachedAppUpdateDownloadUrl,
        preferencesRepository.cachedAppUpdateDownloadSha256,
    ) { latestVersionName, latestVersionCode, publishedAt, downloadUrl, downloadSha256 ->
        CachedCatalogUpdateRelease(
            latestVersionName = latestVersionName,
            latestVersionCode = latestVersionCode,
            publishedAt = publishedAt,
            downloadUrl = downloadUrl,
            downloadSha256 = downloadSha256,
        )
    }

    return cachedRelease.combine(updateInstaller.downloadState) { release, downloadState ->
        val latestVersionName = release.latestVersionName ?: return@combine null
        val updateAvailable = isRemoteVersionNewer(
            release.latestVersionCode,
            latestVersionName,
            release.publishedAt,
        )
        if (!updateAvailable) return@combine null

        CatalogUpdateNotice(
            latestVersionName = latestVersionName,
            downloadSha256 = release.downloadSha256,
            action = AppCatalogUpdatePort.mapAction(
                latestAppUpdateAction(
                    latestVersionName = latestVersionName,
                    downloadUrl = release.downloadUrl,
                    isUpdateAvailable = updateAvailable,
                    downloadState = downloadState,
                )
            ),
        )
    }
}

private data class CachedCatalogUpdateRelease(
    val latestVersionName: String?,
    val latestVersionCode: Int?,
    val publishedAt: String?,
    val downloadUrl: String?,
    val downloadSha256: String?,
)
