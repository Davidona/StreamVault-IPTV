package com.streamvault.player.tracks

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerTrack
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
    private var preferredAudioLanguageTag: String? = null
    private var preferredWifiMaxVideoHeight: Int? = null
    private var preferredEthernetMaxVideoHeight: Int? = null
    private var vodTrackPreferences: VodTrackPreferences? = null

    fun resetSelections() {
        _availableAudioTracks.value = emptyList()
        _availableSubtitleTracks.value = emptyList()
        _availableVideoTracks.value = emptyList()
        selectedVideoTrackId = PLAYER_TRACK_AUTO_ID
    }

    fun applyInitialParameters(player: ExoPlayer, constrainResolutionForMultiView: Boolean) {
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
            .apply {
                resolvedMaxVideoHeightForCurrentNetwork(constrainResolutionForMultiView)?.let { maxHeight ->
                    setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                } ?: clearVideoSizeConstraints()
            }
            .build()
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

    fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        preferredWifiMaxVideoHeight = wifiMaxHeight?.takeIf { it > 0 }
        preferredEthernetMaxVideoHeight = ethernetMaxHeight?.takeIf { it > 0 }
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
                    val id = format.id ?: "${group.mediaTrackGroup.hashCode()}_$index"
                    val track = PlayerTrack(
                        id = id,
                        name = buildTrackName(format, type, index),
                        language = format.language,
                        type = when {
                            isAudio -> TrackType.AUDIO
                            isText -> TrackType.TEXT
                            else -> TrackType.VIDEO
                        },
                        isSelected = if (isVideo) selectedVideoTrackId == id else group.isTrackSelected(index)
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

        _availableAudioTracks.value = audioTracks
        _availableSubtitleTracks.value = subtitleTracks
        _availableVideoTracks.value = when {
            videoTracks.size > 1 -> listOf(
                PlayerTrack(
                    id = PLAYER_TRACK_AUTO_ID,
                    name = "Auto",
                    language = null,
                    type = TrackType.VIDEO,
                    isSelected = selectedVideoTrackId == PLAYER_TRACK_AUTO_ID
                )
            ) + videoTracks
            videoTracks.size == 1 -> listOf(videoTracks.first().copy(isSelected = true))
            else -> emptyList()
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

    fun selectVideoTrack(player: ExoPlayer, trackId: String) {
        if (trackId == PLAYER_TRACK_AUTO_ID) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            selectedVideoTrackId = PLAYER_TRACK_AUTO_ID
        } else {
            val override = findOverride(player.currentTracks, C.TRACK_TYPE_VIDEO, trackId) ?: return
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
    }

    fun selectSubtitleTrack(player: ExoPlayer, trackId: String?) {
        if (trackId == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            return
        }
        val override = findOverride(player.currentTracks, C.TRACK_TYPE_TEXT, trackId) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(override)
            .build()
    }

    private fun findOverride(tracks: Tracks, trackType: Int, trackId: String): TrackSelectionOverride? {
        for (group in tracks.groups) {
            if (group.mediaTrackGroup.type != trackType) continue
            for (index in 0 until group.length) {
                val format = group.mediaTrackGroup.getFormat(index)
                val id = format.id ?: "${group.mediaTrackGroup.hashCode()}_$index"
                if (id == trackId) {
                    return TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
                }
            }
        }
        return null
    }

    private fun resolvedMaxVideoHeightForCurrentNetwork(constrainResolutionForMultiView: Boolean): Int? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        val networkPreference = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> preferredEthernetMaxVideoHeight
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> preferredWifiMaxVideoHeight
            else -> null
        }
        return when {
            constrainResolutionForMultiView && networkPreference != null -> minOf(720, networkPreference)
            constrainResolutionForMultiView -> 720
            else -> networkPreference
        }
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
    availableTrackIds.isEmpty() -> PLAYER_TRACK_AUTO_ID
    selectedTrackId == PLAYER_TRACK_AUTO_ID -> PLAYER_TRACK_AUTO_ID
    selectedTrackId in availableTrackIds -> selectedTrackId
    availableTrackIds.size == 1 -> availableTrackIds.first()
    else -> PLAYER_TRACK_AUTO_ID
}
