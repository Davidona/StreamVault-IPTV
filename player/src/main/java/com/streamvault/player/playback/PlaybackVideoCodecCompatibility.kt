package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
internal class PlaybackVideoCodecCompatibility(
    private val delegate: MediaCodecSelector,
    private val emulatorWorkaroundEligible: Boolean
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): MutableList<MediaCodecInfo> = delegate.getDecoderInfos(
        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
    ).map { info ->
        // Goldfish advertises seamless AVC adaptation but can retain the old output crop.
        // Keep Media3's renderer and let its nonseamless adaptation recreate the codec.
        if (emulatorWorkaroundEligible && mimeType == "video/avc" &&
            info.mimeType == "video/avc" && info.name == "c2.goldfish.h264.decoder" &&
            info.adaptive
        ) {
            MediaCodecInfo.newInstance(
                info.name, info.mimeType, info.codecMimeType, info.capabilities,
                info.hardwareAccelerated, info.softwareOnly, info.vendor,
                /* forceDisableAdaptive = */ true,
                /* forceSecure = */ info.secure
            )
        } else {
            info
        }
    }.toMutableList()
}
