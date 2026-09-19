package com.streamvault.domain.settings

data class DatabaseMaintenanceSnapshot(
    val ranAt: Long,
    val deletedPrograms: Int,
    val deletedExternalProgrammes: Int,
    val deletedOrphanEpisodes: Int,
    val deletedStaleFavorites: Int,
    val vacuumRan: Boolean,
    val mainDbBytes: Long,
    val walBytes: Long,
    val reclaimableBytes: Long,
    val channelRows: Long,
    val movieRows: Long,
    val seriesRows: Long,
    val episodeRows: Long,
    val programRows: Long,
    val epgProgrammeRows: Long,
    val playbackHistoryRows: Long,
    val favoriteRows: Long
)
