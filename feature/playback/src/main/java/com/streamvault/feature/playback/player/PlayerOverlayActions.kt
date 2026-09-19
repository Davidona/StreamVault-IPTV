package com.streamvault.feature.playback.player

import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DIAGNOSTICS_EXTRA_VISIBLE_MS = 10_000L

fun PlayerViewModel.openChannelListOverlay() {
    clearNumericChannelInput()
    showChannelListOverlayFlow.value = true
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.openCategoryListOverlay() {
    if (currentProviderId <= 0 || availableCategoriesFlow.value.isEmpty()) return
    showCategoryListOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.selectCategoryFromOverlay(category: Category) {
    showCategoryListOverlayFlow.value = false
    currentCategoryId = category.id
    activeCategoryIdFlow.value = category.id
    isVirtualCategory = category.isVirtual
    loadPlaylist(
        categoryId = category.id,
        providerId = currentProviderId,
        isVirtual = category.isVirtual,
        initialChannelId = currentContentId
    )
    openChannelListOverlay()
}

fun PlayerViewModel.openEpgOverlay() {
    clearNumericChannelInput()
    showEpgOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showControlsFlow.value = false
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.openFullGuideOverlay() {
    if (currentContentType != ContentType.LIVE) return
    clearNumericChannelInput()
    showFullGuideOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showDiagnosticsFlow.value = false
    showControlsFlow.value = false
    channelInfoHideJob?.cancel()
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.closeFullGuideOverlay() {
    showFullGuideOverlayFlow.value = false
    playerEngine.setScrubbingMode(false)
}

fun PlayerViewModel.playChannelFromGuideOverlay(
    channel: Channel,
    selectedGuideCategoryId: Long,
    favoritesOnly: Boolean,
    combinedProfileId: Long?
) {
    if (currentContentType != ContentType.LIVE || channel.streamUrl.isBlank()) return
    val playbackCategoryId = when {
        favoritesOnly -> VirtualCategoryIds.FAVORITES
        selectedGuideCategoryId != ChannelRepository.ALL_CHANNELS_ID -> selectedGuideCategoryId
        else -> ChannelRepository.ALL_CHANNELS_ID
    }
    val categoryIsVirtual = playbackCategoryId == VirtualCategoryIds.FAVORITES || playbackCategoryId < 0L
    val currentListIndex = channelList.indexOfFirst { it.id == channel.id }

    clearNumericChannelInput()
    closeFullGuideOverlay()

    if (currentListIndex != -1) {
        changeChannel(currentListIndex)
        closeOverlays()
        playerEngine.setScrubbingMode(false)
        return
    }

    prepare(
        streamUrl = channel.streamUrl,
        epgChannelId = channel.epgChannelId,
        internalChannelId = channel.id,
        categoryId = playbackCategoryId,
        providerId = channel.providerId,
        isVirtual = categoryIsVirtual,
        combinedProfileId = combinedProfileId,
        contentType = ContentType.LIVE.name,
        title = channel.name,
        artworkUrl = channel.logoUrl,
        showResumePrompt = false
    )
    playerEngine.setScrubbingMode(false)
    closeOverlays()
}

fun PlayerViewModel.openChannelInfoOverlay() {
    clearNumericChannelInput()
    showChannelInfoOverlayFlow.value = true
    showChannelListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showControlsFlow.value = false
    channelInfoHideJob?.cancel()
    scheduleLiveOverlayAutoHide()
}

fun PlayerViewModel.closeChannelInfoOverlay() {
    channelInfoHideJob?.cancel()
    showChannelInfoOverlayFlow.value = false
    if (!hasVisibleTransientLiveOverlay()) clearLiveOverlayAutoHide()
}

fun PlayerViewModel.closeOverlays() {
    clearNumericChannelInput()
    showChannelListOverlayFlow.value = false
    showCategoryListOverlayFlow.value = false
    showEpgOverlayFlow.value = false
    showFullGuideOverlayFlow.value = false
    showChannelInfoOverlayFlow.value = false
    showDiagnosticsFlow.value = false
    channelInfoHideJob?.cancel()
    clearLiveOverlayAutoHide()
    clearDiagnosticsAutoHide()
}

fun PlayerViewModel.toggleDiagnostics() {
    showDiagnosticsFlow.value = !showDiagnosticsFlow.value
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    } else {
        clearDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.onLiveOverlayInteraction() {
    if (hasVisibleTransientLiveOverlay()) {
        scheduleLiveOverlayAutoHide()
    }
    if (showDiagnosticsFlow.value) {
        scheduleDiagnosticsAutoHide()
    }
}

fun PlayerViewModel.hideControlsAfterDelay() {
    controlsHideJob?.cancel()
    controlsHideJob = viewModelScope.launch {
        delay(playerControlsTimeoutMs)
        showControlsFlow.value = false
    }
}

fun PlayerViewModel.refreshControlsAutoHide() {
    if (showControlsFlow.value) {
        hideControlsAfterDelay()
    }
}

fun PlayerViewModel.cancelControlsAutoHide() {
    controlsHideJob?.cancel()
    controlsHideJob = null
}

internal fun PlayerViewModel.hideZapOverlayAfterDelay() {
    zapOverlayJob?.cancel()
    zapOverlayJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showZapOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.hasVisibleTransientLiveOverlay(): Boolean =
    showChannelInfoOverlayFlow.value ||
        showChannelListOverlayFlow.value ||
        showEpgOverlayFlow.value

internal fun PlayerViewModel.clearLiveOverlayAutoHide() {
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = null
}

internal fun PlayerViewModel.clearDiagnosticsAutoHide() {
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = null
}

internal fun PlayerViewModel.scheduleLiveOverlayAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearLiveOverlayAutoHide()
        return
    }
    liveOverlayHideJob?.cancel()
    liveOverlayHideJob = viewModelScope.launch {
        delay(liveOverlayTimeoutMs)
        showChannelInfoOverlayFlow.value = false
        showChannelListOverlayFlow.value = false
        showEpgOverlayFlow.value = false
    }
}

internal fun PlayerViewModel.scheduleDiagnosticsAutoHide() {
    if (currentContentType != ContentType.LIVE) {
        clearDiagnosticsAutoHide()
        return
    }
    diagnosticsHideJob?.cancel()
    diagnosticsHideJob = viewModelScope.launch {
        delay(diagnosticsTimeoutMs + DIAGNOSTICS_EXTRA_VISIBLE_MS)
        showDiagnosticsFlow.value = false
    }
}
