package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.app.R
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.feature.playback.R as PlaybackFeatureR
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.cast.CastMediaRequestBuildResult
import com.streamvault.feature.playback.cast.CastMediaRequestUnsupportedReason
import com.streamvault.feature.playback.cast.CastPlaybackEvent
import com.streamvault.feature.playback.cast.CastPlaybackReportMode
import com.streamvault.feature.playback.cast.CastStartResult
import com.streamvault.feature.playback.cast.toCastBuildFailureMessageRes
import com.streamvault.feature.playback.cast.toCastPlaybackMessageRes
import com.streamvault.feature.playback.cast.toCastUnsupportedMessageRes
import kotlinx.coroutines.launch

fun PlayerViewModel.castCurrentMedia(onRouteSelectionRequired: () -> Unit) {
    viewModelScope.launch {
        castPlaybackReportMode = CastPlaybackReportMode.NONE
        val request = when (val result = buildCastRequestResult()) {
            is PlayerCastRequestResult.Success -> result.request
            is PlayerCastRequestResult.Failure -> {
                showPlayerNotice(
                    message = result.message,
                    recoveryType = PlayerRecoveryType.SOURCE
                )
                return@launch
            }
        }

        when (playerCastCoordinator.startCasting(request)) {
            CastStartResult.STARTED -> {
                castPlaybackReportMode = CastPlaybackReportMode.SUCCESS_AND_FAILURE
                showPlayerNotice(
                    message = appContext.getString(PlaybackFeatureR.string.cast_started),
                    recoveryType = PlayerRecoveryType.NETWORK
                )
            }

            CastStartResult.ROUTE_SELECTION_REQUIRED -> {
                castPlaybackReportMode = CastPlaybackReportMode.SUCCESS_AND_FAILURE
                onRouteSelectionRequired()
            }

            CastStartResult.UNAVAILABLE -> {
                castPlaybackReportMode = CastPlaybackReportMode.NONE
                showPlayerNotice(
                    message = appContext.getString(PlaybackFeatureR.string.cast_unavailable),
                    recoveryType = PlayerRecoveryType.SOURCE
                )
            }

            CastStartResult.UNSUPPORTED -> {
                castPlaybackReportMode = CastPlaybackReportMode.NONE
                showPlayerNotice(
                    message = toPlayerCastUnsupportedMessage(request),
                    recoveryType = PlayerRecoveryType.SOURCE
                )
            }
        }
    }
}

internal fun PlayerViewModel.observeCastPlaybackEvents() {
    viewModelScope.launch {
        playerCastCoordinator.playbackEvents.collect { event ->
            handleCastPlaybackEvent(event)
        }
    }
}

private fun PlayerViewModel.handleCastPlaybackEvent(event: CastPlaybackEvent) {
    val reportMode = castPlaybackReportMode
    if (reportMode == CastPlaybackReportMode.NONE) return
    if (event is CastPlaybackEvent.RouteSelectionCancelled) {
        castPlaybackReportMode = CastPlaybackReportMode.NONE
        return
    }
    val isSuccess = event is CastPlaybackEvent.MediaLoadSucceeded
    if (isSuccess && reportMode == CastPlaybackReportMode.FAILURES_ONLY) {
        castPlaybackReportMode = CastPlaybackReportMode.NONE
        return
    }
    castPlaybackReportMode = CastPlaybackReportMode.NONE
    if (isSuccess) {
        playerEngine.pause()
    }
    showPlayerNotice(
        message = toPlayerCastPlaybackMessage(event),
        recoveryType = if (isSuccess) PlayerRecoveryType.NETWORK else PlayerRecoveryType.SOURCE
    )
}

fun PlayerViewModel.stopCasting() {
    playerCastCoordinator.stopCasting()
    showPlayerNotice(
        message = appContext.getString(R.string.cast_disconnected),
        recoveryType = PlayerRecoveryType.NETWORK
    )
}

