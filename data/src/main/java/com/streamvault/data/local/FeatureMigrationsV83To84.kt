package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Repairs schema variants produced by the pre-merge Stremio branch at versions 79-80. */
internal object FeatureMigrationsV83To84 {
    val MIGRATION_83_84 = object : Migration(83, 84) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
                // Version 78→79 also expired these caches when it repaired the old episode key.
                db.execSQL("UPDATE `series` SET `detail_hydrated_at` = 1 WHERE `detail_hydrated_at` > 0")
            }

            validateForeignKeys(db, "xtream_live_onboarding_state", "episodes")
        }
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
