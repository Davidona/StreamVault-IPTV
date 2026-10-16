package com.streamvault.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import com.streamvault.data.local.dao.RecordingRunDao
import com.streamvault.data.local.dao.XtreamIndexJobDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.*
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import com.streamvault.domain.sync.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** Marks durable continuations without imposing automatic restrictions on explicit work. */
internal object AutomaticSyncExecution {
    val automatic = ThreadLocal.withInitial { false }
    val idleSatisfied = ThreadLocal.withInitial { false }
    val policy = ThreadLocal<com.streamvault.domain.sync.AutomaticSyncSettings?>()
}

@Singleton
class AutomaticSyncRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: PreferencesRepository,
    private val snapshots: ProviderSnapshotRepository,
    private val metadataRepository: SyncMetadataRepository,
    private val commands: ProviderSyncCommands,
    private val xtreamJobs: XtreamIndexJobDao,
    private val stalkerJobs: StalkerIndexJobStore,
    private val recordings: RecordingRunDao
) {
    suspend fun waitingReason(providerId: Long): AutomaticSyncOutcome? {
        val policy = preferences.automaticSyncConfiguration.first().forProvider(providerId)
        if (!policy.enabled) return AutomaticSyncOutcome.DISABLED
        val network = context.getSystemService(ConnectivityManager::class.java)
        if (network.activeNetwork == null || (policy.unmeteredOnly && network.isActiveNetworkMetered)) return AutomaticSyncOutcome.WAITING_NETWORK
        if (policy.chargingOnly && !context.getSystemService(BatteryManager::class.java).isCharging) return AutomaticSyncOutcome.WAITING_CHARGING
        if (policy.deviceIdleOnly && AutomaticSyncExecution.idleSatisfied.get() != true && !context.getSystemService(PowerManager::class.java).isDeviceIdleMode) return AutomaticSyncOutcome.WAITING_IDLE
        if (!policy.allowsHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return AutomaticSyncOutcome.WAITING_WINDOW
        if (policy.deferWhileBusy && (AutomaticSyncActivity.hasPlayback || recordings.getByStatus(RecordingStatus.RECORDING).isNotEmpty())) return AutomaticSyncOutcome.WAITING_PLAYBACK
        return null
    }

    suspend fun settings(providerId: Long) = preferences.automaticSyncConfiguration.first().forProvider(providerId)

    suspend fun retryCheck(providerId: Long): Boolean = preferences.automaticSyncStatuses.first()[providerId]?.outcome in setOf(
        AutomaticSyncOutcome.WAITING_PLAYBACK)

    suspend fun indexEnabled(providerId: Long, section: ContentType?): Boolean {
        val policy = preferences.automaticSyncConfiguration.first().forProvider(providerId)
        return policy.enabled && when (section) {
            ContentType.MOVIE -> policy.movies
            ContentType.SERIES -> policy.series
            else -> policy.movies || policy.series
        }
    }

    suspend fun reportIndexWaiting(providerId: Long, outcome: AutomaticSyncOutcome) {
        val previous = preferences.automaticSyncStatuses.first()[providerId] ?: AutomaticSyncStatus()
        preferences.recordAutomaticSyncStatus(providerId, previous.copy(checkedAt = System.currentTimeMillis(), outcome = outcome))
    }

    suspend fun reportIndexProgress(providerId: Long, failed: Boolean) {
        val previous = preferences.automaticSyncStatuses.first()[providerId] ?: AutomaticSyncStatus()
        val snapshot = snapshots.getSnapshot(providerId) ?: return
        val policy = settings(providerId)
        val pending = listOf(ContentType.MOVIE, ContentType.SERIES).filter {
            if (it == ContentType.MOVIE) policy.movies else policy.series
        }.any { section ->
            if (snapshot.provider.type == ProviderType.STALKER_PORTAL) {
                stalkerJobs.get(providerId, section)?.state in setOf(StalkerIndexState.QUEUED, StalkerIndexState.RUNNING, StalkerIndexState.RETRY_WAIT, StalkerIndexState.PARTIAL)
            } else {
                xtreamJobs.get(providerId, section.name)?.state in setOf("QUEUED", "RUNNING", "PARTIAL", "FAILED_RETRYABLE")
            }
        }
        val outcome = when {
            !policy.enabled -> AutomaticSyncOutcome.DISABLED
            failed -> AutomaticSyncOutcome.FAILED
            previous.outcome in setOf(AutomaticSyncOutcome.FAILED, AutomaticSyncOutcome.PARTIAL) -> previous.outcome
            pending -> AutomaticSyncOutcome.QUEUED
            else -> AutomaticSyncOutcome.REFRESHED
        }
        preferences.recordAutomaticSyncStatus(providerId, previous.copy(checkedAt = System.currentTimeMillis(), outcome = outcome))
    }

    suspend fun check(providerId: Long): Result<Unit> = withContext(AutomaticSyncExecution.automatic.asContextElement(true)) {
        val now = System.currentTimeMillis()
        val policy = preferences.automaticSyncConfiguration.first().forProvider(providerId)
        val snapshot = snapshots.getSnapshot(providerId) ?: return@withContext Result.error("Provider configuration is unavailable")
        val epgMode = when (val config = snapshot.configuration) {
            is XtreamConfig -> config.epgSyncMode
            is StalkerConfig -> config.epgSyncMode
            is M3uConfig -> config.epgSyncMode
            is JellyfinConfig -> ProviderEpgSyncMode.SKIP
        }
        var metadata = metadataRepository.getMetadata(providerId)
        // Index completion is authoritative; catalog-shell setup is not a completed index.
        if (snapshot.provider.type == ProviderType.XTREAM_CODES) {
            metadata = (metadata ?: SyncMetadata(providerId)).copy(
                lastMovieSuccess = xtreamJobs.get(providerId, ContentType.MOVIE.name)?.lastSuccessAt ?: metadata?.lastMovieSuccess ?: 0,
                lastSeriesSuccess = xtreamJobs.get(providerId, ContentType.SERIES.name)?.lastSuccessAt ?: metadata?.lastSeriesSuccess ?: 0
            )
        } else if ((snapshot.configuration as? StalkerConfig)?.catalogMode == StalkerCatalogMode.BACKGROUND_INDEX) {
            metadata = (metadata ?: SyncMetadata(providerId)).copy(
                lastMovieSuccess = stalkerJobs.get(providerId, ContentType.MOVIE)?.lastSuccessAt ?: metadata?.lastMovieSuccess ?: 0,
                lastSeriesSuccess = stalkerJobs.get(providerId, ContentType.SERIES)?.lastSuccessAt ?: metadata?.lastSeriesSuccess ?: 0
            )
        }
        val plan = ProviderRefreshPlanner.plan(snapshot.provider.type, metadata, policy, now,
            epgAvailable = epgMode != ProviderEpgSyncMode.SKIP,
            unifiedVod = snapshot.accountRuntime.catalogLayout == CatalogLayout.UNIFIED_VOD)
        suspend fun report(outcome: AutomaticSyncOutcome) = preferences.recordAutomaticSyncStatus(providerId,
            AutomaticSyncStatus(System.currentTimeMillis(), outcome, plan.nextDueAt, plan.sections))
        val waiting = waitingReason(providerId)
        if (waiting != null) {
            report(waiting)
            if (waiting in setOf(AutomaticSyncOutcome.WAITING_NETWORK, AutomaticSyncOutcome.WAITING_CHARGING, AutomaticSyncOutcome.WAITING_IDLE))
                ProviderSyncWorker.enqueueAutomaticCheck(context, providerId, policy)
            return@withContext Result.success(Unit)
        }
        if (plan.sections.isEmpty()) { report(AutomaticSyncOutcome.UP_TO_DATE); return@withContext Result.success(Unit) }
        report(AutomaticSyncOutcome.RUNNING)
        var queued = false
        var partial = false
        var failure: Result.Error? = null
        suspend fun permanentlyRejected(section: RefreshSection): Boolean {
            val content = when (section) { RefreshSection.MOVIES -> ContentType.MOVIE; RefreshSection.SERIES -> ContentType.SERIES; else -> return false }
            return when (snapshot.provider.type) {
                ProviderType.XTREAM_CODES -> xtreamJobs.get(providerId, content.name)?.state == "FAILED_PERMANENT"
                ProviderType.STALKER_PORTAL -> stalkerJobs.get(providerId, content)?.state == StalkerIndexState.FAILED
                else -> false
            }
        }
        val runnableSections = mutableListOf<RefreshSection>()
        for (section in plan.sections) {
            if (permanentlyRejected(section)) partial = true else runnableSections += section
        }
        val catalogSections = runnableSections.filter { it != RefreshSection.EPG }
        val sections = if (snapshot.provider.type == ProviderType.M3U && catalogSections.isNotEmpty()) {
            when (val result = commands.refreshScheduledPlaylist(providerId,
                RefreshSection.LIVE in catalogSections, RefreshSection.MOVIES in catalogSections)) {
                is Result.Error -> failure = result
                is Result.Success -> partial = result.data.partial
                Result.Loading -> failure = Result.error("Playlist refresh did not complete")
            }
            runnableSections.filter { it == RefreshSection.EPG }
        } else runnableSections
        for (section in sections) {
            val blocked = waitingReason(providerId)
            if (blocked != null) { report(blocked); return@withContext failure ?: Result.success(Unit) }
            // A switched-off section must also stop an already queued automatic check.
            val latest = preferences.automaticSyncConfiguration.first().forProvider(providerId)
            val enabled = when (section) { RefreshSection.LIVE -> latest.live; RefreshSection.MOVIES -> latest.movies; RefreshSection.SERIES -> latest.series; RefreshSection.EPG -> latest.epg }
            if (!enabled) continue
            val result = withContext(AutomaticSyncExecution.policy.asContextElement(latest)) {
                commands.refreshScheduledSection(providerId, SyncRepairSection.valueOf(section.name))
            }
            when (result) {
                is Result.Error -> failure = result
                is Result.Success -> { queued = queued || result.data.queued; partial = partial || result.data.partial }
                Result.Loading -> failure = Result.error("Scheduled refresh did not complete")
            }
        }
        val latestMetadata = metadataRepository.getMetadata(providerId)
        val nextPlan = ProviderRefreshPlanner.plan(snapshot.provider.type, latestMetadata, policy, System.currentTimeMillis(),
            epgAvailable = epgMode != ProviderEpgSyncMode.SKIP, unifiedVod = snapshot.accountRuntime.catalogLayout == CatalogLayout.UNIFIED_VOD)
        preferences.recordAutomaticSyncStatus(providerId, AutomaticSyncStatus(System.currentTimeMillis(),
            when { failure != null -> AutomaticSyncOutcome.FAILED; partial -> AutomaticSyncOutcome.PARTIAL; queued -> AutomaticSyncOutcome.QUEUED; else -> AutomaticSyncOutcome.REFRESHED },
            nextPlan.nextDueAt, plan.sections))
        failure ?: Result.success(Unit)
    }
}
