package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val MAX_NUMERIC_CHANNEL_INPUT_DIGITS = 6

internal fun appendNumericChannelDigit(currentBuffer: String, digit: Int): String {
    val nextDigit = digit.toString()
    return if (currentBuffer.length >= MAX_NUMERIC_CHANNEL_INPUT_DIGITS) {
        nextDigit
    } else {
        currentBuffer + nextDigit
    }
}

internal data class LivePlaybackRecordCandidate(
    val playbackKey: Pair<Long, Long>,
    val history: PlaybackHistory
)

internal data class LivePlaybackRecordAttempt(
    val candidate: LivePlaybackRecordCandidate,
    val attemptId: Long
)

internal class LivePlaybackRecordCoordinator(
    private val recordPlayback: suspend (PlaybackHistory) -> com.streamvault.domain.model.Result<Unit>
) {
    private val stateLock = Any()
    private val writeMutex = Mutex()
    private var nextAttemptId = 0L
    private var latestRequestedKey: Pair<Long, Long>? = null
    private var lastSuccessfulKey: Pair<Long, Long>? = null
    private val inFlightGenerations = mutableMapOf<Pair<Long, Long>, Long>()

    fun begin(candidate: LivePlaybackRecordCandidate): LivePlaybackRecordAttempt? = synchronized(stateLock) {
        val playbackKey = candidate.playbackKey
        val isUninterruptedDuplicate = latestRequestedKey == playbackKey
        latestRequestedKey = playbackKey
        if (isUninterruptedDuplicate && playbackKey in inFlightGenerations) return@synchronized null
        if (playbackKey == lastSuccessfulKey && inFlightGenerations.isEmpty()) return@synchronized null

        LivePlaybackRecordAttempt(candidate, ++nextAttemptId).also { attempt ->
            inFlightGenerations[playbackKey] = attempt.attemptId
        }
    }

    suspend fun execute(
        attempt: LivePlaybackRecordAttempt
    ): com.streamvault.domain.model.Result<Unit>? {
        var succeeded = false
        try {
            return writeMutex.withLock {
                if (!isLatestRequest(attempt.candidate.playbackKey)) return@withLock null
                recordPlayback(attempt.candidate.history).also { result ->
                    currentCoroutineContext().ensureActive()
                    succeeded = result is com.streamvault.domain.model.Result.Success
                }
            }
        } finally {
            complete(attempt, succeeded)
        }
    }

    fun reset() {
        synchronized(stateLock) {
            latestRequestedKey = null
            lastSuccessfulKey = null
            inFlightGenerations.clear()
        }
    }

    private fun isLatestRequest(playbackKey: Pair<Long, Long>): Boolean = synchronized(stateLock) {
        latestRequestedKey == playbackKey
    }

    private fun complete(attempt: LivePlaybackRecordAttempt, succeeded: Boolean) {
        synchronized(stateLock) {
            val playbackKey = attempt.candidate.playbackKey
            if (inFlightGenerations[playbackKey] != attempt.attemptId) return
            inFlightGenerations.remove(playbackKey)
            if (succeeded && latestRequestedKey == playbackKey) {
                lastSuccessfulKey = playbackKey
            }
        }
    }
}

internal fun buildLivePlaybackRecordCandidate(
    currentProviderId: Long,
    currentContentType: ContentType,
    currentContentId: Long,
    currentTitle: String,
    currentResolvedPlaybackUrl: String,
    currentStreamUrl: String,
    channel: Channel?
): LivePlaybackRecordCandidate? {
    if (currentProviderId <= 0L || currentContentType != ContentType.LIVE) return null

    channel?.let {
        return LivePlaybackRecordCandidate(
            playbackKey = currentProviderId to it.id,
            history = PlaybackHistory(
                contentId = it.id,
                contentType = ContentType.LIVE,
                providerId = currentProviderId,
                title = it.name,
                streamUrl = it.streamUrl,
                lastWatchedAt = System.currentTimeMillis()
            )
        )
    }

    val contentId = currentContentId.takeIf { it > 0L } ?: return null
    val streamUrl = currentResolvedPlaybackUrl.ifBlank { currentStreamUrl }.takeIf { it.isNotBlank() } ?: return null
    return LivePlaybackRecordCandidate(
        playbackKey = currentProviderId to contentId,
        history = PlaybackHistory(
            contentId = contentId,
            contentType = ContentType.LIVE,
            providerId = currentProviderId,
            title = currentTitle,
            streamUrl = streamUrl,
            lastWatchedAt = System.currentTimeMillis()
        )
    )
}

internal fun releaseOutgoingLiveZapPlayback(
    stopPlayback: () -> Unit,
    stopLiveTimeshift: () -> Unit,
    clearPreload: () -> Unit
) {
    stopPlayback()
    stopLiveTimeshift()
    clearPreload()
}

