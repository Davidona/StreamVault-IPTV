package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists the resolved network profile needed by a recording service started later. */
internal object FeatureMigrationsV82To83 {
    val MIGRATION_82_83 = object : Migration(82, 83) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recording_runs` ADD COLUMN `network_profile_json` TEXT NOT NULL DEFAULT '{}'")
        }
    }
}
