package com.streamvault.data.sync

/** Bounds fallback movie/series category work by rows and elapsed time, with a category safety cap. */
internal data class XtreamIndexSliceBudget(
    val maxCategories: Int,
    val maxRows: Int,
    val maxDurationMillis: Long
) {
    init {
        require(maxCategories > 0)
        require(maxRows > 0)
        require(maxDurationMillis > 0L)
    }

    companion object {
        /** Conservative initial budgets; tune these with VOD fixtures on target devices. */
        fun forTier(tier: DeviceSyncTier): XtreamIndexSliceBudget = when (tier) {
            DeviceSyncTier.LOW -> XtreamIndexSliceBudget(
                maxCategories = 4,
                maxRows = 1_000,
                maxDurationMillis = 10_000L
            )
            DeviceSyncTier.MID -> XtreamIndexSliceBudget(
                maxCategories = 8,
                maxRows = 3_000,
                maxDurationMillis = 20_000L
            )
            DeviceSyncTier.HIGH -> XtreamIndexSliceBudget(
                maxCategories = 16,
                maxRows = 8_000,
                maxDurationMillis = 30_000L
            )
        }
    }
}
