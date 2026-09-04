package com.streamvault.domain.model

data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val codecV: String? = null,
    val codecA: String? = null,
    val pixelWidthHeightRatio: Float = 1f,
    val isHdr: Boolean = false
) {
    init {
        require(width >= 0) { "width must be non-negative" }
        require(height >= 0) { "height must be non-negative" }
        require(frameRate >= 0f) { "frameRate must be non-negative" }
        require(bitrate >= 0) { "bitrate must be non-negative" }
        require(pixelWidthHeightRatio > 0f) { "pixelWidthHeightRatio must be positive" }
    }

    val resolutionLabel: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height > 0 -> "${height}p"
            else -> "Unknown"
        }
    
    /**
     * Frame rate as a compact label ("25", "29.97", "50"), or null when the stream did not
     * report one. Many live MPEG-TS feeds omit it, so callers must handle null.
     */
    val frameRateLabel: String? get() = formatFrameRateLabel(frameRate)

    val isEmpty: Boolean get() = width == 0 && height == 0
}

/** Formats a frame rate without a trailing ".00" for whole numbers; null when unknown (<= 0). */
fun formatFrameRateLabel(frameRate: Float): String? {
    if (frameRate <= 0f) return null
    val rounded = Math.round(frameRate * 100f) / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", rounded).trimEnd('0')
    }
}
