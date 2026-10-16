package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds Stalker retry state and repairs databases that reused schema version 80. */
internal object FeatureMigrationsV80To81 {
    val MIGRATION_80_81 = object : Migration(80, 81) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val onboardingColumns = db.query("PRAGMA table_info(xtream_live_onboarding_state)").use { cursor ->
                buildSet {
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }
            if ("sealed_session_id" !in onboardingColumns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_session_id INTEGER")
            }
            if ("sealed_row_count" !in onboardingColumns) {
                db.execSQL(
                    "ALTER TABLE xtream_live_onboarding_state " +
                        "ADD COLUMN sealed_row_count INTEGER NOT NULL DEFAULT 0"
                )
            }
            if ("sealed_configuration_generation" !in onboardingColumns) {
                db.execSQL(
                    "ALTER TABLE xtream_live_onboarding_state " +
                        "ADD COLUMN sealed_configuration_generation INTEGER"
                )
            }
            if ("sealed_mapping_profile" !in onboardingColumns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_mapping_profile TEXT")
            }

            db.execSQL("DROP INDEX IF EXISTS index_episodes_provider_id_episode_id")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_episodes_provider_id_series_id_episode_id " +
                    "ON episodes(provider_id, series_id, episode_id)"
            )
            // Refresh details after correcting legacy episode identity so partial seasons are repaired.
            db.execSQL("UPDATE series SET detail_hydrated_at = 1 WHERE detail_hydrated_at > 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stalker_live_category_retries` (
                    `provider_id` INTEGER NOT NULL,
                    `category_id` INTEGER NOT NULL,
                    `category_name` TEXT NOT NULL,
                    `configuration_generation` INTEGER NOT NULL,
                    `attempt_count` INTEGER NOT NULL,
                    `last_attempt_at` INTEGER NOT NULL,
                    `last_error` TEXT,
                    PRIMARY KEY(`provider_id`, `category_id`),
                    FOREIGN KEY(`provider_id`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_stalker_live_category_retries_provider_id` " +
                    "ON `stalker_live_category_retries` (`provider_id`)"
            )
        }
    }
}
