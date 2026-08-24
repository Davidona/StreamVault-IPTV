package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun PlayerViewModel.finalizePreparedPlaybackContext(
    requestVersion: Long,
    streamUrl: String,
    providerId: Long,
    categoryId: Long,
    isVirtual: Boolean,
    internalChannelId: Long,
    epgChannelId: String?,
    shouldReloadPlaylist: Boolean,
    hasArchiveRequest: Boolean,
    archiveStartMs: Long?,
    archiveEndMs: Long?,
    archiveTitle: String?,
    contentSwitchFlush: Job?
) {
    if (shouldReloadPlaylist) {
        currentCategoryId = categoryId
        activeCategoryIdFlow.value = categoryId
        isVirtualCategory = isVirtual
        loadPlaylist(categoryId, providerId, isVirtual, internalChannelId)
    } else {
        if (channelList.isNotEmpty() && internalChannelId != -1L) {
            currentChannelIndex = channelList.indexOfFirst { it.id == internalChannelId }
            if (currentChannelIndex == -1) {
                currentChannelIndex = channelList.indexOfFirst { it.streamUrl == streamUrl }
            }
        }
    }

    if (currentContentType == ContentType.LIVE && hasArchiveRequest) {
        playerEngine.stopLiveTimeshift()
        playbackSessionScope(requestVersion)?.launch {
            contentSwitchFlush?.join()
            if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
            val catchUpUrls = when (val catchUpResult = playerProviderCoordinator.buildCatchUpUrls(
                    providerId = currentProviderId,
                    streamId = currentContentId,
                    start = (archiveStartMs ?: 0L) / 1000L,
                    end = (archiveEndMs ?: 0L) / 1000L
                )) {
                is Result.Success -> catchUpResult.data
                is Result.Error -> {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    setLastFailureReason(catchUpResult.message)
                    showPlayerNotice(
                        message = catchUpResult.message,
                        recoveryType = PlayerRecoveryType.SOURCE
                    )
                    return@launch
                }
                is Result.Loading -> return@launch
            }
            if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
            if (catchUpUrls.isNotEmpty()) {
                startCatchUpPlayback(
                    urls = catchUpUrls,
                    title = archiveTitle?.takeIf { it.isNotBlank() } ?: currentTitle,
                    recoveryAction = "Opened catch-up stream",
                    requestVersionOverride = requestVersion
                )
            } else {
                val reason = resolveCatchUpFailureMessage(currentChannelFlow.value, archiveRequested = true, programHasArchive = true)
                setLastFailureReason(reason)
                showPlayerNotice(
                    message = reason,
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
            }
        }
    }

    if (currentContentType == ContentType.LIVE) {
        requestEpg(
            providerId = currentProviderId,
            epgChannelId = epgChannelId,
            internalChannelId = internalChannelId
        )
    } else {
        requestEpg(providerId = -1L, epgChannelId = null)
        currentChannelFlow.value = null
    }
    observeRecentChannels()
    observeLastVisitedCategory()

    aspectRatioJob?.cancel()
    _aspectRatio.value = AspectRatio.FIT
    if (shouldResolveChannelPlaybackContext(currentContentType.name, internalChannelId)) {
        aspectRatioJob = playbackSessionScope(requestVersion)?.launch {
            playerPreferencesCoordinator.getAspectRatioForChannel(internalChannelId).collect { savedRatio ->
                _aspectRatio.value = try {
                    savedRatio?.let { AspectRatio.valueOf(it) } ?: AspectRatio.FIT
                } catch (_: Exception) {
                    AspectRatio.FIT
                }
            }
        }

        playbackSessionScope(requestVersion)?.launch {
            val channel = playerChannelCoordinator.getChannel(internalChannelId)
            if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
            currentChannelFlow.value = channel
            refreshCurrentChannelRecording()
            if (channel != null) {
                currentTitle = channel.name.ifBlank { currentTitle }
                playbackTitleFlow.value = currentTitle
                currentStreamUrl = if (isCatchUpPlayback()) currentStreamUrl else channel.streamUrl
                updateStreamClass(
                    when {
                        isCatchUpPlayback() -> "Catch-up"
                        streamUrl == channel.streamUrl -> "Primary"
                        channel.alternativeStreams.contains(streamUrl) -> "Alternate"
                        else -> "Direct"
                    }
                )
                if (currentContentType == ContentType.LIVE) {
                    requestEpg(
                        providerId = currentProviderId,
                        epgChannelId = channel.epgChannelId,
                        streamId = channel.streamId,
                        internalChannelId = channel.id
                    )
                }
                updateChannelDiagnostics(channel)
            }
        }
    }

    startProgressTracking()
}
