package com.streamvault.app.catalog

import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.catalog.api.CatalogStreamPreparer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCatalogStreamPreparer internal constructor(
    private val preparePlayback: suspend (StreamInfo) -> Result<StreamInfo>,
) : CatalogStreamPreparer {
    @Inject
    constructor(pluginManager: StreamVaultPluginManager) : this(
        pluginManager::preparePlaybackStreamInfo
    )

    override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
        preparePlayback(streamInfo)
}
