package com.streamvault.feature.stremio.api

/** Reports whether an enabled app plugin can prepare magnet playback URLs. */
fun interface StremioTorrentPlaybackAvailability {
    suspend fun hasEnabledMagnetPlugin(): Boolean
}
