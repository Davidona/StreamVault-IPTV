package com.streamvault.player.tracks

import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.streamvault.player.playback.DecodePressureRendition

internal data class DecodePressureTrackContext(val group: TrackGroup, val indices: List<Int>, val renditions: List<DecodePressureRendition>)
internal class AdaptiveDecodePressureTracks {
    private var ownedOverride: TrackSelectionOverride? = null

    fun context(tracks: Tracks, selected: Format?, parameters: TrackSelectionParameters, automatic: Boolean): DecodePressureTrackContext? {
        if (!automatic || selected == null || parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) return null
        if (parameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO && it != ownedOverride }) return null
        val current = tracks.groups.singleOrNull {
            it.type == C.TRACK_TYPE_VIDEO && it.isSelected && it.isAdaptiveSupported
        } ?: return null
        if (ownedOverride != null && ownedOverride?.mediaTrackGroup != current.mediaTrackGroup) return null
        val indices = (0 until current.length).filter { index ->
            val f = current.mediaTrackGroup.getFormat(index)
            current.isTrackSupported(index, false) && f.sampleMimeType == selected.sampleMimeType &&
                f.height > 0 && f.bitrate > 0 && f.height <= parameters.maxVideoHeight &&
                f.width <= parameters.maxVideoWidth && f.bitrate <= parameters.maxVideoBitrate &&
                f.height >= parameters.minVideoHeight && f.width >= parameters.minVideoWidth &&
                f.bitrate >= parameters.minVideoBitrate &&
                (f.frameRate == Format.NO_VALUE.toFloat() ||
                    f.frameRate in parameters.minVideoFrameRate.toFloat()..parameters.maxVideoFrameRate.toFloat()) &&
                fitsViewport(f, parameters)
        }
        if (indices.size < 2) return null
        return DecodePressureTrackContext(current.mediaTrackGroup, indices, indices.map {
            val f = current.mediaTrackGroup.getFormat(it)
            DecodePressureRendition(f.height, f.bitrate)
        })
    }

    fun constrain(parameters: TrackSelectionParameters, context: DecodePressureTrackContext, height: Int?, bitrate: Int = Int.MAX_VALUE): TrackSelectionParameters {
        if (height == null) return clear(parameters)
        val indices = context.indices.filter {
            val format = context.group.getFormat(it)
            format.height <= height && format.bitrate <= bitrate
        }
        if (indices.isEmpty()) return parameters
        val override = TrackSelectionOverride(context.group, indices)
        ownedOverride = override
        // A multiple-track override leaves Media3 ABR within this exact supported subset.
        return parameters.buildUpon().setOverrideForType(override).build()
    }

    fun clear(parameters: TrackSelectionParameters): TrackSelectionParameters {
        val own = ownedOverride
        ownedOverride = null
        return if (own != null && parameters.overrides[own.mediaTrackGroup] == own) {
            parameters.buildUpon().clearOverride(own.mediaTrackGroup).build()
        } else parameters
    }

    // Conservative eligibility: decline pressure adaptation when the viewport cannot
    // contain two candidates, rather than bypass the selector's viewport preferences.
    private fun fitsViewport(format: Format, parameters: TrackSelectionParameters): Boolean =
        (format.width <= parameters.viewportWidth && format.height <= parameters.viewportHeight) ||
            (parameters.viewportOrientationMayChange && format.height <= parameters.viewportWidth &&
                format.width <= parameters.viewportHeight)
}
