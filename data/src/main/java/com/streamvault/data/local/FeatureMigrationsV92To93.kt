package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object FeatureMigrationsV92To93 {
    val MIGRATION_92_93 = object : Migration(92, 93) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE virtual_groups ADD COLUMN identity_key TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "UPDATE virtual_groups SET identity_key = 'group-' || lower(hex(randomblob(16))) WHERE identity_key = ''"
            )
        }
    }
}
