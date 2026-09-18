package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persists per-entry adaptive playback metadata parsed from M3U directives. */
object FeatureMigrationsV77To78 {
    val MIGRATION_77_78 = object : Migration(77, 78) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE channels ADD COLUMN playback_metadata_json TEXT")
            db.execSQL("ALTER TABLE movies ADD COLUMN playback_metadata_json TEXT")
            db.execSQL("ALTER TABLE channel_import_stage ADD COLUMN playback_metadata_json TEXT")
            db.execSQL("ALTER TABLE movie_import_stage ADD COLUMN playback_metadata_json TEXT")
        }
    }
}
