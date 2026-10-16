package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Stores the source kind and request headers needed to resume add-on downloads. */
internal object FeatureMigrationsV84To85 {
    val MIGRATION_84_85 = object : Migration(84, 85) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `downloads` ADD COLUMN `source_type` TEXT NOT NULL DEFAULT 'PROVIDER'"
            )
            db.execSQL(
                "ALTER TABLE `downloads` ADD COLUMN `request_headers_json` TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }
}