internal fun shouldPreloadAdjacentChannel(
    streamUrl: String,
    providerType: ProviderType?,
    maxConnections: Int,
    preloadCoolingDown: Boolean
): Boolean {
    if (streamUrl.isBlank() || preloadCoolingDown) return false
    return when (providerType) {
        ProviderType.M3U -> true
        ProviderType.JELLYFIN -> true
        ProviderType.XTREAM_CODES,
        ProviderType.STALKER_PORTAL -> maxConnections >= 2
        null -> false
    }
}

fun PlayerViewModel.playNext() {
    clearNumericChannelInput()
    if (channelList.isEmpty()) return
    val nextIndex = wrappedChannelIndex(1)
    if (nextIndex == -1) return
    changeChannel(nextIndex)
}

fun PlayerViewModel.playPrevious() {
    clearNumericChannelInput()
    if (channelList.isEmpty()) return
    val prevIndex = wrappedChannelIndex(-1)
    if (prevIndex == -1) return
    changeChannel(prevIndex)
}

fun PlayerViewModel.zapToChannel(channelId: Long) {
    clearNumericChannelInput()
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    val index = channelList.indexOfFirst { it.id == channelId }
    if (index != -1) {
        changeChannel(index)
        closeOverlays()
    }
}

fun PlayerViewModel.zapToLastChannel() {
    clearNumericChannelInput()
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    if (previousChannelIndex in channelList.indices && previousChannelIndex != currentChannelIndex) {
        changeChannel(previousChannelIndex)
    }
}

fun PlayerViewModel.hasLastChannel(): Boolean {
    if (currentContentType != ContentType.LIVE) return false
    val channels = channelList
    if (channels.isEmpty()) return false
    return previousChannelIndex in channels.indices && previousChannelIndex != currentChannelIndex
}

fun PlayerViewModel.hasPendingNumericChannelInput(): Boolean = numericInputBuffer.isNotBlank()

fun PlayerViewModel.inputNumericChannelDigit(digit: Int) {
    if (currentContentType != ContentType.LIVE || channelList.isEmpty()) return
    if (digit !in 0..9) return

    numericInputBuffer = appendNumericChannelDigit(numericInputBuffer, digit)
    val exactMatch = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
    val previewMatch = exactMatch ?: resolveChannelByPrefix(numericInputBuffer)

    numericChannelInputFlow.value = NumericChannelInputState(
        input = numericInputBuffer,
        matchedChannelName = previewMatch?.name,
        invalid = false
    )

    scheduleNumericChannelCommit()
}

fun PlayerViewModel.commitNumericChannelInput() {
    numericInputCommitJob?.cancel()
    if (numericInputBuffer.isBlank()) return

    // "0" committed alone after timeout ג†’ zap to last channel (standard IPTV remote behaviour)
    if (numericInputBuffer == "0" && hasLastChannel()) {
        clearNumericChannelInput()
        zapToLastChannel()
        return
    }

    val targetChannel = resolveChannelByNumber(numericInputBuffer.toIntOrNull())
    if (targetChannel != null) {
        val targetIndex = channelList.indexOfFirst { it.id == targetChannel.id }
        if (targetIndex != -1) {
            changeChannel(targetIndex)
        }
        clearNumericChannelInput()
        return
    }

    numericChannelInputFlow.value = NumericChannelInputState(
        input = numericInputBuffer,
        matchedChannelName = null,
        invalid = true
    )

    numericInputFeedbackJob?.cancel()
    numericInputFeedbackJob = viewModelScope.launch {
        delay(900)
        clearNumericChannelInput()
    }
}

fun PlayerViewModel.clearNumericChannelInput() {
    numericInputCommitJob?.cancel()
    numericInputFeedbackJob?.cancel()
    numericInputBuffer = ""
    numericChannelInputFlow.value = null
}

