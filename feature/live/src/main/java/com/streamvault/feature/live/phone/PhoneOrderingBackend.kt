package com.streamvault.feature.live.phone

import com.streamvault.domain.model.*
import com.streamvault.domain.policy.*
import com.streamvault.domain.playback.orderedByRequestedRawIds
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.GetCustomCategories
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.flow.first

internal enum class PhoneSection { PERSONAL, PINNED, PROVIDER }
internal data class PhoneLabels(val favorites: String, val recent: String, val all: String)
internal data class PhoneCategoryRow(val key: String, val name: String, val hidden: Boolean, val canEditChannels: Boolean)
internal data class PhoneChannelRow(val key: String, val name: String)
internal sealed interface PhoneSaveResult {
    data object Saved : PhoneSaveResult
    data object Conflict : PhoneSaveResult
    data object Invalid : PhoneSaveResult
}

/** The network layer serializes only the row DTOs; catalog objects never leave the device. */
internal class PhoneOrderingBackend @Inject constructor(
    private val providers: ProviderRepository,
    private val categories: CategoryRepository,
    private val channels: ChannelRepository,
    private val orders: LiveChannelOrderRepository,
    private val preferences: SettingsPreferences,
    private val customCategories: GetCustomCategories,
    private val usage: LiveWatchUsageRepository,
    private val favorites: FavoriteRepository,
    private val combined: CombinedM3uRepository
) {
    internal data class CategoryCatalog(
        val sourceName: String,
        val sections: Map<PhoneSection, List<PhoneCategoryRow>>,
        val revision: Long,
        val fingerprint: String,
        val normalReference: List<String>,
        internal val saved: LiveCategoryOrder,
        internal val allProviderIds: List<Long>,
        internal val allPersonalIds: List<Long>,
        val editableSections: Set<PhoneSection> = PhoneSection.entries.toSet()
    )

    internal data class ChannelCatalog(
        val title: String,
        val rows: List<PhoneChannelRow>,
        val revision: Long,
        val fingerprint: String,
        val mode: LiveChannelOrderMode,
        val defaultMode: LiveChannelOrderMode,
        val hasManualOrder: Boolean,
        val fixedOrder: Boolean,
        internal val scope: LiveChannelOrderScope?,
        internal val saved: LiveChannelOrder,
        internal val providerRows: List<Channel>,
        internal val allProviderRows: List<Channel> = providerRows,
        internal val orderKeySelector: (Channel) -> List<String> = { it.liveOrderKeys() },
        internal val usageStats: Map<String, LiveWatchUsage> = emptyMap()
    )

    suspend fun categories(providerId: Long, labels: PhoneLabels, allowProtected: Boolean = true): CategoryCatalog {
        val source = checkNotNull(providers.getProvider(providerId))
        val allProviderRows = categories.getCategories(providerId).first()
            .filter { it.type == ContentType.LIVE && !it.isVirtual && it.id != ChannelRepository.ALL_CHANNELS_ID }
            .sortedBy { it.providerOrder }
        val providerRows = if (allowProtected) allProviderRows else allProviderRows.filterNot { it.isAdult || it.isUserProtected }
        val saved = preferences.getLiveCategoryOrder(providerId).first()
        val custom = customCategories(providerId, ContentType.LIVE).first()
        val allPersonal = buildList {
            add(custom.firstOrNull { it.id == VirtualCategoryIds.FAVORITES }?.copy(name = labels.favorites)
                ?: Category(VirtualCategoryIds.FAVORITES, name = labels.favorites, isVirtual = true))
            add(Category(VirtualCategoryIds.RECENT, name = labels.recent, isVirtual = true))
            addAll(custom.filterNot { it.id == VirtualCategoryIds.FAVORITES })
            add(Category(ChannelRepository.ALL_CHANNELS_ID, name = labels.all))
        }
        val personal = if (allowProtected) allPersonal else allPersonal.filterNot { it.isAdult || it.isUserProtected }
        val hidden = preferences.getHiddenCategoryIds(providerId, ContentType.LIVE).first().toMutableSet()
        if (!preferences.showFavoritesCategory.first()) hidden += VirtualCategoryIds.FAVORITES
        if (!preferences.showRecentChannelsCategory.first()) hidden += VirtualCategoryIds.RECENT
        if (!preferences.showAllChannelsCategory.first()) hidden += ChannelRepository.ALL_CHANNELS_ID
        val ordered = applyLiveCategorySections(personal + providerRows, saved.pinnedCategoryIds, saved)
        val sections = PhoneSection.entries.associateWith { section ->
            ordered.filter { category -> when (section) {
                PhoneSection.PERSONAL -> category.isVirtual || category.id == ChannelRepository.ALL_CHANNELS_ID
                PhoneSection.PINNED -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id in saved.pinnedCategoryIds
                PhoneSection.PROVIDER -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id !in saved.pinnedCategoryIds
            } }.map { category -> PhoneCategoryRow(category.id.toString(), category.name, category.id in hidden,
                category.id != VirtualCategoryIds.RECENT) }
        }
        require(sections.values.flatten().map { it.key }.distinct().size == ordered.size)
        val fingerprint = digest(PhoneSection.entries.flatMap { section ->
            listOf(section.name) + sections.getValue(section).map { "${it.key}:${it.hidden}" }
        })
        val providerIds = providerRows.map { it.id }.toSet()
        val normalReference = applyLiveCategorySections(personal + providerRows, emptySet(), saved)
            .filter { it.id in providerIds }.map { it.id.toString() }
        return CategoryCatalog(source.name, sections, saved.revision, fingerprint, normalReference, saved,
            allProviderRows.map { it.id }, allPersonal.map { it.id })
    }

    suspend fun saveCategories(providerId: Long, labels: PhoneLabels, expectedRevision: Long, fingerprint: String,
        proposed: Map<PhoneSection, List<String>>, allowProtected: Boolean = true): PhoneSaveResult {
        val current = categories(providerId, labels, allowProtected)
        if (current.revision != expectedRevision || current.fingerprint != fingerprint) return PhoneSaveResult.Conflict
        if (!sameMembers(proposed[PhoneSection.PERSONAL], current.sections.getValue(PhoneSection.PERSONAL).map { it.key })) return PhoneSaveResult.Invalid
        val proposedPinned = proposed[PhoneSection.PINNED] ?: return PhoneSaveResult.Invalid
        val proposedNormal = proposed[PhoneSection.PROVIDER] ?: return PhoneSaveResult.Invalid
        val currentNormal = current.sections.getValue(PhoneSection.PROVIDER).map { it.key }
        val currentPinned = current.sections.getValue(PhoneSection.PINNED).map { it.key }
        if (!sameMembers(proposedPinned + proposedNormal, currentPinned + currentNormal)) return PhoneSaveResult.Invalid
        fun ids(section: PhoneSection) = proposed.getValue(section).map(String::toLong)
        val normalMembers = proposedNormal.toSet()
        val normalChanged = proposedNormal != current.normalReference.filter { it in normalMembers }
        val saved = current.saved
        val requested = saved.copy(
            sortMode = if (normalChanged) CategorySortMode.MANUAL else saved.sortMode,
            normalIds = if (normalChanged) mergeLiveCategoryPositions((saved.normalIds + current.allProviderIds).distinct(), ids(PhoneSection.PROVIDER)) else saved.normalIds,
            pinnedIds = mergeLiveCategoryPositions(saved.pinnedIds, ids(PhoneSection.PINNED)),
            personalIds = mergeLiveCategoryPositions((saved.personalIds + current.allPersonalIds).distinct(), ids(PhoneSection.PERSONAL)),
            pinnedCategoryIds = (saved.pinnedCategoryIds - (currentPinned + currentNormal).map(String::toLong).toSet()) + ids(PhoneSection.PINNED)
        )
        return when (preferences.saveLiveCategoryOrderWithPins(providerId, expectedRevision, requested)) {
            is SaveLiveCategoryOrderResult.Saved -> PhoneSaveResult.Saved
            is SaveLiveCategoryOrderResult.Conflict -> PhoneSaveResult.Conflict
        }
    }

    suspend fun combinedCategories(profileId: Long, labels: PhoneLabels, allowProtected: Boolean = true): CategoryCatalog {
        val profile = checkNotNull(combined.getProfile(profileId))
        val providerIds = profile.members.filter { it.enabled }.map { it.providerId }.distinct()
        val combinedCategories = combined.getCombinedCategories(profileId).first()
        val allProviderRows = combinedCategories.map { it.category }
            .filter { it.type == ContentType.LIVE && !it.isVirtual && it.id != ChannelRepository.ALL_CHANNELS_ID }
        val protectedBindings = if (allowProtected) emptySet() else providerIds.flatMap { id ->
            categories.getCategories(id).first().filter { it.type == ContentType.LIVE && (it.isAdult || it.isUserProtected) }
                .map { id to it.id }
        }.toSet()
        val providerRows = if (allowProtected) allProviderRows else allProviderRows.filterNot { row ->
            row.isAdult || row.isUserProtected || combinedCategories.firstOrNull { it.category.id == row.id }
                ?.bindings?.any { (it.providerId to it.categoryId) in protectedBindings } == true
        }
        val saved = preferences.getCombinedLiveCategoryOrder(profile.identityKey).first()
        val custom = customCategories(providerIds, ContentType.LIVE).first()
        val allPersonal = buildList {
            add(custom.firstOrNull { it.id == VirtualCategoryIds.FAVORITES }?.copy(name = labels.favorites)
                ?: Category(VirtualCategoryIds.FAVORITES, name = labels.favorites, isVirtual = true))
            add(Category(VirtualCategoryIds.RECENT, name = labels.recent, isVirtual = true))
            addAll(custom.filterNot { it.id == VirtualCategoryIds.FAVORITES })
            add(Category(ChannelRepository.ALL_CHANNELS_ID, name = labels.all))
        }
        val personal = if (allowProtected) allPersonal else allPersonal.filterNot { it.isAdult || it.isUserProtected }
        val hidden = mutableSetOf<Long>()
        if (!preferences.showFavoritesCategory.first()) hidden += VirtualCategoryIds.FAVORITES
        if (!preferences.showRecentChannelsCategory.first()) hidden += VirtualCategoryIds.RECENT
        if (!preferences.showAllChannelsCategory.first()) hidden += ChannelRepository.ALL_CHANNELS_ID
        val ordered = applyLiveCategorySections(personal + providerRows, saved.pinnedCategoryIds, saved)
        val sections = PhoneSection.entries.associateWith { section ->
            ordered.filter { category -> when (section) {
                PhoneSection.PERSONAL -> category.isVirtual || category.id == ChannelRepository.ALL_CHANNELS_ID
                PhoneSection.PINNED -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id in saved.pinnedCategoryIds
                PhoneSection.PROVIDER -> !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID && category.id !in saved.pinnedCategoryIds
            } }.map { category ->
                PhoneCategoryRow(category.id.toString(), category.name, category.id in hidden,
                    category.id != VirtualCategoryIds.RECENT)
            }
        }
        require(sections.values.flatten().map { it.key }.distinct().size == ordered.size)
        val providerIdsSet = providerRows.map { it.id }.toSet()
        val normalReference = applyLiveCategorySections(personal + providerRows, emptySet(), saved)
            .filter { it.id in providerIdsSet }.map { it.id.toString() }
        val fingerprint = digest(listOf("combined:${profile.identityKey}") + PhoneSection.entries.flatMap { section ->
            listOf(section.name) + sections.getValue(section).map { "${it.key}:${it.hidden}" }
        })
        return CategoryCatalog(profile.name, sections, saved.revision, fingerprint, normalReference, saved,
            allProviderRows.map { it.id }, allPersonal.map { it.id },
            editableSections = setOf(PhoneSection.PINNED, PhoneSection.PROVIDER))
    }

    suspend fun saveCombinedCategories(profileId: Long, labels: PhoneLabels, expectedRevision: Long, fingerprint: String,
        proposed: Map<PhoneSection, List<String>>, allowProtected: Boolean = true): PhoneSaveResult {
        val current = combinedCategories(profileId, labels, allowProtected)
        if (current.revision != expectedRevision || current.fingerprint != fingerprint) return PhoneSaveResult.Conflict
        if (!sameMembers(proposed[PhoneSection.PERSONAL], current.sections.getValue(PhoneSection.PERSONAL).map { it.key }))
            return PhoneSaveResult.Invalid
        val proposedPinned = proposed[PhoneSection.PINNED] ?: return PhoneSaveResult.Invalid
        val proposedNormal = proposed[PhoneSection.PROVIDER] ?: return PhoneSaveResult.Invalid
        val currentNormal = current.sections.getValue(PhoneSection.PROVIDER).map { it.key }
        val currentPinned = current.sections.getValue(PhoneSection.PINNED).map { it.key }
        if (!sameMembers(proposedPinned + proposedNormal, currentPinned + currentNormal)) return PhoneSaveResult.Invalid
        fun ids(section: PhoneSection) = proposed.getValue(section).map(String::toLong)
        val normalMembers = proposedNormal.toSet()
        val normalChanged = proposedNormal != current.normalReference.filter { it in normalMembers }
        val saved = current.saved
        val requested = saved.copy(
            sortMode = if (normalChanged) CategorySortMode.MANUAL else saved.sortMode,
            normalIds = if (normalChanged) mergeLiveCategoryPositions((saved.normalIds + current.allProviderIds).distinct(), ids(PhoneSection.PROVIDER)) else saved.normalIds,
            pinnedIds = mergeLiveCategoryPositions(saved.pinnedIds, ids(PhoneSection.PINNED)),
            personalIds = saved.personalIds,
            pinnedCategoryIds = (saved.pinnedCategoryIds - (currentPinned + currentNormal).map(String::toLong).toSet()) + ids(PhoneSection.PINNED)
        )
        val profile = checkNotNull(combined.getProfile(profileId))
        return when (preferences.saveCombinedLiveCategoryOrder(profile.identityKey, expectedRevision, requested)) {
            is SaveLiveCategoryOrderResult.Saved -> PhoneSaveResult.Saved
            is SaveLiveCategoryOrderResult.Conflict -> PhoneSaveResult.Conflict
        }
    }

    suspend fun channels(providerId: Long, categoryId: Long, allLabel: String, allowProtected: Boolean = true): ChannelCatalog? {
        val provider = providers.getProvider(providerId) ?: return null
        if (categoryId != ChannelRepository.ALL_CHANNELS_ID && categoryId < 0) {
            return favoriteChannels(providerId, categoryId, allowProtected)
        }
        val category = if (categoryId == ChannelRepository.ALL_CHANNELS_ID) Category(categoryId, name = allLabel)
            else categories.getCategories(providerId).first().firstOrNull {
                it.id == categoryId && it.type == ContentType.LIVE && !it.isVirtual &&
                    (allowProtected || !it.isAdult && !it.isUserProtected)
            } ?: return null
        val scope = liveChannelOrderScope(provider, category)
        val saved = orders.observe(scope).first()
        val defaultMode = preferences.defaultLiveChannelOrder.first()
        val allRaw = channels.getChannelsByCategory(providerId, categoryId).first()
        val raw = visibleChannels(providerId, allRaw, allowProtected)
        require(raw.all { it.liveOrderKeys().isNotEmpty() })
        require(raw.map(::rowKey).distinct().size == raw.size)
        val stats = if (preferences.isIncognitoMode.first()) emptyMap() else usage.observe(providerId).first()
        val ordered = orderLiveChannels(raw, saved, defaultMode, stats)
        val fingerprint = digest(raw.map { row -> "${rowKey(row)}:${row.liveOrderKeys().sorted().joinToString(",")}" }.sorted())
        return ChannelCatalog(category.name, ordered.map { PhoneChannelRow(rowKey(it), it.name) }, saved.revision,
            fingerprint, saved.mode, defaultMode, saved.manualKeys.isNotEmpty(), false, scope, saved, raw, allRaw)
    }

    suspend fun combinedChannels(profileId: Long, categoryId: Long, labels: PhoneLabels,
        allowProtected: Boolean = true): ChannelCatalog? {
        if (categoryId == VirtualCategoryIds.RECENT) return null
        val profile = combined.getProfile(profileId) ?: return null
        val providerIds = profile.members.filter { it.enabled }.map { it.providerId }.distinct()
        val providersById = providerIds.mapNotNull { id -> providers.getProvider(id)?.let { id to it } }.toMap()
        val identities = providersById.mapValues { (_, provider) -> liveProviderOrderIdentity(provider) }
        val isFavorites = categoryId == VirtualCategoryIds.FAVORITES
        val categoryPair = if (isFavorites || categoryId == ChannelRepository.ALL_CHANNELS_ID) null else {
            combined.getCombinedCategories(profileId).first().singleOrNull { it.category.id == categoryId }
        }
        if (!allowProtected && categoryPair != null) {
            if (categoryPair.category.isAdult || categoryPair.category.isUserProtected) return null
            for (binding in categoryPair.bindings) {
                val rawCategory = categories.getCategories(binding.providerId).first()
                    .firstOrNull { it.type == ContentType.LIVE && it.id == binding.categoryId }
                if (rawCategory == null || rawCategory.isAdult || rawCategory.isUserProtected) return null
            }
        }
        val group = if (categoryId < 0L && categoryId != ChannelRepository.ALL_CHANNELS_ID && !isFavorites && categoryPair == null) {
            favorites.getGroups(providerIds, ContentType.LIVE).first().singleOrNull { it.id == -categoryId }
                ?: return null
        } else null
        if (!isFavorites && categoryId != ChannelRepository.ALL_CHANNELS_ID && categoryPair == null && group == null) return null
        val category = categoryPair?.category ?: if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            Category(categoryId, name = labels.all, type = ContentType.LIVE)
        } else if (isFavorites) {
            Category(categoryId, name = labels.favorites, type = ContentType.LIVE, isVirtual = true)
        } else {
            Category(categoryId, name = group?.name.orEmpty(), type = ContentType.LIVE, isVirtual = true)
        }
        val scope = when {
            isFavorites -> LiveChannelOrderScope("combined:${profile.identityKey}", "live:favorites")
            group != null -> liveCustomGroupOrderScope(checkNotNull(providersById[group.providerId]), group)
            else -> combinedLiveChannelOrderScope(profile, category)
        }
        val favoritesSnapshot = when {
            isFavorites -> favorites.getFavorites(providerIds, ContentType.LIVE).first()
                .filter { it.groupId == null && it.providerId in providerIds && it.contentType == ContentType.LIVE }
                .sortedWith(compareBy<Favorite> { it.position }.thenBy { it.providerId }.thenBy { it.id })
            group != null -> favorites.getFavoritesByGroup(group.id).first()
                .filter { it.groupId == group.id && it.providerId == group.providerId && it.contentType == ContentType.LIVE }
                .sortedWith(compareBy<Favorite> { it.position }.thenBy { it.id })
            else -> emptyList()
        }
        suspend fun providerOrderRows(combinedCategory: CombinedCategory): List<Channel> {
            val priorities = profile.members.filter { it.enabled }.associate { it.providerId to it.priority }
            return combinedCategory.bindings.filter { it.providerId in priorities }
                .sortedBy { priorities.getValue(it.providerId) }
                .flatMap { binding -> channels.getChannelsByCategory(binding.providerId, binding.categoryId).first() }
        }
        val allRows = when {
            isFavorites || group != null -> {
                val ids = favoritesSnapshot.map { it.contentId }
                if (ids.isEmpty()) emptyList() else channels.getChannelsByIds(ids).first()
                    .filter { it.providerId in providerIds && (group == null || it.providerId == group.providerId) }
                    .orderedByRequestedRawIds(ids)
            }
            categoryId == ChannelRepository.ALL_CHANNELS_ID -> combined.getCombinedCategories(profileId).first()
                .flatMap { providerOrderRows(it) }
            else -> providerOrderRows(checkNotNull(categoryPair))
        }
        val raw = visibleCombinedChannels(providerIds, allRows, allowProtected)
        require(raw.all { channel -> (group != null || identities[channel.providerId] != null) && channel.liveOrderKeys().isNotEmpty() })
        require(raw.map(::rowKey).distinct().size == raw.size)
        val keySelector: (Channel) -> List<String> = if (group != null) {
            { channel -> channel.liveOrderKeys() }
        } else {
            { channel -> channel.liveOrderKeys(checkNotNull(identities[channel.providerId])) }
        }
        val saved = if ((isFavorites || group != null) && !orders.hasSavedOrder(scope) && allRows.isNotEmpty()) {
            orders.initializeIfAbsent(scope, LiveChannelOrder(LiveChannelOrderMode.MANUAL, allRows.flatMap(keySelector).distinct()))
        } else orders.observe(scope).first()
        val defaultMode = preferences.defaultLiveChannelOrder.first()
        val private = preferences.isIncognitoMode.first()
        val dynamic = saved.effectiveMode(defaultMode) in listOf(
            LiveChannelOrderMode.RECENTLY_WATCHED, LiveChannelOrderMode.MOST_WATCHED
        )
        val stats = if (private || !dynamic) emptyMap() else buildMap {
            val usageProviderIds = if (group != null) listOf(group.providerId) else providerIds
            usageProviderIds.forEach { providerId ->
                val prefix = if (group != null) "" else identities[providerId].orEmpty()
                usage.observe(providerId).first().forEach { (key, value) ->
                    put(if (prefix.isBlank()) key else "$prefix|$key", value)
                }
            }
        }
        val ordered = orderLiveChannels(raw, saved, defaultMode, stats, keySelector)
        val favoriteStamp = if (isFavorites || group != null) favoritesSnapshot.map {
            "${it.id}:${it.providerId}:${it.contentId}:${it.position}:${it.addedAt}"
        } else emptyList()
        val fingerprint = digest(listOf("combined:${profile.identityKey}", "category:$categoryId", "group:${group?.identityKey.orEmpty()}") +
            favoriteStamp + allRows.map { row -> "${rowKey(row)}:${keySelector(row).sorted().joinToString(",")}" }.sorted())
        return ChannelCatalog(category.name, ordered.map { PhoneChannelRow(rowKey(it), it.name) }, saved.revision,
            fingerprint, saved.mode, defaultMode, saved.manualKeys.isNotEmpty(), false, scope, saved, raw, allRows,
            keySelector, stats)
    }

    private suspend fun favoriteChannels(providerId: Long, categoryId: Long, allowProtected: Boolean): ChannelCatalog? {
        if (categoryId == VirtualCategoryIds.RECENT) return null
        val custom = customCategories(providerId, ContentType.LIVE).first()
        val category = custom.firstOrNull { it.isVirtual && it.id == categoryId &&
            (allowProtected || !it.isAdult && !it.isUserProtected) } ?: return null
        val provider = providers.getProvider(providerId) ?: return null
        val groupId = if (categoryId == VirtualCategoryIds.FAVORITES) null else -categoryId
        val group = if (groupId == null) null else favorites.getGroups(providerId, ContentType.LIVE).first()
            .singleOrNull { it.id == groupId } ?: return null
        val scope = if (group == null) liveFavoritesOrderScope(liveProviderOrderIdentity(provider))
            else liveCustomGroupOrderScope(provider, group)
        val snapshot = (if (groupId == null) favorites.getFavorites(providerId, ContentType.LIVE).first()
            else favorites.getFavoritesByGroup(groupId).first())
            .filter { it.providerId == providerId && it.contentType == ContentType.LIVE && it.groupId == groupId }
            .sortedWith(compareBy(Favorite::position, Favorite::id))
        val ids = snapshot.map { it.contentId }
        val allRaw = if (ids.isEmpty()) emptyList() else channels.getChannelsByIds(ids).first()
            .filter { it.providerId == providerId }.orderedByRequestedRawIds(ids)
        val raw = visibleChannels(providerId, allRaw, allowProtected)
        require(raw.map(::rowKey).distinct().size == raw.size)
        val completeKeys = allRaw.flatMap { it.liveOrderKeys() }.distinct()
        var saved = if (orders.hasSavedOrder(scope)) {
            orders.observe(scope).first()
        } else {
            orders.initializeIfAbsent(scope, LiveChannelOrder(LiveChannelOrderMode.MANUAL, completeKeys))
        }
        if (saved.manualKeys.isNotEmpty() || saved.mode == LiveChannelOrderMode.MANUAL) {
            val reconciled = saved.reconcileVisibleMembership(ids, allRaw, completeKeys)
            if (reconciled.manualKeys != saved.manualKeys) {
                saved = when (val result = orders.save(scope, saved.revision, reconciled)) {
                    is LiveChannelOrderSaveResult.Saved -> result.order
                    is LiveChannelOrderSaveResult.Conflict -> result.current
                }
            }
        }
        val defaultMode = preferences.defaultLiveChannelOrder.first()
        val stats = if (preferences.isIncognitoMode.first()) emptyMap() else usage.observe(providerId).first()
        val ordered = orderLiveChannels(raw, saved, defaultMode, stats)
        val fingerprint = digest(snapshot.map { "${it.id}:${it.contentId}:${it.position}:${it.addedAt}" } +
            raw.map { "${rowKey(it)}:${it.allVariantRawIds().sorted().joinToString(",")}" })
        return ChannelCatalog(category.name, ordered.map { PhoneChannelRow(rowKey(it), it.name) }, saved.revision,
            fingerprint, saved.mode, defaultMode, saved.manualKeys.isNotEmpty(), false,
            scope, saved, raw, allRaw)
    }

    suspend fun previewChannels(providerId: Long, categoryId: Long, allLabel: String, expectedRevision: Long,
        fingerprint: String, mode: LiveChannelOrderMode, allowProtected: Boolean = true): Pair<PhoneSaveResult, List<PhoneChannelRow>> {
        if (mode != LiveChannelOrderMode.USE_DEFAULT && mode != LiveChannelOrderMode.MANUAL && mode !in liveChannelPresetModes)
            return PhoneSaveResult.Invalid to emptyList()
        val current = channels(providerId, categoryId, allLabel, allowProtected) ?: return PhoneSaveResult.Invalid to emptyList()
        if (current.revision != expectedRevision || current.fingerprint != fingerprint) return PhoneSaveResult.Conflict to emptyList()
        if (current.fixedOrder) return if (mode == LiveChannelOrderMode.MANUAL) PhoneSaveResult.Saved to current.rows
            else PhoneSaveResult.Invalid to emptyList()
        if (mode == LiveChannelOrderMode.MANUAL && current.saved.manualKeys.isEmpty() && current.saved.mode != mode)
            return PhoneSaveResult.Invalid to emptyList()
        val stats = if (preferences.isIncognitoMode.first()) emptyMap() else usage.observe(providerId).first()
        val rows = orderLiveChannels(current.providerRows, current.saved.copy(mode = mode), current.defaultMode, stats)
        return PhoneSaveResult.Saved to rows.map { PhoneChannelRow(rowKey(it), it.name) }
    }

    suspend fun saveChannels(providerId: Long, categoryId: Long, allLabel: String, expectedRevision: Long,
        fingerprint: String, mode: LiveChannelOrderMode, manuallyMoved: Boolean, proposedKeys: List<String>,
        allowProtected: Boolean = true): PhoneSaveResult {
        if (mode != LiveChannelOrderMode.USE_DEFAULT && mode != LiveChannelOrderMode.MANUAL && mode !in liveChannelPresetModes)
            return PhoneSaveResult.Invalid
        val current = channels(providerId, categoryId, allLabel, allowProtected) ?: return PhoneSaveResult.Invalid
        if (current.revision != expectedRevision || current.fingerprint != fingerprint) return PhoneSaveResult.Conflict
        if (!sameMembers(proposedKeys, current.rows.map { it.key })) return PhoneSaveResult.Invalid
        if (mode == LiveChannelOrderMode.MANUAL && !manuallyMoved && current.saved.manualKeys.isEmpty() && current.saved.mode != mode)
            return PhoneSaveResult.Invalid
        val requested = if (mode == LiveChannelOrderMode.MANUAL && manuallyMoved) {
            val byKey = current.providerRows.associateBy(::rowKey)
            val visible = proposedKeys.flatMap { byKey.getValue(it).liveOrderKeys() }.distinct()
            val base = (current.saved.manualKeys + current.allProviderRows.flatMap { it.liveOrderKeys() }).distinct()
            current.saved.copy(mode = mode, manualKeys = mergeLiveOrderVisibleKeys(base, visible))
        } else current.saved.copy(mode = mode)
        val scope = current.scope ?: return PhoneSaveResult.Invalid
        return when (orders.save(scope, expectedRevision, requested)) {
            is LiveChannelOrderSaveResult.Saved -> PhoneSaveResult.Saved
            is LiveChannelOrderSaveResult.Conflict -> PhoneSaveResult.Conflict
        }
    }

    suspend fun previewCombinedChannels(profileId: Long, categoryId: Long, labels: PhoneLabels, expectedRevision: Long,
        fingerprint: String, mode: LiveChannelOrderMode, allowProtected: Boolean = true): Pair<PhoneSaveResult, List<PhoneChannelRow>> {
        if (mode != LiveChannelOrderMode.USE_DEFAULT && mode != LiveChannelOrderMode.MANUAL && mode !in liveChannelPresetModes)
            return PhoneSaveResult.Invalid to emptyList()
        val current = combinedChannels(profileId, categoryId, labels, allowProtected)
            ?: return PhoneSaveResult.Invalid to emptyList()
        if (current.revision != expectedRevision || current.fingerprint != fingerprint)
            return PhoneSaveResult.Conflict to emptyList()
        if (mode == LiveChannelOrderMode.MANUAL && current.saved.manualKeys.isEmpty() && current.saved.mode != mode)
            return PhoneSaveResult.Invalid to emptyList()
        val rows = orderLiveChannels(current.providerRows, current.saved.copy(mode = mode), current.defaultMode,
            current.usageStats, current.orderKeySelector)
        return PhoneSaveResult.Saved to rows.map { PhoneChannelRow(rowKey(it), it.name) }
    }

    suspend fun saveCombinedChannels(profileId: Long, categoryId: Long, labels: PhoneLabels, expectedRevision: Long,
        fingerprint: String, mode: LiveChannelOrderMode, manuallyMoved: Boolean, proposedKeys: List<String>,
        allowProtected: Boolean = true): PhoneSaveResult {
        if (mode != LiveChannelOrderMode.USE_DEFAULT && mode != LiveChannelOrderMode.MANUAL && mode !in liveChannelPresetModes)
            return PhoneSaveResult.Invalid
        val current = combinedChannels(profileId, categoryId, labels, allowProtected) ?: return PhoneSaveResult.Invalid
        if (current.revision != expectedRevision || current.fingerprint != fingerprint) return PhoneSaveResult.Conflict
        if (!sameMembers(proposedKeys, current.rows.map { it.key })) return PhoneSaveResult.Invalid
        if (mode == LiveChannelOrderMode.MANUAL && !manuallyMoved && current.saved.manualKeys.isEmpty() && current.saved.mode != mode)
            return PhoneSaveResult.Invalid
        val requested = if (mode == LiveChannelOrderMode.MANUAL && manuallyMoved) {
            val byKey = current.providerRows.associateBy(::rowKey)
            val visible = proposedKeys.flatMap { byKey.getValue(it).let(current.orderKeySelector) }.distinct()
            val base = (current.saved.manualKeys + current.allProviderRows.flatMap(current.orderKeySelector)).distinct()
            current.saved.copy(mode = mode, manualKeys = mergeLiveOrderVisibleKeys(base, visible))
        } else current.saved.copy(mode = mode)
        val scope = current.scope ?: return PhoneSaveResult.Invalid
        return when (orders.save(scope, expectedRevision, requested)) {
            is LiveChannelOrderSaveResult.Saved -> PhoneSaveResult.Saved
            is LiveChannelOrderSaveResult.Conflict -> PhoneSaveResult.Conflict
        }
    }

    /** Presentation may clear channel flags after a category is unlocked on the TV. */
    private suspend fun visibleChannels(providerId: Long, rows: List<Channel>, allowProtected: Boolean): List<Channel> {
        if (allowProtected) return rows
        val protectedCategories = categories.getCategories(providerId).first()
            .filter { it.type == ContentType.LIVE && (it.isAdult || it.isUserProtected) }
            .mapTo(mutableSetOf()) { it.id }
        return rows.filterNot { row ->
            row.isAdult || row.isUserProtected || row.categoryId in protectedCategories || row.variants.any { variant ->
                variant.isAdult || variant.isUserProtected || variant.categoryId in protectedCategories
            }
        }
    }

    private suspend fun visibleCombinedChannels(providerIds: List<Long>, rows: List<Channel>, allowProtected: Boolean): List<Channel> {
        if (allowProtected) return rows
        val protectedCategories = providerIds.associateWith { providerId ->
            categories.getCategories(providerId).first()
                .filter { it.type == ContentType.LIVE && (it.isAdult || it.isUserProtected) }
                .mapTo(mutableSetOf()) { it.id }
        }
        return rows.filterNot { row ->
            row.isAdult || row.isUserProtected || row.categoryId in protectedCategories[row.providerId].orEmpty() ||
                row.variants.any { variant ->
                    variant.isAdult || variant.isUserProtected || variant.categoryId in protectedCategories[variant.providerId].orEmpty()
                }
        }
    }

    private fun rowKey(channel: Channel) = "${channel.providerId}:${channel.id}"
    private fun sameMembers(proposed: List<String>?, current: List<String>) = proposed != null &&
        proposed.size == current.size && proposed.distinct().size == proposed.size && proposed.toSet() == current.toSet()
    private fun digest(parts: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
