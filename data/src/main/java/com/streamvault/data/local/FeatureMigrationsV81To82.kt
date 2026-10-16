package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds encrypted SMB credentials to the recording destination and each created recording. */
internal object FeatureMigrationsV81To82 {
    val MIGRATION_81_82 = object : Migration(81, 82) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recording_storage` ADD COLUMN `smb_url` TEXT")
            db.execSQL("ALTER TABLE `recording_storage` ADD COLUMN `smb_credentials_encrypted` TEXT")
            db.execSQL("ALTER TABLE `recording_runs` ADD COLUMN `output_credentials_encrypted` TEXT")
        }
    }
}
