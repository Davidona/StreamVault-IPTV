package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds durable receipt fields for staged Xtream live onboarding sessions. */
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
                db.execSQL(
                    "ALTER TABLE xtream_live_onboarding_state " +
                        "ADD COLUMN sealed_row_count INTEGER NOT NULL DEFAULT 0"
                )
            }
            if ("sealed_configuration_generation" !in columns) {
                db.execSQL(
                    "ALTER TABLE xtream_live_onboarding_state " +
                        "ADD COLUMN sealed_configuration_generation INTEGER"
                )
            }
            if ("sealed_mapping_profile" !in columns) {
                db.execSQL(
                    "ALTER TABLE xtream_live_onboarding_state ADD COLUMN sealed_mapping_profile TEXT"
                )
            }
        }
    }
}
