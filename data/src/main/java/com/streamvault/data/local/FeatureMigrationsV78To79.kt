package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the installed Stremio addon registry. Addon content is fetched on demand, not synced. */
object FeatureMigrationsV78To79 {
    val MIGRATION_78_79 = object : Migration(78, 79) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stremio_addons` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `install_url` TEXT NOT NULL,
                    `manifest_id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `version` TEXT NOT NULL,
                    `manifest_json` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `catalog_settings_json` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_stremio_addons_install_url` " +
                    "ON `stremio_addons` (`install_url`)"
            )
        }
    }
}
