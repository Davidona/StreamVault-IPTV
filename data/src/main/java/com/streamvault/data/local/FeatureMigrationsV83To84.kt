package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists the resolved network profile needed by a recording service started later. */
internal object FeatureMigrationsV83To84 {
    val MIGRATION_83_84 = object : Migration(83, 84) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recording_runs` ADD COLUMN `network_profile_json` TEXT NOT NULL DEFAULT '{}'")
        }
    }
}
