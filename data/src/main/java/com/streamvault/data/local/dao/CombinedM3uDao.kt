package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.streamvault.data.local.entity.CombinedM3uProfileEntity
import com.streamvault.data.local.entity.CombinedM3uProfileMemberEntity
import com.streamvault.data.local.entity.CombinedM3uProfileMemberWithProvider
import kotlinx.coroutines.flow.Flow

@Dao
interface CombinedM3uProfileDao {
    @Query("SELECT * FROM combined_m3u_profiles ORDER BY updated_at DESC, created_at DESC")
    fun getAll(): Flow<List<CombinedM3uProfileEntity>>

    @Query("SELECT * FROM combined_m3u_profiles WHERE id = :profileId LIMIT 1")
    suspend fun getById(profileId: Long): CombinedM3uProfileEntity?

    @Query("UPDATE combined_m3u_profiles SET identity_key = :newIdentity WHERE id = :profileId AND identity_key = :oldIdentity")
    suspend fun replaceIdentityIfUnchanged(profileId: Long, oldIdentity: String, newIdentity: String): Int

    @Query("UPDATE combined_m3u_profiles SET name = :name, updated_at = :updatedAt WHERE id = :profileId")
    suspend fun rename(profileId: Long, name: String, updatedAt: Long)

    @Query("UPDATE combined_m3u_profiles SET updated_at = :updatedAt WHERE id = :profileId")
    suspend fun touch(profileId: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: CombinedM3uProfileEntity): Long

    @Update
    suspend fun update(profile: CombinedM3uProfileEntity)

    @Query("DELETE FROM combined_m3u_profiles WHERE id = :profileId")
    suspend fun delete(profileId: Long)
}

@Dao
interface CombinedM3uProfileMemberDao {
    @Query(
        """
        SELECT m.id, m.profile_id, m.provider_id, m.priority, m.enabled, p.name AS provider_name
        FROM combined_m3u_profile_members m
        INNER JOIN providers p ON p.id = m.provider_id
        WHERE m.profile_id = :profileId
        ORDER BY m.priority ASC, m.id ASC
        """
    )
    fun getForProfile(profileId: Long): Flow<List<CombinedM3uProfileMemberWithProvider>>

    @Query(
        """
        SELECT m.id, m.profile_id, m.provider_id, m.priority, m.enabled, p.name AS provider_name
        FROM combined_m3u_profile_members m
        INNER JOIN providers p ON p.id = m.provider_id
        WHERE m.profile_id = :profileId
        ORDER BY m.priority ASC, m.id ASC
        """
    )
    suspend fun getForProfileSync(profileId: Long): List<CombinedM3uProfileMemberWithProvider>

    @Query("SELECT * FROM combined_m3u_profile_members WHERE profile_id = :profileId AND provider_id = :providerId LIMIT 1")
    suspend fun getMember(profileId: Long, providerId: Long): CombinedM3uProfileMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(member: CombinedM3uProfileMemberEntity): Long

    @Update
    suspend fun update(member: CombinedM3uProfileMemberEntity)

    @Query("DELETE FROM combined_m3u_profile_members WHERE profile_id = :profileId AND provider_id = :providerId")
    suspend fun delete(profileId: Long, providerId: Long)

    @Query("SELECT COUNT(*) FROM combined_m3u_profile_members WHERE profile_id = :profileId")
    suspend fun countForProfile(profileId: Long): Int

    @Query("DELETE FROM combined_m3u_profile_members WHERE profile_id = :profileId")
    suspend fun deleteForProfile(profileId: Long)

    @Transaction
    suspend fun replacePriorities(profileId: Long, members: List<CombinedM3uProfileMemberEntity>) {
        deleteForProfile(profileId)
        members.forEach { insert(it) }
    }
}
