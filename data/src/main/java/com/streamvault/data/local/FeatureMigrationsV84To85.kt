package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds Stremio catalog/history storage and download request metadata. */
internal object FeatureMigrationsV84To85 {
    val MIGRATION_84_85 = object : Migration(84, 85) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createStremioTables(db)
            addColumnIfMissing(db, "downloads", "source_type", "TEXT NOT NULL DEFAULT 'PROVIDER'")
            addColumnIfMissing(db, "downloads", "request_headers_json", "TEXT NOT NULL DEFAULT '{}'")
        }
    }

    private fun createStremioTables(db: SupportSQLiteDatabase) {
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
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stremio_playback_history` (
                `content_key` TEXT NOT NULL,
                `media_type` TEXT NOT NULL,
                `exact_video_id` TEXT NOT NULL,
                `parent_series_id` TEXT,
                `title` TEXT NOT NULL,
                `poster_url` TEXT,
                `resume_position_ms` INTEGER NOT NULL,
                `total_duration_ms` INTEGER NOT NULL,
                `is_watched` INTEGER NOT NULL,
                `last_watched_at` INTEGER NOT NULL,
                `catalog_origin_key` TEXT NOT NULL,
                `stream_origin_key_hint` TEXT,
                PRIMARY KEY(`content_key`)
            )
            """.trimIndent()
        )
    }

    private fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        declaration: String
    ) {
        if (!tableHasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $declaration")
        }
    }
}
