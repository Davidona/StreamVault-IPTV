package com.streamvault.domain.sync

/** Receipt for foreground section work; queued work has not finished importing items. */
data class ProviderSectionSyncResult(
    val partial: Boolean = false,
    val warnings: List<String> = emptyList(),
    val queued: Boolean = false,
    val catalogChanged: Boolean = false
)
