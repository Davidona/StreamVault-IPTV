package com.streamvault.feature.playback.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.ui.components.dialogs.ProgramHistoryDialog
import com.streamvault.feature.playback.player.overlay.ChannelVariantSelectionDialog
import com.streamvault.feature.playback.player.overlay.PlayerAudioVideoOffsetDialog
import com.streamvault.feature.playback.player.overlay.PlayerEpisodeSelectionDialog
import com.streamvault.feature.playback.player.overlay.PlayerSleepTimerDialog
import com.streamvault.feature.playback.player.overlay.PlayerSpeedSelectionDialog
import com.streamvault.feature.playback.player.overlay.PlayerSubtitleOffsetDialog
import com.streamvault.feature.playback.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.domain.settings.QualityLimitOverride
import com.streamvault.player.TrackType

@Composable
internal fun PlayerTopLevelModalHost(
    viewModel: PlayerViewModel,
    isInPictureInPictureMode: Boolean,
    showProgramHistory: Boolean,
    onDismissProgramHistory: () -> Unit,
    onSelectProgramHistory: (Program) -> Unit,
    showSplitDialog: Boolean,
    currentChannel: Channel?,
    onDismissSplitDialog: () -> Unit,
    onLaunchMultiView: () -> Unit,
    splitScreenPlanner: @Composable (Channel, () -> Unit, () -> Unit) -> Unit,
) {
    if (!isInPictureInPictureMode && showProgramHistory) {
        val programHistory by viewModel.programHistory.collectAsStateWithLifecycle()
        ProgramHistoryDialog(
            programs = programHistory,
            onDismiss = onDismissProgramHistory,
            onProgramSelect = onSelectProgramHistory
        )
    }

    if (showSplitDialog && currentChannel != null) {
        splitScreenPlanner(currentChannel, onDismissSplitDialog, onLaunchMultiView)
    }
}

