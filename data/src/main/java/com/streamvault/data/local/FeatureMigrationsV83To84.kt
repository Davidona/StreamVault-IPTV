package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Repairs pre-merge Stremio schemas and persists the recording network profile. */
internal object FeatureMigrationsV83To84 {
    val MIGRATION_83_84 = object : Migration(83, 84) {
        override fun migrate(db: SupportSQLiteDatabase) {
            if (!tableHasColumn(db, "recording_runs", "network_profile_json")) {
                db.execSQL("ALTER TABLE `recording_runs` ADD COLUMN `network_profile_json` TEXT NOT NULL DEFAULT '{}' ")
            }

            ensurePlaybackQualityPreferences(db)
            repairPreMergeStremioSchema(db)
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

    private fun repairPreMergeStremioSchema(db: SupportSQLiteDatabase) {
        val receiptColumns = listOf(
            "sealed_session_id" to "INTEGER",
            "sealed_row_count" to "INTEGER NOT NULL DEFAULT 0",
            "sealed_configuration_generation" to "INTEGER",
            "sealed_mapping_profile" to "TEXT"
        )
        val missingReceiptColumns = receiptColumns.filter { (name, _) ->
            !tableHasColumn(db, "xtream_live_onboarding_state", name)
        }
        val hasLegacyEpisodeIndex = hasIndex(db, "episodes", "index_episodes_provider_id_episode_id")
        val isPreMergeStremioSchema = missingReceiptColumns.isNotEmpty() || hasLegacyEpisodeIndex

        missingReceiptColumns.forEach { (name, declaration) ->
            db.execSQL(
                "ALTER TABLE `xtream_live_onboarding_state` " +
                    "ADD COLUMN `$name` $declaration"
            )
        }

        db.execSQL("DROP INDEX IF EXISTS `index_episodes_provider_id_episode_id`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_episodes_provider_id_series_id_episode_id` " +
                "ON `episodes` (`provider_id`, `series_id`, `episode_id`)"
        )

        if (isPreMergeStremioSchema) {
            db.execSQL("UPDATE `series` SET `detail_hydrated_at` = 1 WHERE `detail_hydrated_at` > 0")
        }

        validateForeignKeys(db, "xtream_live_onboarding_state", "episodes")
    }

    private fun hasIndex(db: SupportSQLiteDatabase, tableName: String, indexName: String): Boolean =
        db.query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == indexName) found = true
            }
            found
        }
}
