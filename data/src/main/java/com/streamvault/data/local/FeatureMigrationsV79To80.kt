package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing staged sessions have no receipt and must be fetched again. */
internal object FeatureMigrationsV79To80 {
    val MIGRATION_79_80 = object : Migration(79, 80) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(xtream_live_onboarding_state)").use { cursor ->
                buildSet {
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }
            if ("sealed_session_id" !in columns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_session_id INTEGER")
            }
            if ("sealed_row_count" !in columns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_row_count INTEGER NOT NULL DEFAULT 0")
            }
            if ("sealed_configuration_generation" !in columns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_configuration_generation INTEGER")
            }
            if ("sealed_mapping_profile" !in columns) {
                db.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_mapping_profile TEXT")
            }

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