internal fun PlayerViewModel.changeChannel(index: Int, isAutoFallback: Boolean = false) {
    check(index in channelList.indices) {
        "changeChannel index=$index out of channelList bounds (size=${channelList.size})"
    }
    clearNumericChannelInput()
    if (currentChannelIndex != -1 && currentChannelIndex != index) {
        previousChannelIndex = currentChannelIndex
    }
    val requestVersion = beginPlaybackSession()
    releaseOutgoingLiveZapPlayback(
        stopPlayback = playerEngine::stop,
        stopLiveTimeshift = playerEngine::stopLiveTimeshift,
        clearPreload = { playerEngine.preload(null) }
    )
    clearResolvedStream()
    val channel = channelList[index]
    currentChannelIndex = index
    currentContentId = channel.id
    currentTitle = channel.name
    playbackTitleFlow.value = currentTitle
    currentStreamUrl = channel.streamUrl
    pendingCatchUpUrls = emptyList()
    updateStreamClass("Primary")
    currentChannelFlow.value = channel
    refreshCurrentChannelRecording()
    displayChannelNumberFlow.value = resolveChannelNumber(channel, index)
    recentChannelsFlow.update { channels -> channels.filterNot { it.id == channel.id } }

    // Start a fresh live channel with scrubbing mode OFF. Scrubbing mode disables the
    // audio track and changes codec behaviour; previously the whole zap prepare was
    // wrapped so scrubbing was toggled ON before prepare and OFF at first frame. That
    // exit toggle re-enabled the audio renderer mid-stream and reset the codec, which
    // stalled playback for ~1-2s right as sound came in. Channel changes should behave
    // like normal playback (matching the smooth pro-mode preview).
    playerEngine.setScrubbingMode(false)

    playbackSessionScope(requestVersion)?.launch {
        val streamInfo = resolvePlaybackStreamInfo(channel.streamUrl, channel.id, channel.providerId, ContentType.LIVE)
            ?: return@launch
        if (!isActivePlaybackSession(requestVersion, channel.streamUrl)) return@launch
        if (!preparePlayer(streamInfo, requestVersion)) return@launch
        playerEngine.play()

        playerEngine.playbackState
            .filter {
                it == com.streamvault.player.PlaybackState.READY ||
                    !isActivePlaybackSession(requestVersion, channel.streamUrl)
            }
            .first()
    }

    preloadAdjacentChannel(index)

    requestEpg(
        providerId = currentProviderId,
        epgChannelId = channel.epgChannelId,
        streamId = channel.streamId,
        internalChannelId = channel.id
    )

    showZapOverlayFlow.value = false
    showControlsFlow.value = false
    openChannelInfoOverlay()

    playerRecoveryCoordinator.clearStreamAttempts()
    playerRecoveryCoordinator.markStreamAttempt(channel.streamUrl)
    if (currentContentType == ContentType.LIVE && !isAutoFallback) scheduleZapBufferWatchdog(index)
}

internal fun PlayerViewModel.preloadAdjacentChannel(currentIndex: Int) {
    if (channelList.size < 2) return
    val nextIndex = (currentIndex + 1) % channelList.size
    val nextChannel = channelList[nextIndex]
    if (nextChannel.streamUrl.isBlank()) {
        playerEngine.preload(null)
        return
    }
    playbackSessionScope()?.launch {
        val provider = playerProviderCoordinator.getProvider(nextChannel.providerId)
        if (!shouldPreloadAdjacentChannel(
                streamUrl = nextChannel.streamUrl,
                providerType = provider?.type,
                maxConnections = provider?.maxConnections ?: 1,
                preloadCoolingDown = playerRecoveryCoordinator.isLivePreloadCoolingDown(nextChannel.providerId)
            )
        ) {
            playerEngine.preload(null)
            return@launch
        }
        val streamInfo = resolvePlaybackStreamInfo(
            nextChannel.streamUrl,
            nextChannel.id,
            nextChannel.providerId,
            ContentType.LIVE
        ) ?: return@launch
        playerEngine.preload(streamInfo)
    }
}

internal fun PlayerViewModel.recordLivePlayback(channel: com.streamvault.domain.model.Channel) {
    recordActiveLivePlayback(channel)
}

internal fun PlayerViewModel.recordActiveLivePlayback(channel: Channel? = currentChannelFlow.value?.sanitizedForPlayer()) {
    val candidate = buildLivePlaybackRecordCandidate(
        currentProviderId = currentProviderId,
        currentContentType = currentContentType,
        currentContentId = currentContentId,
        currentTitle = currentTitle,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        currentStreamUrl = currentStreamUrl,
        channel = channel
    ) ?: return

    val attempt = livePlaybackRecordCoordinator.begin(candidate) ?: return

    viewModelScope.launch {
        livePlaybackRecordCoordinator.execute(attempt)?.let { result ->
            logRepositoryFailure(operation = "Record live playback", result = result)
        }
    }
}

internal fun PlayerViewModel.scheduleNumericChannelCommit() {
    numericInputCommitJob?.cancel()
    numericInputCommitJob = viewModelScope.launch {
        delay(1300)
        commitNumericChannelInput()
    }
}

internal fun PlayerViewModel.resolveChannelByNumber(number: Int?): com.streamvault.domain.model.Channel? {
    if (number == null) return null
    return channelNumberIndex[number]
}

internal fun PlayerViewModel.resolveChannelByPrefix(prefix: String): com.streamvault.domain.model.Channel? {
    return channelNumberIndex.entries
        .firstOrNull { (key, _) -> key.toString().startsWith(prefix) }
        ?.value
}

internal fun PlayerViewModel.resolveChannelNumber(
    channel: com.streamvault.domain.model.Channel,
    index: Int
): Int = when (channelNumberingMode) {
    ChannelNumberingMode.GROUP -> if (index >= 0) index + 1 else channel.number.takeIf { it > 0 } ?: 0
    ChannelNumberingMode.PROVIDER -> channel.number.takeIf { it > 0 } ?: if (index >= 0) index + 1 else 0
    ChannelNumberingMode.HIDDEN -> 0
}
