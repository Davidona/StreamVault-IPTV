package com.streamvault.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stremio_addons",
    indices = [Index(value = ["install_url"], unique = true)]
)
data class StremioAddonEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "install_url") val installUrl: String,
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "catalog_settings_json") val catalogSettingsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "manifest_refreshed_at", defaultValue = "0") val manifestRefreshedAt: Long = 0L
)
