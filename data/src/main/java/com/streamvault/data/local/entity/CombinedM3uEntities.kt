package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "combined_m3u_profiles"
)
data class CombinedM3uProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "identity_key", defaultValue = "''")
    val identityKey: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "combined_m3u_profile_members",
    foreignKeys = [
        ForeignKey(
            entity = CombinedM3uProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["provider_id"]),
        Index(value = ["profile_id", "provider_id"], unique = true)
    ]
)
data class CombinedM3uProfileMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    val priority: Int,
    val enabled: Boolean = true
)

data class CombinedM3uProfileMemberWithProvider(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "provider_name") val providerName: String
)
