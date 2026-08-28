package com.streamvault.data.local

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamVaultDatabaseMigrationTest {

    private val testDbName = "streamvault-migration-test"

    @get:Rule
    val migrationTestHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StreamVaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun everyExportedHistoricalSchemaMigratesToCurrent() {
        // Version 2 was never exported; validate every historical artifact that can ship.
        val exportedHistoricalVersions =
            (StreamVaultDatabaseMigrationRegistry.CURRENT_VERSION - 1 downTo 3).toList() + 1

        exportedHistoricalVersions.forEach { startVersion ->
            val databaseName = "streamvault-every-schema-$startVersion"
            migrationTestHelper.createDatabase(databaseName, startVersion).close()
            val pathToCurrent = StreamVaultDatabaseMigrationRegistry.all
                .filter { migration -> migration.startVersion >= startVersion }

            try {
                migrationTestHelper.runMigrationsAndValidate(
                    databaseName,
                    StreamVaultDatabaseMigrationRegistry.CURRENT_VERSION,
                    true,
                    *pathToCurrent.toTypedArray()
                ).close()
            } catch (error: Throwable) {
                throw AssertionError("Migration failed from exported schema v$startVersion", error)
            }
        }
    }

    @Test
    fun migrate9To10_createsBackfillsAndMaintainsFtsTables() {
        migrationTestHelper.createDatabase(testDbName, 9).apply {
            execSQL(
                """
                INSERT INTO channels (
                    stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count
                ) VALUES (1001, 'News One', 'http://test/live/news', 101, 0, 0, 1, 0, 0, '', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, last_watched_at, is_adult, is_user_protected
                ) VALUES (2001, 'Movie Alpha', 'http://test/movie/alpha', 7200, 7.5, 1, 0, 0, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    series_id, name, rating, last_modified, provider_id, is_adult, is_user_protected
                ) VALUES (3001, 'Series Prime', 8.1, 0, 1, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            testDbName,
            10,
            true,
            StreamVaultDatabase.MIGRATION_9_10
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'news*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies_fts WHERE movies_fts MATCH 'movie*'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM series_fts WHERE series_fts MATCH 'series*'"))

        migratedDb.execSQL(
            """
            INSERT INTO channels (
                stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                provider_id, is_adult, is_user_protected, logical_group_id, error_count
            ) VALUES (1002, 'Sports Arena', 'http://test/live/sports', 102, 0, 0, 1, 0, 0, '', 0)
            """.trimIndent()
        )
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'sports*'"))

        migratedDb.execSQL("UPDATE channels SET name = 'Sports Central' WHERE stream_id = 1002")
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.execSQL("DELETE FROM channels WHERE stream_id = 1002")
        assertFalse(exists(migratedDb, "SELECT 1 FROM channels_fts WHERE channels_fts MATCH 'central*'"))

        migratedDb.close()
    }

    @Test
    fun migrate1To42_fullChainValidatesLatestSchema() {
        migrationTestHelper.createDatabase("streamvault-full-chain-test", 1).close()

        migrationTestHelper.runMigrationsAndValidate(
            "streamvault-full-chain-test",
            42,
            true,
            StreamVaultDatabase.MIGRATION_1_2,
            StreamVaultDatabase.MIGRATION_2_3,
            StreamVaultDatabase.MIGRATION_3_4,
            StreamVaultDatabase.MIGRATION_4_5,
            StreamVaultDatabase.MIGRATION_5_6,
            StreamVaultDatabase.MIGRATION_6_7,
            StreamVaultDatabase.MIGRATION_7_8,
            StreamVaultDatabase.MIGRATION_8_9,
            StreamVaultDatabase.MIGRATION_9_10,
            StreamVaultDatabase.MIGRATION_10_11,
            StreamVaultDatabase.MIGRATION_11_12,
            StreamVaultDatabase.MIGRATION_12_13,
            StreamVaultDatabase.MIGRATION_13_14,
            StreamVaultDatabase.MIGRATION_14_15,
            StreamVaultDatabase.MIGRATION_15_16,
            StreamVaultDatabase.MIGRATION_16_17,
            StreamVaultDatabase.MIGRATION_17_18,
            StreamVaultDatabase.MIGRATION_18_19,
            StreamVaultDatabase.MIGRATION_19_20,
            StreamVaultDatabase.MIGRATION_20_21,
            StreamVaultDatabase.MIGRATION_21_22,
            StreamVaultDatabase.MIGRATION_22_23,
            StreamVaultDatabase.MIGRATION_23_24,
            StreamVaultDatabase.MIGRATION_24_25,
            StreamVaultDatabase.MIGRATION_25_26,
            StreamVaultDatabase.MIGRATION_26_27,
            StreamVaultDatabase.MIGRATION_27_28,
            StreamVaultDatabase.MIGRATION_28_29,
            StreamVaultDatabase.MIGRATION_29_30,
            StreamVaultDatabase.MIGRATION_30_31,
            StreamVaultDatabase.MIGRATION_31_32,
            StreamVaultDatabase.MIGRATION_32_33,
            StreamVaultDatabase.MIGRATION_33_34,
            StreamVaultDatabase.MIGRATION_34_35,
            StreamVaultDatabase.MIGRATION_35_36,
            StreamVaultDatabase.MIGRATION_36_37,
            StreamVaultDatabase.MIGRATION_37_38,
            StreamVaultDatabase.MIGRATION_38_39,
            StreamVaultDatabase.MIGRATION_39_40,
            StreamVaultDatabase.MIGRATION_40_41,
            StreamVaultDatabase.MIGRATION_41_42,
            StreamVaultDatabase.MIGRATION_42_43,
            StreamVaultDatabase.MIGRATION_43_44
        ).close()
    }

    @Test
    fun migrate44To51_publicMasterUpgradeValidatesLatestSchema() {
        migrationTestHelper.createDatabase("streamvault-public-master-44-51-test", 44).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    stalker_mac_address, stalker_device_profile, stalker_device_timezone, stalker_device_locale,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Public Master Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', '', '', '', '', 1, 1, '[]', 'UPFRONT', 1, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-public-master-44-51-test",
            52,
            true,
            StreamVaultDatabase.MIGRATION_44_45,
            StreamVaultDatabase.MIGRATION_45_46,
            StreamVaultDatabase.MIGRATION_46_47,
            StreamVaultDatabase.MIGRATION_47_48,
            StreamVaultDatabase.MIGRATION_48_49,
            StreamVaultDatabase.MIGRATION_49_50,
            StreamVaultDatabase.MIGRATION_50_51,
            StreamVaultDatabase.MIGRATION_51_52
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND name = 'Public Master Provider'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'http_user_agent'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'xtream_live_onboarding_state'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_tier'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_available_mem_mb'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'xtream_live_sync_mode'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND xtream_live_sync_mode = 'AUTO'"))

        migratedDb.close()
    }

    @Test
    fun migrate48To49_addsProviderHttpProfileColumns() {
        migrationTestHelper.createDatabase("streamvault-48-49-test", 48).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers,
                    stalker_mac_address, stalker_device_profile, stalker_device_timezone, stalker_device_locale,
                    is_active, max_connections, expiration_date, api_version, allowed_output_formats_json,
                    epg_sync_mode, xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (
                    1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', '', '',
                    '', '', '', '', 1, 1, NULL, NULL, '[]',
                    'UPFRONT', 1, 0, 'ACTIVE', 0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-48-49-test",
            49,
            true,
            StreamVaultDatabase.MIGRATION_48_49
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'http_user_agent'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'http_headers'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND http_user_agent = '' AND http_headers = ''"))

        migratedDb.close()
    }

    @Test
    fun migrate49To50_createsXtreamLiveOnboardingStateTable() {
        migrationTestHelper.createDatabase("streamvault-49-50-test", 49).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-49-50-test",
            50,
            true,
            StreamVaultDatabase.MIGRATION_49_50
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'xtream_live_onboarding_state'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_xtream_live_onboarding_state_provider_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'staged_flush_count'"))

        migratedDb.close()
    }

    @Test
    fun migrate50To51_addsXtreamLiveOnboardingProfileDiagnostics() {
        migrationTestHelper.createDatabase("streamvault-50-51-test", 50).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-50-51-test",
            51,
            true,
            StreamVaultDatabase.MIGRATION_50_51
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_tier'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_batch_size'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_strategy'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_low_memory'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_memory_class_mb'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('xtream_live_onboarding_state') WHERE name = 'sync_profile_available_mem_mb'"))

        migratedDb.close()
    }

    @Test
    fun migrate51To52_addsXtreamLiveSyncMode() {
        migrationTestHelper.createDatabase("streamvault-51-52-test", 51).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-51-52-test",
            52,
            true,
            StreamVaultDatabase.MIGRATION_51_52
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'xtream_live_sync_mode'"))

        migratedDb.close()
    }

    @Test
    fun migrate52To53_addsStalkerHardeningHydrationColumns() {
        migrationTestHelper.createDatabase("streamvault-52-53-test", 52).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-52-53-test",
            53,
            true,
            StreamVaultDatabase.MIGRATION_52_53
        )

        val movieTable = "movie_category_hydration"
        val seriesTable = "series_category_hydration"
        listOf(
            "last_attempted_page",
            "last_successful_page",
            "retry_after_ms",
            "failure_count",
            "retry_budget_remaining",
            "last_page_fingerprint"
        ).forEach { column ->
            assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('$movieTable') WHERE name = '$column'"))
            assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('$seriesTable') WHERE name = '$column'"))
        }

        migratedDb.close()
    }

    @Test
    fun migrate53To54_addsStalkerIdentityColumns() {
        migrationTestHelper.createDatabase("streamvault-53-54-test", 53).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers, stalker_mac_address, stalker_device_profile,
                    stalker_device_timezone, stalker_device_locale, is_active, max_connections,
                    expiration_date, api_version, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, xtream_live_sync_mode, m3u_vod_classification_enabled,
                    status, last_synced_at, created_at
                ) VALUES (
                    1, 'Portal', 'STALKER_PORTAL', 'https://portal.example.com', '', '', '', '',
                    '', '', '00:1A:79:12:34:56', 'MAG250', 'UTC', 'en', 1, 1,
                    NULL, NULL, '[]', 'BACKGROUND', 0, 'AUTO', 0, 'ACTIVE', 0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-53-54-test",
            54,
            true,
            StreamVaultDatabase.MIGRATION_53_54
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_serial_number'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_id2'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_signature'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_serial_number = '' AND stalker_device_id = '' AND stalker_device_id2 = '' AND stalker_signature = ''"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate40To41_addsAudioVideoOffsetColumn() {
        migrationTestHelper.createDatabase("streamvault-40-41-test", 40).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-40-41-test",
            41,
            true,
            StreamVaultDatabase.MIGRATION_40_41
        )

        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('channel_preferences') WHERE name = 'audio_video_offset_ms'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate41To42_createsPlaybackCompatibilityRecordsTable() {
        migrationTestHelper.createDatabase("streamvault-41-42-test", 41).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-41-42-test",
            42,
            true,
            StreamVaultDatabase.MIGRATION_41_42
        )

        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'playback_compatibility_records'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('playback_compatibility_records') WHERE name = 'decoder_name'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate42To43_addsProviderSeriesIdColumn() {
        migrationTestHelper.createDatabase("streamvault-42-43-test", 42).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-42-43-test",
            43,
            true,
            StreamVaultDatabase.MIGRATION_42_43
        )

        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('series') WHERE name = 'provider_series_id'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate43To44_addsPagedVodHydrationColumns() {
        migrationTestHelper.createDatabase("streamvault-43-44-test", 43).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-43-44-test",
            44,
            true,
            StreamVaultDatabase.MIGRATION_43_44
        )

        listOf("movie_category_hydration", "series_category_hydration").forEach { table ->
            assertEquals(
                4,
                countRows(
                    migratedDb,
                    """
                    SELECT COUNT(*) FROM pragma_table_info('$table')
                    WHERE name IN ('last_loaded_page', 'total_pages', 'is_complete', 'page_size')
                    """.trimIndent()
                )
            )
        }

        migratedDb.close()
    }

    @Test
    fun migrate38To39_addsStalkerProviderColumnsAndUniqueIndex() {
        migrationTestHelper.createDatabase("streamvault-38-39-test", 38).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'UPFRONT', 1, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-38-39-test",
            39,
            true,
            StreamVaultDatabase.MIGRATION_38_39
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_mac_address'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_profile'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_timezone'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_device_locale'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_mac_address = ''"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_providers_server_url_username_stalker_mac_address'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate32To33_backfillsMovieWatchCount() {
        migrationTestHelper.createDatabase("streamvault-32-33-test", 32).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, last_watched_at, is_adult, is_user_protected, sync_fingerprint, added_at
                ) VALUES (10, 2001, 'Movie', 'https://provider.example.com/movie.mp4', 7200, 7.5, 1, 0, 0, 0, 0, 'fp-10', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    content_id, content_type, provider_id, title, poster_url, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, watched_status,
                    series_id, season_number, episode_number
                ) VALUES (10, 'MOVIE', 1, 'Movie', NULL, 'https://provider.example.com/movie.mp4', 1200, 7200, 55, 6, 'IN_PROGRESS', NULL, NULL, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, provider_id, content_id, content_type, position, group_id, added_at)
                VALUES (1, 1, 10, 'MOVIE', 0, NULL, 0)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-32-33-test",
            33,
            true,
            StreamVaultDatabase.MIGRATION_32_33
        )

        assertEquals(6, countRows(migratedDb, "SELECT watch_count FROM movies WHERE id = 10"))
        migratedDb.close()
    }

    @Test
    fun migrate33To34_addsSuccessTimestampsAndProgramsEndTimeIndex() {
        migrationTestHelper.createDatabase("streamvault-33-34-test", 33).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO sync_metadata (
                    provider_id, last_live_sync, last_movie_sync, last_series_sync, last_epg_sync,
                    last_movie_attempt, last_movie_success, last_movie_partial,
                    live_count, movie_count, series_count, epg_count, last_sync_status, movie_sync_mode,
                    movie_warnings_count, movie_catalog_stale, live_avoid_full_until, movie_avoid_full_until,
                    series_avoid_full_until, live_sequential_failures_remembered, live_healthy_sync_streak,
                    movie_parallel_failures_remembered, movie_healthy_sync_streak,
                    series_sequential_failures_remembered, series_healthy_sync_streak
                ) VALUES (
                    1, 11, 22, 33, 44,
                    55, 66, 77,
                    1, 2, 3, 4, 'SUCCESS', 'FULL',
                    0, 0, 0, 0,
                    0, 0, 0,
                    0, 0,
                    0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-33-34-test",
            34,
            true,
            StreamVaultDatabase.MIGRATION_33_34
        )

        assertEquals(11, countRows(migratedDb, "SELECT last_live_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(33, countRows(migratedDb, "SELECT last_series_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(44, countRows(migratedDb, "SELECT last_epg_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_programs_provider_id_end_time_channel_id'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate44To45_dedupesGlobalFavoritesAndAddsNullSafeUniqueKey() {
        migrationTestHelper.createDatabase("streamvault-44-45-test", 44).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    stalker_mac_address, stalker_device_profile, stalker_device_timezone, stalker_device_locale,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'M3U', 'https://provider.example.com', '', '', '', '', '', '', '', '', 1, 1, '[]', 'UPFRONT', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, provider_id, content_id, content_type, position, group_id, added_at)
                VALUES (1, 1, 10, 'MOVIE', 400, NULL, 200)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, provider_id, content_id, content_type, position, group_id, added_at)
                VALUES (2, 1, 10, 'MOVIE', 100, NULL, 100)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-44-45-test",
            45,
            true,
            StreamVaultDatabase.MIGRATION_44_45
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1 AND content_id = 10 AND content_type = 'MOVIE' AND group_id IS NULL"))
        assertEquals(100, countRows(migratedDb, "SELECT position FROM favorites WHERE provider_id = 1 AND content_id = 10 AND content_type = 'MOVIE' AND group_id IS NULL"))
        assertEquals(100, countRows(migratedDb, "SELECT added_at FROM favorites WHERE provider_id = 1 AND content_id = 10 AND content_type = 'MOVIE' AND group_id IS NULL"))
        assertEquals(0, countRows(migratedDb, "SELECT group_key FROM favorites WHERE provider_id = 1 AND content_id = 10 AND content_type = 'MOVIE' AND group_id IS NULL"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_favorites_provider_id_content_id_content_type_group_key'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate45To46_rebuildsSeriesStageWithProviderSeriesKey() {
        migrationTestHelper.createDatabase("streamvault-45-46-test", 45).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    stalker_mac_address, stalker_device_profile, stalker_device_timezone, stalker_device_locale,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'STALKER_PORTAL', 'https://provider.example.com', '', '', '', '', '00:1A:79:00:00:01', 'MAG250', 'UTC', 'en', 1, 1, '[]', 'UPFRONT', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series_import_stage (
                    session_id, provider_id, series_id, name, poster_url, backdrop_url,
                    category_id, category_name, plot, cast, director, genre, release_date,
                    rating, tmdb_id, youtube_trailer, episode_run_time, last_modified,
                    is_adult, sync_fingerprint
                ) VALUES (10, 1, 256103980, 'Series', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, 0, 0, 'fp')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-45-46-test",
            46,
            true,
            StreamVaultDatabase.MIGRATION_45_46
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('series_import_stage') WHERE name = 'provider_series_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('series_import_stage') WHERE name = 'provider_series_key'"))
        assertEquals(256103980, countRows(migratedDb, "SELECT CAST(provider_series_key AS INTEGER) FROM series_import_stage WHERE session_id = 10 AND provider_id = 1"))

        migratedDb.close()
    }

    @Test
    fun migrate46To47_createsBrowseQueryIndexes() {
        migrationTestHelper.createDatabase("streamvault-46-47-test", 46).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-46-47-test",
            47,
            true,
            StreamVaultDatabase.MIGRATION_46_47
        )

        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_movies_provider_id_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_movies_provider_id_category_id_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_movies_provider_id_rating_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_movies_provider_id_added_at_release_date_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_series_provider_id_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_series_provider_id_category_id_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_series_provider_id_rating_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_series_provider_id_last_modified_name_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_playback_history_provider_id_content_type_content_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_playback_history_provider_id_content_type_last_watched_at'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate47To48_createsXtreamIndexBackfillsRowsAndMarksSummaryState() {
        migrationTestHelper.createDatabase("streamvault-47-48-test", 47).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    stalker_mac_address, stalker_device_profile, stalker_device_timezone, stalker_device_locale,
                    is_active, max_connections, expiration_date, api_version, allowed_output_formats_json,
                    epg_sync_mode, xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (
                    1, 'Xtream Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '',
                    '', '', '', '', 1, 1, NULL, NULL, '[]',
                    'UPFRONT', 1, 0, 'ACTIVE', 1234, 1000
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO channels (
                    id, stream_id, name, logo_url, group_title, category_id, category_name,
                    stream_url, epg_channel_id, number, catch_up_supported, catch_up_days, catchUpSource,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count,
                    quality_options_json, sync_fingerprint
                ) VALUES (
                    10, 1001, 'News One', 'https://provider.example.com/news.png', 'News', 5, 'News',
                    'https://provider.example.com/live/1001', NULL, 1, 0, 0, NULL,
                    1, 0, 0, '', 0, NULL, 'live-fp'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO categories (
                    id, category_id, name, parent_id, type, provider_id, is_adult, is_user_protected, sync_fingerprint
                ) VALUES
                    (101, 5, 'News', NULL, 'LIVE', 1, 0, 0, 'cat-live'),
                    (102, 6, 'Movies', NULL, 'MOVIE', 1, 0, 0, 'cat-movies'),
                    (103, 7, 'Series', NULL, 'SERIES', 1, 0, 0, 'cat-series')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, poster_url, backdrop_url, category_id, category_name,
                    stream_url, container_extension, plot, cast, director, genre, release_date,
                    duration, duration_seconds, rating, year, tmdb_id, youtube_trailer,
                    provider_id, watch_progress, watch_count, last_watched_at, is_adult,
                    is_user_protected, sync_fingerprint, added_at
                ) VALUES (
                    20, 2001, 'Thin Movie', 'https://provider.example.com/thin.jpg', NULL, 6, 'Movies',
                    'https://provider.example.com/movie/2001.mp4', 'mp4', NULL, NULL, NULL, NULL, NULL,
                    NULL, 0, 6.5, NULL, NULL, NULL,
                    1, 0, 0, 0, 0, 0, 'movie-thin-fp', 111
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, poster_url, backdrop_url, category_id, category_name,
                    stream_url, container_extension, plot, cast, director, genre, release_date,
                    duration, duration_seconds, rating, year, tmdb_id, youtube_trailer,
                    provider_id, watch_progress, watch_count, last_watched_at, is_adult,
                    is_user_protected, sync_fingerprint, added_at
                ) VALUES (
                    21, 2002, 'Rich Movie', 'https://provider.example.com/rich.jpg', NULL, 6, 'Movies',
                    'https://provider.example.com/movie/2002.mp4', 'mp4', 'Plot', NULL, NULL, 'Drama', NULL,
                    '90 min', 5400, 7.5, '2025', 9876, NULL,
                    1, 0, 0, 0, 0, 0, 'movie-rich-fp', 222
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    id, series_id, provider_series_id, name, poster_url, backdrop_url, category_id,
                    category_name, plot, cast, director, genre, release_date, rating, tmdb_id,
                    youtube_trailer, episode_run_time, last_modified, provider_id, is_adult,
                    is_user_protected, sync_fingerprint
                ) VALUES (
                    30, 3001, NULL, 'Thin Series', 'https://provider.example.com/thin-series.jpg', NULL, 7,
                    'Series', NULL, NULL, NULL, NULL, NULL, 6.0, NULL,
                    NULL, NULL, 333, 1, 0, 0, 'series-thin-fp'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    id, series_id, provider_series_id, name, poster_url, backdrop_url, category_id,
                    category_name, plot, cast, director, genre, release_date, rating, tmdb_id,
                    youtube_trailer, episode_run_time, last_modified, provider_id, is_adult,
                    is_user_protected, sync_fingerprint
                ) VALUES (
                    31, 3002, 'provider-series-3002', 'Rich Series', 'https://provider.example.com/rich-series.jpg', NULL, 7,
                    'Series', 'Plot', NULL, NULL, 'Drama', NULL, 8.0, 12345,
                    NULL, '45', 444, 1, 0, 0, 'series-rich-fp'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO sync_metadata (
                    provider_id, last_live_sync, last_live_success, last_movie_sync, last_series_sync,
                    last_series_success, last_epg_sync, last_epg_success, last_movie_attempt,
                    last_movie_success, last_movie_partial, live_count, movie_count, series_count,
                    epg_count, last_sync_status, movie_sync_mode, movie_warnings_count, movie_catalog_stale,
                    live_avoid_full_until, movie_avoid_full_until, series_avoid_full_until,
                    live_sequential_failures_remembered, live_healthy_sync_streak,
                    movie_parallel_failures_remembered, movie_healthy_sync_streak,
                    series_sequential_failures_remembered, series_healthy_sync_streak
                ) VALUES (
                    1, 1200, 1200, 1300, 1400,
                    1400, 1500, 1500, 1300,
                    1300, 0, 1, 2, 2,
                    3, 'SUCCESS', 'FULL', 0, 0,
                    0, 0, 0,
                    0, 0,
                    0, 0,
                    0, 0
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-47-48-test",
            48,
            true,
            StreamVaultDatabase.MIGRATION_47_48
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'xtream_content_index'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'xtream_index_jobs'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_xtream_content_index_provider_id_content_type_local_content_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('movies') WHERE name = 'cache_state'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('series') WHERE name = 'detail_hydrated_at'"))
        assertEquals(5, countRows(migratedDb, "SELECT COUNT(*) FROM xtream_content_index WHERE provider_id = 1"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM xtream_content_index WHERE content_type = 'LIVE' AND remote_id = '1001' AND image_url LIKE '%news.png'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM xtream_content_index WHERE content_type = 'SERIES' AND remote_id = 'provider-series-3002'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies WHERE id = 20 AND cache_state = 'SUMMARY_ONLY'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies WHERE id = 21 AND cache_state = 'DETAIL_HYDRATED'"))
        assertEquals(1234, countRows(migratedDb, "SELECT detail_hydrated_at FROM movies WHERE id = 21"))
        assertEquals(0, countRows(migratedDb, "SELECT detail_hydrated_at FROM movies WHERE id = 20"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM series WHERE id = 30 AND cache_state = 'SUMMARY_ONLY'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM series WHERE id = 31 AND cache_state = 'DETAIL_HYDRATED'"))
        assertEquals(1234, countRows(migratedDb, "SELECT detail_hydrated_at FROM series WHERE id = 31"))
        assertEquals(0, countRows(migratedDb, "SELECT detail_hydrated_at FROM series WHERE id = 30"))
        assertEquals(4, countRows(migratedDb, "SELECT COUNT(*) FROM xtream_index_jobs WHERE provider_id = 1"))
        assertEquals(2, countRows(migratedDb, "SELECT indexed_rows FROM xtream_index_jobs WHERE provider_id = 1 AND section = 'MOVIE'"))
        assertEquals(1, countRows(migratedDb, "SELECT total_categories FROM xtream_index_jobs WHERE provider_id = 1 AND section = 'MOVIE'"))
        assertEquals(1, countRows(migratedDb, "SELECT completed_categories FROM xtream_index_jobs WHERE provider_id = 1 AND section = 'MOVIE'"))

        migratedDb.close()
    }

    @Test
    fun migrate34To35_createsSearchHistoryTable() {
        migrationTestHelper.createDatabase("streamvault-34-35-test", 34).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-34-35-test",
            35,
            true,
            StreamVaultDatabase.MIGRATION_34_35
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('search_history') WHERE name = 'query'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('search_history') WHERE name = 'content_scope'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_search_history_query_content_scope_provider_id'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate31To32_scopesFavoritesAndSplitsMixedProviderGroups() {
        migrationTestHelper.createDatabase("streamvault-31-32-test", 31).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES
                    (1, 'Provider One', 'XTREAM_CODES', 'https://one.example.com', 'one', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0),
                    (2, 'Provider Two', 'XTREAM_CODES', 'https://two.example.com', 'two', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO channels (
                    id, stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count, sync_fingerprint
                ) VALUES
                    (101, 1001, 'Provider One Global', 'https://one.example.com/live/1001', 1, 0, 0, 1, 0, 0, 'lg-101', 0, 'fp-101'),
                    (102, 1002, 'Provider One Grouped', 'https://one.example.com/live/1002', 2, 0, 0, 1, 0, 0, 'lg-102', 0, 'fp-102'),
                    (201, 2001, 'Provider Two Grouped', 'https://two.example.com/live/2001', 1, 0, 0, 2, 0, 0, 'lg-201', 0, 'fp-201')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO virtual_groups (id, name, icon_emoji, position, created_at, content_type)
                VALUES (10, 'Mixed Group', NULL, 0, 100, 'LIVE')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO favorites (id, content_id, content_type, position, group_id, added_at)
                VALUES
                    (1, 101, 'LIVE', 0, NULL, 1000),
                    (2, 102, 'LIVE', 1, 10, 1001),
                    (3, 201, 'LIVE', 0, 10, 1002)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-31-32-test",
            32,
            true,
            StreamVaultDatabase.MIGRATION_31_32
        )

        assertEquals(2, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE name = 'Mixed Group'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 1 AND name = 'Mixed Group'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 2 AND name = 'Mixed Group'"))

        assertEquals(2, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 2"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1 AND group_id IS NULL"))

        val providerOneGroupId = firstLong(
            migratedDb,
            "SELECT group_id FROM favorites WHERE provider_id = 1 AND content_id = 102"
        )
        val providerTwoGroupId = firstLong(
            migratedDb,
            "SELECT group_id FROM favorites WHERE provider_id = 2 AND content_id = 201"
        )

        assertEquals(10L, providerOneGroupId)
        assertNotEquals(providerOneGroupId, providerTwoGroupId)

        migratedDb.close()
    }

    @Test
    fun migrate31To32_assignsLegacyGroupsWhenNoProviderIsActive() {
        migrationTestHelper.createDatabase("streamvault-31-32-no-active-test", 31).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES
                    (5, 'First Provider', 'XTREAM_CODES', 'https://one.example.com', 'one', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 10),
                    (7, 'Second Provider', 'XTREAM_CODES', 'https://two.example.com', 'two', 'secret', '', '', 0, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 20)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO virtual_groups (id, name, icon_emoji, position, created_at, content_type)
                VALUES (10, 'Legacy Group', NULL, 0, 100, 'LIVE')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-31-32-no-active-test",
            32,
            true,
            StreamVaultDatabase.MIGRATION_31_32
        )

        assertEquals(0, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE provider_id = 0 OR provider_id IS NULL"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM virtual_groups WHERE id = 10 AND provider_id = 5"))

        migratedDb.close()
    }

    @Test
    fun migrate35To36_addsProgramRemindersAndEpgMatchMetadata() {
        migrationTestHelper.createDatabase("streamvault-35-36-test", 35).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-35-36-test",
            36,
            true,
            StreamVaultDatabase.MIGRATION_35_36
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'matched_at'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'failed_attempts'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channel_epg_mappings') WHERE name = 'source'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'program_reminders'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_program_reminders_provider_id_channel_id_program_title_program_start_time'"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate36To37_createsTmdbIdentityTable() {
        migrationTestHelper.createDatabase("streamvault-36-37-test", 36).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, allowed_output_formats_json, epg_sync_mode,
                    xtream_fast_sync_enabled, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, '[]', 'PROVIDER', 0, 0, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movies (
                    id, stream_id, name, stream_url, duration_seconds, rating, tmdb_id, provider_id,
                    watch_progress, watch_count, last_watched_at, is_adult, is_user_protected, sync_fingerprint, added_at
                ) VALUES (10, 2001, 'Movie', 'https://provider.example.com/movie.mp4', 7200, 7.5, 12345, 1, 0, 0, 0, 0, 0, 'fp-10', 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO series (
                    id, series_id, name, rating, tmdb_id, last_modified, provider_id, is_adult, is_user_protected, sync_fingerprint
                ) VALUES (20, 3001, 'Series', 8.1, 67890, 0, 1, 0, 0, 'fp-20')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-36-37-test",
            37,
            true,
            StreamVaultDatabase.MIGRATION_36_37
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'tmdb_identity'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM tmdb_identity WHERE tmdb_id = 12345 AND content_type = 'MOVIE'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM tmdb_identity WHERE tmdb_id = 67890 AND content_type = 'SERIES'"))

        migratedDb.close()
    }

    @Test
    fun migrate14To15_addsAuditCompletionColumns() {
        migrationTestHelper.createDatabase("streamvault-14-15-test", 14).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    is_active, max_connections, expiration_date, status, last_synced_at, created_at
                ) VALUES (1, 'Provider', 'XTREAM_CODES', 'https://provider.example.com', 'demo', 'secret', '', '', 1, 1, NULL, 'ACTIVE', 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    content_id, content_type, provider_id, title, poster_url, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, series_id, season_number, episode_number
                ) VALUES (10, 'MOVIE', 1, 'Movie', NULL, 'https://provider.example.com/movie.mp4', 0, 7200, 0, 1, NULL, NULL, NULL)
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-14-15-test",
            15,
            true,
            StreamVaultDatabase.MIGRATION_14_15
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'api_version'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('channels') WHERE name = 'quality_options_json'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('programs') WHERE name = 'rating'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('playback_history') WHERE name = 'watched_status'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM playback_history WHERE watched_status = 'IN_PROGRESS'"))

        migratedDb.close()
    }

    @Test
    fun migrate14And15To77_preservesLegacyCatalogFavoritesAndHistory() {
        listOf(14, 15).forEach { startVersion ->
            val name = "streamvault-$startVersion-77-populated-legacy-chain"
            migrationTestHelper.createDatabase(name, startVersion).apply {
                execSQL(
                    """
                    INSERT INTO providers (
                        id, name, type, server_url, username, password, m3u_url, epg_url,
                        is_active, max_connections, expiration_date, status, last_synced_at, created_at
                    ) VALUES (1, 'Legacy Provider', 'XTREAM_CODES', 'https://provider.invalid', 'legacy-user', 'legacy-password', '', '', 1, 1, NULL, 'ACTIVE', 123, 456)
                    """.trimIndent()
                )
                execSQL(
                    """
                    INSERT INTO categories (
                        id, category_id, name, parent_id, type, provider_id, is_adult, is_user_protected
                    ) VALUES (10, 100, 'Legacy protected category', NULL, 'MOVIE', 1, 0, 1)
                    """.trimIndent()
                )
                execSQL(
                    """
                    INSERT INTO channels (
                        id, stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                        provider_id, is_adult, is_user_protected, logical_group_id, error_count
                    ) VALUES (11, 101, 'Legacy protected channel', 'https://stream.invalid/live', 101, 0, 0, 1, 0, 1, '', 0)
                    """.trimIndent()
                )
                execSQL(
                    "INSERT INTO channel_preferences (id, channel_id, aspect_ratio, updated_at) " +
                        "VALUES (12, 11, '4:3', 789)"
                )
                execSQL(
                    """
                    INSERT INTO movies (
                        id, stream_id, name, stream_url, duration_seconds, rating, provider_id,
                        watch_progress, last_watched_at, is_adult, is_user_protected
                    ) VALUES (20, 202, 'Legacy movie', 'https://stream.invalid/movie', 5400, 7.5, 1, 222, 333, 0, 1)
                    """.trimIndent()
                )
                execSQL(
                    """
                    INSERT INTO virtual_groups (id, name, icon_emoji, position, created_at, content_type)
                    VALUES (30, 'Legacy favorites group', '★', 2, 111, 'MOVIE')
                    """.trimIndent()
                )
                execSQL(
                    """
                    INSERT INTO favorites (id, content_id, content_type, position, group_id, added_at)
                    VALUES (31, 20, 'MOVIE', 1, 30, 222)
                    """.trimIndent()
                )
                if (startVersion >= 15) {
                    execSQL(
                        """
                        INSERT INTO playback_history (
                            id, content_id, content_type, provider_id, title, stream_url,
                            resume_position_ms, total_duration_ms, last_watched_at, watch_count,
                            watched_status, series_id, season_number, episode_number
                        ) VALUES (40, 20, 'MOVIE', 1, 'Legacy movie', 'https://stream.invalid/movie', 222000, 5400000, 333, 2, 'IN_PROGRESS', NULL, NULL, NULL)
                        """.trimIndent()
                    )
                } else {
                    execSQL(
                        """
                        INSERT INTO playback_history (
                            id, content_id, content_type, provider_id, title, stream_url,
                            resume_position_ms, total_duration_ms, last_watched_at, watch_count,
                            series_id, season_number, episode_number
                        ) VALUES (40, 20, 'MOVIE', 1, 'Legacy movie', 'https://stream.invalid/movie', 222000, 5400000, 333, 2, NULL, NULL, NULL)
                        """.trimIndent()
                    )
                }
                execSQL(
                    """
                    INSERT INTO sync_metadata (
                        provider_id, last_live_sync, last_movie_sync, last_series_sync, last_epg_sync,
                        live_count, movie_count, series_count, epg_count, last_sync_status
                    ) VALUES (1, 11, 22, 33, 44, 1, 2, 3, 4, 'SUCCESS')
                    """.trimIndent()
                )
                close()
            }

            val migrated = migrationTestHelper.runMigrationsAndValidate(
                name,
                StreamVaultDatabaseMigrationRegistry.CURRENT_VERSION,
                true,
                *StreamVaultDatabaseMigrationRegistry.all
                    .filter { migration -> migration.startVersion >= startVersion }
                    .toTypedArray()
            )

            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id = 1 AND name = 'Legacy Provider'"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM categories WHERE id = 10 AND is_user_protected = 1"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channels WHERE id = 11 AND is_user_protected = 1"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channel_preferences WHERE channel_id = 11 AND aspect_ratio = '4:3'"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM movies WHERE id = 20 AND watch_progress = 222 AND is_user_protected = 1"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM virtual_groups WHERE id = 30 AND provider_id = 1"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM favorites WHERE id = 31 AND provider_id = 1 AND content_id = 20 AND group_id IS NOT NULL"))
            assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM playback_history WHERE id = 40 AND provider_id = 1 AND watched_status = 'IN_PROGRESS'"))
            assertEquals(11, countRows(migrated, "SELECT last_live_success FROM sync_metadata WHERE provider_id = 1"))
            assertEquals(22, countRows(migrated, "SELECT last_movie_sync FROM sync_metadata WHERE provider_id = 1"))
            assertEquals(0, countRows(migrated, "SELECT last_movie_success FROM sync_metadata WHERE provider_id = 1"))
            assertEquals(33, countRows(migrated, "SELECT last_series_success FROM sync_metadata WHERE provider_id = 1"))
            assertEquals(44, countRows(migrated, "SELECT last_epg_success FROM sync_metadata WHERE provider_id = 1"))
            assertEquals(0, countRows(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
            migrated.close()
        }
    }

    @Test
    fun migrate59To60_addsDownloadSourceColumns() {
        migrationTestHelper.createDatabase("streamvault-59-60-test", 59).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-59-60-test",
            60,
            true,
            StreamVaultDatabase.MIGRATION_59_60
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'source_stream_url'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'source_stream_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'container_extension'"))

        migratedDb.close()
    }

    @Test
    fun migrate57To61_upgradeChainValidatesLatestSchema() {
        migrationTestHelper.createDatabase("streamvault-57-61-test", 57).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-57-61-test",
            61,
            true,
            StreamVaultDatabase.MIGRATION_57_58,
            StreamVaultDatabase.MIGRATION_58_59,
            StreamVaultDatabase.MIGRATION_59_60,
            StreamVaultDatabase.MIGRATION_60_61
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'source_stream_url'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'source_stream_id'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'container_extension'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'supports_resume'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('downloads') WHERE name = 'retry_count'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_advanced_options_json'"))

        migratedDb.close()
    }

    @Test
    fun migrate60To61_release114To115UpgradePreservesProviders() {
        migrationTestHelper.createDatabase("streamvault-release-114-115-test", 60).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers, stalker_mac_address, stalker_device_profile,
                    stalker_device_timezone, stalker_device_locale, stalker_serial_number,
                    stalker_device_id, stalker_device_id2, stalker_signature, stalker_auth_mode,
                    stalker_portal_profile, stalker_portal_fingerprint, stalker_mag_preset,
                    stalker_last_bootstrap_recipe, stalker_endpoint_preference, stalker_cookie_mode,
                    stalker_playback_backend_hint, stalker_last_playback_mode,
                    stalker_credentials_required, stalker_mac_required, stalker_uses_temp_links,
                    stalker_module_restricted, stalker_strict_fingerprint_required,
                    stalker_recipe_fallback_used, stalker_recipe_rediscovery_attempts,
                    is_active, max_connections, expiration_date, api_version,
                    allowed_output_formats_json, epg_sync_mode, xtream_fast_sync_enabled,
                    xtream_live_sync_mode, m3u_vod_classification_enabled, status,
                    last_synced_at, created_at
                ) VALUES (
                    1, 'Release 1.0.14 Provider', 'STALKER_PORTAL', 'https://portal.example.com', '', '', '', '',
                    'MAG200 stbapp', '{\"X-Test\":\"1\"}', '00:1A:79:12:34:56', 'MAG250',
                    'UTC', 'en', 'serial-1', 'device-1', 'device-2', 'signature-1', 'AUTO',
                    'MAG_BASIC', 'BASIC_MAC', 'GENERIC_SAFE', 'GENERIC_SAFE', 'AUTO', 'NONE',
                    'AUTO', NULL, 0, 1, 0,
                    0, 0, 0, 0,
                    1, 1, NULL, 'legacy-api',
                    '[]', 'UPFRONT', 1,
                    'AUTO', 0, 'ACTIVE',
                    1234, 5678
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-release-114-115-test",
            61,
            true,
            StreamVaultDatabase.MIGRATION_60_61
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_advanced_options_json'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM providers WHERE id = 1 AND name = 'Release 1.0.14 Provider' AND stalker_advanced_options_json = ''"
            )
        )

        migratedDb.close()
    }

    @Test
    fun migrate60To61_addsStalkerAdvancedOptionsJson() {
        migrationTestHelper.createDatabase("streamvault-60-61-test", 60).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-60-61-test",
            61,
            true,
            StreamVaultDatabase.MIGRATION_60_61
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_advanced_options_json'"))
        migratedDb.close()
    }

    @Test
    fun migrate62To64_addsStalkerStateAndStableCompatibilityProfile() {
        migrationTestHelper.createDatabase("streamvault-62-64-test", 62).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers, stalker_mac_address, stalker_device_profile,
                    stalker_device_timezone, stalker_device_locale, stalker_serial_number,
                    stalker_device_id, stalker_device_id2, stalker_signature, stalker_advanced_options_json,
                    stalker_auth_mode, stalker_portal_profile, stalker_portal_fingerprint,
                    stalker_mag_preset, stalker_last_bootstrap_recipe, stalker_endpoint_preference,
                    stalker_cookie_mode, stalker_playback_backend_hint, stalker_last_playback_mode,
                    stalker_credentials_required, stalker_mac_required, stalker_uses_temp_links,
                    stalker_module_restricted, stalker_strict_fingerprint_required,
                    stalker_recipe_fallback_used, stalker_recipe_rediscovery_attempts,
                    is_active, max_connections, expiration_date, api_version,
                    allowed_output_formats_json, epg_sync_mode, guide_source_policy,
                    channel_logo_source_policy, xtream_fast_sync_enabled, xtream_live_sync_mode,
                    m3u_vod_classification_enabled, status, last_synced_at, created_at
                ) VALUES (
                    1, 'Migrated Stalker', 'STALKER_PORTAL', 'https://portal.invalid', '', '', '', '',
                    '', '', '00:1A:79:00:00:01', 'MAG250', 'Asia/Jerusalem', 'en', '', '', '', '', '',
                    'AUTO', 'MAG_BASIC', 'BASIC_MAC', 'GENERIC_SAFE', 'GENERIC_SAFE', 'AUTO',
                    'NONE', 'AUTO', NULL, 0, 1, 0, 0, 0, 0, 0,
                    1, 1, NULL, NULL, '[]', 'BACKGROUND', 'AUTO', 'SUPPLIER_PREFERRED',
                    0, 'AUTO', 0, 'ACTIVE', 1234, 5678
                )
                """.trimIndent()
            )
            execSQL("INSERT INTO categories (category_id, name, parent_id, type, provider_id, is_adult, is_user_protected, sync_fingerprint) VALUES (10, 'Movies', NULL, 'MOVIE', 1, 0, 0, 'cat')")
            execSQL(
                """
                INSERT INTO movies (
                    stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, watch_count, last_watched_at, is_adult, is_user_protected,
                    sync_fingerprint, added_at, cache_state, detail_hydrated_at, remote_stale_at
                ) VALUES (99, 'Cached Movie', 'stalker://cached', 7200, 8.0, 1, 321, 2, 999, 0, 0, 'movie', 1, 'HYDRATED', 2, 0)
                """.trimIndent()
            )
            execSQL("INSERT INTO favorites (provider_id, content_id, content_type, position, group_id, group_key, added_at) VALUES (1, 99, 'MOVIE', 0, NULL, 0, 100)")
            execSQL(
                """
                INSERT INTO movie_category_hydration (
                    provider_id, category_id, last_hydrated_at, item_count, last_status,
                    last_error, last_loaded_page, last_attempted_page, last_successful_page,
                    total_pages, is_complete, page_size, retry_after_ms, failure_count,
                    retry_budget_remaining, last_page_fingerprint
                ) VALUES (1, 10, 1000, 1, 'SUCCESS', NULL, 1, 1, 1, 3, 0, 18, 0, 0, 3, 'page-one')
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-62-64-test",
            64,
            true,
            StreamVaultDatabase.MIGRATION_62_63,
            StreamVaultDatabase.MIGRATION_63_64
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_catalog_mode'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stalker_index_jobs'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stalker_portal_state'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stalker_remote_identities'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('stalker_index_jobs') WHERE name = 'skipped_malformed_rows'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('stalker_portal_state') WHERE name = 'endpoint_health_json'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_catalog_mode = 'ON_DEMAND'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM categories WHERE provider_id = 1 AND category_id = 10"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movies WHERE provider_id = 1 AND stream_id = 99 AND watch_progress = 321 AND watch_count = 2"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM favorites WHERE provider_id = 1 AND content_id = 99"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM movie_category_hydration WHERE provider_id = 1 AND category_id = 10 AND last_loaded_page = 1"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_requested_profile_id = 'classic.mag250.generic'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_learned_profile_id = 'classic.mag250.generic' AND stalker_profile_revision = 1"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM providers WHERE id = 1 AND stalker_profile_verification = 'VERIFIED' AND stalker_protocol_family = 'CLASSIC_MAG'"))

        migratedDb.close()
    }

    @Test
    fun migrate64To65_addsScopedTransportAndResetsLegacyStalkerToStrict() {
        migrationTestHelper.createDatabase("streamvault-64-65-test", 64).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers, stalker_mac_address, stalker_device_profile,
                    stalker_device_timezone, stalker_device_locale, stalker_serial_number,
                    stalker_device_id, stalker_device_id2, stalker_signature,
                    stalker_advanced_options_json, stalker_auth_mode, stalker_portal_profile,
                    stalker_portal_fingerprint, stalker_mag_preset, stalker_protocol_preference,
                    stalker_requested_profile_id, stalker_learned_profile_id,
                    stalker_profile_revision, stalker_profile_verification,
                    stalker_protocol_family, stalker_last_bootstrap_recipe,
                    stalker_endpoint_preference, stalker_cookie_mode,
                    stalker_playback_backend_hint, stalker_last_playback_mode,
                    stalker_credentials_required, stalker_mac_required,
                    stalker_uses_temp_links, stalker_module_restricted,
                    stalker_strict_fingerprint_required, stalker_recipe_fallback_used,
                    stalker_recipe_rediscovery_attempts, is_active, max_connections,
                    expiration_date, api_version, allowed_output_formats_json, epg_sync_mode,
                    stalker_catalog_mode, guide_source_policy, channel_logo_source_policy,
                    xtream_fast_sync_enabled, xtream_live_sync_mode,
                    m3u_vod_classification_enabled, status, last_synced_at, created_at
                ) VALUES (
                    1, 'Legacy HTTP Stalker', 'STALKER_PORTAL', 'http://portal.invalid', '', '', '', '',
                    '', '', '00:1A:79:00:00:01', 'MAG250', 'UTC', 'en', '', '', '', '',
                    '', 'AUTO', 'MAG_BASIC', 'BASIC_MAC', 'GENERIC_SAFE', 'CLASSIC_MAG',
                    'classic.mag250.generic', 'classic.mag250.generic', 1, 'VERIFIED',
                    'CLASSIC_MAG', 'GENERIC_SAFE', 'AUTO', 'NONE', 'AUTO', NULL,
                    0, 1, 0, 0, 0, 0, 0, 1, 1,
                    NULL, NULL, '[]', 'BACKGROUND', 'ON_DEMAND', 'AUTO',
                    'SUPPLIER_PREFERRED', 0, 'AUTO', 0, 'ACTIVE', 1234, 5678
                )
                """.trimIndent()
            )
            close()
        }

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-64-65-test",
            65,
            true,
            StreamVaultDatabase.MIGRATION_64_65
        )

        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_transport_mode'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_tls_spki_sha256'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'stalker_configuration_generation'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'stalker_discovery_staging'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_stalker_discovery_staging_created_at'"))
        assertEquals(
            1,
            countRows(
                migratedDb,
                """
                SELECT COUNT(*) FROM providers
                WHERE id = 1
                  AND stalker_transport_mode = 'AUTO_STRICT'
                  AND stalker_transport_origin = ''
                  AND stalker_tls_spki_sha256 = ''
                  AND stalker_transport_consent_at = 0
                  AND status = 'PARTIAL'
                  AND is_active = 0
                """.trimIndent()
            )
        )
        migratedDb.close()
    }

    @Test
    fun migrate65To66_addsProviderCatalogLayoutAndUnifiedHydration() {
        migrationTestHelper.createDatabase("streamvault-65-66-test", 65).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-65-66-test",
            66,
            true,
            StreamVaultDatabase.MIGRATION_65_66
        )

        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'catalog_layout' AND dflt_value = '''SPLIT'''"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name = 'catalog_layout_detection_version' AND dflt_value = '0'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('categories') WHERE name = 'provider_order' AND dflt_value = '0'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('category_import_stage') WHERE name = 'provider_order' AND dflt_value = '0'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'vod_category_hydration'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_vod_category_hydration_provider_id'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('series') WHERE name = 'catalog_origin' AND dflt_value = '''NATIVE'''"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('series') WHERE name = 'episode_playback_template_url' AND type = 'TEXT'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'vod_catalog_entries'"
            )
        )
        assertEquals(
            1,
            countRows(
                migratedDb,
                "SELECT COUNT(*) FROM pragma_table_info('vod_category_hydration') WHERE name = 'last_successful_page'"
            )
        )
        migratedDb.close()
    }

    @Test
    fun migrate62To72_combinesStalkerAndInnerBugFixMigrations() {
        migrationTestHelper.createDatabase("streamvault-62-72-combined-test", 62).close()

        val migratedDb = migrationTestHelper.runMigrationsAndValidate(
            "streamvault-62-72-combined-test",
            72,
            true,
            StreamVaultDatabase.MIGRATION_62_63,
            StreamVaultDatabase.MIGRATION_63_64,
            StreamVaultDatabase.MIGRATION_64_65,
            StreamVaultDatabase.MIGRATION_65_66,
            StreamVaultDatabase.MIGRATION_66_67,
            StreamVaultDatabase.MIGRATION_67_68,
            StreamVaultDatabase.MIGRATION_68_69,
            StreamVaultDatabase.MIGRATION_69_70,
            StreamVaultDatabase.MIGRATION_70_71,
            StreamVaultDatabase.MIGRATION_71_72
        )

        listOf(
            "stalker_index_jobs",
            "stalker_portal_state",
            "stalker_remote_identities",
            "provider_deletion_cleanup",
            "plugin_provider_ownership",
            "stalker_discovery_staging",
            "vod_category_hydration",
            "vod_catalog_entries",
            "provider_config_revisions",
            "backup_restore_checkpoints",
            "provider_workflows",
            "provider_workflow_phases"
        ).forEach { table ->
            assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"))
        }
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('recording_runs') WHERE name = 'exact_alarm_armed'"))
        assertEquals(1, countRows(migratedDb, "SELECT COUNT(*) FROM pragma_table_info('epg_sources') WHERE name = 'timezone_policy'"))
        migratedDb.close()
    }

    @Test
    fun migrate72To73_backfillsTypedSnapshotsForEveryProviderTypeWithoutIdOrFkLoss() {
        val name = "streamvault-72-73-provider-snapshots"
        migrationTestHelper.createDatabase(name, 72).apply {
            insertProvider72(1, "Xtream", "XTREAM_CODES")
            insertProvider72(2, "M3U", "M3U")
            insertProvider72(3, "Stalker", "STALKER_PORTAL")
            insertProvider72(4, "Jellyfin", "JELLYFIN")
            execSQL("UPDATE providers SET server_url='https://x.test',username='alice',password='enc:v1:xtream',is_active=1 WHERE id=1")
            execSQL("UPDATE providers SET server_url='https://m.test/list.m3u',m3u_url='https://m.test/list.m3u',epg_url='https://m.test/epg.xml' WHERE id=2")
            execSQL("UPDATE providers SET server_url='https://s.test',username='bob',password='enc:v1:stalker',stalker_mac_address='00:11:22:33:44:55',stalker_configuration_generation=4 WHERE id=3")
            execSQL("UPDATE providers SET server_url='https://j.test',username='carol',password='enc:v1:jellyfin' WHERE id=4")
            insertChannel72(10, 100, "News", 1)
            insertStalkerPortalState72(3)
            close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            73,
            true,
            StreamVaultDatabase.MIGRATION_72_73
        )

        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM provider_configs"))
        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM provider_account_runtime"))
        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM provider_configs WHERE length(identity_key)=64"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM provider_configs WHERE provider_id=3 AND configuration_generation=4 AND encrypted_config_json LIKE '%enc:v1:stalker%'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channels WHERE id=10 AND provider_id=1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM stalker_portal_state WHERE provider_id=3 AND configuration_generation=4"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM stalker_portal_state WHERE provider_id=3 AND learning_json LIKE '%\"portalFingerprint\"%' AND learning_json LIKE '%\"configurationGeneration\":4%'"))
        assertEquals(0, countRows(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        migrated.close()
    }

    @Test
    fun migrate72To73_preservesProvidersWhoseNormalizedIdentitiesCollide() {
        val name = "streamvault-72-73-normalized-duplicates"
        migrationTestHelper.createDatabase(name, 72).apply {
            insertProvider72(1, "Original", "XTREAM_CODES")
            insertProvider72(2, "Equivalent legacy URL", "XTREAM_CODES")
            execSQL("UPDATE providers SET server_url='https://host/',username='alice' WHERE id=1")
            execSQL("UPDATE providers SET server_url='https://host:443',username='alice' WHERE id=2")
            close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            73,
            true,
            StreamVaultDatabase.MIGRATION_72_73
        )

        assertEquals(2, countRows(migrated, "SELECT COUNT(*) FROM providers"))
        assertEquals(2, countRows(migrated, "SELECT COUNT(*) FROM provider_configs"))
        assertEquals(2, countRows(migrated, "SELECT COUNT(DISTINCT identity_key) FROM provider_configs"))
        assertEquals(2, countRows(migrated, "SELECT COUNT(*) FROM provider_configs WHERE length(identity_key)=64"))
        migrated.close()
    }

    @Test
    fun migrate72To73_canonicalizesAliasesAndUnknownLegacyTypes() {
        val name = "streamvault-72-73-provider-aliases"
        migrationTestHelper.createDatabase(name, 72).apply {
            insertProvider72(1, "Xtream alias", "XTREAM_CODES_API")
            insertProvider72(2, "Stalker alias", "STB")
            insertProvider72(3, "Playlist alias", "PLAYLIST")
            insertProvider72(4, "Unknown playlist", "vendor_specific_playlist")
            execSQL("UPDATE providers SET server_url='https://x.test',username='alice' WHERE id=1")
            execSQL("UPDATE providers SET server_url='https://s.test',stalker_mac_address='00:11:22:33:44:55' WHERE id=2")
            execSQL("UPDATE providers SET m3u_url='https://m.test/one.m3u' WHERE id=3")
            execSQL("UPDATE providers SET m3u_url='https://m.test/two.m3u' WHERE id=4")
            close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            73,
            true,
            StreamVaultDatabase.MIGRATION_72_73
        )

        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id=1 AND type='XTREAM_CODES'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id=2 AND type='STALKER_PORTAL'"))
        assertEquals(2, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id IN (3,4) AND type='M3U'"))
        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM provider_configs"))
        migrated.close()
    }

    @Test
    fun migrate72To74_rebuildsStableProvidersWithoutCatalogOrForeignKeyLoss() {
        val name = "streamvault-72-74-stable-provider-identity"
        migrationTestHelper.createDatabase(name, 72).apply {
            insertProvider72(1, "Xtream", "XTREAM_CODES")
            insertProvider72(2, "M3U", "M3U")
            insertProvider72(3, "Stalker", "STALKER_PORTAL")
            insertProvider72(4, "Jellyfin", "JELLYFIN")
            execSQL("UPDATE providers SET server_url='https://x.test',username='alice',password='enc:v1:xtream',is_active=1,guide_source_policy='EXTERNAL_ONLY',channel_logo_source_policy='EPG_PREFERRED',status='ACTIVE',last_synced_at=123,created_at=456 WHERE id=1")
            execSQL("UPDATE providers SET server_url='https://m.test/list.m3u',m3u_url='https://m.test/list.m3u',epg_url='https://m.test/epg.xml' WHERE id=2")
            execSQL("UPDATE providers SET server_url='https://s.test',username='bob',password='enc:v1:stalker',stalker_mac_address='00:11:22:33:44:55',stalker_configuration_generation=4,status='PARTIAL' WHERE id=3")
            execSQL("UPDATE providers SET server_url='https://j.test',username='carol',password='enc:v1:jellyfin' WHERE id=4")
            insertChannel72(10, 100, "News", 1)
            execSQL("INSERT INTO categories(id,category_id,name,type,provider_id,is_adult,is_user_protected,sync_fingerprint) VALUES(20,200,'Live','LIVE',1,0,0,'category-20')")
            insertStalkerPortalState72(3)
            execSQL("INSERT INTO provider_config_revisions(provider_id,revision,config_json,state,attempt_count,last_error,created_at,updated_at) VALUES(1,7,'{\"legacy\":true}','PENDING',2,NULL,700,701)")
            close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            74,
            true,
            StreamVaultDatabase.MIGRATION_72_73,
            StreamVaultDatabase.MIGRATION_73_74
        )

        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM providers"))
        assertEquals(4, countRows(migrated, "SELECT COUNT(*) FROM provider_configs"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id=1 AND is_active=1 AND status='ACTIVE' AND last_synced_at=123 AND created_at=456"))
        assertEquals(0, countRows(migrated, "SELECT COUNT(*) FROM pragma_table_info('providers') WHERE name IN ('server_url','username','password','stalker_mac_address')"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM provider_configs WHERE provider_id=1 AND guide_source_policy='EXTERNAL_ONLY' AND channel_logo_source_policy='EPG_PREFERRED'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channels WHERE id=10 AND provider_id=1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM categories WHERE id=20 AND provider_id=1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM stalker_portal_state WHERE provider_id=3 AND configuration_generation=4"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM provider_config_revisions WHERE provider_id=1 AND revision=7 AND state='PENDING' AND config_json='{\"legacy\":true}'"))
        assertEquals(0, countRows(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        migrated.close()
    }

    @Test
    fun migrate62To77_preservesPopulatedProviderCatalogAndUserStateAcrossFullChain() {
        val name = "streamvault-62-77-populated-full-chain"
        migrationTestHelper.createDatabase(name, 62).apply {
            execSQL(
                """
                INSERT INTO providers (
                    id, name, type, server_url, username, password, m3u_url, epg_url,
                    http_user_agent, http_headers, stalker_mac_address, stalker_device_profile,
                    stalker_device_timezone, stalker_device_locale, stalker_serial_number,
                    stalker_device_id, stalker_device_id2, stalker_signature, stalker_advanced_options_json,
                    stalker_auth_mode, stalker_portal_profile, stalker_portal_fingerprint,
                    stalker_mag_preset, stalker_last_bootstrap_recipe, stalker_endpoint_preference,
                    stalker_cookie_mode, stalker_playback_backend_hint, stalker_last_playback_mode,
                    stalker_credentials_required, stalker_mac_required, stalker_uses_temp_links,
                    stalker_module_restricted, stalker_strict_fingerprint_required,
                    stalker_recipe_fallback_used, stalker_recipe_rediscovery_attempts,
                    is_active, max_connections, expiration_date, api_version,
                    allowed_output_formats_json, epg_sync_mode, guide_source_policy,
                    channel_logo_source_policy, xtream_fast_sync_enabled, xtream_live_sync_mode,
                    m3u_vod_classification_enabled, status, last_synced_at, created_at
                ) VALUES (
                    1, 'Legacy Stalker', 'STALKER_PORTAL', 'https://portal.invalid', '', '', '', '',
                    '', '', '00:1A:79:00:00:01', 'MAG250', 'Asia/Jerusalem', 'en', '', '', '', '', '',
                    'AUTO', 'MAG_BASIC', 'BASIC_MAC', 'GENERIC_SAFE', 'GENERIC_SAFE', 'AUTO',
                    'NONE', 'AUTO', NULL, 0, 1, 0, 0, 0, 0, 0,
                    1, 1, NULL, NULL, '[]', 'BACKGROUND', 'AUTO', 'SUPPLIER_PREFERRED',
                    0, 'AUTO', 0, 'ACTIVE', 1234, 5678
                )
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO categories (id, category_id, name, parent_id, type, provider_id, is_adult, is_user_protected, sync_fingerprint) " +
                    "VALUES (10, 100, 'Protected category', NULL, 'LIVE', 1, 0, 1, 'category-v62')"
            )
            execSQL(
                """
                INSERT INTO channels (
                    id, stream_id, name, stream_url, number, catch_up_supported, catch_up_days,
                    provider_id, is_adult, is_user_protected, logical_group_id, error_count, sync_fingerprint
                ) VALUES (11, 101, 'Protected channel', 'https://stream.invalid/live', 101, 0, 0, 1, 0, 1, '', 0, 'channel-v62')
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO channel_preferences (id, channel_id, aspect_ratio, audio_video_offset_ms, updated_at) " +
                    "VALUES (12, 11, '16:9', 250, 9000)"
            )
            execSQL(
                """
                INSERT INTO movies (
                    stream_id, name, stream_url, duration_seconds, rating, provider_id,
                    watch_progress, watch_count, last_watched_at, is_adult, is_user_protected,
                    sync_fingerprint, added_at, cache_state, detail_hydrated_at, remote_stale_at
                ) VALUES (99, 'Cached Movie', 'https://stream.invalid/movie', 7200, 8.0, 1, 321, 2, 999, 0, 1, 'movie-v62', 1, 'HYDRATED', 2, 0)
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO virtual_groups (id, provider_id, name, icon_emoji, position, created_at, content_type) " +
                    "VALUES (30, 1, 'Legacy group', '⭐', 4, 1000, 'MOVIE')"
            )
            execSQL(
                "INSERT INTO favorites (id, provider_id, content_id, content_type, position, group_id, group_key, added_at) " +
                    "VALUES (31, 1, 99, 'MOVIE', 2, 30, 30, 100)"
            )
            execSQL(
                """
                INSERT INTO playback_history (
                    id, content_id, content_type, provider_id, title, stream_url,
                    resume_position_ms, total_duration_ms, last_watched_at, watch_count, watched_status
                ) VALUES (40, 99, 'MOVIE', 1, 'Cached Movie', 'https://stream.invalid/movie', 321000, 7200000, 999, 2, 'IN_PROGRESS')
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO search_history (id, query, content_scope, provider_id, used_at, use_count) " +
                    "VALUES (50, 'legacy search', 'MOVIES', 1, 5000, 3)"
            )
            execSQL(
                """
                INSERT INTO sync_metadata (
                    provider_id, last_live_sync, last_live_success, last_movie_sync,
                    last_series_sync, last_series_success, last_epg_sync, last_epg_success,
                    last_movie_attempt, last_movie_success, last_movie_partial, live_count,
                    movie_count, series_count, epg_count, last_sync_status, movie_sync_mode,
                    movie_warnings_count, movie_catalog_stale, live_avoid_full_until,
                    movie_avoid_full_until, series_avoid_full_until,
                    live_sequential_failures_remembered, live_healthy_sync_streak,
                    movie_parallel_failures_remembered, movie_healthy_sync_streak,
                    series_sequential_failures_remembered, series_healthy_sync_streak
                ) VALUES (
                    1, 11, 12, 22, 33, 34, 44, 45, 55, 56, 57, 1, 2, 3, 4,
                    'SUCCESS', 'FULL', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO movie_category_hydration (
                    provider_id, category_id, last_hydrated_at, item_count, last_status,
                    last_error, last_loaded_page, last_attempted_page, last_successful_page,
                    total_pages, is_complete, page_size, retry_after_ms, failure_count,
                    retry_budget_remaining, last_page_fingerprint
                ) VALUES (1, 10, 1000, 1, 'SUCCESS', NULL, 1, 1, 1, 3, 0, 18, 0, 0, 3, 'page-v62')
                """.trimIndent()
            )
            close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            StreamVaultDatabaseMigrationRegistry.CURRENT_VERSION,
            true,
            *StreamVaultDatabaseMigrationRegistry.all
                .filter { migration -> migration.startVersion >= 62 }
                .toTypedArray()
        )

        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM providers WHERE id = 1 AND type = 'STALKER_PORTAL'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM categories WHERE id = 10 AND is_user_protected = 1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channels WHERE id = 11 AND is_user_protected = 1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM channel_preferences WHERE channel_id = 11 AND audio_video_offset_ms = 250"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM movies WHERE stream_id = 99 AND watch_progress = 321 AND is_user_protected = 1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM virtual_groups WHERE id = 30 AND name = 'Legacy group'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM favorites WHERE id = 31 AND group_id = 30"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM playback_history WHERE id = 40 AND resume_position_ms = 321000"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM search_history WHERE id = 50 AND use_count = 3"))
        assertEquals(12, countRows(migrated, "SELECT last_live_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(45, countRows(migrated, "SELECT last_epg_success FROM sync_metadata WHERE provider_id = 1"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM movie_category_hydration WHERE provider_id = 1 AND category_id = 10 AND last_page_fingerprint = 'page-v62'"))
        assertEquals(0, countRows(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'backup_restore_jobs'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'backup_restore_items'"))
        migrated.close()
    }

    @Test
    fun migrate76To77_createsDurableBackupRestoreLedger() {
        val name = "streamvault-76-77-backup-restore-ledger"
        migrationTestHelper.createDatabase(name, 76).close()

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            name,
            77,
            true,
            *StreamVaultDatabaseMigrationRegistry.all.toTypedArray()
        )

        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='backup_restore_jobs'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='backup_restore_items'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM pragma_foreign_key_list('backup_restore_items') WHERE `table`='backup_restore_jobs' AND on_delete='CASCADE'"))
        assertEquals(1, countRows(migrated, "SELECT COUNT(*) FROM pragma_index_list('backup_restore_items') WHERE name='index_backup_restore_items_job_id_section_stable_reference_key' AND `unique`=1"))
        migrated.close()
    }

    private fun SupportSQLiteDatabase.insertProvider72(id: Long, name: String, type: String) {
        execSQL(
            """
            INSERT INTO providers (
                id,name,type,server_url,username,password,m3u_url,epg_url,http_user_agent,http_headers,
                stalker_mac_address,stalker_device_profile,stalker_device_timezone,stalker_device_locale,
                stalker_serial_number,stalker_device_id,stalker_device_id2,stalker_signature,
                stalker_advanced_options_json,stalker_auth_mode,stalker_portal_profile,
                stalker_portal_fingerprint,stalker_mag_preset,stalker_protocol_preference,
                stalker_transport_mode,stalker_transport_origin,stalker_tls_spki_sha256,
                stalker_transport_consent_at,stalker_configuration_generation,stalker_discovery_summary,
                stalker_capabilities_json,stalker_requested_profile_id,stalker_learned_profile_id,
                stalker_profile_revision,stalker_profile_verification,stalker_protocol_family,
                stalker_last_bootstrap_recipe,stalker_endpoint_preference,stalker_cookie_mode,
                stalker_playback_backend_hint,stalker_last_playback_mode,stalker_credentials_required,
                stalker_mac_required,stalker_uses_temp_links,stalker_module_restricted,
                stalker_strict_fingerprint_required,stalker_recipe_fallback_used,
                stalker_recipe_rediscovery_attempts,is_active,max_connections,expiration_date,api_version,
                allowed_output_formats_json,epg_sync_mode,stalker_catalog_mode,guide_source_policy,
                channel_logo_source_policy,xtream_fast_sync_enabled,xtream_live_sync_mode,
                m3u_vod_classification_enabled,catalog_layout,catalog_layout_detection_version,status,
                last_synced_at,created_at
            ) VALUES (
                ?,?,?,?,?,'','','','','',?,'','','','','','','','','AUTO','MAG_BASIC','BASIC_MAC',
                'GENERIC_SAFE','AUTO','AUTO_STRICT','','',0,0,'','','auto','',0,'UNVERIFIED',
                'CLASSIC_MAG','GENERIC_SAFE','AUTO','NONE','AUTO',NULL,0,1,0,0,0,0,0,0,1,NULL,NULL,
                '[]','BACKGROUND','ON_DEMAND','AUTO','SUPPLIER_PREFERRED',1,'AUTO',0,'SPLIT',0,
                'UNKNOWN',0,0
            )
            """.trimIndent(),
            arrayOf<Any?>(id, name, type, "seed://$id", "seed-$id", "seed-mac-$id")
        )
    }

    private fun SupportSQLiteDatabase.insertChannel72(
        id: Long,
        streamId: Long,
        name: String,
        providerId: Long
    ) {
        execSQL(
            """
            INSERT INTO channels (
                id,stream_id,name,stream_url,number,catch_up_supported,catch_up_days,provider_id,
                is_adult,is_user_protected,logical_group_id,error_count,sync_fingerprint
            ) VALUES (?,?,?,'https://stream.test/live',0,0,0,?,0,0,'',0,'channel-test')
            """.trimIndent(),
            arrayOf<Any?>(id, streamId, name, providerId)
        )
    }

    private fun SupportSQLiteDatabase.insertStalkerPortalState72(providerId: Long) {
        execSQL(
            """
            INSERT INTO stalker_portal_state (
                provider_id,working_endpoint,safe_metadata_concurrency,stress_cooldown_until,
                endpoint_health_json,endpoint_failed_until,validated_at,schema_version
            ) VALUES (?,'https://s.test/load.php',1,0,'{}',0,100,1)
            """.trimIndent(),
            arrayOf(providerId)
        )
    }

    private fun countRows(db: SupportSQLiteDatabase, sql: String): Int {
        val normalizedSql = sql.trim().replace(Regex("\\s+"), " ")
        tableValuedPragmaQuery(normalizedSql, "table_info")?.let { (table, whereClause) ->
            return countPragmaRows(db, "table_info", table, whereClause)
        }
        tableValuedPragmaQuery(normalizedSql, "foreign_key_list")?.let { (table, whereClause) ->
            return countPragmaRows(db, "foreign_key_list", table, whereClause)
        }
        tableValuedPragmaQuery(normalizedSql, "index_list")?.let { (table, whereClause) ->
            return countPragmaRows(db, "index_list", table, whereClause)
        }
        if (normalizedSql.equals("SELECT COUNT(*) FROM pragma_foreign_key_check", ignoreCase = true)) {
            return countPragmaRows(db, "foreign_key_check", null, null)
        }

        db.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    private fun tableValuedPragmaQuery(sql: String, pragma: String): Pair<String, String?>? {
        val match = Regex(
            "SELECT COUNT\\(\\*\\) FROM pragma_$pragma\\('([^']+)'\\)(?: WHERE (.+))?",
            RegexOption.IGNORE_CASE
        ).matchEntire(sql) ?: return null
        return match.groupValues[1] to match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    private fun countPragmaRows(
        db: SupportSQLiteDatabase,
        pragma: String,
        table: String?,
        whereClause: String?
    ): Int {
        val sql = if (table == null) {
            "PRAGMA $pragma"
        } else {
            "PRAGMA $pragma('$table')"
        }
        var count = 0
        db.query(sql).use { cursor ->
            while (cursor.moveToNext()) {
                if (whereClause == null || matchesPragmaWhere(cursor, whereClause)) {
                    count++
                }
            }
        }
        return count
    }

    private fun matchesPragmaWhere(cursor: Cursor, whereClause: String): Boolean {
        return whereClause.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE)).all { condition ->
            val normalizedCondition = condition.trim()
            val inMatch = Regex(
                "`?([A-Za-z_][A-Za-z0-9_]*)`?\\s+IN\\s*\\((.*)\\)",
                RegexOption.IGNORE_CASE
            ).matchEntire(normalizedCondition)
            if (inMatch != null) {
                val actualValue = pragmaColumnValue(cursor, inMatch.groupValues[1])
                inMatch.groupValues[2]
                    .split(Regex("\\s*,\\s*"))
                    .map(::parseSqlLiteral)
                    .contains(actualValue)
            } else {
                val equalsMatch = Regex(
                    "`?([A-Za-z_][A-Za-z0-9_]*)`?\\s*=\\s*(.+)",
                    RegexOption.IGNORE_CASE
                ).matchEntire(normalizedCondition)
                    ?: error("Unsupported PRAGMA assertion: $condition")
                pragmaColumnValue(cursor, equalsMatch.groupValues[1]) ==
                    parseSqlLiteral(equalsMatch.groupValues[2])
            }
        }
    }

    private fun pragmaColumnValue(cursor: Cursor, columnName: String): String? {
        val columnIndex = cursor.getColumnIndex(columnName)
        if (columnIndex < 0 || cursor.isNull(columnIndex)) return null
        return cursor.getString(columnIndex)
    }

    private fun parseSqlLiteral(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.equals("NULL", ignoreCase = true)) return null
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        return value
    }

    private fun exists(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Boolean {
        db.query(sql).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun firstLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }
}
