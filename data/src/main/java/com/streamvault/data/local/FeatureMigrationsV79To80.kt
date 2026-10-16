package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds provider-independent progress for Stremio media without retaining playable URLs. */
object FeatureMigrationsV79To80 {
    val MIGRATION_79_80 = object : Migration(79, 80) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
    }
}
