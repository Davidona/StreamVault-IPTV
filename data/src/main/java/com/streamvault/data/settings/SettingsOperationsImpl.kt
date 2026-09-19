package com.streamvault.data.settings

import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.local.dao.XtreamLiveOnboardingDao
import com.streamvault.data.local.entity.XtreamIndexJobEntity
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import com.streamvault.data.sync.ProviderSyncCommands
import com.streamvault.data.sync.SyncRepairSection
import com.streamvault.domain.model.Result
import com.streamvault.domain.settings.SettingsOperations
import com.streamvault.domain.settings.SettingsSyncSection
import com.streamvault.domain.settings.SettingsXtreamIndexJob
import com.streamvault.domain.settings.SettingsXtreamLiveOnboarding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsOperationsImpl @Inject constructor(
    private val programDao: ProgramDao,
    private val xtreamIndexJobDao: XtreamIndexJobDao,
    private val xtreamLiveOnboardingDao: XtreamLiveOnboardingDao,
    private val syncCommands: ProviderSyncCommands
) : SettingsOperations {
    override fun observeProgramCount(providerId: Long): Flow<Int> =
        programDao.observeCountByProvider(providerId)

    override fun observeXtreamIndexJobs(): Flow<List<SettingsXtreamIndexJob>> =
        xtreamIndexJobDao.observeAll().map { jobs -> jobs.map { it.toSettingsSnapshot() } }

    override fun observeIncompleteXtreamLiveOnboarding(): Flow<List<SettingsXtreamLiveOnboarding>> =
        xtreamLiveOnboardingDao.observeIncomplete().map { states ->
            states.map { it.toSettingsSnapshot() }
        }

    override suspend fun syncProvider(providerId: Long, force: Boolean): Result<Unit> =
        syncCommands.sync(providerId = providerId, force = force)

    override suspend fun retryProviderSection(
        providerId: Long,
        section: SettingsSyncSection,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> = syncCommands.retrySection(
        providerId = providerId,
        section = section.toSyncRepairSection(),
        onProgress = onProgress
    )

    override suspend fun rebuildXtreamIndex(
        providerId: Long,
        onProgress: ((String) -> Unit)?
    ): Result<Unit> = syncCommands.rebuildXtreamIndex(providerId, onProgress)

    override fun scheduleBackgroundEpgSync(providerId: Long) {
        syncCommands.scheduleBackgroundEpgSync(providerId)
    }

    private fun XtreamIndexJobEntity.toSettingsSnapshot() = SettingsXtreamIndexJob(
        providerId = providerId,
        section = section,
        state = state,
        indexedRows = indexedRows,
        lastError = lastError
    )

    private fun XtreamLiveOnboardingStateEntity.toSettingsSnapshot() = SettingsXtreamLiveOnboarding(
        providerId = providerId,
        phase = phase,
        importStrategy = importStrategy,
        acceptedRowCount = acceptedRowCount,
        stagedFlushCount = stagedFlushCount,
        syncProfileTier = syncProfileTier,
        syncProfileBatchSize = syncProfileBatchSize,
        syncProfileStrategy = syncProfileStrategy,
        syncProfileLowMemory = syncProfileLowMemory,
        syncProfileMemoryClassMb = syncProfileMemoryClassMb,
        syncProfileAvailableMemMb = syncProfileAvailableMemMb,
        lastError = lastError,
        updatedAt = updatedAt
    )

    private fun SettingsSyncSection.toSyncRepairSection(): SyncRepairSection = when (this) {
        SettingsSyncSection.LIVE -> SyncRepairSection.LIVE
        SettingsSyncSection.MOVIES -> SyncRepairSection.MOVIES
        SettingsSyncSection.SERIES -> SyncRepairSection.SERIES
        SettingsSyncSection.EPG -> SyncRepairSection.EPG
    }
}
