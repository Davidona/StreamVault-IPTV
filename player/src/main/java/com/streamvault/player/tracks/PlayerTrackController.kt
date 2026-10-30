package com.streamvault.player.tracks

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.domain.settings.SubtitleLanguageOrdering
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.domain.settings.PlaybackConnectionType
import com.streamvault.domain.settings.PlaybackQualityConstraint
import com.streamvault.domain.settings.PlaybackQualityConstraintSource
import com.streamvault.domain.settings.PlaybackQualityPolicyInput
import com.streamvault.domain.settings.PlaybackQualityPolicyResolver
import com.streamvault.domain.settings.PlaybackQualityPreferences
import com.streamvault.domain.settings.ScopedPlaybackQualityPreference
import com.streamvault.domain.settings.ResolvedPlaybackQualityPolicy
import com.streamvault.domain.settings.evaluate
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SubtitleTrackSource
import com.streamvault.player.TrackType
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PlayerTrackController(
    private val context: Context
) {
    private val _availableAudioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val availableAudioTracks: StateFlow<List<PlayerTrack>> = _availableAudioTracks.asStateFlow()

    private val _availableSubtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = _availableSubtitleTracks.asStateFlow()

    private val _availableVideoTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val availableVideoTracks: StateFlow<List<PlayerTrack>> = _availableVideoTracks.asStateFlow()

    private var selectedVideoTrackId: String = PLAYER_TRACK_AUTO_ID
    internal val isVideoAutoSelected: Boolean get() = selectedVideoTrackId == PLAYER_TRACK_AUTO_ID
    private var preferredAudioLanguageTag: String? = null
    private var playbackQualityPreferences: PlaybackQualityPreferences = PlaybackQualityPreferences()
    private var providerQualityPreference: ScopedPlaybackQualityPreference? = null
    private var contentQualityPreference: ScopedPlaybackQualityPreference? = null
    private var temporaryQualityPreference: ScopedPlaybackQualityPreference? = null
    private var consentedVideoTrackId: String? = null
    private var consentedVideoQualityIssues: Set<com.streamvault.domain.settings.VideoQualityIssue> = emptySet()
    private var vodTrackPreferences: VodTrackPreferences? = null
    private var pendingExternalSubtitleAutoSelect: SubtitleTrackSource? = null
    private var pinnedSubtitleLanguages: List<String> = emptyList()

    fun resetSelections(preserveVideoSelection: Boolean = false) {
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        _availableVideoTracks.value = emptyList()
        if (!preserveVideoSelection) {
            selectedVideoTrackId = PLAYER_TRACK_AUTO_ID
            clearQualityConsent()
        }
        pendingExternalSubtitleAutoSelect = null
    }

    fun applyInitialParameters(
        player: ExoPlayer,
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean = false
    ) {
        val qualityPolicy = resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setPreferredAudioLanguage(preferredAudioLanguageTag)
            .applyPreferredTextTrackPolicy(resolvePreferredTextTrackPolicy(vodTrackPreferences))
            .setViewportSizeToPhysicalDisplaySize(true)
            .apply { applyVideoQualityLimit(qualityPolicy) }
            .build()
    }

    fun applyVideoQualityParameters(
        player: ExoPlayer,
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean = false
    ) {
        val policy = policyForCurrentVideoSelection(
            resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        )
        revalidateVideoOverride(player, policy)
        val updated = player.trackSelectionParameters
            .buildUpon()
            .apply { applyVideoQualityLimit(policy) }
            .build()
        if (updated != player.trackSelectionParameters) player.trackSelectionParameters = updated
    }

    fun setVodTrackPreferences(player: ExoPlayer?, preferences: VodTrackPreferences?) {
        vodTrackPreferences = preferences
        player?.let(::applyVodTrackPreferences)
    }

    fun applyVodTrackPreferences(player: ExoPlayer) {
        val preferences = vodTrackPreferences ?: return
        val builder = player.trackSelectionParameters
            .buildUpon()
            .applyPreferredTextTrackPolicy(resolvePreferredTextTrackPolicy(preferences))

        preferences.audio?.let { preference ->
            val selected = resolvePreferredPlayerTrack(preference, _availableAudioTracks.value)
            if (selected != null) {
                findOverride(player.currentTracks, C.TRACK_TYPE_AUDIO, selected.id)?.let { override ->
                    builder
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(override)
                    _availableAudioTracks.update { tracks ->
                        tracks.map { track -> track.copy(isSelected = track.id == selected.id) }
                    }
                }
            }
        }

        preferences.subtitle?.let { preference ->
            if (preference.externalSubtitleDownloadId != null) {
                builder
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                _availableSubtitleTracks.update { tracks -> tracks.map { it.copy(isSelected = false) } }
            } else {
                val selected = resolvePreferredPlayerTrack(preference, _availableSubtitleTracks.value)
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (preference.disabled || selected == null) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    _availableSubtitleTracks.update { tracks -> tracks.map { it.copy(isSelected = false) } }
                } else {
                    findOverride(player.currentTracks, C.TRACK_TYPE_TEXT, selected.id)?.let { override ->
                        builder
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(override)
                        _availableSubtitleTracks.update { tracks ->
                            tracks.map { track -> track.copy(isSelected = track.id == selected.id) }
                        }
                    }
                }
            }
        }

        val updatedParameters = builder.build()
        if (updatedParameters != player.trackSelectionParameters) {
            player.trackSelectionParameters = updatedParameters
        }
    }

    fun setPreferredAudioLanguage(player: ExoPlayer?, languageTag: String?) {
        preferredAudioLanguageTag = languageTag?.trim()?.takeIf { it.isNotBlank() }
        player?.trackSelectionParameters = player?.trackSelectionParameters
            ?.buildUpon()
            ?.setPreferredAudioLanguage(preferredAudioLanguageTag)
            ?.build()
            ?: return
    }

    fun setPinnedSubtitleLanguages(languages: List<String>) {
        pinnedSubtitleLanguages = SubtitleLanguageOrdering.normalizePinned(languages)
        _availableSubtitleTracks.update { tracks ->
            SubtitleLanguageOrdering.sortByPinned(
                pinnedTags = pinnedSubtitleLanguages,
                items = tracks,
                languageOf = PlayerTrack::language
            )
        }
    }

    fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?): Boolean {
        val updated = playbackQualityPreferences.copy(
            wifiLimit = playbackQualityPreferences.wifiLimit.copy(maxVideoHeight = wifiMaxHeight),
            ethernetLimit = playbackQualityPreferences.ethernetLimit.copy(maxVideoHeight = ethernetMaxHeight)
        ).normalized()
        if (updated == playbackQualityPreferences) return false
        clearQualityConsent()
        playbackQualityPreferences = updated
        return true
    }

    fun setPlaybackQualityPreferences(preferences: PlaybackQualityPreferences): Boolean {
        val normalized = preferences.normalized()
        if (normalized == playbackQualityPreferences) return false
        clearQualityConsent()
        playbackQualityPreferences = normalized
        return true
    }

    fun setScopedPlaybackQualityPreferences(
        providerDefault: ScopedPlaybackQualityPreference?,
        contentPreference: ScopedPlaybackQualityPreference?,
        temporarySelection: ScopedPlaybackQualityPreference? = null
    ): Boolean {
        if (providerDefault == providerQualityPreference &&
            contentPreference == contentQualityPreference &&
            temporarySelection == temporaryQualityPreference
        ) return false
        clearQualityConsent()
        providerQualityPreference = providerDefault
        contentQualityPreference = contentPreference
        temporaryQualityPreference = temporarySelection
        return true
    }

    fun hasActiveQualityLimit(
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): Boolean {
        val limit = resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode).limit
        return limit.maxVideoHeight != null || limit.maxVideoBitrateBps != null
    }

    fun qualityPolicySnapshot(
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): ResolvedPlaybackQualityPolicy =
        resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)

    fun qualityPolicyDiagnostic(
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): String {
        val policy = resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        val activeConstraints = policy.constraints
            .filter { it.limit.maxVideoHeight != null || it.limit.maxVideoBitrateBps != null }
            .joinToString(separator = ",") { constraint ->
                buildString {
                    append(constraint.source.name)
                    constraint.limit.maxVideoHeight?.let { append(":height=$it") }
                    constraint.limit.maxVideoBitrateBps?.let { append(":bitrateBps=$it") }
                }
            }.ifBlank { "none" }
        return "effectiveHeight=${policy.limit.maxVideoHeight ?: "none"} " +
            "effectiveBitrateBps=${policy.limit.maxVideoBitrateBps ?: "none"} " +
            "constraints=$activeConstraints"
    }

    fun clearQualityConsent() {
        consentedVideoTrackId = null
        consentedVideoQualityIssues = emptySet()
    }

    fun playbackQualityGateState(
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): com.streamvault.player.PlaybackQualityGateState? {
        val tracks = _availableVideoTracks.value.filter { it.id != PLAYER_TRACK_AUTO_ID }
        if (tracks.isEmpty()) return null

        val policy = resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        val userPolicy = policyForSources(
            policy,
            PlaybackQualityConstraintSource.entries.filterNot(::isRuntimeConstraint).toSet()
        )
        val runtimePolicy = policyForSources(
            policy,
            PlaybackQualityConstraintSource.entries.filter(::isRuntimeConstraint).toSet()
        )
        val consentedTrack = consentedVideoTrackId?.let { id -> tracks.firstOrNull { it.id == id } }
        if (consentedTrack != null) {
            if (runtimePolicy.evaluate(consentedTrack.toVideoQualityCandidate()).isEligible) return null
            clearQualityConsent()
        }
        val hasUserLimit = userPolicy.limit.maxVideoHeight != null || userPolicy.limit.maxVideoBitrateBps != null
        val hasRuntimeLimit = runtimePolicy.limit.maxVideoHeight != null || runtimePolicy.limit.maxVideoBitrateBps != null
        if (!hasUserLimit && !hasRuntimeLimit) return null

        val runtimeEligibleTracks = tracks.filter { track ->
            runtimePolicy.evaluate(track.toVideoQualityCandidate()).isEligible
        }
        if (runtimeEligibleTracks.any { track ->
                userPolicy.evaluate(track.toVideoQualityCandidate()).isEligible
            }
        ) return null

        if (runtimeEligibleTracks.isEmpty()) {
            val candidate = tracks.maxWithOrNull(
                compareBy<PlayerTrack> { it.videoHeight ?: -1 }
                    .thenBy { it.videoBitrateBps ?: -1L }
                    .thenBy { it.id }
            ) ?: return null
            return com.streamvault.player.PlaybackQualityGateState(
                candidate = candidate,
                limit = policy.limit,
                issues = policy.evaluate(candidate.toVideoQualityCandidate()).issues,
                canContinueOnce = false
            )
        }

        if (!hasUserLimit) return null

        val candidate = runtimeEligibleTracks.asSequence()
            .map { it to userPolicy.evaluate(it.toVideoQualityCandidate()) }
            .filter { (_, evaluation) -> evaluation.issues.isNotEmpty() }
            .sortedWith(
                compareByDescending<Pair<PlayerTrack, com.streamvault.domain.settings.VideoQualityCandidateEvaluation>> {
                    it.first.videoHeight ?: -1
                }.thenByDescending { it.first.videoBitrateBps ?: -1L }
                    .thenBy { it.first.id }
            )
            .firstOrNull()
            ?: return null

        return com.streamvault.player.PlaybackQualityGateState(
            candidate = candidate.first,
            limit = userPolicy.limit,
            issues = candidate.second.issues
        )
    }

    fun continueVideoTrackOnce(
        player: ExoPlayer,
        trackId: String,
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): Boolean {
        val gate = playbackQualityGateState(constrainResolutionForMultiView, compatibilityMode) ?: return false
        if (!gate.canContinueOnce || gate.candidate.id != trackId) return false
        val track = _availableVideoTracks.value.firstOrNull { it.id == trackId } ?: return false
        val qualityPolicy = policyForCurrentVideoSelection(
            resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        )
        val runtimePolicy = policyForSources(
            qualityPolicy,
            PlaybackQualityConstraintSource.entries.filter(::isRuntimeConstraint).toSet()
        )
        val candidate = track.toVideoQualityCandidate()
        val evaluation = qualityPolicy.evaluate(candidate)
        if (evaluation.isEligible || !runtimePolicy.evaluate(candidate).isEligible) return false
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_VIDEO, trackId) ?: return false

        consentedVideoTrackId = trackId
        consentedVideoQualityIssues = evaluation.issues
        selectedVideoTrackId = trackId
        val updated = player.trackSelectionParameters
            .buildUpon()
            .apply { applyVideoQualityLimit(policyForCurrentVideoSelection(qualityPolicy)) }
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setOverrideForType(override)
            .build()
        if (updated != player.trackSelectionParameters) player.trackSelectionParameters = updated
        _availableVideoTracks.update { tracks -> tracks.map { it.copy(isSelected = it.id == trackId) } }
        return true
    }

    fun onTracksChanged(tracks: Tracks) {
        val audioTracks = mutableListOf<PlayerTrack>()
        val subtitleTracks = mutableListOf<PlayerTrack>()
        val videoTracks = mutableListOf<PlayerTrack>()

        for (group in tracks.groups) {
            val type = group.mediaTrackGroup.type
            val isAudio = type == C.TRACK_TYPE_AUDIO
            val isText = type == C.TRACK_TYPE_TEXT
            val isVideo = type == C.TRACK_TYPE_VIDEO

            if (isAudio || isText || isVideo) {
                for (index in 0 until group.length) {
                    val format = group.mediaTrackGroup.getFormat(index)
                    val id = playerTrackId(format, type, group.mediaTrackGroup.hashCode(), index)
                    val track = PlayerTrack(
                        id = id,
                        name = buildTrackName(format, type, index),
                        language = format.language,
                        type = when {
                            isAudio -> TrackType.AUDIO
                            isText -> TrackType.TEXT
                            else -> TrackType.VIDEO
                        },
                        isSelected = if (isVideo) selectedVideoTrackId == id else group.isTrackSelected(index),
                        subtitleSource = when (format.id) {
                            com.streamvault.player.ONLINE_SUBTITLE_TRACK_ID -> com.streamvault.player.SubtitleTrackSource.ONLINE
                            com.streamvault.player.DEVICE_SUBTITLE_TRACK_ID -> com.streamvault.player.SubtitleTrackSource.DEVICE
                            com.streamvault.player.STREMIO_SUBTITLE_TRACK_ID -> com.streamvault.player.SubtitleTrackSource.STREMIO
                            else -> com.streamvault.player.SubtitleTrackSource.PROVIDER
                        },
                        videoHeight = format.height.takeIf { isVideo && it > 0 },
                        videoBitrateBps = format.bitrate.takeIf { isVideo && it > 0 }?.toLong(),
                    )
                    when {
                        isAudio && group.isTrackSupported(index, false) -> audioTracks += track
                        isText && group.isTrackSupported(index, false) -> subtitleTracks += track
                        isVideo && group.isTrackSupported(index, false) -> videoTracks += track
                    }
                }
            }
        }

        selectedVideoTrackId = normalizeSelectedVideoTrackId(
            selectedTrackId = selectedVideoTrackId,
            availableTrackIds = videoTracks.map(PlayerTrack::id)
        )
        if (videoTracks.isNotEmpty() && consentedVideoTrackId?.let { id -> videoTracks.none { it.id == id } } == true) {
            clearQualityConsent()
        }

        _availableAudioTracks.value = audioTracks
        _availableSubtitleTracks.value = SubtitleLanguageOrdering.sortByPinned(
            pinnedTags = pinnedSubtitleLanguages,
            items = subtitleTracks,
            languageOf = PlayerTrack::language
        )
        _availableVideoTracks.value = when {
            videoTracks.size > 1 -> listOf(
                PlayerTrack(
                    id = PLAYER_TRACK_AUTO_ID,
                    name = "",
                    language = null,
                    type = TrackType.VIDEO,
                    isSelected = selectedVideoTrackId == PLAYER_TRACK_AUTO_ID
                )
            ) + videoTracks
            videoTracks.size == 1 -> listOf(videoTracks.first().copy(isSelected = true))
            else -> emptyList()
        }
    }

    fun reapplyVideoSelection(
        player: ExoPlayer,
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ) {
        if (selectedVideoTrackId == PLAYER_TRACK_AUTO_ID) return
        if (_availableVideoTracks.value.none { it.id != PLAYER_TRACK_AUTO_ID }) return
        val policy = policyForCurrentVideoSelection(
            resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        )
        revalidateVideoOverride(player, policy)
        if (selectedVideoTrackId == PLAYER_TRACK_AUTO_ID) return
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_VIDEO, selectedVideoTrackId)
            ?: run {
                selectVideoTrack(player, PLAYER_TRACK_AUTO_ID)
                return
            }
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setOverrideForType(override)
            .build()
        _availableVideoTracks.update { tracks ->
            tracks.map { track -> track.copy(isSelected = track.id == selectedVideoTrackId) }
        }
    }

    fun selectAudioTrack(player: ExoPlayer, trackId: String) {
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_AUDIO, trackId) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(override)
            .build()
    }

    fun selectVideoTrack(
        player: ExoPlayer,
        trackId: String,
        constrainResolutionForMultiView: Boolean = false,
        compatibilityMode: Boolean = false
    ): Boolean {
        if (trackId == PLAYER_TRACK_AUTO_ID) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            clearQualityConsent()
            selectedVideoTrackId = PLAYER_TRACK_AUTO_ID
        } else {
            val selectedTrack = _availableVideoTracks.value.firstOrNull { it.id == trackId } ?: return false
            val policy = policyForCurrentVideoSelection(
                resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
            )
            val evaluation = policy.evaluate(
                com.streamvault.domain.settings.VideoQualityCandidate(
                    identity = selectedTrack.id,
                    height = selectedTrack.videoHeight,
                    advertisedBitrateBps = selectedTrack.videoBitrateBps
                )
            )
            if (!evaluation.isEligible) return false
            val override = findOverride(player.currentTracks, C.TRACK_TYPE_VIDEO, trackId) ?: return false
            if (trackId != consentedVideoTrackId) clearQualityConsent()
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setOverrideForType(override)
                .build()
            selectedVideoTrackId = trackId
        }
        _availableVideoTracks.update { tracks ->
            tracks.map { track ->
                track.copy(isSelected = track.id == selectedVideoTrackId)
            }
        }
        return true
    }

    fun canSelectVideoTrack(
        player: ExoPlayer,
        trackId: String,
        constrainResolutionForMultiView: Boolean = false,
        compatibilityMode: Boolean = false
    ): Boolean {
        if (trackId == PLAYER_TRACK_AUTO_ID) return true
        val track = _availableVideoTracks.value.firstOrNull { it.id == trackId } ?: return false
        val policy = policyForCurrentVideoSelection(
            resolveQualityPolicy(constrainResolutionForMultiView, compatibilityMode)
        )
        val candidate = track.toVideoQualityCandidate()
        return policy.evaluate(candidate).isEligible &&
            findOverride(player.currentTracks, C.TRACK_TYPE_VIDEO, trackId) != null
    }

    fun selectSubtitleTrack(player: ExoPlayer, trackId: String?) {
        if (trackId == null) {
            vodTrackPreferences = (vodTrackPreferences ?: VodTrackPreferences()).copy(
                subtitle = VodTrackPreference(disabled = true)
            )
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            return
        }
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_TEXT, trackId) ?: return
        val selectedTrack = _availableSubtitleTracks.value.firstOrNull { it.id == trackId }
        val currentPreferences = vodTrackPreferences ?: VodTrackPreferences()
        val preservedDownloadId = currentPreferences.subtitle
            ?.externalSubtitleDownloadId
            ?.takeIf { selectedTrack?.subtitleSource == SubtitleTrackSource.ONLINE }
        vodTrackPreferences = currentPreferences.copy(
            subtitle = selectedTrack?.let { track ->
                VodTrackPreference(
                    trackId = track.id,
                    language = track.language,
                    label = track.name,
                    externalSubtitleDownloadId = preservedDownloadId
                )
            } ?: VodTrackPreference(trackId = trackId, externalSubtitleDownloadId = preservedDownloadId)
        )
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(override)
            .build()
    }

    fun requestExternalSubtitleAutoSelect(source: SubtitleTrackSource) {
        pendingExternalSubtitleAutoSelect = source
    }

    fun applyPendingExternalSubtitleAutoSelect(player: ExoPlayer) {
        val source = pendingExternalSubtitleAutoSelect ?: return
        val track = _availableSubtitleTracks.value.lastOrNull { it.subtitleSource == source } ?: return
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_TEXT, track.id) ?: return
        pendingExternalSubtitleAutoSelect = null
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(override)
            .build()
        _availableSubtitleTracks.update { tracks ->
            tracks.map { existing -> existing.copy(isSelected = existing.id == track.id) }
        }
        val currentPreferences = vodTrackPreferences ?: VodTrackPreferences()
        val preservedDownloadId = currentPreferences.subtitle
            ?.externalSubtitleDownloadId
            ?.takeIf { source == SubtitleTrackSource.ONLINE }
        vodTrackPreferences = currentPreferences.copy(
            subtitle = VodTrackPreference(
                trackId = track.id,
                language = track.language,
                label = track.name,
                externalSubtitleDownloadId = preservedDownloadId
            )
        )
    }

    private fun findOverride(tracks: Tracks, trackType: Int, trackId: String): TrackSelectionOverride? {
        for (group in tracks.groups) {
            if (group.mediaTrackGroup.type != trackType) continue
            for (index in 0 until group.length) {
                val format = group.mediaTrackGroup.getFormat(index)
                val id = playerTrackId(format, trackType, group.mediaTrackGroup.hashCode(), index)
                if (id == trackId) {
                    return TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
                }
            }
        }
        return null
    }

    private fun resolveQualityPolicy(
        constrainResolutionForMultiView: Boolean,
        compatibilityMode: Boolean
    ): ResolvedPlaybackQualityPolicy {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = if (connectivityManager != null && network != null) {
            connectivityManager.getNetworkCapabilities(network)
        } else {
            null
        }
        val connection = when {
            capabilities == null && network == null -> PlaybackConnectionType.OFFLINE
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> PlaybackConnectionType.ETHERNET
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> PlaybackConnectionType.WIFI
            else -> PlaybackConnectionType.UNCLASSIFIED
        }
        val runtimeConstraints = buildList {
            if (constrainResolutionForMultiView) {
                add(
                    PlaybackQualityConstraint(
                        PlaybackQualityConstraintSource.MULTIVIEW,
                        com.streamvault.domain.settings.PlaybackQualityLimit(maxVideoHeight = 720)
                    )
                )
            }
            if (compatibilityMode) {
                add(
                    PlaybackQualityConstraint(
                        PlaybackQualityConstraintSource.COMPATIBILITY_MODE,
                        com.streamvault.domain.settings.PlaybackQualityLimit(maxVideoHeight = 720)
                    )
                )
            }
        }
        return PlaybackQualityPolicyResolver.resolve(
            PlaybackQualityPolicyInput(
                preferences = playbackQualityPreferences,
                connection = connection,
                providerDefault = providerQualityPreference,
                contentPreference = contentQualityPreference,
                temporarySelection = temporaryQualityPreference,
                runtimeConstraints = runtimeConstraints
            )
        )
    }

    private fun policyForCurrentVideoSelection(
        policy: ResolvedPlaybackQualityPolicy
    ): ResolvedPlaybackQualityPolicy {
        if (selectedVideoTrackId != consentedVideoTrackId || consentedVideoQualityIssues.isEmpty()) return policy
        val hardLimits = policyForSources(
            policy,
            PlaybackQualityConstraintSource.entries.filter(::isRuntimeConstraint).toSet()
        ).limit
        val relaxHeight = consentedVideoQualityIssues.any {
            it == com.streamvault.domain.settings.VideoQualityIssue.EXCEEDS_HEIGHT_LIMIT ||
                it == com.streamvault.domain.settings.VideoQualityIssue.UNKNOWN_HEIGHT
        }
        val relaxBitrate = consentedVideoQualityIssues.any {
            it == com.streamvault.domain.settings.VideoQualityIssue.EXCEEDS_BITRATE_LIMIT ||
                it == com.streamvault.domain.settings.VideoQualityIssue.UNKNOWN_BITRATE
        }
        return policy.copy(
            limit = com.streamvault.domain.settings.PlaybackQualityLimit(
                maxVideoHeight = if (relaxHeight) hardLimits.maxVideoHeight else policy.limit.maxVideoHeight,
                maxVideoBitrateBps = if (relaxBitrate) hardLimits.maxVideoBitrateBps else policy.limit.maxVideoBitrateBps
            )
        )
    }

    private fun policyForSources(
        policy: ResolvedPlaybackQualityPolicy,
        sources: Set<PlaybackQualityConstraintSource>
    ): ResolvedPlaybackQualityPolicy {
        val constraints = policy.constraints.filter { it.source in sources }
        return ResolvedPlaybackQualityPolicy(
            limit = com.streamvault.domain.settings.PlaybackQualityLimit(
                maxVideoHeight = constraints.mapNotNull { it.limit.maxVideoHeight }.minOrNull(),
                maxVideoBitrateBps = constraints.mapNotNull { it.limit.maxVideoBitrateBps }.minOrNull()
            ),
            constraints = constraints
        )
    }

    private fun isRuntimeConstraint(source: PlaybackQualityConstraintSource): Boolean =
        source == PlaybackQualityConstraintSource.MULTIVIEW ||
            source == PlaybackQualityConstraintSource.COMPATIBILITY_MODE ||
            source == PlaybackQualityConstraintSource.DECODER_PRESSURE

    private fun PlayerTrack.toVideoQualityCandidate() =
        com.streamvault.domain.settings.VideoQualityCandidate(
            identity = id,
            height = videoHeight,
            advertisedBitrateBps = videoBitrateBps
        )

    private fun androidx.media3.common.TrackSelectionParameters.Builder.applyVideoQualityLimit(
        policy: ResolvedPlaybackQualityPolicy
    ) {
        policy.limit.maxVideoHeight?.let { maxHeight ->
            setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        } ?: clearVideoSizeConstraints()
        setMaxVideoBitrate(
            policy.limit.maxVideoBitrateBps
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: Int.MAX_VALUE
        )
    }

    private fun revalidateVideoOverride(player: ExoPlayer, policy: ResolvedPlaybackQualityPolicy) {
        if (selectedVideoTrackId == PLAYER_TRACK_AUTO_ID) return
        val selected = _availableVideoTracks.value.firstOrNull { it.id == selectedVideoTrackId } ?: return
        val evaluation = policy.evaluate(
            com.streamvault.domain.settings.VideoQualityCandidate(
                identity = selected.id,
                height = selected.videoHeight,
                advertisedBitrateBps = selected.videoBitrateBps
            )
        )
        if (!evaluation.isEligible) selectVideoTrack(player, PLAYER_TRACK_AUTO_ID)
    }

    private fun buildTrackName(format: Format, trackType: Int, index: Int): String {
        if (trackType == C.TRACK_TYPE_VIDEO) return buildVideoTrackName(format, index)

        val explicitLabel = format.label
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)^track\\s*\\d+$")) }
        if (explicitLabel != null) return explicitLabel

        val parts = mutableListOf<String>()
        format.language
            ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { code ->
                val locale = Locale.forLanguageTag(code)
                val display = locale.getDisplayLanguage(Locale.getDefault())
                    .takeIf { it.isNotBlank() }
                    ?: locale.displayLanguage.takeIf { it.isNotBlank() }
                if (!display.isNullOrBlank()) {
                    parts += display.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
            }

        if (trackType == C.TRACK_TYPE_TEXT) {
            if ((format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts += "Forced"
            if ((format.roleFlags and C.ROLE_FLAG_CAPTION) != 0) parts += "CC"
            if ((format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) parts += "SDH"
        }

        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return when (trackType) {
            C.TRACK_TYPE_AUDIO -> "Audio ${index + 1}"
            C.TRACK_TYPE_TEXT -> "Subtitle ${index + 1}"
            else -> "Track ${index + 1}"
        }
    }

    private fun buildVideoTrackName(format: Format, index: Int): String {
        val parts = mutableListOf<String>()
        val explicitLabel = format.label
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)^track\\s*\\d+$")) }
        val resolutionLabel = when {
            format.height > 0 -> "${format.height}p"
            format.width > 0 && format.height > 0 -> "${format.width}x${format.height}"
            else -> null
        }
        val bitrateLabel = format.bitrate.takeIf { it > 0 }
            ?.let { bitrate -> String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000f) }
        explicitLabel?.let(parts::add)
        if (!resolutionLabel.isNullOrBlank() && parts.none { it.contains(resolutionLabel, true) }) parts += resolutionLabel
        if (!bitrateLabel.isNullOrBlank()) parts += bitrateLabel
        return parts.firstOrNull()?.takeIf { parts.size == 1 }
            ?: parts.joinToString(" · ").ifBlank { "Quality ${index + 1}" }
    }
}

internal fun normalizeSelectedVideoTrackId(
    selectedTrackId: String,
    availableTrackIds: List<String>
): String = when {
    selectedTrackId == PLAYER_TRACK_AUTO_ID -> PLAYER_TRACK_AUTO_ID
    availableTrackIds.isEmpty() -> selectedTrackId
    selectedTrackId in availableTrackIds -> selectedTrackId
    availableTrackIds.size == 1 -> availableTrackIds.first()
    else -> PLAYER_TRACK_AUTO_ID
}

internal fun playerTrackId(format: Format, trackType: Int, trackGroupHash: Int, index: Int): String =
    format.id ?: if (trackType == C.TRACK_TYPE_VIDEO) {
        listOf(
            "video",
            format.sampleMimeType.orEmpty(),
            format.codecs.orEmpty(),
            "${format.width}x${format.height}",
            format.bitrate,
            format.averageBitrate,
            format.frameRate.toBits(),
            format.rotationDegrees,
            format.roleFlags,
            format.selectionFlags,
            format.language.orEmpty(),
            format.label.orEmpty(),
            format.colorInfo?.hashCode() ?: 0,
            index
        ).joinToString(":")
    } else {
        "${trackGroupHash}_$index"
    }