fun PlayerViewModel.startManualRecording() {
    val channel = currentChannel.value
    if (currentContentType != ContentType.LIVE || channel == null || currentProviderId <= 0) {
        showPlayerNotice(message = "Recording needs a valid live channel context.")
        return
    }
    viewModelScope.launch {
        val now = System.currentTimeMillis()
        val result = playerRecordingCoordinator.startManualRecording(
            RecordingRequest(
                providerId = currentProviderId,
                channelId = channel.id,
                channelName = channel.name,
                streamUrl = currentStreamUrl,
                scheduledStartMs = now,
                scheduledEndMs = currentProgram.value?.endTime ?: (now + 30 * 60_000L),
                programTitle = currentProgram.value?.title
            )
        )
        if (result is Result.Error) {
            showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
        } else {
            showPlayerNotice(message = "Recording started for ${channel.name}.")
        }
    }
}

fun PlayerViewModel.scheduleRecording() {
    scheduleRecordingInternal(RecordingRecurrence.NONE)
}

fun PlayerViewModel.scheduleDailyRecording() {
    scheduleRecordingInternal(RecordingRecurrence.DAILY)
}

fun PlayerViewModel.scheduleWeeklyRecording() {
    scheduleRecordingInternal(RecordingRecurrence.WEEKLY)
}

private fun PlayerViewModel.scheduleRecordingInternal(recurrence: RecordingRecurrence) {
    viewModelScope.launch {
        val result = playerRecordingCoordinator.scheduleRecording(
            ScheduleRecordingCommand(
                contentType = currentContentType,
                providerId = currentProviderId,
                channel = currentChannel.value,
                streamUrl = currentStreamUrl,
                currentProgram = currentProgram.value,
                nextProgram = nextProgram.value,
                recurrence = recurrence
            )
        )
        if (result is Result.Error) {
            showPlayerNotice(message = result.message, recoveryType = PlayerRecoveryType.SOURCE)
        } else {
            val recurrenceLabel = when (recurrence) {
                RecordingRecurrence.NONE -> ""
                RecordingRecurrence.DAILY -> " daily"
                RecordingRecurrence.WEEKLY -> " weekly"
            }
            val scheduledItem = (result as? Result.Success)?.data
            val title = scheduledItem?.programTitle ?: "Recording"
            showPlayerNotice(message = "$title scheduled$recurrenceLabel.")
        }
    }
}

fun PlayerViewModel.stopCurrentRecording() {
    val recording = currentChannelRecording.value ?: return
    viewModelScope.launch {
        val result = playerRecordingCoordinator.stopRecording(recording.id)
        if (result is Result.Error) {
            showPlayerNotice(message = result.message)
        } else {
            showPlayerNotice(message = "Recording stopped.")
        }
    }
}

internal suspend fun PlayerViewModel.buildCastRequest(): CastMediaRequest? {
    return (buildCastRequestResult() as? PlayerCastRequestResult.Success)?.request
}

internal suspend fun PlayerViewModel.buildCastRequestResult(): PlayerCastRequestResult {
    return when (currentContentType) {
        ContentType.LIVE -> {
            val channel = currentChannel.value
                ?: return PlayerCastRequestResult.Failure(
                    toPlayerCastMessage(CastMediaRequestUnsupportedReason.STREAM_UNAVAILABLE)
                )
            // Use preferStableUrl = true for Cast: the credential-based portal URL
            // does not expire, unlike the tokenized direct-source CDN URL.
            val streamInfo = playerChannelCoordinator.getStreamInfo(channel, preferStableUrl = true)
                .getOrNull()
                ?: return PlayerCastRequestResult.Failure(
                    toPlayerCastMessage(CastMediaRequestUnsupportedReason.STREAM_UNAVAILABLE)
                )
            toPlayerCastRequestResult(
                playerCastCoordinator.buildFromStreamInfo(
                    streamInfo = streamInfo,
                    title = mediaTitle.value ?: channel.name,
                    subtitle = currentProgram.value?.title,
                    artworkUrl = channel.logoUrl ?: currentArtworkUrl,
                    isLive = true,
                    startPositionMs = 0L
                )
            )
        }

        ContentType.MOVIE,
        ContentType.VOD -> {
            val movie = playerContentResolver.getMovie(currentContentId)
            val repositoryStreamInfo = movie?.let { playerContentResolver.getMovieStreamInfo(it).getOrNull() }
            val streamInfo = selectPreferredVodCastStreamInfo(
                activeStreamInfo = currentResolvedStreamInfo.takeIf { currentContentType == ContentType.MOVIE },
                activePlaybackUrl = currentResolvedPlaybackUrl,
                fallbackStreamInfo = repositoryStreamInfo
            )
                ?: return directCastRequest()
            toPlayerCastRequestResult(
                playerCastCoordinator.buildFromStreamInfo(
                    streamInfo = streamInfo,
                    title = currentTitle.ifBlank { movie?.name.orEmpty() },
                    subtitle = movie?.genre,
                    artworkUrl = currentArtworkUrl ?: movie?.posterUrl ?: movie?.backdropUrl,
                    isLive = false,
                    startPositionMs = playerEngine.currentPosition.value
                )
            )
        }

        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> buildSeriesCastRequestResult()
    }
}

