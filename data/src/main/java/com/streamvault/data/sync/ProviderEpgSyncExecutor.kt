package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.entity.ChannelGuideSyncEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.data.remote.stalker.StalkerRequestDescriptor
import com.streamvault.data.remote.stalker.StalkerTelemetry
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.SyncMetadata
import com.streamvault.domain.model.StalkerRequestPriority
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.SyncMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Owns provider-neutral guide policy and the provider-specific XMLTV/native guide handoffs. */
internal class ProviderEpgSyncExecutor(
    private val preferencesRepository: PreferencesRepository,
    private val epgRepository: EpgRepository,
    private val epgSourceRepository: EpgSourceRepository,
    private val channelDao: ChannelDao,
    private val programDao: ProgramDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val stalkerPortalStateStore: StalkerPortalStateStore,
    private val stalkerRequestCoordinator: StalkerRequestCoordinator,
    private val createStalkerProvider: (Provider) -> StalkerProvider,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val progress: (Long, ((String) -> Unit)?, String) -> Unit,
    private val sanitizeThrowableMessage: (Throwable?) -> String
) {
    suspend fun syncXtreamProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): ProviderGuideSyncResult = syncProviderEpg(provider, metadata, now, force, onProgress) { warnings ->
        var retryable = false
        if (shouldUseProviderGuide(provider.guideSourcePolicy) &&
            (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
        ) {
            try {
                progress(provider.id, onProgress, "Downloading EPG...")
                val xmltvUrl = provider.epgUrl.ifBlank {
                    XtreamUrlFactory.buildXmltvUrl(
                        provider.serverUrl.trimEnd('/'),
                        provider.username,
                        provider.password
                    )
                }
                UrlSecurityPolicy.validateXtreamEpgUrl(xmltvUrl)?.let { throw IllegalStateException(it) }
                xtreamSupport.retryTransient {
                    requireResult(epgRepository.refreshEpg(provider.id, xmltvUrl), "Failed to refresh EPG")
                }
                val epgCount = programDao.countByProvider(provider.id)
                syncMetadataRepository.updateMetadata(
                    metadata.copy(lastEpgSync = now, lastEpgSuccess = now, epgCount = epgCount)
                )
                if (epgCount == 0) warnings += "EPG imported zero programs; live guide may require provider fallback."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                retryable = isRetryableEpgException(e)
                warnings += "EPG XMLTV sync failed."
            }
        }
        retryable
    }

    suspend fun syncM3uProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): ProviderGuideSyncResult = syncProviderEpg(provider, metadata, now, force, onProgress) { warnings ->
        var retryable = false
        val epgUrl = provider.epgUrl
        if (shouldUseProviderGuide(provider.guideSourcePolicy) && epgUrl.isNotBlank() &&
            (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
        ) {
            val validationError = UrlSecurityPolicy.validateOptionalEpgUrl(epgUrl)
            if (validationError != null) {
                warnings += validationError
            } else {
                try {
                    progress(provider.id, onProgress, "Downloading EPG...")
                    xtreamSupport.retryTransient {
                        requireResult(epgRepository.refreshEpg(provider.id, epgUrl), "Failed to refresh EPG")
                    }
                    syncMetadataRepository.updateMetadata(
                        metadata.copy(
                            lastEpgSync = now,
                            lastEpgSuccess = now,
                            epgCount = programDao.countByProvider(provider.id)
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "EPG sync failed (non-fatal): ${sanitizeThrowableMessage(e)}")
                    retryable = isRetryableEpgException(e)
                    warnings += "EPG sync failed"
                }
            }
        }
        retryable
    }

    suspend fun syncStalkerProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): ProviderGuideSyncResult = syncProviderEpg(provider, metadata, now, force, onProgress) { warnings ->
        var retryable = false
        if (shouldUseProviderGuide(provider.guideSourcePolicy) &&
            (force || ContentCachePolicy.shouldRefresh(metadata.lastEpgSuccess, ContentCachePolicy.EPG_TTL_MILLIS, now))
        ) {
            val learnedEpgSupport = stalkerPortalStateStore.getValidated(provider.id)?.epgSupported
            if (learnedEpgSupport == false && provider.epgUrl.isBlank()) {
                StalkerTelemetry.strategySelected(provider.id, "EPG_SKIP", "EPG_KNOWN_UNSUPPORTED")
                warnings += "Stalker portal guide is known to be unsupported; keeping cached guide data."
            } else {
                val stalkerWarnings = syncStalkerPreferredEpg(provider, now, onProgress)
                retryable = stalkerWarnings.isNotEmpty()
                warnings += stalkerWarnings
            }
        }
        retryable
    }

    private suspend fun syncStalkerPreferredEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val warnings = mutableListOf<String>()
        val currentEpgUrl = provider.epgUrl
        var shouldUseNativeGuide = currentEpgUrl.isBlank()

        if (currentEpgUrl.isNotBlank()) {
            val epgValidationError = UrlSecurityPolicy.validateOptionalEpgUrl(currentEpgUrl)
            if (epgValidationError != null) {
                Log.w(TAG, "Portal XMLTV URL invalid for provider ${provider.id}: $epgValidationError")
                warnings.add("Portal XMLTV URL is invalid; using the Stalker portal guide instead.")
                shouldUseNativeGuide = true
            } else {
                try {
                    progress(provider.id, onProgress, "Downloading EPG...")
                    xtreamSupport.retryTransient {
                        requireResult(epgRepository.refreshEpg(provider.id, currentEpgUrl), "Failed to refresh EPG")
                    }
                    val epgCount = programDao.countByProvider(provider.id)
                    if (epgCount > 0) {
                        syncMetadataRepository.updateMetadata(
                            (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
                                lastEpgSync = now,
                                lastEpgSuccess = now,
                                epgCount = epgCount
                            )
                        )
                    } else {
                        warnings.add("Portal XMLTV imported zero programs; using the Stalker portal guide instead.")
                        shouldUseNativeGuide = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Portal XMLTV sync failed for provider ${provider.id}: ${sanitizeThrowableMessage(e)}")
                    warnings.add("Portal XMLTV sync failed; using the Stalker portal guide instead.")
                    shouldUseNativeGuide = true
                }
            }
        }

        if (shouldUseNativeGuide) {
            warnings += syncStalkerPortalEpg(
                provider = provider,
                now = now,
                onProgress = onProgress
            )
        }

        return warnings
    }

    private suspend fun syncStalkerPortalEpg(
        provider: Provider,
        now: Long,
        onProgress: ((String) -> Unit)?
    ): List<String> {
        val channels = channelDao.getGuideSyncEntriesByProvider(provider.id)
        if (channels.isEmpty()) return emptyList()

        val guideRequests = channels
            .mapNotNull(::toStalkerGuideRequest)
            .distinctBy(StalkerGuideRequest::channelKey)
        if (guideRequests.isEmpty()) {
            return listOf("Stalker portal guide sync skipped because no valid guide channel IDs were available.")
        }

        val previousProgramCount = programDao.countByProvider(provider.id)
        val api = createStalkerProvider(provider)
        val aliasToChannelKey = buildMap {
            guideRequests.forEach { request ->
                request.aliases.forEach { alias -> putIfAbsent(alias, request.channelKey) }
            }
        }
        val failedChannels = mutableListOf<String>()
        val insertBuffer = ArrayList<com.streamvault.data.local.entity.ProgramEntity>(STALKER_GUIDE_PROGRAM_BATCH_SIZE)
        val replacedChannelKeys = linkedSetOf<String>()
        val bulkCoveredChannelKeys = linkedSetOf<String>()
        var importedProgramCount = 0
        var providerRateLimited = false
        var bulkEpgReturnedEmpty = false

        suspend fun flushPrograms() {
            if (insertBuffer.isEmpty()) return
            val chunk = insertBuffer.toList()
            insertBuffer.clear()
            transactionRunner.inTransaction {
                chunk
                    .map { it.channelId }
                    .distinct()
                    .forEach { channelId ->
                        if (replacedChannelKeys.add(channelId)) {
                            programDao.deleteForChannel(provider.id, channelId)
                        }
                    }
                programDao.insertAll(chunk)
                importedProgramCount += chunk.size
            }
        }

        runSuspendCatching {
            stalkerRequestCoordinator.execute(
                providerId = provider.id,
                priority = StalkerRequestPriority.EPG,
                descriptor = StalkerRequestDescriptor(
                    contentType = "EPG",
                    action = "BULK_GUIDE"
                )
            ) {
                api.streamBulkEpg(periodHours = 6) { program ->
                    val resolvedChannelKey = aliasToChannelKey[program.channelId] ?: return@streamBulkEpg
                    if (program.endTime <= program.startTime) return@streamBulkEpg
                    bulkCoveredChannelKeys += resolvedChannelKey
                    insertBuffer += program.copy(
                        providerId = provider.id,
                        channelId = resolvedChannelKey
                    ).toEntity()
                    if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) flushPrograms()
                }
            }.let { result ->
                if (result is Result.Error) {
                    throw result.exception ?: IllegalStateException(result.message)
                } else if (result is Result.Success && result.data == 0) {
                    // Bulk call succeeded but carried no programs: on portals with a broken
                    // get_epg_info this is the signal to probe one per-channel request and
                    // then skip the rest instead of grinding through all of them.
                    bulkEpgReturnedEmpty = true
                }
            }
        }.onFailure { error ->
            providerRateLimited = error.hasStalkerRateLimit()
            Log.d(TAG, "Bulk Stalker portal EPG fetch unavailable for provider ${provider.id}", error)
        }

        val uncoveredGuideRequests = guideRequests.filterNot { it.channelKey in bulkCoveredChannelKeys }
        val perChannelGuideWindowed = uncoveredGuideRequests.size > STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN
        val fallbackGuideRequests = if (providerRateLimited) {
            emptyList()
        } else if (perChannelGuideWindowed) {
            val windowCount = (uncoveredGuideRequests.size + STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN - 1) /
                STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN
            val windowIndex = ((now / MILLIS_PER_DAY) % windowCount).toInt()
            uncoveredGuideRequests
                .drop(windowIndex * STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN)
                .take(STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN)
        } else {
            uncoveredGuideRequests
        }

        var ignorePerChannelGuide = false
        fallbackGuideRequests.forEachIndexed { index, request ->
            if (ignorePerChannelGuide) return@forEachIndexed
            progress(provider.id, onProgress, "Downloading portal EPG... ${index + 1} of ${fallbackGuideRequests.size}")
            var channelRecordCount = 0
            runSuspendCatching {
                var perChannelRecordCount = 0
                val foreignChannelIds = HashSet<String>()
                val streamResult = stalkerRequestCoordinator.execute(
                    providerId = provider.id,
                    priority = StalkerRequestPriority.EPG,
                    descriptor = StalkerRequestDescriptor(
                        contentType = "EPG",
                        action = "CHANNEL_GUIDE",
                        itemKey = request.channelKey
                    )
                ) {
                    api.streamEpg(request.channelKey) { program ->
                        if (program.endTime <= program.startTime) return@streamEpg
                        if (program.channelId != request.channelKey) foreignChannelIds += program.channelId
                        insertBuffer += program.copy(
                            providerId = provider.id,
                            channelId = request.channelKey
                        ).toEntity()
                        perChannelRecordCount++
                        channelRecordCount = perChannelRecordCount
                        if (perChannelRecordCount >= STALKER_PER_CHANNEL_RECORD_SANITY_CAP || foreignChannelIds.size > 1) {
                            throw StalkerBrokenPerChannelEpgException(request.channelName)
                        }
                        if (insertBuffer.size >= STALKER_GUIDE_PROGRAM_BATCH_SIZE) flushPrograms()
                    }
                }
                if (streamResult is Result.Error) {
                    throw streamResult.exception ?: IllegalStateException(streamResult.message)
                }
                if (channelRecordCount == 0 && bulkEpgReturnedEmpty && !providerRateLimited) {
                    // Bulk and the first per-channel request both came back empty: this
                    // portal's get_epg_info is dead, so skip the remaining calls instead
                    // of grinding through every channel. The guide fills on demand via
                    // short EPG when its pages are opened.
                    ignorePerChannelGuide = true
                    Log.w(
                        TAG,
                        "Stalker portal get_epg_info returned no programs for provider ${provider.id}; " +
                            "skipping remaining per-channel requests."
                    )
                }
            }.onFailure { error ->
                if (error.hasStalkerRateLimit()) {
                    providerRateLimited = true
                    ignorePerChannelGuide = true
                    Log.w(
                        TAG,
                        "Stalker provider ${provider.id} entered rate-limit cooldown; cancelling remaining per-channel EPG requests."
                    )
                } else if (error is StalkerBrokenPerChannelEpgException) {
                    ignorePerChannelGuide = true
                    Log.w(
                        TAG,
                        "Stalker portal returned bulk-shaped payload for per-channel EPG request " +
                            "(${request.channelKey}); skipping remaining per-channel guide calls."
                    )
                } else {
                    failedChannels += request.channelName
                    Log.w(
                        TAG,
                        "Stalker portal EPG fetch failed for provider ${provider.id} channel ${request.channelKey}",
                        error
                    )
                }
            }
        }

        flushPrograms()

        if (replacedChannelKeys.isEmpty() && failedChannels.isNotEmpty()) {
            api.recordEpgCapability(supported = false)
            return listOf(
                "Stalker portal guide sync failed for all ${failedChannels.size} channels; keeping existing guide data."
            )
        }

        val epgCount = programDao.countByProvider(provider.id)
        if (replacedChannelKeys.isNotEmpty() || bulkCoveredChannelKeys.isNotEmpty()) {
            api.recordEpgCapability(supported = true)
        }
        val guideLooksHealthy = epgCount >= STALKER_MIN_HEALTHY_EPG_PROGRAMS || previousProgramCount == 0
        val existingMetadata = syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)
        syncMetadataRepository.updateMetadata(
            existingMetadata.copy(
                lastEpgSync = now,
                lastEpgSuccess = if (guideLooksHealthy) now else existingMetadata.lastEpgSuccess,
                epgCount = epgCount
            )
        )

        val warnings = mutableListOf<String>()
        if (providerRateLimited) {
            warnings.add("Stalker portal rate limit reached; remaining guide requests were cancelled.")
        }
        if (perChannelGuideWindowed) {
            warnings.add(
                "Portal EPG fallback was limited to $STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN channels this run; " +
                    "later background runs rotate through the remaining channels."
            )
        }
        if (epgCount == 0) warnings.add("Stalker portal guide import returned zero programs.")
        if (failedChannels.isNotEmpty()) {
            warnings.add(
                "Stalker portal guide imported $epgCount programs, but ${failedChannels.size} channels failed (${summarizeChannelNames(failedChannels)})."
            )
        }
        if (!guideLooksHealthy && previousProgramCount >= STALKER_MIN_HEALTHY_EPG_PROGRAMS) {
            warnings.add(
                "Stalker portal guide import looked incomplete ($importedProgramCount programs across ${replacedChannelKeys.size} channels); preserved untouched prior guide data for the rest."
            )
        }
        return warnings
    }

    private data class StalkerGuideRequest(
        val channelKey: String,
        val channelName: String,
        val aliases: Set<String>
    )

    private fun toStalkerGuideRequest(channel: ChannelGuideSyncEntity): StalkerGuideRequest? {
        val normalizedEpgKey = channel.epgChannelId
            ?.trim()
            ?.takeUnless(::isLikelyPlaceholderStalkerGuideKey)
        val streamKey = channel.streamId.takeIf { it > 0L }?.toString()
        val channelKey = normalizedEpgKey ?: streamKey ?: return null
        val aliases = linkedSetOf<String>().apply {
            normalizedEpgKey?.let(::add)
            streamKey?.let(::add)
        }
        return StalkerGuideRequest(
            channelKey = channelKey,
            channelName = channel.name.ifBlank { channelKey },
            aliases = aliases
        )
    }

    private fun isLikelyPlaceholderStalkerGuideKey(value: String): Boolean =
        when (value.trim().lowercase()) {
            "no details available", "n/a", "null", "none", "unknown" -> true
            else -> false
        }

    private fun summarizeChannelNames(channelNames: List<String>): String {
        val distinctNames = channelNames.distinct()
        if (distinctNames.isEmpty()) return "unknown channels"
        val preview = distinctNames.take(3).joinToString()
        val remaining = distinctNames.size - 3
        return if (remaining > 0) "$preview, and $remaining more" else preview
    }

    suspend fun syncJellyfinProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?
    ): ProviderGuideSyncResult = syncProviderEpg(provider, metadata, now, force, onProgress) { false }

    suspend fun syncXtreamEpgOnly(provider: Provider, onProgress: ((String) -> Unit)?) =
        syncXmlTvEpgOnly(
            provider = provider,
            epgUrl = provider.epgUrl.ifBlank {
                XtreamUrlFactory.buildXmltvUrl(
                    provider.serverUrl.trimEnd('/'),
                    provider.username,
                    provider.password
                )
            },
            validateUrl = UrlSecurityPolicy::validateXtreamEpgUrl,
            onProgress = onProgress
        )

    suspend fun syncM3uEpgOnly(provider: Provider, onProgress: ((String) -> Unit)?) =
        syncXmlTvEpgOnly(
            provider = provider,
            epgUrl = provider.epgUrl,
            validateUrl = UrlSecurityPolicy::validateOptionalEpgUrl,
            onProgress = onProgress
        )

    suspend fun syncStalkerEpgOnly(provider: Provider, onProgress: ((String) -> Unit)?) {
        progress(provider.id, onProgress, "Retrying EPG...")
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val guidePolicy = provider.guideSourcePolicy
        if (guidePolicy == GuideSourcePolicy.DISABLED) {
            progress(provider.id, onProgress, "Guide data disabled for this provider.")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            return
        }
        if (shouldUseProviderGuide(guidePolicy)) {
            syncStalkerPreferredEpg(provider, System.currentTimeMillis(), onProgress)
        }
        if (shouldUseExternalGuide(guidePolicy)) {
            refreshExternalGuide(provider, hiddenLiveCategoryIds, onProgress)
        } else {
            progress(provider.id, onProgress, "Resolving provider EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        }
    }

    private suspend fun syncXmlTvEpgOnly(
        provider: Provider,
        epgUrl: String,
        validateUrl: (String) -> String?,
        onProgress: ((String) -> Unit)?
    ) {
        progress(provider.id, onProgress, "Retrying EPG...")
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        val guidePolicy = provider.guideSourcePolicy
        if (guidePolicy == GuideSourcePolicy.DISABLED) {
            progress(provider.id, onProgress, "Guide data disabled for this provider.")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            return
        }
        if (!shouldUseProviderGuide(guidePolicy)) {
            if (shouldUseExternalGuide(guidePolicy)) {
                refreshExternalGuide(provider, hiddenLiveCategoryIds, onProgress)
            }
            return
        }
        if (epgUrl.isBlank()) throw IllegalStateException("No EPG URL configured for this provider")
        validateUrl(epgUrl)?.let { throw IllegalStateException(it) }
        xtreamSupport.retryTransient {
            requireResult(epgRepository.refreshEpg(provider.id, epgUrl), "Failed to refresh EPG")
        }
        val now = System.currentTimeMillis()
        val metadata = (syncMetadataRepository.getMetadata(provider.id) ?: SyncMetadata(provider.id)).copy(
            lastEpgSync = now,
            lastEpgSuccess = now,
            epgCount = programDao.countByProvider(provider.id)
        )
        syncMetadataRepository.updateMetadata(metadata)
        if (shouldUseExternalGuide(guidePolicy)) {
            refreshExternalGuide(provider, hiddenLiveCategoryIds, onProgress)
        } else {
            progress(provider.id, onProgress, "Resolving provider EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
        }
    }

    private suspend fun syncProviderEpg(
        provider: Provider,
        metadata: SyncMetadata,
        now: Long,
        force: Boolean,
        onProgress: ((String) -> Unit)?,
        syncNativeGuide: suspend (MutableList<String>) -> Boolean
    ): ProviderGuideSyncResult {
        val warnings = mutableListOf<String>()
        var hasRetryableFailure = syncNativeGuide(warnings)
        val hiddenLiveCategoryIds = preferencesRepository.getHiddenCategoryIds(provider.id, ContentType.LIVE).first()
        when {
            shouldUseExternalGuide(provider.guideSourcePolicy) -> {
                hasRetryableFailure = hasRetryableFailure ||
                    refreshExternalGuide(provider, hiddenLiveCategoryIds, onProgress, warnings)
            }
            provider.guideSourcePolicy == GuideSourcePolicy.DISABLED -> {
                progress(provider.id, onProgress, "Guide data disabled for this provider.")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            }
            else -> {
                progress(provider.id, onProgress, "Resolving provider EPG mappings...")
                epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            }
        }
        return ProviderGuideSyncResult(warnings, hasRetryableFailure)
    }

    private suspend fun refreshExternalGuide(
        provider: Provider,
        hiddenLiveCategoryIds: Set<Long>,
        onProgress: ((String) -> Unit)?,
        warnings: MutableList<String>? = null
    ): Boolean {
        try {
            progress(provider.id, onProgress, "Refreshing external EPG sources...")
            xtreamSupport.retryTransient {
                requireResult(epgSourceRepository.refreshAllForProvider(provider.id), "External EPG source refresh failed")
            }
            progress(provider.id, onProgress, "Resolving EPG mappings...")
            epgSourceRepository.resolveForProvider(provider.id, hiddenLiveCategoryIds)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "External EPG resolution failed (non-fatal): ${sanitizeThrowableMessage(e)}")
            if (warnings == null) throw e
            warnings.add("External EPG source refresh/resolution failed.")
            return isRetryableEpgException(e)
        }
    }

    private fun shouldUseProviderGuide(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.PROVIDER_ONLY

    private fun shouldUseExternalGuide(policy: GuideSourcePolicy): Boolean =
        policy == GuideSourcePolicy.AUTO || policy == GuideSourcePolicy.EXTERNAL_ONLY

    private fun Throwable.hasStalkerRateLimit(): Boolean =
        generateSequence(this as Throwable?) { it.cause }
            .any { it is StalkerApiError.RateLimited }

    private fun isRetryableEpgException(e: Exception): Boolean =
        e is java.io.IOException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e.cause?.let {
                it is java.io.IOException ||
                    it is java.net.SocketTimeoutException ||
                    it is java.net.ConnectException ||
                    it is java.net.UnknownHostException
            } == true

    private fun <T> requireResult(result: Result<T>, fallbackMessage: String): T = when (result) {
        is Result.Success -> result.data
        is Result.Error -> throw IllegalStateException(result.message.ifBlank { fallbackMessage }, result.exception)
        is Result.Loading -> throw IllegalStateException("Unexpected loading state")
    }

    private class StalkerBrokenPerChannelEpgException(channelName: String) :
        RuntimeException("Stalker portal returned bulk-shaped EPG for per-channel request ($channelName)")

    private companion object {
        const val TAG = "ProviderEpgSync"
        const val STALKER_GUIDE_PROGRAM_BATCH_SIZE = 500
        const val STALKER_MIN_HEALTHY_EPG_PROGRAMS = 3
        const val STALKER_PER_CHANNEL_RECORD_SANITY_CAP = 5_000
        const val STALKER_PER_CHANNEL_EPG_REQUESTS_PER_RUN = 48
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
