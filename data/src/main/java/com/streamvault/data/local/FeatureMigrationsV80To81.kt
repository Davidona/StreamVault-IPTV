package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Repairs episode uniqueness for databases that reached v80 before the fix was added. */
internal object FeatureMigrationsV80To81 {
    val MIGRATION_80_81 = object : Migration(80, 81) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_episodes_provider_id_episode_id")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_episodes_provider_id_series_id_episode_id " +
                    "ON episodes(provider_id, series_id, episode_id)"
            )
            db.execSQL("UPDATE series SET detail_hydrated_at = 1 WHERE detail_hydrated_at > 0")
        }
    }
}
