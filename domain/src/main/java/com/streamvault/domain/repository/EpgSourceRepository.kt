package com.streamvault.domain.repository

import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.EpgSource
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ProviderEpgSourceAssignment
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import kotlinx.coroutines.flow.Flow

interface EpgSourceRepository {

    // ── EPG Source CRUD ────────────────────────────────────────────

    fun getAllSources(): Flow<List<EpgSource>>

    suspend fun getSourceById(id: Long): EpgSource?

    suspend fun addSource(
        name: String,
        url: String,
        timezonePolicy: XmltvTimezonePolicy = XmltvTimezonePolicy.REQUIRE_OFFSET,
        timezoneId: String? = null
    ): Result<EpgSource>

    suspend fun updateSource(source: EpgSource): Result<Unit>

    suspend fun deleteSource(id: Long)

    suspend fun setSourceEnabled(id: Long, enabled: Boolean)
    /** Returns all provider IDs that have this source assigned, regardless of UI load state. */
    suspend fun getProviderIdsForSource(sourceId: Long): List<Long>
    // ── Provider ↔ Source assignment ───────────────────────────────

    fun getAssignmentsForProvider(providerId: Long): Flow<List<ProviderEpgSourceAssignment>>

    suspend fun assignSourceToProvider(providerId: Long, epgSourceId: Long, priority: Int): Result<Unit>

    suspend fun unassignSourceFromProvider(providerId: Long, epgSourceId: Long)

    suspend fun updateAssignmentPriority(providerId: Long, epgSourceId: Long, priority: Int)

    /** Atomically swaps the priorities of two assignments within a single database transaction. */
    suspend fun swapAssignmentPriorities(
        providerId: Long,
        epgSourceId1: Long,
        newPriority1: Int,
        epgSourceId2: Long,
        newPriority2: Int
    )

    // ── Refresh / Ingestion ────────────────────────────────────────

    suspend fun refreshSource(sourceId: Long): Result<Unit>

    suspend fun refreshAllForProvider(providerId: Long): Result<Unit>

    // ── Resolution ─────────────────────────────────────────────────

    suspend fun resolveForProvider(providerId: Long, hiddenLiveCategoryIds: Set<Long> = emptySet()): EpgResolutionSummary

    suspend fun getResolutionSummary(providerId: Long): EpgResolutionSummary

    suspend fun getChannelMapping(providerId: Long, channelId: Long): ChannelEpgMapping?

    suspend fun getChannelMappingConfidences(providerId: Long, channelIds: List<Long>): Map<Long, Float>

    suspend fun getOverrideCandidates(
        providerId: Long,
        query: String,
        limit: Int = 150
    ): List<EpgOverrideCandidate>

    suspend fun applyManualOverride(
        providerId: Long,
        channelId: Long,
        epgSourceId: Long,
        xmltvChannelId: String
    ): Result<Unit>

    suspend fun clearManualOverride(providerId: Long, channelId: Long): Result<Unit>

    // ── Resolved query ─────────────────────────────────────────────

    suspend fun getResolvedProgramsForChannels(
        providerId: Long,
        channelIds: List<Long>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>>
}
