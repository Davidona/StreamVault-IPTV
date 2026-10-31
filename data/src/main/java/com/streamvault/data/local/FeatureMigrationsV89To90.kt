package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists M3U catch-up mode and the channel-specific archive time correction. */
internal object FeatureMigrationsV89To90 {
    val MIGRATION_89_90 = object : Migration(89, 90) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE channels ADD COLUMN catch_up_type TEXT")
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN catch_up_correction_seconds INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE channel_import_stage ADD COLUMN catch_up_type TEXT")
            db.execSQL(
                "ALTER TABLE channel_import_stage ADD COLUMN catch_up_correction_seconds INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
