package com.streamvault.data.epg

import android.util.Log
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toDomainProgram
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.Program
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the best EPG source and channel for each provider channel.
 *
 * Resolution order per channel:
 * 1. Manual override (future)
 * 2. External source with exact ID match (highest-priority source first)
 * 3. External source with normalized name match (highest-priority source first)
 * 4. Provider-native EPG if available
 * 5. No EPG
 *
 * Results are persisted in `channel_epg_mappings` and used by the guide grid.
 */
@Singleton
class EpgResolutionEngine @Inject constructor(
    private val channelDao: ChannelDao,
    private val channelEpgMappingDao: ChannelEpgMappingDao,
    private val providerEpgSourceDao: ProviderEpgSourceDao,
    private val epgChannelDao: EpgChannelDao,
    private val epgProgrammeDao: EpgProgrammeDao,
    private val programDao: ProgramDao,
    private val providerSnapshotDao: ProviderSnapshotDao
) {
    companion object {
        private const val TAG = "EpgResolutionEngine"
        private const val LOW_CONFIDENCE_THRESHOLD = 0.7f
        private const val MAX_REMATCH_ATTEMPTS = 6
    }

    /**
     * Runs a full resolution pass for a provider.
     * - Loads all channels for the provider
     * - Loads all assigned external EPG sources (priority-ordered)
     * - For each channel, finds the best match
     * - Persists results in `channel_epg_mappings`
     *
     * @return summary statistics
     */
    suspend fun resolveForProvider(
        providerId: Long,
        hiddenLiveCategoryIds: Set<Long> = emptySet()
    ): EpgResolutionSummary = withContext(Dispatchers.Default) {
        val channels = channelDao.getByProviderSync(providerId)
            .filterNot { channel -> channel.categoryId != null && channel.categoryId in hiddenLiveCategoryIds }
        val guideSourcePolicy = providerSnapshotDao.getConfig(providerId)?.guideSourcePolicy ?: GuideSourcePolicy.AUTO
        if (channels.isEmpty()) {
            channelEpgMappingDao.deleteByProvider(providerId)
            return@withContext EpgResolutionSummary()
        }

        val allowExternalMappings = allowsExternalMappings(guideSourcePolicy)
        val allowProviderNativeMappings = allowsProviderNativeMappings(guideSourcePolicy)

        val enabledAssignments = if (allowExternalMappings) {
            providerEpgSourceDao.getEnabledForProviderSync(providerId)
        } else {
            emptyList()
        }
        val sourceIds = enabledAssignments.map { it.epgSourceId }

        // Load all EPG channels from assigned sources in one query
        val epgChannelsBySource = if (sourceIds.isNotEmpty()) {
            epgChannelDao.getBySources(sourceIds).groupBy { it.epgSourceId }
        } else {
            emptyMap()
        }

        // Build lookup indexes for fast matching
        // sourceId -> set of trimmed xmltvChannelIds for existence checks
        val exactIdIndex = mutableMapOf<Long, Set<String>>()
        // sourceId -> (normalizedName -> xmltvChannelId)
        val nameIndex = mutableMapOf<Long, Map<String, String>>()

        for ((sourceId, epgChannels) in epgChannelsBySource) {
            exactIdIndex[sourceId] = epgChannels.map { it.xmltvChannelId.trim() }.toHashSet()
            // For name index, first occurrence wins (avoids ambiguity)
            val nameMap = mutableMapOf<String, String>()
            for (ch in epgChannels) {
                val normalized = ch.normalizedName
                if (normalized.isNotEmpty() && normalized !in nameMap) {
                    nameMap[normalized] = ch.xmltvChannelId
                }
            }
            nameIndex[sourceId] = nameMap
        }

        // Check which channels have provider-native EPG data
        val providerEpgChannelIds = channels.mapNotNull { it.epgChannelId?.trim()?.takeIf(String::isNotEmpty) }
        val providerNativeChannelIds = if (providerEpgChannelIds.isNotEmpty()) {
            programDao.getChannelIdsWithPrograms(providerId, providerEpgChannelIds).toHashSet()
        } else {
            emptySet()
        }

        // Preserve existing manual overrides
        val existingMappings = channelEpgMappingDao.getForProvider(providerId)
        val existingMappingsByChannel = existingMappings.associateBy { it.providerChannelId }
        val manualOverrides = existingMappings.filter { it.isManualOverride }
            .associateBy { it.providerChannelId }

        val now = System.currentTimeMillis()
        var exactIdMatches = 0
        var normalizedNameMatches = 0
        var providerNativeMatches = 0
        var manualMatches = 0
        var unresolvedCount = 0

        val mappings = channels.map { channel ->
            val existing = existingMappingsByChannel[channel.id]
            // 1. Check for manual override
            val manual = manualOverrides[channel.id]
            if (manual != null && isMappingAllowed(manual, guideSourcePolicy)) {
                manualMatches++
                return@map manual.copy(
                    matchedAt = manual.matchedAt.takeIf { it > 0L } ?: now,
                    failedAttempts = 0,
                    source = "override",
                    updatedAt = now
                )
            }

            val channelEpgId = channel.epgChannelId?.trim()?.takeIf(String::isNotEmpty)

            // 2. Try external sources in priority order — exact ID match
            for (assignment in enabledAssignments) {
                val sid = assignment.epgSourceId
                val index = exactIdIndex[sid] ?: continue
                if (channelEpgId != null && channelEpgId in index) {
                    exactIdMatches++
                    return@map ChannelEpgMappingEntity(
                        providerChannelId = channel.id,
                        providerId = providerId,
                        sourceType = EpgSourceType.EXTERNAL.name,
                        epgSourceId = sid,
                        xmltvChannelId = channelEpgId,
                        matchType = EpgMatchType.EXACT_ID.name,
                        confidence = 1.0f,
                        matchedAt = now,
                        failedAttempts = 0,
                        source = "tvg_id",
                        updatedAt = now
                    )
                }
            }

            // 3. Try external sources — normalized name match
            val channelNormalizedName = EpgNameNormalizer.normalize(channel.name)
            if (channelNormalizedName.isNotEmpty()) {
                for (assignment in enabledAssignments) {
                    val sid = assignment.epgSourceId
                    val nIndex = nameIndex[sid] ?: continue
                    val matchedXmltvId = nIndex[channelNormalizedName]
                    if (matchedXmltvId != null) {
                        normalizedNameMatches++
                        return@map ChannelEpgMappingEntity(
                            providerChannelId = channel.id,
                            providerId = providerId,
                            sourceType = EpgSourceType.EXTERNAL.name,
                            epgSourceId = sid,
                            xmltvChannelId = matchedXmltvId,
                            matchType = EpgMatchType.NORMALIZED_NAME.name,
                            confidence = 0.7f,
                            matchedAt = now,
                            failedAttempts = 0,
                            source = "name_match",
                            updatedAt = now
                        )
                    }
                }
            }

            // 4. Fall back to provider-native EPG
            if (allowProviderNativeMappings && channelEpgId != null && channelEpgId in providerNativeChannelIds) {
                providerNativeMatches++
                return@map ChannelEpgMappingEntity(
                    providerChannelId = channel.id,
                    providerId = providerId,
                    sourceType = EpgSourceType.PROVIDER.name,
                    epgSourceId = null,
                    xmltvChannelId = channelEpgId,
                    matchType = EpgMatchType.PROVIDER_NATIVE.name,
                    confidence = 0.5f,
                    matchedAt = now,
                    failedAttempts = 0,
                    source = "provider_native",
                    updatedAt = now
                )
            }

            // 5. No EPG
            unresolvedCount++
            Log.v(TAG, "Unresolved: channel=${channel.name} (id=${channel.id}), " +
                "epgId=${channelEpgId ?: "null"}, " +
                "normalizedName=$channelNormalizedName, " +
                "idInAnySource=${enabledAssignments.any { channelEpgId != null && channelEpgId in (exactIdIndex[it.epgSourceId] ?: emptySet()) }}, " +
                "nameInAnySource=${enabledAssignments.any { channelNormalizedName in (nameIndex[it.epgSourceId] ?: emptyMap()) }}")
            ChannelEpgMappingEntity(
                providerChannelId = channel.id,
                providerId = providerId,
                sourceType = EpgSourceType.NONE.name,
                epgSourceId = null,
                xmltvChannelId = null,
                matchType = null,
                confidence = 0f,
                matchedAt = existing?.matchedAt ?: 0L,
                failedAttempts = (existing?.failedAttempts ?: 0).coerceAtMost(MAX_REMATCH_ATTEMPTS - 1) + 1,
                source = "unmatched",
                updatedAt = now
            )
        }

        channelEpgMappingDao.replaceForProvider(providerId, mappings)

        val summary = EpgResolutionSummary(
            totalChannels = channels.size,
            exactIdMatches = exactIdMatches,
            normalizedNameMatches = normalizedNameMatches,
            providerNativeMatches = providerNativeMatches,
            manualMatches = manualMatches,
            unresolvedChannels = unresolvedCount,
            lowConfidenceChannels = mappings.count { it.confidence > 0f && it.confidence < LOW_CONFIDENCE_THRESHOLD },
            rematchCandidateChannels = mappings.count {
                it.failedAttempts < MAX_REMATCH_ATTEMPTS &&
                    (it.sourceType == EpgSourceType.NONE.name || (it.confidence > 0f && it.confidence < LOW_CONFIDENCE_THRESHOLD))
            }
        )
        Log.d(TAG, "Resolution for provider $providerId: $summary")
        summary
    }

    /**
     * Queries resolved programme data for a set of channels.
     *
     * For channels mapped to EXTERNAL, reads from `epg_programmes`.
     * For channels mapped to PROVIDER, reads from `programs`.
     * For unmapped channels, returns empty.
     *
     * Returns map keyed by the channel's `epgChannelId` (the lookup key used by the guide grid).
     */
    suspend fun getResolvedProgrammes(
        providerId: Long,
        channelIds: List<Long>,
        startTime: Long,
        endTime: Long
    ): Map<String, List<Program>> = withContext(Dispatchers.IO) {
        if (channelIds.isEmpty()) return@withContext emptyMap()
        val guideSourcePolicy = providerSnapshotDao.getConfig(providerId)?.guideSourcePolicy ?: GuideSourcePolicy.AUTO
        if (guideSourcePolicy == GuideSourcePolicy.DISABLED) return@withContext emptyMap()

        val mappings = if (channelIds.size <= 500) {
            channelEpgMappingDao.getForChannels(providerId, channelIds)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                channelEpgMappingDao.getForChannels(providerId, chunk)
            }
        }.filter { mapping -> isMappingAllowed(mapping, guideSourcePolicy) }

        if (mappings.isEmpty()) return@withContext emptyMap()

        val channels = if (channelIds.size <= 500) {
            channelDao.getGuideLookupsByIds(channelIds)
        } else {
            channelIds.chunked(500).flatMap { chunk ->
                channelDao.getGuideLookupsByIds(chunk)
            }
        }
        val channelById = channels.associateBy { it.id }

        val result = mutableMapOf<String, List<Program>>()

        // Group external mappings by source
        val externalBySource = mappings
            .filter { it.sourceType == EpgSourceType.EXTERNAL.name && it.epgSourceId != null && it.xmltvChannelId != null }
            .groupBy { it.epgSourceId!! }

        for ((sourceId, sourceMappings) in externalBySource) {
            val xmltvIds = sourceMappings.map { it.xmltvChannelId!! }.distinct()
            val programmes = if (xmltvIds.size <= 500) {
                epgProgrammeDao.getForChannels(sourceId, xmltvIds, startTime, endTime)
            } else {
                xmltvIds.chunked(500).flatMap { chunk ->
                    epgProgrammeDao.getForChannels(sourceId, chunk, startTime, endTime)
                }
            }
            val programsByXmltvId = programmes.groupBy { it.xmltvChannelId }

            for (mapping in sourceMappings) {
                val channel = channelById[mapping.providerChannelId] ?: continue
                val lookupKey = channel.streamId.takeIf { it > 0L }?.toString()
                    ?: channel.epgChannelId?.trim()?.takeIf(String::isNotEmpty)
                    ?: continue
                val progs = programsByXmltvId[mapping.xmltvChannelId]
                    ?.map { it.toDomainProgram(providerId) }
                    ?.sortedBy { it.startTime }
                    .orEmpty()
                if (progs.isNotEmpty()) {
                    result[lookupKey] = progs
                }
            }
        }

        // For PROVIDER type, we don't need to query — the existing ProgramDao path
        // in EpgRepositoryImpl handles that. But we can include them here for completeness
        // of the resolved query path.
        val providerMappings = if (allowsProviderNativeMappings(guideSourcePolicy)) {
            mappings.filter { it.sourceType == EpgSourceType.PROVIDER.name && it.xmltvChannelId != null }
        } else {
            emptyList()
        }
        if (providerMappings.isNotEmpty()) {
            val providerXmltvIds = providerMappings.mapNotNull { it.xmltvChannelId }.distinct()
            val providerPrograms = if (providerXmltvIds.size <= 500) {
                programDao.getForChannelsSync(providerId, providerXmltvIds, startTime, endTime)
            } else {
                providerXmltvIds.chunked(500).flatMap { chunk ->
                    programDao.getForChannelsSync(providerId, chunk, startTime, endTime)
                }
            }
            val groupedByChannel = providerPrograms
                .map { it.toDomain() }
                .groupBy { it.channelId }

            for (mapping in providerMappings) {
                val channel = channelById[mapping.providerChannelId] ?: continue
                val lookupKey = channel.streamId.takeIf { it > 0L }?.toString()
                    ?: channel.epgChannelId?.trim()?.takeIf(String::isNotEmpty)
                    ?: continue
                // Don't overwrite external data (external wins if already resolved)
                if (lookupKey !in result) {
                    val progs = groupedByChannel[mapping.xmltvChannelId].orEmpty()
                    if (progs.isNotEmpty()) {
                        result[lookupKey] = progs
                    }
                }
            }
        }

        result
    }

    private fun allowsExternalMappings(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.EXTERNAL_ONLY

    private fun allowsProviderNativeMappings(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.PROVIDER_ONLY

    private fun isMappingAllowed(mapping: ChannelEpgMappingEntity, policy: GuideSourcePolicy): Boolean = when (mapping.sourceType) {
        EpgSourceType.EXTERNAL.name -> allowsExternalMappings(policy)
        EpgSourceType.PROVIDER.name -> allowsProviderNativeMappings(policy)
        EpgSourceType.NONE.name -> policy != GuideSourcePolicy.DISABLED
        else -> false
    }

    /**
     * Returns resolution statistics from the persisted mappings.
     */
    suspend fun getResolutionSummary(providerId: Long): EpgResolutionSummary {
        val stats = channelEpgMappingDao.getResolutionStats(providerId)
        var total = 0
        var exactId = 0
        var normalizedName = 0
        var providerNative = 0
        var manual = 0
        var unresolved = 0

        for (row in stats) {
            total += row.cnt
            when {
                row.sourceType == EpgSourceType.NONE.name -> unresolved += row.cnt
                row.matchType == EpgMatchType.EXACT_ID.name -> exactId += row.cnt
                row.matchType == EpgMatchType.NORMALIZED_NAME.name -> normalizedName += row.cnt
                row.matchType == EpgMatchType.PROVIDER_NATIVE.name -> providerNative += row.cnt
                row.matchType == EpgMatchType.MANUAL.name -> manual += row.cnt
            }
        }

        return EpgResolutionSummary(
            totalChannels = total,
            exactIdMatches = exactId,
            normalizedNameMatches = normalizedName,
            providerNativeMatches = providerNative,
            manualMatches = manual,
            unresolvedChannels = unresolved,
            lowConfidenceChannels = channelEpgMappingDao.countLowConfidence(providerId, LOW_CONFIDENCE_THRESHOLD),
            rematchCandidateChannels = channelEpgMappingDao.countRematchCandidates(
                providerId = providerId,
                minConfidence = LOW_CONFIDENCE_THRESHOLD,
                maxAttempts = MAX_REMATCH_ATTEMPTS
            )
        )
    }
}
