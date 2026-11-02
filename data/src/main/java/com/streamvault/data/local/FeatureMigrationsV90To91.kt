package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object FeatureMigrationsV90To91 {
    val MIGRATION_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE stremio_playback_history ADD COLUMN dismissed_at_ms INTEGER NOT NULL DEFAULT 0")
        }
    }
}
