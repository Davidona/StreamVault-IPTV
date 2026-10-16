package com.streamvault.data.sync

data class CatalogSizeLimits(
    val maxChannelsPerProvider: Int = 400_000,
    val maxMoviesPerProvider: Int = 400_000,
    val maxSeriesPerProvider: Int = 100_000,
    val maxM3uDecompressedBytes: Long = 100L * 1024L * 1024L,
    val maxM3uLineBytes: Int = 16 * 1024,
    val maxM3uEntries: Int = 500_000,
    val maxM3uCategoriesPerType: Int = 10_000,
    val maxM3uFieldLength: Int = 8_192,
    val maxM3uInvalidEntryRatioPercent: Int = 50
)
