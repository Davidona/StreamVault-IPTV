package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Scopes episode uniqueness to the owning series and re-hydrates series damaged by the old key. */
object FeatureMigrationsV78To79 {
    val MIGRATION_78_79 = object : Migration(78, 79) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_episodes_provider_id_episode_id")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_episodes_provider_id_series_id_episode_id " +
                    "ON episodes(provider_id, series_id, episode_id)"
            )

            // Expire every detail TTL: collisions usually truncate a season rather than empty it, and
            // nothing persisted tells the two apart. A timestamp of 1 (not 0) keeps sync preserving details.
            db.execSQL("UPDATE series SET detail_hydrated_at = 1 WHERE detail_hydrated_at > 0")
        }
    }
}
