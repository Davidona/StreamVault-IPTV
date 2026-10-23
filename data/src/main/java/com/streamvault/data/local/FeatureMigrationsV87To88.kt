package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Rebuilds catalog FTS indexes with Unicode-aware Latin diacritic folding. */
internal object FeatureMigrationsV87To88 {
    val MIGRATION_87_88 = object : Migration(87, 88) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildFtsIndex(db, "channels_fts", "channels")
            rebuildFtsIndex(db, "movies_fts", "movies")
            rebuildFtsIndex(db, "series_fts", "series")
        }

        private fun rebuildFtsIndex(db: SupportSQLiteDatabase, index: String, contentTable: String) {
            db.execSQL("DROP TABLE IF EXISTS `$index`")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `$index` " +
                    "USING FTS4(`name` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=2`, " +
                    "content=`$contentTable`)"
            )
            db.execSQL("INSERT INTO `$index`(`$index`) VALUES('rebuild')")
        }
    }
}
