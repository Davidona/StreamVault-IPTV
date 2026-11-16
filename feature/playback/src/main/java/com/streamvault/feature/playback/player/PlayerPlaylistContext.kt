package com.streamvault.feature.playback.player

internal data class PlayerPlaylistContext(
    val categoryId: Long,
    val providerId: Long,
    val isVirtual: Boolean,
    val combinedProfileId: Long?,
    val combinedSourceFilterProviderId: Long?
)

internal fun shouldReloadPlayerPlaylist(
    current: PlayerPlaylistContext,
    requested: PlayerPlaylistContext
): Boolean {
    // Group 1 is represented by category -1. Only a non-virtual -1 means no category.
    val hasCategory = requested.isVirtual || requested.categoryId != -1L
    return hasCategory && requested != current
}
