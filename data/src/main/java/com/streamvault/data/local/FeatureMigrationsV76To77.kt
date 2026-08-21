package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FeatureMigrationsV76To77 {
    val MIGRATION_76_77 = object : Migration(76, 77) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS backup_restore_jobs (
                    id TEXT NOT NULL,
                    restore_key TEXT NOT NULL,
                    backup_version INTEGER NOT NULL,
                    conflict_strategy TEXT NOT NULL,
                    status TEXT NOT NULL,
                    total_count INTEGER NOT NULL DEFAULT 0,
                    pending_count INTEGER NOT NULL DEFAULT 0,
                    applied_count INTEGER NOT NULL DEFAULT 0,
                    unresolved_count INTEGER NOT NULL DEFAULT 0,
                    failed_count INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_restore_jobs_restore_key ON backup_restore_jobs(restore_key)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_restore_jobs_status ON backup_restore_jobs(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_restore_jobs_updated_at ON backup_restore_jobs(updated_at)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS backup_restore_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    job_id TEXT NOT NULL,
                    provider_identity_key TEXT NOT NULL,
                    local_provider_id INTEGER,
                    section TEXT NOT NULL,
                    content_type TEXT,
                    stable_reference_key TEXT NOT NULL,
                    reference_json TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(job_id) REFERENCES backup_restore_jobs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_restore_items_job_id ON backup_restore_items(job_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_restore_items_provider_identity_key_status ON backup_restore_items(provider_identity_key, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_restore_items_job_id_status ON backup_restore_items(job_id, status)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_backup_restore_items_job_id_section_stable_reference_key " +
                    "ON backup_restore_items(job_id, section, stable_reference_key)"
            )
        }
    }
}
