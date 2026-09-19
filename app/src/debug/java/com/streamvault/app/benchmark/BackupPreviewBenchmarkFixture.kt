package com.streamvault.app.benchmark

import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview

internal object BackupPreviewBenchmarkFixture {
    val preview = BackupPreview(
        version = 14,
        providerCount = 3,
        favoriteCount = 12,
        groupCount = 4,
        playbackHistoryCount = 8,
        multiViewPresetCount = 2,
        preferenceCount = 10,
        protectedCategoryCount = 1,
        scheduledRecordingCount = 3,
        providerConflicts = 1,
        favoriteConflicts = 2,
        groupConflicts = 0,
        historyConflicts = 1,
        protectedCategoryConflicts = 0,
        recordingConflicts = 1,
    )

    val plan = BackupImportPlan(
        importPreferences = true,
        importProviders = false,
        importSavedLibrary = true,
        importPlaybackHistory = false,
        importMultiViewPresets = true,
        importRecordingSchedules = false,
    )
}
