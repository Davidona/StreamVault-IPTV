package com.streamvault.app.stremio

import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.feature.stremio.api.StremioTorrentPlaybackAvailability
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStremioTorrentPlaybackAvailability @Inject constructor(
    private val pluginManager: StreamVaultPluginManager
) : StremioTorrentPlaybackAvailability {
    override suspend fun hasEnabledMagnetPlugin(): Boolean =
        pluginManager.hasEnabledMagnetPlaybackPlugin()
}
