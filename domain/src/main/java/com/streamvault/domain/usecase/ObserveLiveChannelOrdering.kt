package com.streamvault.domain.usecase

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.policy.liveChannelOrderScope
import com.streamvault.domain.policy.LiveChannelOrderSnapshot
import com.streamvault.domain.policy.effectiveMode
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.LiveChannelOrderScope
import com.streamvault.domain.repository.LiveWatchUsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.settings.SettingsPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Applies source/category settings to a complete catalog, without changing channel numbers. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveLiveChannelOrdering @Inject constructor(
    private val providers: ProviderRepository,
    private val orders: LiveChannelOrderRepository,
    private val preferences: SettingsPreferences,
    private val usage: LiveWatchUsageRepository? = null
) {
    operator fun invoke(providerId: Long, categoryId: Long, membership: Flow<List<Channel>>): Flow<List<Channel>> = flow {
        val provider = providers.getProvider(providerId)
        if (provider == null) {
            emitAll(membership)
        } else {
            val scope = liveChannelOrderScope(provider, Category(categoryId, name = "", type = ContentType.LIVE))
            emitAll(combine(membership, observeSettings(scope, providerId)) { rows, snapshot -> snapshot.apply(rows) })
        }
    }
    private data class Request(val order: LiveChannelOrder, val defaultMode: LiveChannelOrderMode, val isPrivate: Boolean, val reset: Long)

    /** Stats are sampled on entry or explicit changes, never on periodic watch-time writes. */
    fun observeSettings(scope: LiveChannelOrderScope, providerId: Long): Flow<LiveChannelOrderSnapshot> =
        observeSettings(scope, listOf(providerId), mapOf(providerId to ""))

    /** Combined favorites use one settings scope with provider-qualified watch statistics. */
    fun observeSettings(
        scope: LiveChannelOrderScope,
        providerIds: List<Long>,
        providerIdentityById: Map<Long, String>
    ): Flow<LiveChannelOrderSnapshot> = combine(
        orders.observe(scope), preferences.defaultLiveChannelOrder,
        if (usage == null) flowOf(false) else preferences.isIncognitoMode,
        usage?.resetVersion ?: flowOf(0L)
    ) { order, defaultMode, isPrivate, reset -> Request(order, defaultMode, isPrivate, reset) }
        .distinctUntilChanged().mapLatest { request ->
            val dynamic = request.order.effectiveMode(request.defaultMode) in
                listOf(LiveChannelOrderMode.RECENTLY_WATCHED, LiveChannelOrderMode.MOST_WATCHED)
            val stats = if (!request.isPrivate && dynamic && usage != null) {
                buildMap {
                    providerIds.distinct().forEach { providerId ->
                        val prefix = providerIdentityById[providerId].orEmpty()
                        usage.observe(providerId).first().forEach { (streamKey, value) ->
                            put(if (prefix.isBlank()) streamKey else "$prefix|$streamKey", value)
                        }
                    }
                }
            } else emptyMap()
            LiveChannelOrderSnapshot(request.order, request.defaultMode, stats)
        }

}
