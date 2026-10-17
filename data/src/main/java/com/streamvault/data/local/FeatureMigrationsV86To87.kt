package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Stores the last successful Stremio addon manifest refresh independently of addon settings edits. */
internal object FeatureMigrationsV86To87 {
    val MIGRATION_86_87 = object : Migration(86, 87) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!tableHasColumn(db, "stremio_addons", "manifest_refreshed_at")) {
                db.execSQL(
                    "ALTER TABLE `stremio_addons` " +
                        "ADD COLUMN `manifest_refreshed_at` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
