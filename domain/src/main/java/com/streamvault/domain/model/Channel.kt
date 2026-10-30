package com.streamvault.domain.model

data class Channel(
    val id: Long,
    val name: String,
    val canonicalName: String = name,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val streamUrl: String = "",
    val epgChannelId: String? = null,
    val number: Int = 0,
    val isFavorite: Boolean = false,
    val catchUpSupported: Boolean = false,
    val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    val catchUpFallbackAvailable: Boolean = true,
    val providerId: Long = 0,
    val currentProgram: Program? = null,
    val nextProgram: Program? = null,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false,
    val logicalGroupId: String = "",
    val selectedVariantId: Long = id,
    val errorCount: Int = 0,
    val qualityOptions: List<ChannelQualityOption> = emptyList(),
    val alternativeStreams: List<String> = emptyList(),
    val variants: List<LiveChannelVariant> = emptyList(),
    val streamId: Long = 0L,
    val catchUpType: String? = null,
    val catchUpCorrectionSeconds: Int = 0
) {
    init {
        require(number >= 0) { "number must be non-negative" }
        require(catchUpDays >= 0) { "catchUpDays must be non-negative" }
        require(errorCount >= 0) { "errorCount must be non-negative" }
    }

    val currentVariant: LiveChannelVariant?
        get() = variants.firstOrNull { it.rawChannelId == selectedVariantId }

    fun allVariantRawIds(): Set<Long> =
        buildSet {
            add(id)
            addAll(variants.map(LiveChannelVariant::rawChannelId))
        }

    fun withSelectedVariant(rawChannelId: Long): Channel? {
        val selected = variants.firstOrNull { it.rawChannelId == rawChannelId } ?: return null
        val updatedQualityOptions = variants.mapNotNull { variant ->
            if (variant.streamUrl.isBlank()) return@mapNotNull null
            ChannelQualityOption(
                label = buildString {
                    append(variant.attributes.resolutionLabel ?: variant.originalName)
                    variant.attributes.codecLabel?.takeIf { it == "HEVC" || it == "AV1" }?.let {
                        append(' ')
                        append(it)
                    }
                }.trim(),
                height = variant.attributes.declaredHeight ?: variant.observedQuality.lastObservedHeight.takeIf { it > 0 },
                url = variant.streamUrl
            )
        }.distinctBy { it.url ?: "${it.height}:${it.label}" }
        return copy(
            id = selected.rawChannelId,
            streamUrl = selected.streamUrl,
            epgChannelId = selected.epgChannelId,
            number = selected.number.takeIf { it > 0 } ?: number,
            catchUpSupported = selected.catchUpSupported,
            catchUpDays = selected.catchUpDays,
            catchUpSource = selected.catchUpSource,
            catchUpType = selected.catchUpType,
            catchUpCorrectionSeconds = selected.catchUpCorrectionSeconds,
            selectedVariantId = selected.rawChannelId,
            errorCount = selected.errorCount,
            qualityOptions = updatedQualityOptions,
            alternativeStreams = variants.map(LiveChannelVariant::streamUrl)
                .filter { it.isNotBlank() && it != selected.streamUrl }
                .distinct(),
            streamId = selected.streamId
        )
    }
}

data class ChannelQualityOption(
    val label: String,
    val height: Int? = null,
    val url: String? = null
)

data class LiveChannelVariant(
    val rawChannelId: Long,
    val logicalGroupId: String,
    val providerId: Long,
    val originalName: String,
    val canonicalName: String,
    val streamUrl: String,
    val streamId: Long = 0L,
    val epgChannelId: String? = null,
    val number: Int = 0,
    val errorCount: Int = 0,
    val catchUpSupported: Boolean = false,
    val catchUpDays: Int = 0,
    val catchUpSource: String? = null,
    val attributes: LiveChannelVariantAttributes = LiveChannelVariantAttributes(),
    val observedQuality: LiveChannelObservedQuality = LiveChannelObservedQuality(),
    val catchUpType: String? = null,
    val catchUpCorrectionSeconds: Int = 0
) {
    init {
        require(number >= 0) { "number must be non-negative" }
        require(errorCount >= 0) { "errorCount must be non-negative" }
        require(catchUpDays >= 0) { "catchUpDays must be non-negative" }
    }
}

data class LiveChannelVariantAttributes(
    val resolutionLabel: String? = null,
    val declaredHeight: Int? = null,
    val qualityTier: Int = 0,
    val codecLabel: String? = null,
    val transportLabel: String? = null,
    val frameRate: Int? = null,
    val isHdr: Boolean = false,
    val sourceHint: String? = null,
    val regionHint: String? = null,
    val languageHint: String? = null,
    val rawTags: List<String> = emptyList()
)

data class LiveChannelObservedQuality(
    val lastObservedWidth: Int = 0,
    val lastObservedHeight: Int = 0,
    val lastObservedBitrate: Int = 0,
    val lastObservedFrameRate: Float = 0f,
    val successCount: Int = 0,
    val lastSuccessfulAt: Long = 0L
) {
    init {
        require(lastObservedWidth >= 0) { "lastObservedWidth must be non-negative" }
        require(lastObservedHeight >= 0) { "lastObservedHeight must be non-negative" }
        require(lastObservedBitrate >= 0) { "lastObservedBitrate must be non-negative" }
        require(lastObservedFrameRate >= 0f) { "lastObservedFrameRate must be non-negative" }
        require(successCount >= 0) { "successCount must be non-negative" }
        require(lastSuccessfulAt >= 0L) { "lastSuccessfulAt must be non-negative" }
    }
}
