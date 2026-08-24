package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import javax.inject.Inject

/** Owns the mutable identity of the item currently being prepared or played. */
class PlayerPlaybackContextCoordinator @Inject constructor() {
    internal var currentStreamUrl: String = ""
    internal var currentResolvedPlaybackUrl: String = ""
    internal var currentResolvedStreamInfo: StreamInfo? = null
    internal var currentContentId: Long = -1L
    internal var currentProviderId: Long = -1L
    internal var currentContentType: ContentType = ContentType.LIVE
    internal var currentTitle: String = ""
    internal var currentArtworkUrl: String? = null
    internal var pendingCatchUpUrls: List<String> = emptyList()

    internal fun clearResolvedStream() {
        currentResolvedPlaybackUrl = ""
        currentResolvedStreamInfo = null
    }
}
