package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable Stalker live-category retry scopes survive process death and provider refreshes. */
internal object FeatureMigrationsV79To80 {
    val MIGRATION_79_80 = object : Migration(79, 80) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
