package com.streamvault.domain.settings

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface SettingsOperations {
    fun observeProgramCount(providerId: Long): Flow<Int>
    fun observeXtreamIndexJobs(): Flow<List<SettingsXtreamIndexJob>>
    fun observeIncompleteXtreamLiveOnboarding(): Flow<List<SettingsXtreamLiveOnboarding>>
    suspend fun syncProvider(providerId: Long, force: Boolean): Result<Unit>
    suspend fun retryProviderSection(
        providerId: Long,
        section: SettingsSyncSection,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>
    suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>
    fun scheduleBackgroundEpgSync(providerId: Long)
}

enum class SettingsSyncSection {
    LIVE,
    MOVIES,
    SERIES,
    EPG
}

data class SettingsXtreamIndexJob(
    val providerId: Long,
    val section: String,
    val state: String,
    val indexedRows: Int,
    val lastError: String?
)

data class SettingsXtreamLiveOnboarding(
    val providerId: Long,
    val phase: String,
    val importStrategy: String?,
    val acceptedRowCount: Int,
    val stagedFlushCount: Int,
    val syncProfileTier: String?,
    val syncProfileBatchSize: Int,
    val syncProfileStrategy: String?,
    val syncProfileLowMemory: Boolean,
    val syncProfileMemoryClassMb: Int,
    val syncProfileAvailableMemMb: Long,
    val lastError: String?,
    val updatedAt: Long
)
