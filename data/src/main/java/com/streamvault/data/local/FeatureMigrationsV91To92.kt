package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object FeatureMigrationsV91To92 {
    val MIGRATION_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE combined_m3u_profiles ADD COLUMN identity_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE combined_m3u_profiles SET identity_key = 'combined-' || lower(hex(randomblob(16))) WHERE identity_key = ''")
        }
    }
}
