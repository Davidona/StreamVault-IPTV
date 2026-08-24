package com.streamvault.app.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.launch

internal suspend fun resolveCatchUpStreamInfo(
    candidateUrl: String,
    title: String,
    currentContentId: Long,
    currentProviderId: Long,
    resolveStreamInfo: suspend (String, Long, Long, ContentType) -> StreamInfo?
): StreamInfo? = resolveStreamInfo(candidateUrl, currentContentId, currentProviderId, ContentType.LIVE)
    ?.copy(title = title)

internal suspend fun PlayerViewModel.startCatchUpPlayback(
    urls: List<String>,
    title: String,
    recoveryAction: String,
    requestVersionOverride: Long? = null
) {
    val requestVersion = requestVersionOverride ?: beginPlaybackSession()
    val candidates = urls
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
    if (candidates.isEmpty()) return

    currentTitle = title
    pendingCatchUpUrls = candidates
    playerRecoveryCoordinator.clearStreamAttempts()
    updateStreamClass("Catch-up")
    appendRecoveryAction(recoveryAction)

    var attemptedPreparation = false
    var showedPreparationFailure = false
    candidates.forEachIndexed { index, candidateUrl ->
        if (!isActivePlaybackSession(requestVersion)) return
        currentStreamUrl = candidateUrl
        playerRecoveryCoordinator.markStreamAttempt(candidateUrl)
        appendRecoveryAction("Trying catch-up candidate ${index + 1}/${candidates.size}")
        android.util.Log.i(
            "PlayerVM",
            "catch-up candidate selected index=${index + 1}/${candidates.size}"
        )

        val catchupStream = resolveCatchUpStreamInfo(
            candidateUrl = candidateUrl,
            title = currentTitle,
            currentContentId = currentContentId,
            currentProviderId = currentProviderId,
            resolveStreamInfo = ::resolvePlaybackStreamInfo
        ) ?: run {
            android.util.Log.w(
                "PlayerVM",
                "catch-up candidate unresolved index=${index + 1}/${candidates.size}"
            )
            return@forEachIndexed
        }

        attemptedPreparation = true
        val shouldShowFailureNotice = index == candidates.lastIndex
        if (preparePlayer(
                streamInfo = catchupStream,
                requestVersion = requestVersion,
                showFailureNotice = shouldShowFailureNotice
            )
        ) {
            appendRecoveryAction("Started catch-up candidate ${index + 1}/${candidates.size}")
            android.util.Log.i(
                "PlayerVM",
                "catch-up candidate prepared index=${index + 1}/${candidates.size}"
            )
            playerEngine.play()
            return
        }
        if (shouldShowFailureNotice) {
            showedPreparationFailure = true
        }
        android.util.Log.w(
            "PlayerVM",
            "catch-up candidate failed index=${index + 1}/${candidates.size}"
        )
    }

    if (isActivePlaybackSession(requestVersion) && !showedPreparationFailure) {
        val fallbackReason = "Replay is unavailable for the selected program right now."
        val reason = if (attemptedPreparation) {
            playerDiagnostics.value.lastFailureReason ?: fallbackReason
        } else {
            fallbackReason
        }
        setLastFailureReason(reason)
        showPlayerNotice(
            message = reason,
            recoveryType = PlayerRecoveryType.CATCH_UP,
            actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
        )
    }
}

fun PlayerViewModel.playCatchUp(program: Program) {
    viewModelScope.launch {
        val requestVersion = prepareRequestVersion
        val channel = currentChannelFlow.value
        if (channel == null || !channel.isArchivePlayable(program)) {
            return@launch
        }
        val start = program.startTime / 1000L
        val end = program.endTime / 1000L
        val streamId = channel.id
        val providerId = currentProviderId

        if (providerId == -1L || streamId == 0L) {
            setLastFailureReason("Catch-up playback needs a valid live channel context.")
            showPlayerNotice(
                message = "Catch-up playback needs a valid live channel context.",
                recoveryType = PlayerRecoveryType.CATCH_UP,
                actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
            )
            return@launch
        }

        val catchUpUrls = when (val catchUpResult = playerProviderCoordinator.buildCatchUpUrls(providerId, streamId, start, end)) {
            is Result.Success -> catchUpResult.data
            is Result.Error -> {
            if (!isActivePlaybackSession(requestVersion)) return@launch
            setLastFailureReason(catchUpResult.message)
            showPlayerNotice(
                message = catchUpResult.message,
                recoveryType = PlayerRecoveryType.SOURCE,
                actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
            )
            return@launch
            }
            is Result.Loading -> return@launch
        }
        if (!isActivePlaybackSession(requestVersion)) return@launch
        if (catchUpUrls.isNotEmpty()) {
            startCatchUpPlayback(
                urls = catchUpUrls,
                title = "${channel.name}: ${program.title}",
                recoveryAction = "Started program replay"
            )
        } else {
            val reason = resolveCatchUpFailureMessage(
                channel,
                archiveRequested = true,
                programHasArchive = program.hasArchive
            )
            setLastFailureReason(reason)
            showPlayerNotice(
                message = reason,
                recoveryType = PlayerRecoveryType.CATCH_UP,
                actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
            )
        }
    }
}