@Composable
internal fun PlayerControlsModalHost(
    viewModel: PlayerViewModel,
    isInPictureInPictureMode: Boolean,
    showTrackSelection: TrackType?,
    showSubtitleSearch: Boolean,
    onlineSubtitleSearchAvailable: Boolean,
    showVariantSelection: Boolean,
    currentChannel: Channel?,
    showSpeedSelection: Boolean,
    showStopPlaybackTimerDialog: Boolean,
    showIdleStandbyTimerDialog: Boolean,
    canSaveChannel: Boolean,
    showAudioVideoOffsetDialog: Boolean,
    showSubtitleOffsetDialog: Boolean,
    showEpisodePicker: Boolean,
    fallbackTitle: String,
    fallbackEpisodeId: Long,
    fallbackSeasonNumber: Int?,
    onOpenSubtitleSearch: () -> Unit,
    onOpenSubtitleOffset: () -> Unit,
    onPickLocalSubtitle: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissModal: () -> Unit,
) {
    val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
    val isCastConnected = castConnectionState == com.streamvault.feature.playback.cast.CastConnectionState.CONNECTED

    if (showAudioVideoOffsetDialog) {
        val audioVideoSyncEnabled by viewModel.audioVideoSyncEnabled.collectAsStateWithLifecycle()
        val audioVideoOffsetState by viewModel.audioVideoOffsetUiState.collectAsStateWithLifecycle()
        LaunchedEffect(audioVideoSyncEnabled) {
            if (!audioVideoSyncEnabled) {
                onDismissModal()
                viewModel.dismissAudioVideoOffsetPreview()
            }
        }
        if (!isInPictureInPictureMode) {
            PlayerAudioVideoOffsetDialog(
                visible = audioVideoSyncEnabled && castConnectionState != com.streamvault.feature.playback.cast.CastConnectionState.CONNECTED,
                state = audioVideoOffsetState,
                canSaveChannel = canSaveChannel,
                onDismiss = {
                    onDismissModal()
                    viewModel.dismissAudioVideoOffsetPreview()
                },
                onAdjust = viewModel::adjustAudioVideoOffset,
                onReset = viewModel::resetAudioVideoOffsetPreview,
                onSaveForChannel = viewModel::saveAudioVideoOffsetForChannel,
                onSaveAsGlobal = viewModel::saveAudioVideoOffsetAsGlobal,
                onUseGlobal = viewModel::useGlobalAudioVideoOffset
            )
        }
    }

    if (!isInPictureInPictureMode && showTrackSelection != null) {
        val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
        val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
        val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
        val qualityPreferenceScope by viewModel.playbackQualityPreferenceScope.collectAsStateWithLifecycle()
        val savedQualityPreference by viewModel.savedPlaybackQualityPreference.collectAsStateWithLifecycle()
        val savedProviderQualityPreference by viewModel.savedProviderPlaybackQualityPreference.collectAsStateWithLifecycle()
        val temporaryQualityPreference by viewModel.temporaryPlaybackQualityPreference.collectAsStateWithLifecycle()
        val videoFormat by viewModel.videoFormat.collectAsStateWithLifecycle()
        val liveTranslationAvailable by viewModel.liveTranslationAvailable.collectAsStateWithLifecycle()
        val liveTranslationActive by viewModel.liveTranslationActive.collectAsStateWithLifecycle()
        val subtitleOffsetMs by viewModel.subtitleOffsetMs.collectAsStateWithLifecycle()
        if (viewModel.currentContentType != com.streamvault.domain.model.ContentType.LIVE &&
            showTrackSelection != TrackType.VIDEO) {
            val textScale by viewModel.subtitleTextScale.collectAsStateWithLifecycle()
            val backgroundColor by viewModel.subtitleBackgroundColor.collectAsStateWithLifecycle()
            VodTrackSelectionSheet(
                trackType = showTrackSelection,
                tracks = if (showTrackSelection == TrackType.TEXT) availableSubtitleTracks else availableAudioTracks,
                offsetMs = subtitleOffsetMs,
                textScale = textScale,
                backgroundColor = backgroundColor,
                onlineAvailable = onlineSubtitleSearchAvailable,
                onDismiss = onDismissModal,
                onSelectTrack = { id ->
                    if (showTrackSelection == TrackType.TEXT) {
                        viewModel.deactivateLiveTranslation()
                        viewModel.selectSubtitleTrack(id)
                    } else if (id != null) viewModel.selectAudioTrack(id)
                },
                onSearchOnline = onOpenSubtitleSearch,
                onLoadLocal = { onDismissModal(); onPickLocalSubtitle() },
                onAdjustOffset = viewModel::adjustSubtitleOffset,
                onResetOffset = viewModel::resetSubtitleOffset,
                onSetTextScale = viewModel::setSubtitleTextScale,
                onSetOpacity = viewModel::setSubtitleBackgroundOpacity,
            )
        } else PlayerTrackSelectionDialog(
            trackType = showTrackSelection,
            audioTracks = availableAudioTracks,
            subtitleTracks = availableSubtitleTracks,
            videoTracks = availableVideoQualities,
            isCastConnected = isCastConnected,
            liveTranslationAvailable = liveTranslationAvailable,
            liveTranslationActive = liveTranslationActive,
            onlineSubtitleSearchAvailable = onlineSubtitleSearchAvailable,
            subtitleOffsetMs = subtitleOffsetMs,
            canSavePlaybackQuality = qualityPreferenceScope != null &&
                !isCastConnected &&
                (availableVideoQualities.any { it.videoHeight != null && it.isSelected } || videoFormat.height > 0),
            canSaveProviderPlaybackQuality = viewModel.currentProviderId > 0L &&
                !isCastConnected &&
                (availableVideoQualities.any { it.videoHeight != null && it.isSelected } || videoFormat.height > 0),
            canSavePlaybackBitrate = qualityPreferenceScope != null &&
                !isCastConnected &&
                (availableVideoQualities.any { it.videoBitrateBps != null && it.isSelected } || videoFormat.bitrate > 0),
            canSaveProviderPlaybackBitrate = viewModel.currentProviderId > 0L &&
                !isCastConnected &&
                (availableVideoQualities.any { it.videoBitrateBps != null && it.isSelected } || videoFormat.bitrate > 0),
            canSetTemporaryPlaybackQuality = !isCastConnected &&
                (availableVideoQualities.any { it.videoHeight != null && it.isSelected } || videoFormat.height > 0),
            hasSavedPlaybackQuality = savedQualityPreference != null,
            hasSavedProviderPlaybackQuality = savedProviderQualityPreference != null,
            canEditPlaybackQuality = qualityPreferenceScope != null,
            hasSavedPlaybackHeight = savedQualityPreference?.let {
                it.maxVideoHeight != QualityLimitOverride.Inherit
            } == true,
            hasSavedPlaybackBitrate = savedQualityPreference?.let {
                it.maxVideoBitrateBps != QualityLimitOverride.Inherit
            } == true,
            hasSavedProviderHeight = savedProviderQualityPreference?.let {
                it.maxVideoHeight != QualityLimitOverride.Inherit
            } == true,
            hasSavedProviderBitrate = savedProviderQualityPreference?.let {
                it.maxVideoBitrateBps != QualityLimitOverride.Inherit
            } == true,
            playbackHeightHasNoAdditionalLimit = savedQualityPreference?.maxVideoHeight ==
                QualityLimitOverride.NoAdditionalLimit,
            playbackBitrateHasNoAdditionalLimit = savedQualityPreference?.maxVideoBitrateBps ==
                QualityLimitOverride.NoAdditionalLimit,
            hasTemporaryPlaybackQuality = temporaryQualityPreference != null,
            onSavePlaybackQuality = viewModel::saveCurrentQualityAsAdaptiveLimit,
            onSavePlaybackBitrate = viewModel::saveCurrentVideoBitrateAsAdaptiveLimit,
            onRemovePlaybackQuality = viewModel::removeSavedQualityLimit,
            onSaveProviderPlaybackQuality = viewModel::saveCurrentQualityAsProviderDefaultLimit,
            onSaveProviderPlaybackBitrate = viewModel::saveCurrentVideoBitrateAsProviderDefaultLimit,
            onRemoveProviderPlaybackQuality = viewModel::removeProviderQualityLimit,
            onInheritPlaybackHeight = viewModel::useInheritedPlaybackHeightLimit,
            onInheritPlaybackBitrate = viewModel::useInheritedPlaybackBitrateLimit,
            onNoAdditionalPlaybackHeight = viewModel::useNoAdditionalPlaybackHeightLimit,
            onNoAdditionalPlaybackBitrate = viewModel::useNoAdditionalPlaybackBitrateLimit,
            onInheritProviderHeight = viewModel::useInheritedProviderHeightLimit,
            onInheritProviderBitrate = viewModel::useInheritedProviderBitrateLimit,
            onSetTemporaryPlaybackQuality = viewModel::setTemporaryQualityLimitAtCurrentResolution,
            onClearTemporaryPlaybackQuality = viewModel::clearTemporaryQualityLimit,
            onDismiss = onDismissModal,
            onSelectAudio = viewModel::selectAudioTrack,
            onSelectVideo = { viewModel.selectVideoQuality(it) },
            onTrySelectVideo = viewModel::selectVideoQuality,
            onSelectSubtitle = { trackId ->
                viewModel.deactivateLiveTranslation()
                viewModel.selectSubtitleTrack(trackId)
            },
            onSelectLiveTranslation = {
                viewModel.selectSubtitleTrack(null)
                viewModel.activateLiveTranslation()
            },
            onSearchOnlineSubtitles = onOpenSubtitleSearch,
            onOpenSubtitleOffset = onOpenSubtitleOffset,
            onLoadLocalSubtitle = {
                onDismissModal()
                onPickLocalSubtitle()
            },
        )
    }
    if (!isInPictureInPictureMode && showSubtitleOffsetDialog) {
        val subtitleOffsetMs by viewModel.subtitleOffsetMs.collectAsStateWithLifecycle()
        PlayerSubtitleOffsetDialog(
            visible = true,
            offsetMs = subtitleOffsetMs,
            onDismiss = onDismissModal,
            onAdjust = viewModel::adjustSubtitleOffset,
            onReset = viewModel::resetSubtitleOffset
        )
    }
    if (!isInPictureInPictureMode && showSubtitleSearch) {
        PlayerExternalSubtitleSearchDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.cancelExternalSubtitleSearch()
                onDismissModal()
            },
            onOpenSettings = {
                viewModel.cancelExternalSubtitleSearch()
                onDismissModal()
                onOpenSettings()
            },
        )
    }
    if (!isInPictureInPictureMode && showVariantSelection) {
        ChannelVariantSelectionDialog(
            visible = true,
            channel = currentChannel,
            onDismiss = onDismissModal,
            onSelectVariant = viewModel::selectLiveVariant
        )
    }
    if (!isInPictureInPictureMode && showSpeedSelection) {
        val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
        PlayerSpeedSelectionDialog(
            visible = true,
            selectedSpeed = playbackSpeed,
            onDismiss = onDismissModal,
            onSelectSpeed = viewModel::setPlaybackSpeed
        )
    }
    if (!isInPictureInPictureMode && showStopPlaybackTimerDialog) {
        val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
        PlayerSleepTimerDialog(
            visible = true,
            title = stringResource(R.string.player_stop_playback_after),
            selectedMinutes = sleepTimerUiState.stopTimerMinutes,
            onDismiss = onDismissModal,
            onSelectMinutes = { minutes ->
                viewModel.notifyUserActivity()
                viewModel.setStopPlaybackTimer(minutes)
                onDismissModal()
            }
        )
    }
    if (!isInPictureInPictureMode && showIdleStandbyTimerDialog) {
        val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
        PlayerSleepTimerDialog(
            visible = true,
            title = stringResource(R.string.player_idle_standby_after),
            selectedMinutes = sleepTimerUiState.idleTimerMinutes,
            onDismiss = onDismissModal,
            onSelectMinutes = { minutes ->
                viewModel.notifyUserActivity()
                viewModel.setIdleStandbyTimer(minutes)
                onDismissModal()
            }
        )
    }
    if (!isInPictureInPictureMode && showEpisodePicker) {
        val playbackTitle by viewModel.playbackTitle.collectAsStateWithLifecycle()
        val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
        val currentEpisode by viewModel.currentEpisode.collectAsStateWithLifecycle()
        val seasons = remember(currentSeries) {
            currentSeries?.seasons.sanitizedForPlayer().orEmpty()
        }
        PlayerEpisodeSelectionDialog(
            visible = true,
            seriesTitle = currentSeries?.name ?: playbackTitle.ifBlank { fallbackTitle },
            seasons = seasons,
            currentEpisodeId = currentEpisode?.id ?: fallbackEpisodeId,
            currentSeasonNumber = currentEpisode?.seasonNumber ?: fallbackSeasonNumber,
            onDismiss = onDismissModal,
            onSelectEpisode = { episode ->
                onDismissModal()
                viewModel.playEpisode(episode)
            }
        )
    }
}
