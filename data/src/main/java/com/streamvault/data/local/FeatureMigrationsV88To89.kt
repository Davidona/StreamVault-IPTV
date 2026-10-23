package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Rebuilds catalog FTS indexes with a tokenizer option supported by the minimum Android SQLite. */
internal object FeatureMigrationsV88To89 {
    val MIGRATION_88_89 = object : Migration(88, 89) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildFtsIndex(db, "channels_fts", "channels")
            rebuildFtsIndex(db, "movies_fts", "movies")
            rebuildFtsIndex(db, "series_fts", "series")
        }

        private fun rebuildFtsIndex(db: SupportSQLiteDatabase, index: String, contentTable: String) {
            db.execSQL("DROP TABLE IF EXISTS `$index`")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `$index` " +
                    "USING FTS4(`name` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=1`, " +
                    "content=`$contentTable`)"
            )
            db.execSQL("INSERT INTO `$index`(`$index`) VALUES('rebuild')")
        }
    }
}
