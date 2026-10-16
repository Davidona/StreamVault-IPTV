package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Normalizes databases created on either side of the Stremio/develop branch split. */
internal object FeatureMigrationsV85To86 {
    val MIGRATION_85_86 = object : Migration(85, 86) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!tableHasColumn(db, "recording_runs", "network_profile_json")) {
                db.execSQL("ALTER TABLE `recording_runs` ADD COLUMN `network_profile_json` TEXT NOT NULL DEFAULT '{}' ")
            }
            ensurePlaybackQualityPreferences(db)
        }
    }

    private fun ensurePlaybackQualityPreferences(db: SupportSQLiteDatabase) {
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
