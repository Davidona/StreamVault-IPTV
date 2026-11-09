package com.streamvault.data.repository

import com.streamvault.data.local.dao.CombinedM3uProfileDao
import com.streamvault.data.local.dao.CombinedM3uProfileMemberDao
import com.streamvault.data.local.entity.CombinedM3uProfileEntity
import com.streamvault.data.local.entity.CombinedM3uProfileMemberEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedCategoryBinding
import com.streamvault.domain.model.CombinedCategoryIdentity
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.LiveWatchUsage
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.LiveWatchUsageRepository
import com.streamvault.domain.policy.combinedLiveChannelOrderScope
import com.streamvault.domain.policy.liveProviderOrderIdentity
import com.streamvault.domain.policy.liveOrderKeys
import com.streamvault.domain.policy.effectiveMode
import com.streamvault.domain.policy.orderLiveChannels
import com.streamvault.domain.settings.SettingsPreferences
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CombinedM3uRepositoryImpl @Inject constructor(
    private val profileDao: CombinedM3uProfileDao,
    private val memberDao: CombinedM3uProfileMemberDao,
    private val channelRepository: ChannelRepository,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository,
    private val liveChannelOrders: LiveChannelOrderRepository,
    private val settings: SettingsPreferences,
    private val liveUsage: LiveWatchUsageRepository,
    private val legacyIdentityMigrator: LegacyCombinedIdentityMigrator? = null
) : CombinedM3uRepository {

    override fun getProfiles(): Flow<List<CombinedM3uProfile>> = flow {
        legacyIdentityMigrator?.migrate()
        emitAll(profileDao.getAll().flatMapLatest { profiles ->
            if (profiles.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(profiles.map { profile ->
                    memberDao.getForProfile(profile.id).map { members ->
                        profile.toDomain(members.map { it.toDomain() })
                    }
                }) { it.toList() }
            }
        })
    }

    override suspend fun getProfile(profileId: Long): CombinedM3uProfile? {
        legacyIdentityMigrator?.migrate()
        val profile = profileDao.getById(profileId) ?: return null
        return profile.toDomain(memberDao.getForProfileSync(profileId).map { it.toDomain() })
    }

    override suspend fun createProfile(name: String, providerIds: List<Long>): Result<CombinedM3uProfile> {
        return try {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return Result.error("Combined profile name is required.")
        }
        val distinctProviderIds = providerIds.distinct()
        if (distinctProviderIds.isEmpty()) {
            return Result.error("Select at least one M3U provider.")
        }
        val providers = distinctProviderIds.mapNotNull { providerId ->
            providerRepository.getProvider(providerId)
        }
        if (providers.size != distinctProviderIds.size || providers.any { it.type != ProviderType.M3U }) {
            return Result.error("Combined profiles support M3U providers only.")
        }
        val now = System.currentTimeMillis()
        val profileId = profileDao.insert(
            CombinedM3uProfileEntity(
                name = normalizedName,
                enabled = true,
                createdAt = now,
                updatedAt = now
            )
        )
        distinctProviderIds.forEachIndexed { index, providerId ->
            memberDao.insert(
                CombinedM3uProfileMemberEntity(
                    profileId = profileId,
                    providerId = providerId,
                    priority = index,
                    enabled = true
                )
            )
        }
        Result.success(getProfile(profileId)!!)
    } catch (e: Exception) {
        Result.error("Failed to create combined profile: ${e.message}", e)
    }
    }

    override suspend fun updateProfileName(profileId: Long, name: String): Result<Unit> {
        return try {
        val current = profileDao.getById(profileId) ?: return Result.error("Combined profile not found.")
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return Result.error("Combined profile name is required.")
        }
        profileDao.rename(current.id, normalizedName, System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update combined profile: ${e.message}", e)
    }
    }

    override suspend fun deleteProfile(profileId: Long): Result<Unit> {
        return try {
        val activeSource = preferencesRepository.activeLiveSource.first()
        profileDao.delete(profileId)
        if (activeSource is ActiveLiveSource.CombinedM3uSource && activeSource.profileId == profileId) {
            preferencesRepository.setActiveLiveSource(null)
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to delete combined profile: ${e.message}", e)
    }
    }

    override suspend fun addProvider(profileId: Long, providerId: Long): Result<Unit> {
        return try {
        val profile = profileDao.getById(profileId) ?: return Result.error("Combined profile not found.")
        val provider = providerRepository.getProvider(providerId)
            ?: return Result.error("Provider not found.")
        if (provider.type != ProviderType.M3U) {
            return Result.error("Only M3U providers can be added to a combined profile.")
        }
        if (memberDao.getMember(profileId, providerId) != null) {
            return Result.success(Unit)
        }
        val nextPriority = memberDao.countForProfile(profileId)
        memberDao.insert(
            CombinedM3uProfileMemberEntity(
                profileId = profileId,
                providerId = providerId,
                priority = nextPriority,
                enabled = true
            )
        )
        profileDao.touch(profile.id, System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update combined profile: ${e.message}", e)
    }
    }

    override suspend fun removeProvider(profileId: Long, providerId: Long): Result<Unit> {
        return try {
        val profile = profileDao.getById(profileId) ?: return Result.error("Combined profile not found.")
        memberDao.delete(profileId, providerId)
        profileDao.touch(profile.id, System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update combined profile: ${e.message}", e)
    }
    }

    override suspend fun setMemberEnabled(profileId: Long, providerId: Long, enabled: Boolean): Result<Unit> {
        return try {
        val profile = profileDao.getById(profileId) ?: return Result.error("Combined profile not found.")
        val member = memberDao.getMember(profileId, providerId) ?: return Result.error("Member not found.")
        memberDao.update(member.copy(enabled = enabled))
        profileDao.touch(profile.id, System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to update combined profile: ${e.message}", e)
    }
    }

    override suspend fun reorderMembers(profileId: Long, orderedProviderIds: List<Long>): Result<Unit> {
        return try {
        val profile = profileDao.getById(profileId) ?: return Result.error("Combined profile not found.")
        val currentMembers = memberDao.getForProfileSync(profileId)
        if (orderedProviderIds.toSet() != currentMembers.map { it.providerId }.toSet()) {
            return Result.error("Combined profile reorder list is invalid.")
        }
        val reordered = orderedProviderIds.mapIndexed { index, providerId ->
            val existing = currentMembers.first { it.providerId == providerId }
            CombinedM3uProfileMemberEntity(
                id = existing.id,
                profileId = profileId,
                providerId = providerId,
                priority = index,
                enabled = existing.enabled
            )
        }
        memberDao.replacePriorities(profileId, reordered)
        profileDao.touch(profile.id, System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reorder combined profile: ${e.message}", e)
    }
    }

    override fun getAvailableM3uProviders(): Flow<List<Provider>> =
        providerRepository.getProviders().map { providers ->
            providers.filter { it.type == ProviderType.M3U }
        }

    override fun getActiveLiveSource(): Flow<ActiveLiveSource?> =
        combine(
            preferencesRepository.activeLiveSource,
            providerRepository.getProviders()
        ) { activeSource, providers ->
            when (activeSource) {
                is ActiveLiveSource.ProviderSource -> activeSource.takeIf { source ->
                    providers.any { provider -> provider.id == source.providerId }
                }
                else -> activeSource
            }
        }

    override suspend fun setActiveLiveSource(source: ActiveLiveSource?): Result<Unit> = try {
        preferencesRepository.setActiveLiveSource(source)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to switch live source: ${e.message}", e)
    }

    override fun getActiveLiveSourceOptions(): Flow<List<ActiveLiveSourceOption>> =
        combine(getAvailableM3uProviders(), getProfiles()) { providers, profiles ->
            buildList {
                providers.forEach { provider ->
                    add(
                        ActiveLiveSourceOption(
                            source = ActiveLiveSource.ProviderSource(provider.id),
                            title = provider.name,
                            subtitle = "M3U Provider",
                            isEnabled = true
                        )
                    )
                }
                profiles.forEach { profile ->
                    add(
                        ActiveLiveSourceOption(
                            source = ActiveLiveSource.CombinedM3uSource(profile.id),
                            title = profile.name,
                            subtitle = "Combined M3U",
                            isEnabled = profile.enabled && profile.members.any { it.enabled }
                        )
                    )
                }
            }
        }

    override suspend fun getActiveCombinedProfile(): CombinedM3uProfile? {
        val source = preferencesRepository.activeLiveSource.first()
        return if (source is ActiveLiveSource.CombinedM3uSource) getProfile(source.profileId) else null
    }

    override fun getCombinedCategories(profileId: Long): Flow<List<CombinedCategory>> =
        buildCombinedProfileFlow(profileId).flatMapLatest { profile ->
            val memberProviders = profile?.members.orEmpty().filter { it.enabled }
            if (memberProviders.isEmpty()) {
                flowOf(emptyList())
            } else {
                val categoryFlows = memberProviders.map { member ->
                    combine(
                        channelRepository.getCategories(member.providerId),
                        preferencesRepository.getHiddenCategoryIds(member.providerId, ContentType.LIVE)
                    ) { categories, hiddenCategoryIds ->
                        Triple(member, categories, hiddenCategoryIds)
                    }
                }
                combine(categoryFlows) { arrays ->
                    arrays.toList()
                        .map { it as Triple<*, *, *> }
                        .flatMap { triple ->
                            val member = triple.first as com.streamvault.domain.model.CombinedM3uProfileMember
                            val categories = triple.second as List<Category>
                            val hiddenCategoryIds = triple.third as Set<Long>
                            categories
                                .filter {
                                    !it.isVirtual &&
                                        it.id != com.streamvault.domain.repository.ChannelRepository.ALL_CHANNELS_ID &&
                                        it.id !in hiddenCategoryIds
                                }
                                .map { category ->
                                    Triple(member, category, CombinedCategoryIdentity.keyForName(category.name))
                                }
                        }
                        .groupBy { it.third }
                        .values
                        .map { group ->
                            val first = group.first()
                            val bindings = group.map { (member, category, _) ->
                                CombinedCategoryBinding(
                                    providerId = member.providerId,
                                    providerName = member.providerName,
                                    categoryId = category.id
                                )
                            }
                            CombinedCategory(
                                category = first.second.copy(
                                    id = CombinedCategoryIdentity.idForKey(first.third),
                                    roomId = CombinedCategoryIdentity.idForKey(first.third),
                                    name = first.second.name,
                                    count = group.sumOf { it.second.count },
                                    isVirtual = false,
                                    isAdult = group.any { it.second.isAdult },
                                    isUserProtected = group.any { it.second.isUserProtected }
                                ),
                                bindings = bindings
                            )
                        }
                        .sortedWith(
                            compareBy<CombinedCategory>(
                                { category -> category.bindings.minOfOrNull { binding ->
                                    memberProviders.firstOrNull { it.providerId == binding.providerId }?.priority ?: Int.MAX_VALUE
                                } ?: Int.MAX_VALUE },
                                { it.category.name.lowercase(Locale.ROOT) }
                            )
                        )
                }
            }
        }

    override fun getCombinedChannels(profileId: Long, category: CombinedCategory): Flow<List<Channel>> =
        combineMemberChannelFlows(profileId, category, query = null, applyOrder = true)

    override fun searchCombinedChannels(profileId: Long, category: CombinedCategory, query: String): Flow<List<Channel>> =
        combineMemberChannelFlows(profileId, category, query = query.trim(), applyOrder = true)

    override fun getCombinedAllChannels(profileId: Long): Flow<List<Channel>> =
        getCombinedCategories(profileId).flatMapLatest { combinedCategories ->
            if (combinedCategories.isEmpty()) {
                flowOf(emptyList())
            } else {
                val memberships = combinedCategories.map { category ->
                    combineMemberChannelFlows(profileId, category, query = null, applyOrder = false)
                }
                val allMembership = combine(memberships) { arrays -> arrays.toList().flatten() }
                buildCombinedProfileFlow(profileId).flatMapLatest { profile ->
                    if (profile == null) flowOf(emptyList()) else orderCombinedMembership(
                        profile = profile,
                        category = Category(ChannelRepository.ALL_CHANNELS_ID, name = "", type = ContentType.LIVE),
                        membership = allMembership
                    )
                }
            }
        }

    private fun combineMemberChannelFlows(
        profileId: Long,
        category: CombinedCategory,
        query: String?,
        applyOrder: Boolean
    ): Flow<List<Channel>> =
        buildCombinedProfileFlow(profileId).flatMapLatest { profile ->
            val enabledProviders = profile?.members.orEmpty().filter { it.enabled }.associateBy { it.providerId }
            val bindings = category.bindings.filter { enabledProviders.containsKey(it.providerId) }
            if (bindings.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = bindings.map { binding ->
                    val baseFlow = when {
                        query.isNullOrBlank() -> channelRepository.getChannelsByCategory(binding.providerId, binding.categoryId)
                        else -> channelRepository.searchChannelsByCategory(binding.providerId, binding.categoryId, query)
                    }
                    baseFlow.map { channels ->
                        val priority = enabledProviders[binding.providerId]?.priority ?: Int.MAX_VALUE
                        priority to channels
                    }
                }
                val membership = combine(flows) { arrays ->
                    arrays.toList()
                        .map { it as Pair<Int, List<Channel>> }
                        .sortedBy { it.first }
                        .flatMap { it.second }
                }
                if (!applyOrder || profile == null) {
                    membership
                } else {
                    orderCombinedMembership(profile, category.category, membership)
                }
            }
        }

    private fun orderCombinedMembership(
        profile: CombinedM3uProfile,
        category: Category,
        membership: Flow<List<Channel>>
    ): Flow<List<Channel>> {
        val scope = combinedLiveChannelOrderScope(profile, category)
        val providerIdentityFlow = flow {
            val identities = linkedMapOf<Long, String>()
            profile.members.forEach { member ->
                providerRepository.getProvider(member.providerId)?.let { provider ->
                    identities[member.providerId] = liveProviderOrderIdentity(provider)
                }
            }
            emit(identities)
        }
        return providerIdentityFlow.flatMapLatest { providerIdentity ->
            val statsFlows = profile.members.filter { it.enabled }.map { member ->
                val identity = checkNotNull(providerIdentity[member.providerId])
                liveUsage.observe(member.providerId).map { stats ->
                    stats.mapKeys { (key, _) -> "$identity|$key" }
                }
            }
            val stats = if (statsFlows.isEmpty()) flowOf(emptyMap<String, LiveWatchUsage>()) else combine(statsFlows) { arrays ->
                arrays.toList().flatMap { it.entries }.associate { it.toPair() }
            }
            data class OrderRequest(
                val order: com.streamvault.domain.model.LiveChannelOrder,
                val defaultMode: com.streamvault.domain.model.LiveChannelOrderMode,
                val privateMode: Boolean,
                val resetVersion: Long
            )
            data class OrderSnapshot(
                val order: com.streamvault.domain.model.LiveChannelOrder,
                val defaultMode: com.streamvault.domain.model.LiveChannelOrderMode,
                val usage: Map<String, LiveWatchUsage>
            )
            val orderSnapshots = combine(
                liveChannelOrders.observe(scope),
                settings.defaultLiveChannelOrder,
                settings.isIncognitoMode,
                liveUsage.resetVersion
            ) { order, defaultMode, privateMode, resetVersion ->
                OrderRequest(order, defaultMode, privateMode, resetVersion)
            }.distinctUntilChanged().mapLatest { request ->
                val dynamic = request.order.effectiveMode(request.defaultMode) in setOf(
                    com.streamvault.domain.model.LiveChannelOrderMode.RECENTLY_WATCHED,
                    com.streamvault.domain.model.LiveChannelOrderMode.MOST_WATCHED
                )
                val usageSnapshot = if (!request.privateMode && dynamic) stats.first() else emptyMap()
                OrderSnapshot(request.order, request.defaultMode, usageSnapshot)
            }
            combine(
                membership,
                orderSnapshots
            ) { rows, snapshot ->
                orderLiveChannels(
                    channels = rows,
                    order = snapshot.order,
                    defaultMode = snapshot.defaultMode,
                    usage = snapshot.usage,
                    keySelector = { channel -> channel.liveOrderKeys(checkNotNull(providerIdentity[channel.providerId])) }
                )
            }
        }
    }

    private fun buildCombinedProfileFlow(profileId: Long): Flow<CombinedM3uProfile?> = flow {
        legacyIdentityMigrator?.migrate()
        emitAll(profileDao.getAll().map { profiles -> profiles.firstOrNull { it.id == profileId } }
            .combine(memberDao.getForProfile(profileId)) { profile, members ->
                profile?.toDomain(members.map { it.toDomain() })
            })
    }

}
