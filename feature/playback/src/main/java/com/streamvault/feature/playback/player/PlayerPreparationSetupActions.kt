package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.PlayerSurfaceMode
import kotlinx.coroutines.launch

internal data class ResolvedSeriesEpisodeIdentity(
    val seriesId: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?
)

internal suspend fun resolveSeriesEpisodeIdentity(
    providerId: Long,
    internalChannelId: Long,
    seriesId: Long?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    lookupEpisode: suspend (Long) -> Episode?
): ResolvedSeriesEpisodeIdentity? {
    val normalizedSeriesId = seriesId?.takeIf { it > 0L }
    if (normalizedSeriesId != null) {
        return ResolvedSeriesEpisodeIdentity(
            seriesId = normalizedSeriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }
    if (providerId <= 0L || internalChannelId <= 0L) return null

    val persistedEpisode = lookupEpisode(internalChannelId)
        ?.takeIf { it.providerId == providerId }
        ?.takeIf { it.seriesId > 0L }
        ?: return null

    return ResolvedSeriesEpisodeIdentity(
        seriesId = persistedEpisode.seriesId,
        seasonNumber = seasonNumber ?: persistedEpisode.seasonNumber,
        episodeNumber = episodeNumber ?: persistedEpisode.episodeNumber
    )
}

internal fun PlayerViewModel.applyPrepareSessionState(
    streamUrl: String,
    internalChannelId: Long,
    categoryId: Long,
    providerId: Long,
    combinedProfileId: Long?,
    combinedSourceFilterProviderId: Long?,
    contentType: String,
    title: String,
    artworkUrl: String?,
    seriesId: Long?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeId: Long?,
    hasArchiveRequest: Boolean,
    preferredAudioDecoderMode: DecoderMode,
    preferredVideoDecoderMode: DecoderMode,
    preferredSurfaceMode: PlayerSurfaceMode
): Boolean {
    val previousProviderId = currentProviderId
    val previousCategoryId = currentCategoryId
    val previousCombinedProfileId = currentCombinedProfileId
    val previousCombinedSourceFilterProviderId = currentCombinedSourceFilterProviderId
    val shouldReloadPlaylist = categoryId != -1L &&
        (
            categoryId != previousCategoryId ||
                providerId != previousProviderId ||
                combinedProfileId != previousCombinedProfileId ||
                combinedSourceFilterProviderId != previousCombinedSourceFilterProviderId
            )

    val requestedContentType = try {
        ContentType.valueOf(contentType)
    } catch (_: Exception) {
        ContentType.LIVE
    }
    if (requestedContentType == ContentType.LIVE && !hasArchiveRequest) {
        clearPreloadWindow()
    }

    clearSeekPreview()
    currentResolvedPlaybackUrl = ""
    currentResolvedStreamInfo = null
    currentStreamUrl = streamUrl
    currentContentId = internalChannelId
    currentTitle = title
    playbackTitleFlow.value = title
    currentArtworkUrl = artworkUrl
    currentContentType = requestedContentType
    currentProviderId = providerId
    currentCombinedProfileId = combinedProfileId?.takeIf { it > 0L }
    currentCombinedSourceFilterProviderId = combinedSourceFilterProviderId?.takeIf { it > 0L }
    currentSeriesId = seriesId?.takeIf { it > 0L }
    currentSeasonNumber = seasonNumber
    currentEpisodeNumber = episodeNumber
    currentStableEpisodeId = episodeId?.takeIf { it > 0L }
    activeVodTrackPreferences = null
    val streamClassLabel = if (hasArchiveRequest) "Catch-up" else "Primary"
    applyDefaultPlaybackTimersIfNeeded()

    if (currentContentType == ContentType.LIVE && currentCombinedProfileId != null) {
        val activeCombinedProfileId = currentCombinedProfileId
        viewModelScope.launch {
            val members = activeCombinedProfileId?.let { playerPlaylistCoordinator.getProfile(it)?.members }.orEmpty()
            if (currentCombinedProfileId == activeCombinedProfileId) {
                currentCombinedProfileMembers = members
            }
        }
    } else {
        currentCombinedProfileMembers = emptyList()
        combinedCategoriesById = emptyMap()
    }

    if (!hasArchiveRequest) {
        pendingCatchUpUrls = emptyList()
        playerPlaybackContextCoordinator.clearSelectedCatchUpProgram()
    }
    if (currentContentType != ContentType.SERIES_EPISODE || providerId <= 0 || currentSeriesId == null) {
        clearSeriesEpisodeContext()
    }
    if (currentContentType != ContentType.LIVE) {
        livePlaybackRecordCoordinator.reset()
        recentChannelsJob?.cancel()
        recentChannelsFlow.value = emptyList()
        lastVisitedCategoryJob?.cancel()
        _lastVisitedCategory.value = null
        playerEngine.stopLiveTimeshift()
    }

    playerRecoveryCoordinator.resetPreparationAttempts()
    playerEngine.setDecoderModes(
        audioMode = preferredAudioDecoderMode,
        videoMode = preferredVideoDecoderMode
    )
    playerEngine.setSurfaceMode(preferredSurfaceMode)
    updateDecoderModes(
        audioMode = preferredAudioDecoderMode,
        videoMode = preferredVideoDecoderMode
    )
    updateStreamClass(streamClassLabel)

    playerRecoveryCoordinator.clearStreamAttempts()
    if (!hasArchiveRequest) {
        playerRecoveryCoordinator.markStreamAttempt(streamUrl)
    }

    return shouldReloadPlaylist
}
