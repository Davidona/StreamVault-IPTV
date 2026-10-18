package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Stores sparse provider and stable remote-content playback quality overrides. */
internal object FeatureMigrationsV82To83 {
    val MIGRATION_82_83 = object : Migration(82, 83) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `playback_quality_preferences` (
                    `provider_id` INTEGER NOT NULL,
                    `scope_type` TEXT NOT NULL,
                    `scope_key` TEXT NOT NULL,
                    `identity_version` INTEGER NOT NULL DEFAULT 1,
                    `height_override` TEXT NOT NULL,
                    `max_video_height` INTEGER,
                    `bitrate_override` TEXT NOT NULL,
                    `max_video_bitrate_bps` INTEGER,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`provider_id`, `scope_type`, `scope_key`),
                    FOREIGN KEY(`provider_id`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_playback_quality_preferences_provider_id` " +
                    "ON `playback_quality_preferences` (`provider_id`)"
            )
        }
    }
}
