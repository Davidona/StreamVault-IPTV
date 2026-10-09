package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing staged sessions have no receipt and must be fetched again. */
internal object FeatureMigrationsV78To79 {
    val MIGRATION_78_79 = object : Migration(78, 79) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_session_id INTEGER")
            db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_row_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_configuration_generation INTEGER")
            db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_mapping_profile TEXT")
        }
    }
}
