package com.streamvault.feature.settings.presentation

import com.streamvault.domain.settings.SettingsXtreamLiveOnboarding
import com.streamvault.domain.settings.DatabaseMaintenanceSnapshot
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.VodSyncMode

enum class ProviderCatalogCountStatus {
    PENDING,
    QUEUED,
    SYNCING,
    PARTIAL,
    FAILED,
    READY
}

data class ProviderCatalogCountUiModel(
    val count: Int = 0,
    val status: ProviderCatalogCountStatus = ProviderCatalogCountStatus.PENDING
)

data class ProviderDiagnosticsUiModel(
    val lastSyncStatus: String = "NONE",
    val lastLiveSync: Long = 0L,
    val lastLiveSuccess: Long = 0L,
    val lastMovieSync: Long = 0L,
    val lastMovieAttempt: Long = 0L,
    val lastMovieSuccess: Long = 0L,
    val lastMoviePartial: Long = 0L,
    val lastSeriesSync: Long = 0L,
    val lastSeriesSuccess: Long = 0L,
    val lastEpgSync: Long = 0L,
    val lastEpgSuccess: Long = 0L,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val epgCount: Int = 0,
    val movieSyncMode: VodSyncMode = VodSyncMode.UNKNOWN,
    val movieWarningsCount: Int = 0,
    val movieCatalogStale: Boolean = false,
    val liveSequentialFailuresRemembered: Boolean = false,
    val liveHealthySyncStreak: Int = 0,
    val movieParallelFailuresRemembered: Boolean = false,
    val movieHealthySyncStreak: Int = 0,
    val seriesSequentialFailuresRemembered: Boolean = false,
    val seriesHealthySyncStreak: Int = 0,
    val capabilitySummary: String = "",
    val sourceLabel: String = "",
    val expirySummary: String = "",
    val connectionSummary: String = "",
    val archiveSummary: String = ""
) {
    val hasHealthWarning: Boolean
        get() = liveSequentialFailuresRemembered ||
            movieParallelFailuresRemembered ||
            seriesSequentialFailuresRemembered ||
            movieCatalogStale ||
            movieWarningsCount > 0
}

data class XtreamLiveOnboardingUiModel(
    val phase: String,
    val importStrategy: String,
    val acceptedRowCount: Int,
    val stagedFlushCount: Int,
    val profileTier: String,
    val profileBatchSize: Int,
    val profileStrategy: String,
    val lowMemory: Boolean,
    val memoryClassMb: Int,
    val availableMemMb: Long,
    val lastError: String?,
    val updatedAt: Long
) {
    val summary: String
        get() = buildList {
            add("phase=$phase")
            if (profileTier.isNotBlank()) add("profile=$profileTier")
            if (profileBatchSize > 0) add("batch=$profileBatchSize")
            if (profileStrategy.isNotBlank()) add("strategy=$profileStrategy")
            add("accepted=$acceptedRowCount")
            add("flushes=$stagedFlushCount")
            if (memoryClassMb > 0) add("memory=${memoryClassMb}MB")
            if (availableMemMb > 0L) add("available=${availableMemMb}MB")
            if (lowMemory) add("lowMemory=true")
            lastError?.takeIf { it.isNotBlank() }?.let { add("error=$it") }
        }.joinToString(" • ")
}

fun SettingsXtreamLiveOnboarding.toUiModel(): XtreamLiveOnboardingUiModel =
    XtreamLiveOnboardingUiModel(
        phase = phase,
        importStrategy = importStrategy.orEmpty(),
        acceptedRowCount = acceptedRowCount,
        stagedFlushCount = stagedFlushCount,
        profileTier = syncProfileTier.orEmpty(),
        profileBatchSize = syncProfileBatchSize,
        profileStrategy = syncProfileStrategy.orEmpty(),
        lowMemory = syncProfileLowMemory,
        memoryClassMb = syncProfileMemoryClassMb,
        availableMemMb = syncProfileAvailableMemMb,
        lastError = lastError,
        updatedAt = updatedAt
    )

data class DatabaseMaintenanceUiModel(
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

fun DatabaseMaintenanceSnapshot.toUiModel(): DatabaseMaintenanceUiModel =
    DatabaseMaintenanceUiModel(
        ranAt = ranAt,
        deletedPrograms = deletedPrograms,
        deletedExternalProgrammes = deletedExternalProgrammes,
        deletedOrphanEpisodes = deletedOrphanEpisodes,
        deletedStaleFavorites = deletedStaleFavorites,
        vacuumRan = vacuumRan,
        mainDbBytes = mainDbBytes,
        walBytes = walBytes,
        reclaimableBytes = reclaimableBytes,
        channelRows = channelRows,
        movieRows = movieRows,
        seriesRows = seriesRows,
        episodeRows = episodeRows,
        programRows = programRows,
        epgProgrammeRows = epgProgrammeRows,
        playbackHistoryRows = playbackHistoryRows,
        favoriteRows = favoriteRows
    )

data class InternetSpeedTestUiModel(
    val megabitsPerSecond: Double,
    val measuredAtMs: Long,
    val transportLabel: String,
    val recommendedMaxVideoHeight: Int?,
    val isEstimated: Boolean
)

data class CategoryManagementSnapshot(
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList()
)