private suspend fun PlayerViewModel.buildSeriesCastRequestResult(): PlayerCastRequestResult {
    val resolution = resolvePlaybackStreamResolution(
        logicalUrl = currentStreamUrl,
        internalContentId = currentStableEpisodeId?.takeIf { it > 0L } ?: currentContentId,
        providerId = currentProviderId,
        contentType = currentContentType,
    )
    resolution.credentialFailureMessage?.let { message ->
        return PlayerCastRequestResult.Failure(message)
    }
    resolution.resolutionFailureMessage?.let { message ->
        return PlayerCastRequestResult.Failure(message)
    }
    val streamInfo = selectPreferredVodCastStreamInfo(
        activeStreamInfo = currentResolvedStreamInfo.takeIf {
            currentContentType == ContentType.SERIES || currentContentType == ContentType.SERIES_EPISODE
        },
        activePlaybackUrl = currentResolvedPlaybackUrl,
        fallbackStreamInfo = resolution.streamInfo
    )
        ?: return PlayerCastRequestResult.Failure(
            toPlayerCastMessage(CastMediaRequestUnsupportedReason.STREAM_UNAVAILABLE)
        )
    val episode = currentEpisode.value
    val series = currentSeries.value
    val castTitle = if (series != null && episode != null) {
        "${series.name} - S${episode.seasonNumber}E${episode.episodeNumber}"
    } else {
        currentTitle.ifBlank { episode?.let(::buildEpisodePlaybackTitle).orEmpty() }
    }
    return toPlayerCastRequestResult(
        playerCastCoordinator.buildFromStreamInfo(
            streamInfo = streamInfo,
            title = castTitle,
            subtitle = episode?.title,
            artworkUrl = currentArtworkUrl ?: episode?.coverUrl ?: series?.posterUrl ?: series?.backdropUrl,
            isLive = false,
            startPositionMs = playerEngine.currentPosition.value
        )
    )
}

internal fun PlayerViewModel.directCastRequest(): PlayerCastRequestResult {
    val url = currentStreamUrl.takeIf { it.isNotBlank() }
        ?: return PlayerCastRequestResult.Failure(
            toPlayerCastMessage(CastMediaRequestUnsupportedReason.EMPTY_URL)
        )
    return toPlayerCastRequestResult(
        playerCastCoordinator.buildFromStreamInfo(
            streamInfo = StreamInfo(url = url),
            title = currentTitle,
            subtitle = null,
            artworkUrl = currentArtworkUrl,
            isLive = false,
            startPositionMs = playerEngine.currentPosition.value
        )
    )
}

private fun PlayerViewModel.toPlayerCastRequestResult(
    result: CastMediaRequestBuildResult
): PlayerCastRequestResult = when (result) {
    is CastMediaRequestBuildResult.Success -> PlayerCastRequestResult.Success(result.request)
    is CastMediaRequestBuildResult.Unsupported -> PlayerCastRequestResult.Failure(toPlayerCastMessage(result.reason))
}

sealed interface PlayerCastRequestResult {
    data class Success(val request: CastMediaRequest) : PlayerCastRequestResult
    data class Failure(val message: String) : PlayerCastRequestResult
}

private fun PlayerViewModel.toPlayerCastMessage(
    reason: CastMediaRequestUnsupportedReason
): String = appContext.getString(reason.toCastBuildFailureMessageRes())

private fun PlayerViewModel.toPlayerCastUnsupportedMessage(request: CastMediaRequest): String =
    appContext.getString(request.toCastUnsupportedMessageRes())

private fun PlayerViewModel.toPlayerCastPlaybackMessage(event: CastPlaybackEvent): String =
    appContext.getString(event.toCastPlaybackMessageRes())
