package com.streamvault.domain.repository

import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.ActiveLiveSourceOption
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface CombinedM3uRepository {
    fun getProfiles(): Flow<List<CombinedM3uProfile>>
    suspend fun getProfile(profileId: Long): CombinedM3uProfile?
    suspend fun createProfile(name: String, providerIds: List<Long>): Result<CombinedM3uProfile>
    suspend fun updateProfileName(profileId: Long, name: String): Result<Unit>
    suspend fun deleteProfile(profileId: Long): Result<Unit>
    suspend fun addProvider(profileId: Long, providerId: Long): Result<Unit>
    suspend fun removeProvider(profileId: Long, providerId: Long): Result<Unit>
    suspend fun setMemberEnabled(profileId: Long, providerId: Long, enabled: Boolean): Result<Unit>
    suspend fun reorderMembers(profileId: Long, orderedProviderIds: List<Long>): Result<Unit>
    fun getAvailableM3uProviders(): Flow<List<Provider>>
    fun getActiveLiveSource(): Flow<ActiveLiveSource?>
    suspend fun setActiveLiveSource(source: ActiveLiveSource?): Result<Unit>
    fun getActiveLiveSourceOptions(): Flow<List<ActiveLiveSourceOption>>
    suspend fun getActiveCombinedProfile(): CombinedM3uProfile?
    fun getCombinedCategories(profileId: Long): Flow<List<CombinedCategory>>
    fun getCombinedChannels(profileId: Long, category: CombinedCategory): Flow<List<Channel>>
    fun getCombinedAllChannels(profileId: Long): Flow<List<Channel>>
    fun searchCombinedChannels(profileId: Long, category: CombinedCategory, query: String): Flow<List<Channel>>
}
