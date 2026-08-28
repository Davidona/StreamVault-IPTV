@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "TYPE_INTERSECTION_AS_REIFIED")

package com.streamvault.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.net.URI

/** Provider runtime, workflow, backup, plugin ownership, and typed-configuration migrations (v49 through v75). Executable bodies live here, outside the Room schema declaration. */
internal object FeatureMigrationsV49To75 {
    val MIGRATION_49_50 = object : Migration(49, 50) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS xtream_live_onboarding_state (
                            provider_id INTEGER NOT NULL,
                            provider_type TEXT NOT NULL DEFAULT 'XTREAM_CODES',
                            content_type TEXT NOT NULL DEFAULT 'LIVE',
                            phase TEXT NOT NULL DEFAULT 'STARTING',
                            staged_session_id INTEGER,
                            import_strategy TEXT,
                            next_category_index INTEGER NOT NULL DEFAULT 0,
                            accepted_row_count INTEGER NOT NULL DEFAULT 0,
                            staged_flush_count INTEGER NOT NULL DEFAULT 0,
                            last_error TEXT,
                            created_at INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            completed_at INTEGER,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_provider_id ON xtream_live_onboarding_state(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_phase ON xtream_live_onboarding_state(phase)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_updated_at ON xtream_live_onboarding_state(updated_at)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_xtream_live_onboarding_state_staged_session_id ON xtream_live_onboarding_state(staged_session_id)")
    
                    validateForeignKeys(database, "xtream_live_onboarding_state")
                }
            }

    val MIGRATION_50_51 = object : Migration(50, 51) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_tier TEXT")
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_batch_size INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_strategy TEXT")
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_low_memory INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_memory_class_mb INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE xtream_live_onboarding_state ADD COLUMN sync_profile_available_mem_mb INTEGER NOT NULL DEFAULT 0")
                    validateForeignKeys(database, "xtream_live_onboarding_state")
                }
            }

    val MIGRATION_51_52 = object : Migration(51, 52) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN xtream_live_sync_mode TEXT NOT NULL DEFAULT 'AUTO'")
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_52_53 = object : Migration(52, 53) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addStalkerHardeningColumns(database, "movie_category_hydration")
                    addStalkerHardeningColumns(database, "series_category_hydration")
                    validateForeignKeys(database, "movie_category_hydration")
                    validateForeignKeys(database, "series_category_hydration")
                }
            }

    val MIGRATION_53_54 = object : Migration(53, 54) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_serial_number TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_id TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_device_id2 TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_signature TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_auth_mode TEXT NOT NULL DEFAULT 'AUTO'")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_portal_profile TEXT NOT NULL DEFAULT 'MAG_BASIC'")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_last_playback_mode TEXT")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_credentials_required INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_mac_required INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_uses_temp_links INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE providers ADD COLUMN stalker_module_restricted INTEGER NOT NULL DEFAULT 0")
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_54_55 = object : Migration(54, 55) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Schema 55 is identical to 54; the complete identity/auth shape shipped in 54.
                }
            }
    
            private fun addStalkerHardeningColumns(database: SupportSQLiteDatabase, tableName: String) {
                database.execSQL("ALTER TABLE $tableName ADD COLUMN last_attempted_page INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN last_successful_page INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN retry_after_ms INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN retry_budget_remaining INTEGER NOT NULL DEFAULT 3")
                database.execSQL("ALTER TABLE $tableName ADD COLUMN last_page_fingerprint TEXT")
            }
    
            private fun addColumnIfMissing(
                database: SupportSQLiteDatabase,
                tableName: String,
                columnName: String,
                columnDefinition: String
            ) {
                if (tableHasColumn(database, tableName, columnName)) {
                    return
                }
                database.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
            }
    
            private fun backfillTypedProviderSnapshots(database: SupportSQLiteDatabase) {
                val canonicalTypes = mutableListOf<Pair<Long, String>>()
                database.query("SELECT id, type FROM providers ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) {
                        canonicalTypes += cursor.getLong(0) to canonicalLegacyProviderType(cursor.getString(1))
                    }
                }
                canonicalTypes.forEach { (providerId, type) ->
                    database.execSQL(
                        "UPDATE providers SET type=? WHERE id=?",
                        arrayOf<Any?>(type, providerId)
                    )
                }

                database.query("SELECT * FROM providers ORDER BY id").use { cursor ->
                    fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
                    fun long(name: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                    fun int(name: String): Int = cursor.getInt(cursor.getColumnIndexOrThrow(name))
                    fun nullableText(name: String): String? {
                        val index = cursor.getColumnIndexOrThrow(name)
                        return if (cursor.isNull(index)) null else cursor.getString(index)
                    }
                    fun nullableLong(name: String): Long? {
                        val index = cursor.getColumnIndexOrThrow(name)
                        return if (cursor.isNull(index)) null else cursor.getLong(index)
                    }
    
                    while (cursor.moveToNext()) {
                        val providerId = long("id")
                        val type = text("type")
                        val generation = if (type == "STALKER_PORTAL") long("stalker_configuration_generation") else 0L
                        val config = when (type) {
                            "XTREAM_CODES" -> JSONObject()
                                .put("serverUrl", text("server_url"))
                                .put("username", text("username"))
                                .put("password", text("password"))
                                .put("httpUserAgent", text("http_user_agent"))
                                .put("httpHeaders", text("http_headers"))
                                .put("epgSyncMode", text("epg_sync_mode"))
                                .put("guideSourcePolicy", text("guide_source_policy"))
                                .put("channelLogoSourcePolicy", text("channel_logo_source_policy"))
                                .put("fastSyncEnabled", int("xtream_fast_sync_enabled") != 0)
                                .put("liveSyncMode", text("xtream_live_sync_mode"))
                                .put("schemaVersion", 1)
                            "M3U" -> JSONObject()
                                .put("playlistUrl", text("m3u_url").ifBlank { text("server_url") })
                                .put("epgUrl", text("epg_url"))
                                .put("httpUserAgent", text("http_user_agent"))
                                .put("httpHeaders", text("http_headers"))
                                .put("epgSyncMode", text("epg_sync_mode"))
                                .put("guideSourcePolicy", text("guide_source_policy"))
                                .put("channelLogoSourcePolicy", text("channel_logo_source_policy"))
                                .put("vodClassificationEnabled", int("m3u_vod_classification_enabled") != 0)
                                .put("schemaVersion", 1)
                            "STALKER_PORTAL" -> JSONObject()
                                .put("portalUrl", text("server_url"))
                                .put("device", JSONObject()
                                    .put("macAddress", text("stalker_mac_address"))
                                    .put("deviceProfile", text("stalker_device_profile"))
                                    .put("timezone", text("stalker_device_timezone"))
                                    .put("locale", text("stalker_device_locale"))
                                    .put("serialNumber", text("stalker_serial_number"))
                                    .put("deviceId", text("stalker_device_id"))
                                    .put("deviceId2", text("stalker_device_id2"))
                                    .put("signature", text("stalker_signature")))
                                .put("username", text("username"))
                                .put("password", text("password"))
                                .put("httpUserAgent", text("http_user_agent"))
                                .put("httpHeaders", text("http_headers"))
                                .put("advancedOptionsJson", text("stalker_advanced_options_json"))
                                .put("authMode", text("stalker_auth_mode"))
                                .put("requestedProfileId", text("stalker_requested_profile_id"))
                                .put("protocolPreference", text("stalker_protocol_preference"))
                                .put("transportGrant", legacyTransportGrant(cursor))
                                .put("epgSyncMode", text("epg_sync_mode"))
                                .put("catalogMode", text("stalker_catalog_mode"))
                                .put("guideSourcePolicy", text("guide_source_policy"))
                                .put("channelLogoSourcePolicy", text("channel_logo_source_policy"))
                                .put("schemaVersion", 1)
                            "JELLYFIN" -> JSONObject()
                                .put("serverUrl", text("server_url"))
                                .put("username", text("username"))
                                .put("credential", text("password"))
                                .put("schemaVersion", 1)
                            else -> error("Provider type was not canonicalized: $type")
                        }
                        val identity = when (type) {
                            "XTREAM_CODES", "JELLYFIN" -> listOf(type, migrationNormalizeOrigin(text("server_url")), text("username").trim())
                            "M3U" -> listOf(type, text("m3u_url").ifBlank { text("server_url") }.trim())
                            "STALKER_PORTAL" -> listOf(
                                type,
    
                                migrationNormalizeOrigin(text("server_url")),
                                text("stalker_mac_address").trim().uppercase(),
                                text("username").trim()
                            )
                            else -> error("unreachable")
                        }
                        val canonicalIdentityKey = migrationIdentityKey(identity)
                        val identityOwner = database.query(
                            "SELECT provider_id FROM provider_configs WHERE identity_key=? LIMIT 1",
                            arrayOf(canonicalIdentityKey)
                        ).use { ownerCursor ->
                            if (ownerCursor.moveToFirst()) ownerCursor.getLong(0) else null
                        }
                        val identityKey = if (identityOwner == null || identityOwner == providerId) {
                            canonicalIdentityKey
                        } else {
                            disambiguatedMigrationIdentityKey(canonicalIdentityKey, providerId)
                        }
                        val updatedAt = long("last_synced_at").takeIf { it > 0L } ?: long("created_at")
    
                        database.execSQL(
                            "INSERT INTO provider_configs(provider_id,type,schema_version,configuration_generation,identity_key,encrypted_config_json,updated_at) VALUES(?,?,?,?,?,?,?)",
                            arrayOf<Any?>(providerId, type, 1, generation, identityKey, config.toString(), updatedAt)
                        )
                        database.execSQL(
                            "INSERT INTO provider_account_runtime(provider_id,max_connections,expiration_date,api_version,allowed_output_formats_json,catalog_layout,catalog_layout_detection_version,observed_at) VALUES(?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                providerId,
                                int("max_connections"),
                                nullableLong("expiration_date"),
                                nullableText("api_version"),
                                text("allowed_output_formats_json"),
                                text("catalog_layout"),
                                int("catalog_layout_detection_version"),
                                updatedAt
                            )
                        )
                        if (type == "STALKER_PORTAL") {
                            database.execSQL(
                                "UPDATE stalker_portal_state SET configuration_generation=?, learning_json=?, observation_source='DISCOVERY', observed_at=CASE WHEN validated_at > 0 THEN validated_at ELSE ? END WHERE provider_id=?",
                                arrayOf<Any?>(
                                    generation,
                                    legacyStalkerLearning(cursor, generation, updatedAt).toString(),
                                    updatedAt,
                                    providerId
                                )
                            )
                        }
                    }
                }
            }

    val MIGRATION_55_56 = object : Migration(55, 56) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_portal_fingerprint TEXT NOT NULL DEFAULT 'BASIC_MAC'"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_mag_preset TEXT NOT NULL DEFAULT 'GENERIC_SAFE'"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_last_bootstrap_recipe TEXT NOT NULL DEFAULT 'GENERIC_SAFE'"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_strict_fingerprint_required INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_recipe_fallback_used INTEGER NOT NULL DEFAULT 0"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_recipe_rediscovery_attempts INTEGER NOT NULL DEFAULT 0"
                    )
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_56_57 = object : Migration(56, 57) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_endpoint_preference TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_cookie_mode TEXT NOT NULL DEFAULT 'NONE'"
                    )
                    database.execSQL(
                        "ALTER TABLE providers ADD COLUMN stalker_playback_backend_hint TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                    validateForeignKeys(database, "providers")
                }
            }
    
            /**
             * Migration 57 → 58: add downloads table for tracking in-app download operations.
             */

    val MIGRATION_57_58 = object : Migration(57, 58) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS downloads (
                            id TEXT NOT NULL PRIMARY KEY,
                            provider_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            content_id INTEGER NOT NULL,
                            content_name TEXT NOT NULL,
                            stream_url TEXT NOT NULL,
                            source_stream_url TEXT,
                            source_stream_id INTEGER,
                            container_extension TEXT,
                            poster_url TEXT,
                            output_uri TEXT,
                            output_display_path TEXT,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            bytes_written INTEGER NOT NULL DEFAULT 0,
                            total_bytes INTEGER,
                            created_at INTEGER NOT NULL,
                            completed_at INTEGER,
                            failure_reason TEXT,
                            series_id INTEGER,
                            season_number INTEGER,
                            episode_number INTEGER
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_provider_id ON downloads(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_content_type_content_id ON downloads(content_type, content_id)")
                }
            }

    val MIGRATION_58_59 = object : Migration(58, 59) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE downloads ADD COLUMN supports_resume INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE downloads ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                }
            }

    val MIGRATION_59_60 = object : Migration(59, 60) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(database, "downloads", "source_stream_url", "TEXT")
    
                    addColumnIfMissing(database, "downloads", "source_stream_id", "INTEGER")
                    addColumnIfMissing(database, "downloads", "container_extension", "TEXT")
                }
            }

    val MIGRATION_60_61 = object : Migration(60, 61) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        tableName = "providers",
                        columnName = "stalker_advanced_options_json",
                        columnDefinition = "TEXT NOT NULL DEFAULT ''"
                    )
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_61_62 = object : Migration(61, 62) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        tableName = "providers",
                        columnName = "guide_source_policy",
                        columnDefinition = "TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                    addColumnIfMissing(
                        database,
                        tableName = "providers",
                        columnName = "channel_logo_source_policy",
                        columnDefinition = "TEXT NOT NULL DEFAULT 'SUPPLIER_PREFERRED'"
                    )
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_62_63 = object : Migration(62, 63) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        tableName = "providers",
                        columnName = "stalker_catalog_mode",
                        columnDefinition = "TEXT NOT NULL DEFAULT 'ON_DEMAND'"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_index_jobs (
                            provider_id INTEGER NOT NULL,
                            section TEXT NOT NULL,
                            state TEXT NOT NULL DEFAULT 'DISABLED',
                            total_categories INTEGER NOT NULL DEFAULT 0,
                            completed_categories INTEGER NOT NULL DEFAULT 0,
                            next_category_index INTEGER NOT NULL DEFAULT 0,
                            failed_categories INTEGER NOT NULL DEFAULT 0,
                            indexed_rows INTEGER NOT NULL DEFAULT 0,
                            skipped_malformed_rows INTEGER NOT NULL DEFAULT 0,
                            deleted_pruned_rows INTEGER NOT NULL DEFAULT 0,
                            last_error TEXT,
                            last_attempt_at INTEGER NOT NULL DEFAULT 0,
                            last_success_at INTEGER NOT NULL DEFAULT 0,
                            updated_at INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(provider_id, section),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_provider_id ON stalker_index_jobs(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_state ON stalker_index_jobs(state)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_updated_at ON stalker_index_jobs(updated_at)")
                    database.execSQL(
                        """
                        INSERT OR REPLACE INTO stalker_index_jobs (
                            provider_id, section, state, total_categories, completed_categories,
                            next_category_index, failed_categories, indexed_rows, skipped_malformed_rows,
                            deleted_pruned_rows, last_error,
                            last_attempt_at, last_success_at, updated_at
                        )
                        SELECT j.provider_id, j.section, 'DISABLED', j.total_categories, j.completed_categories,
                               j.next_category_index, j.failed_categories, j.indexed_rows, j.skipped_malformed_rows,
                               j.deleted_pruned_rows, j.last_error,
                               j.last_attempt_at, j.last_success_at, j.updated_at
                        FROM xtream_index_jobs j
                        INNER JOIN providers p ON p.id = j.provider_id
                        WHERE p.type = 'STALKER_PORTAL' AND j.section IN ('MOVIE', 'SERIES')
                        """.trimIndent()
                    )
                    database.execSQL(
                        "DELETE FROM xtream_index_jobs WHERE provider_id IN (SELECT id FROM providers WHERE type = 'STALKER_PORTAL')"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_portal_state (
                            provider_id INTEGER NOT NULL,
                            working_endpoint TEXT,
                            bootstrap_recipe TEXT,
                            bulk_live_supported INTEGER,
                            bulk_live_category_fidelity INTEGER,
                            movie_wildcard_supported INTEGER,
                            series_wildcard_supported INTEGER,
                            epg_supported INTEGER,
                            safe_metadata_concurrency INTEGER NOT NULL,
                            stress_cooldown_until INTEGER NOT NULL,
                            endpoint_health_json TEXT NOT NULL,
                            endpoint_failed_until INTEGER NOT NULL,
                            validated_at INTEGER NOT NULL,
                            schema_version INTEGER NOT NULL,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_stalker_portal_state_provider_id ON stalker_portal_state(provider_id)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_remote_identities (
                            provider_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            raw_id TEXT NOT NULL,
                            surrogate_id INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, content_type, raw_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_remote_identities_provider_id ON stalker_remote_identities(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_stalker_remote_identities_provider_id_content_type_surrogate_id ON stalker_remote_identities(provider_id, content_type, surrogate_id)")
                    validateForeignKeys(database, "providers", "stalker_index_jobs", "stalker_portal_state", "stalker_remote_identities")
                }
            }

    val MIGRATION_63_64 = object : Migration(63, 64) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(database, "providers", "stalker_protocol_preference", "TEXT NOT NULL DEFAULT 'AUTO'")
                    addColumnIfMissing(database, "providers", "stalker_requested_profile_id", "TEXT NOT NULL DEFAULT 'auto'")
                    addColumnIfMissing(database, "providers", "stalker_learned_profile_id", "TEXT NOT NULL DEFAULT ''")
                    addColumnIfMissing(database, "providers", "stalker_profile_revision", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "providers", "stalker_profile_verification", "TEXT NOT NULL DEFAULT 'UNVERIFIED'")
                    addColumnIfMissing(database, "providers", "stalker_protocol_family", "TEXT NOT NULL DEFAULT 'CLASSIC_MAG'")
                    database.execSQL(
    
                        """
                        UPDATE providers SET stalker_requested_profile_id = CASE stalker_mag_preset
                            WHEN 'MAG250_LEGACY' THEN 'classic.mag250.legacy'
                            WHEN 'MAG254_STRICT' THEN 'classic.mag254.strict'
                            WHEN 'MINISTRA_MODERN' THEN 'classic.mag322.modern'
                            ELSE 'classic.mag250.generic'
                        END,
                        stalker_learned_profile_id = CASE stalker_mag_preset
                            WHEN 'MAG250_LEGACY' THEN 'classic.mag250.legacy'
                            WHEN 'MAG254_STRICT' THEN 'classic.mag254.strict'
                            WHEN 'MINISTRA_MODERN' THEN 'classic.mag322.modern'
                            ELSE 'classic.mag250.generic'
                        END,
                        stalker_profile_revision = 1,
                        stalker_profile_verification = 'VERIFIED'
                        WHERE type = 'STALKER_PORTAL'
                        """.trimIndent()
                    )
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_64_65 = object : Migration(64, 65) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_transport_mode",
                        "TEXT NOT NULL DEFAULT 'AUTO_STRICT'"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_transport_origin",
                        "TEXT NOT NULL DEFAULT ''"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_tls_spki_sha256",
                        "TEXT NOT NULL DEFAULT ''"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_transport_consent_at",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_configuration_generation",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_discovery_summary",
                        "TEXT NOT NULL DEFAULT ''"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "stalker_capabilities_json",
                        "TEXT NOT NULL DEFAULT ''"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_discovery_staging (
                            discovery_id TEXT NOT NULL PRIMARY KEY,
                            provider_id INTEGER,
                            configuration_generation INTEGER NOT NULL,
                            sanitized_summary TEXT NOT NULL,
                            categories_json TEXT NOT NULL,
                            channels_json TEXT NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_stalker_discovery_staging_provider_id ON stalker_discovery_staging(provider_id)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_stalker_discovery_staging_created_at ON stalker_discovery_staging(created_at)"
                    )
                    // Existing installs used a global trust-all client. They deliberately migrate to
                    // strict verification and must consent in the foreground if their portal needs
                    // HTTP or invalid TLS.
                    database.execSQL(
                        """
                        UPDATE providers
                        SET stalker_transport_mode = 'AUTO_STRICT',
                            stalker_transport_origin = '',
                            stalker_tls_spki_sha256 = '',
                            stalker_transport_consent_at = 0
                        WHERE type = 'STALKER_PORTAL'
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        UPDATE providers
                        SET status = 'PARTIAL',
                            is_active = 0
                        WHERE type = 'STALKER_PORTAL'
                          AND lower(server_url) LIKE 'http://%'
                        """.trimIndent()
                    )
                    listOf(
                        imageUrlMigrationSql("movies", "poster_url"),
                        imageUrlMigrationSql("movies", "backdrop_url"),
                        imageUrlMigrationSql("series", "poster_url"),
                        imageUrlMigrationSql("series", "backdrop_url"),
                        imageUrlMigrationSql("episodes", "cover_url")
                    ).forEach(database::execSQL)
                    validateForeignKeys(database, "providers")
                }
            }

    val MIGRATION_65_66 = object : Migration(65, 66) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        "providers",
                        "catalog_layout",
                        "TEXT NOT NULL DEFAULT 'SPLIT'"
                    )
                    addColumnIfMissing(
                        database,
                        "providers",
                        "catalog_layout_detection_version",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    addColumnIfMissing(
                        database,
                        "categories",
                        "provider_order",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    addColumnIfMissing(
                        database,
    
                        "category_import_stage",
                        "provider_order",
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                    addColumnIfMissing(
                        database,
                        "series",
                        "catalog_origin",
                        "TEXT NOT NULL DEFAULT 'NATIVE'"
                    )
                    addColumnIfMissing(
                        database,
                        "series",
                        "episode_playback_template_url",
                        "TEXT"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS vod_category_hydration (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            last_loaded_page INTEGER NOT NULL DEFAULT 0,
                            last_attempted_page INTEGER NOT NULL DEFAULT 0,
                            last_successful_page INTEGER NOT NULL DEFAULT 0,
                            total_pages INTEGER NOT NULL DEFAULT 0,
                            page_size INTEGER NOT NULL DEFAULT 0,
                            item_count INTEGER NOT NULL DEFAULT 0,
                            is_complete INTEGER NOT NULL DEFAULT 0,
                            has_movies INTEGER NOT NULL DEFAULT 0,
                            has_series INTEGER NOT NULL DEFAULT 0,
                            last_hydrated_at INTEGER NOT NULL DEFAULT 0,
                            last_status TEXT NOT NULL DEFAULT 'IDLE',
                            last_error TEXT,
                            retry_after_ms INTEGER NOT NULL DEFAULT 0,
                            failure_count INTEGER NOT NULL DEFAULT 0,
                            retry_budget_remaining INTEGER NOT NULL DEFAULT 3,
                            last_page_fingerprint TEXT,
                            PRIMARY KEY(provider_id, category_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_vod_category_hydration_provider_id ON vod_category_hydration(provider_id)"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS vod_catalog_entries (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            raw_item_id TEXT NOT NULL,
                            item_type TEXT NOT NULL,
                            target_id INTEGER NOT NULL,
                            raw_page INTEGER NOT NULL,
                            raw_index INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, category_id, raw_item_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id ON vod_catalog_entries(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id_category_id_raw_page_raw_index ON vod_catalog_entries(provider_id, category_id, raw_page, raw_index)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id_item_type_target_id ON vod_catalog_entries(provider_id, item_type, target_id)")
                    validateForeignKeys(database, "providers", "vod_category_hydration", "vod_catalog_entries")
                }
            }
    
            /** Migration 66 -> 67: retain candidate provider edits until they are atomically promoted. */

    val MIGRATION_66_67 = object : Migration(66, 67) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("""CREATE TABLE IF NOT EXISTS plugin_provider_ownership (
                        package_name TEXT NOT NULL,
                        service_class_name TEXT NOT NULL,
                        manifest_id TEXT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(package_name, service_class_name, manifest_id),
                        FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_plugin_provider_ownership_provider_id ON plugin_provider_ownership(provider_id)")
                    database.execSQL("""CREATE TABLE IF NOT EXISTS provider_deletion_cleanup (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        target_id TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT
                    )""")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_deletion_cleanup_provider_id_action_target_id ON provider_deletion_cleanup(provider_id, action, target_id)")
                    addColumnIfMissing(database, "recording_runs", "exact_alarm_armed", "INTEGER NOT NULL DEFAULT 1")
                    addColumnIfMissing(database, "program_reminders", "exact_alarm_armed", "INTEGER NOT NULL DEFAULT 1")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS provider_config_revisions (
                            provider_id INTEGER NOT NULL,
                            revision INTEGER NOT NULL,
                            config_json TEXT NOT NULL,
                            state TEXT NOT NULL,
                            attempt_count INTEGER NOT NULL DEFAULT 0,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, revision),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_config_revisions_provider_id_state ON provider_config_revisions(provider_id, state)")
                    validateForeignKeys(database, "provider_config_revisions")
                }
            }
    
            /** Migration 67 -> 68: retain cross-store backup restore progress for safe retry. */

    val MIGRATION_67_68 = object : Migration(67, 68) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS backup_restore_checkpoints (
                            restore_key TEXT NOT NULL,
                            room_complete INTEGER NOT NULL DEFAULT 0,
                            preferences_complete INTEGER NOT NULL DEFAULT 0,
                            presets_complete INTEGER NOT NULL DEFAULT 0,
                            schedules_complete INTEGER NOT NULL DEFAULT 0,
                            state TEXT NOT NULL,
                            preference_snapshot_json TEXT,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(restore_key)
                        )
                        """.trimIndent()
                    )
                }
            }
    
            /** Migration 68 -> 69: make active download ownership durable and reclaimable. */

    val MIGRATION_68_69 = object : Migration(68, 69) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(database, "downloads", "owner_id", "TEXT")
                    addColumnIfMissing(database, "downloads", "owner_epoch", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "downloads", "heartbeat_at", "INTEGER")
                }
            }
    
            /** Migration 69 -> 70: make reminder delivery outcomes durable and recoverable. */

    val MIGRATION_69_70 = object : Migration(69, 70) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(database, "program_reminders", "delivery_state", "TEXT NOT NULL DEFAULT 'PENDING'")
                    addColumnIfMissing(database, "program_reminders", "delivery_attempt_token", "TEXT")
                    addColumnIfMissing(database, "program_reminders", "delivery_attempted_at", "INTEGER")
                    addColumnIfMissing(database, "program_reminders", "delivery_attempt_count", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "program_reminders", "delivery_failure_reason", "TEXT")
                    database.execSQL(
                        """
                        UPDATE program_reminders
                        SET delivery_state = CASE
                            WHEN notified_at IS NOT NULL THEN 'DELIVERED'
                            WHEN is_dismissed = 1 THEN 'DISMISSED'
                            ELSE 'PENDING'
    
                        END
                        """.trimIndent()
                    )
                }
            }
    
            /** Migration 70 -> 71: persist the provider workflow generation, lease, and phase ledger. */

    val MIGRATION_70_71 = object : Migration(70, 71) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS provider_workflows (
                            provider_id INTEGER NOT NULL,
                            generation INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            priority INTEGER NOT NULL,
                            force INTEGER NOT NULL,
                            current_phase TEXT,
                            lease_token TEXT,
                            lease_expires_at INTEGER,
                            heartbeat_at INTEGER,
                            progress_message TEXT,
                            last_error_code TEXT,
                            last_error_message TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            completed_at INTEGER,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflows_state ON provider_workflows(state)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflows_updated_at ON provider_workflows(updated_at)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflows_lease_expires_at ON provider_workflows(lease_expires_at)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS provider_workflow_phases (
                            provider_id INTEGER NOT NULL,
                            generation INTEGER NOT NULL,
                            phase TEXT NOT NULL,
                            state TEXT NOT NULL,
                            attempt_count INTEGER NOT NULL,
                            checkpoint TEXT,
                            last_error_code TEXT,
                            last_error_message TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            completed_at INTEGER,
                            PRIMARY KEY(provider_id, generation, phase),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflow_phases_provider_id ON provider_workflow_phases(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflow_phases_state ON provider_workflow_phases(state)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_workflow_phases_updated_at ON provider_workflow_phases(updated_at)")
                    validateForeignKeys(database, "provider_workflows", "provider_workflow_phases")
                }
            }
    
            /** Migration 71 -> 72: make offset-free XMLTV timestamp handling explicit per source. */

    val MIGRATION_71_72 = object : Migration(71, 72) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(database, "epg_sources", "timezone_policy", "TEXT NOT NULL DEFAULT 'REQUIRE_OFFSET'")
                    addColumnIfMissing(database, "epg_sources", "timezone_id", "TEXT")
                    addColumnIfMissing(database, "categories", "provider_order", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "category_import_stage", "provider_order", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "series", "catalog_origin", "TEXT NOT NULL DEFAULT 'NATIVE'")
                    addColumnIfMissing(database, "series", "episode_playback_template_url", "TEXT")
                    // The entity was absent from exported schemas v67-v71 and returns in v72.
                    // IF NOT EXISTS also preserves state for older paths where v62 created it.
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_portal_state (
                            provider_id INTEGER NOT NULL,
                            working_endpoint TEXT,
                            bootstrap_recipe TEXT,
                            bulk_live_supported INTEGER,
                            bulk_live_category_fidelity INTEGER,
                            movie_wildcard_supported INTEGER,
                            series_wildcard_supported INTEGER,
                            epg_supported INTEGER,
                            safe_metadata_concurrency INTEGER NOT NULL,
                            stress_cooldown_until INTEGER NOT NULL,
                            endpoint_health_json TEXT NOT NULL,
                            endpoint_failed_until INTEGER NOT NULL,
                            validated_at INTEGER NOT NULL,
                            schema_version INTEGER NOT NULL,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_stalker_portal_state_provider_id ON stalker_portal_state(provider_id)"
                    )
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_discovery_staging (
                            discovery_id TEXT NOT NULL,
                            provider_id INTEGER,
                            configuration_generation INTEGER NOT NULL,
                            sanitized_summary TEXT NOT NULL,
                            categories_json TEXT NOT NULL,
                            channels_json TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            PRIMARY KEY(discovery_id)
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_discovery_staging_provider_id ON stalker_discovery_staging(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_discovery_staging_created_at ON stalker_discovery_staging(created_at)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_index_jobs (
                            provider_id INTEGER NOT NULL,
                            section TEXT NOT NULL,
                            state TEXT NOT NULL,
                            total_categories INTEGER NOT NULL,
                            completed_categories INTEGER NOT NULL,
                            next_category_index INTEGER NOT NULL,
                            failed_categories INTEGER NOT NULL,
                            indexed_rows INTEGER NOT NULL,
                            skipped_malformed_rows INTEGER NOT NULL,
                            deleted_pruned_rows INTEGER NOT NULL,
                            last_error TEXT,
                            last_attempt_at INTEGER NOT NULL,
                            last_success_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, section),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_provider_id ON stalker_index_jobs(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_state ON stalker_index_jobs(state)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_index_jobs_updated_at ON stalker_index_jobs(updated_at)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS stalker_remote_identities (
                            provider_id INTEGER NOT NULL,
                            content_type TEXT NOT NULL,
                            raw_id TEXT NOT NULL,
                            surrogate_id INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, content_type, raw_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_stalker_remote_identities_provider_id ON stalker_remote_identities(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_stalker_remote_identities_provider_id_content_type_surrogate_id ON stalker_remote_identities(provider_id, content_type, surrogate_id)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS vod_catalog_entries (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            raw_item_id TEXT NOT NULL,
                            item_type TEXT NOT NULL,
                            target_id INTEGER NOT NULL,
                            raw_page INTEGER NOT NULL,
                            raw_index INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, category_id, raw_item_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id ON vod_catalog_entries(provider_id)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id_category_id_raw_page_raw_index ON vod_catalog_entries(provider_id, category_id, raw_page, raw_index)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_catalog_entries_provider_id_item_type_target_id ON vod_catalog_entries(provider_id, item_type, target_id)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS vod_category_hydration (
                            provider_id INTEGER NOT NULL,
                            category_id INTEGER NOT NULL,
                            last_loaded_page INTEGER NOT NULL,
                            last_attempted_page INTEGER NOT NULL,
                            last_successful_page INTEGER NOT NULL,
                            total_pages INTEGER NOT NULL,
                            page_size INTEGER NOT NULL,
                            item_count INTEGER NOT NULL,
                            is_complete INTEGER NOT NULL,
                            has_movies INTEGER NOT NULL,
                            has_series INTEGER NOT NULL,
                            last_hydrated_at INTEGER NOT NULL,
                            last_status TEXT NOT NULL,
                            last_error TEXT,
                            retry_after_ms INTEGER NOT NULL,
                            failure_count INTEGER NOT NULL,
                            retry_budget_remaining INTEGER NOT NULL,
                            last_page_fingerprint TEXT,
                            PRIMARY KEY(provider_id, category_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_vod_category_hydration_provider_id ON vod_category_hydration(provider_id)")
                }
            }
    
            /** Migration 72 -> 73: add the typed provider snapshot boundary alongside legacy rows. */

    val MIGRATION_72_73 = object : Migration(72, 73) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS provider_configs (
                            provider_id INTEGER NOT NULL,
                            type TEXT NOT NULL,
                            schema_version INTEGER NOT NULL,
                            configuration_generation INTEGER NOT NULL,
                            identity_key TEXT NOT NULL,
                            encrypted_config_json TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_configs_provider_id ON provider_configs(provider_id)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_configs_identity_key ON provider_configs(identity_key)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_provider_configs_type ON provider_configs(type)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS provider_account_runtime (
                            provider_id INTEGER NOT NULL,
                            max_connections INTEGER NOT NULL,
                            expiration_date INTEGER,
                            api_version TEXT,
                            allowed_output_formats_json TEXT NOT NULL,
                            catalog_layout TEXT NOT NULL,
                            catalog_layout_detection_version INTEGER NOT NULL,
                            observed_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_provider_account_runtime_provider_id ON provider_account_runtime(provider_id)")
    
                    addColumnIfMissing(database, "stalker_portal_state", "configuration_generation", "INTEGER NOT NULL DEFAULT 0")
                    addColumnIfMissing(database, "stalker_portal_state", "learning_json", "TEXT NOT NULL DEFAULT '{}'")
                    addColumnIfMissing(database, "stalker_portal_state", "observation_source", "TEXT NOT NULL DEFAULT 'DISCOVERY'")
                    addColumnIfMissing(database, "stalker_portal_state", "observed_at", "INTEGER NOT NULL DEFAULT 0")
    
                    backfillTypedProviderSnapshots(database)
                    validateForeignKeys(database, "provider_configs", "provider_account_runtime", "stalker_portal_state")
                }
            }
    
            /** Migration 73 -> 74: make providers a stable identity/status row. */

    val MIGRATION_73_74 = object : Migration(73, 74) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    addColumnIfMissing(
                        database,
                        "provider_configs",
                        "guide_source_policy",
                        "TEXT NOT NULL DEFAULT 'AUTO'"
                    )
                    addColumnIfMissing(
                        database,
                        "provider_configs",
                        "channel_logo_source_policy",
                        "TEXT NOT NULL DEFAULT 'SUPPLIER_PREFERRED'"
                    )
                    database.execSQL(
                        """
                        UPDATE provider_configs
                        SET guide_source_policy = COALESCE(
                                (SELECT guide_source_policy FROM providers WHERE providers.id = provider_configs.provider_id),
                                'AUTO'
                            ),
                            channel_logo_source_policy = COALESCE(
                                (SELECT channel_logo_source_policy FROM providers WHERE providers.id = provider_configs.provider_id),
                                'SUPPLIER_PREFERRED'
                            )
                        """.trimIndent()
                    )
    
                    // Room runs migrations in a transaction with foreign keys enabled. SQLite ignores
                    // PRAGMA foreign_keys changes inside that transaction, and dropping the parent
    
                    // table would therefore cascade-delete the complete provider catalog. Preserve the
                    // full transitive provider-owned FK graph in temporary shadow tables and restore it
                    // after the stable parent row has been rebuilt.
                    val providerDependents = backupProviderDependentTables(database)
                    clearProviderDependentTables(database, providerDependents)
                    database.execSQL(
                        """
                        CREATE TABLE providers_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            type TEXT NOT NULL,
                            is_active INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            last_synced_at INTEGER NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO providers_new (
                            id, name, type, is_active, status, last_synced_at, created_at
                        )
                        SELECT id, name, type, is_active, status, last_synced_at, created_at
                        FROM providers
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE providers")
                    database.execSQL("ALTER TABLE providers_new RENAME TO providers")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_providers_type ON providers(type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_providers_is_active ON providers(is_active)")
                    restoreProviderDependentTables(database, providerDependents)
                    validateForeignKeys(
                        database,
                        *providerDependents.map { it.table }.toTypedArray()
                    )
                }
            }
    
            /** Migration 74 -> 75: retain Stalker server pagination totals for resumable catalogs. */

    val MIGRATION_74_75 = object : Migration(74, 75) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    listOf(
                        "movie_category_hydration",
                        "series_category_hydration",
                        "vod_category_hydration"
                    ).forEach { tableName ->
                        addColumnIfMissing(database, tableName, "advertised_total_items", "INTEGER")
                        addColumnIfMissing(database, tableName, "advertised_total_pages", "INTEGER")
                    }

                    // Older paths added these fields with ALTER TABLE defaults. Rebuild at the
                    // convergence boundary so every historical path matches Room's v75 schema.
                    database.execSQL(
                        """
                        CREATE TABLE sync_metadata_new (
                            provider_id INTEGER NOT NULL,
                            last_live_sync INTEGER NOT NULL,
                            last_live_success INTEGER NOT NULL,
                            last_movie_sync INTEGER NOT NULL,
                            last_series_sync INTEGER NOT NULL,
                            last_series_success INTEGER NOT NULL,
                            last_epg_sync INTEGER NOT NULL,
                            last_epg_success INTEGER NOT NULL,
                            last_movie_attempt INTEGER NOT NULL,
                            last_movie_success INTEGER NOT NULL,
                            last_movie_partial INTEGER NOT NULL,
                            live_count INTEGER NOT NULL,
                            movie_count INTEGER NOT NULL,
                            series_count INTEGER NOT NULL,
                            epg_count INTEGER NOT NULL,
                            last_sync_status TEXT NOT NULL,
                            movie_sync_mode TEXT NOT NULL,
                            movie_warnings_count INTEGER NOT NULL,
                            movie_catalog_stale INTEGER NOT NULL,
                            live_avoid_full_until INTEGER NOT NULL,
                            movie_avoid_full_until INTEGER NOT NULL,
                            series_avoid_full_until INTEGER NOT NULL,
                            live_sequential_failures_remembered INTEGER NOT NULL,
                            live_healthy_sync_streak INTEGER NOT NULL,
                            movie_parallel_failures_remembered INTEGER NOT NULL,
                            movie_healthy_sync_streak INTEGER NOT NULL,
                            series_sequential_failures_remembered INTEGER NOT NULL,
                            series_healthy_sync_streak INTEGER NOT NULL,
                            PRIMARY KEY(provider_id),
                            FOREIGN KEY(provider_id) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    database.execSQL(
                        """
                        INSERT INTO sync_metadata_new (
                            provider_id, last_live_sync, last_live_success, last_movie_sync,
                            last_series_sync, last_series_success, last_epg_sync, last_epg_success,
                            last_movie_attempt, last_movie_success, last_movie_partial, live_count,
                            movie_count, series_count, epg_count, last_sync_status, movie_sync_mode,
                            movie_warnings_count, movie_catalog_stale, live_avoid_full_until,
                            movie_avoid_full_until, series_avoid_full_until,
                            live_sequential_failures_remembered, live_healthy_sync_streak,
                            movie_parallel_failures_remembered, movie_healthy_sync_streak,
                            series_sequential_failures_remembered, series_healthy_sync_streak
                        )
                        SELECT
                            provider_id, last_live_sync, last_live_success, last_movie_sync,
                            last_series_sync, last_series_success, last_epg_sync, last_epg_success,
                            last_movie_attempt, last_movie_success, last_movie_partial, live_count,
                            movie_count, series_count, epg_count, last_sync_status, movie_sync_mode,
                            movie_warnings_count, movie_catalog_stale, live_avoid_full_until,
                            movie_avoid_full_until, series_avoid_full_until,
                            live_sequential_failures_remembered, live_healthy_sync_streak,
                            movie_parallel_failures_remembered, movie_healthy_sync_streak,
                            series_sequential_failures_remembered, series_healthy_sync_streak
                        FROM sync_metadata
                        """.trimIndent()
                    )
                    database.execSQL("DROP TABLE sync_metadata")
                    database.execSQL("ALTER TABLE sync_metadata_new RENAME TO sync_metadata")
                }
            }
    
            private data class ProviderDependentBackup(
                val table: String,
                val temporaryTable: String
            )
    
            private fun backupProviderDependentTables(
                database: SupportSQLiteDatabase
            ): List<ProviderDependentBackup> {
                val tables = buildList {
                    database.query(
                        "SELECT name FROM sqlite_master " +
                            "WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                            "AND name NOT IN ('providers','room_master_table','android_metadata')"
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
                val parentsByTable = tables.associateWith { table ->
                    buildSet {
                        database.query("PRAGMA foreign_key_list(${quoteSqlIdentifier(table)})").use { cursor ->
                            while (cursor.moveToNext()) add(cursor.getString(2))
                        }
                    }
                }
                val affected = linkedSetOf("providers")
                var changed: Boolean
                do {
                    changed = false
                    parentsByTable.forEach { (table, parents) ->
                        if (table !in affected && parents.any(affected::contains)) {
                            affected += table
                            changed = true
                        }
                    }
                } while (changed)
    
                val dependentTables = affected - "providers"
                val pending = dependentTables.toMutableSet()
                val ordered = mutableListOf<String>()
                val restoredParents = mutableSetOf("providers")
                while (pending.isNotEmpty()) {
                    val ready = pending.filter { table ->
                        parentsByTable.getValue(table)
                            .filter { parent -> parent in dependentTables && parent != table }
                            .all(restoredParents::contains)
                    }
                    check(ready.isNotEmpty()) {
                        "Provider-dependent foreign-key cycle cannot be rebuilt safely: $pending"
                    }
                    ready.sorted().forEach { table ->
                        ordered += table
                        restoredParents += table
                        pending -= table
                    }
                }
    
                return ordered.mapIndexed { index, table ->
                    val temporaryTable = "provider_rebuild_backup_$index"
                    database.execSQL("DROP TABLE IF EXISTS ${quoteSqlIdentifier(temporaryTable)}")
                    database.execSQL(
                        "CREATE TEMP TABLE ${quoteSqlIdentifier(temporaryTable)} AS " +
                            "SELECT * FROM ${quoteSqlIdentifier(table)}"
                    )
                    ProviderDependentBackup(
                        table = table,
                        temporaryTable = temporaryTable
                    )
                }
            }
    
            private fun clearProviderDependentTables(
                database: SupportSQLiteDatabase,
                backups: List<ProviderDependentBackup>
            ) {
                // Every row is already present in a temporary shadow table. Clear children first so
                // rows outside the provider cascade are not left behind and inserted a second time.
                backups.asReversed().forEach { backup ->
                    database.execSQL("DELETE FROM ${quoteSqlIdentifier(backup.table)}")
                }
            }
    
            private fun restoreProviderDependentTables(
                database: SupportSQLiteDatabase,
                backups: List<ProviderDependentBackup>
            ) {
                backups.forEach { backup ->
                    database.execSQL(
                        "INSERT INTO ${quoteSqlIdentifier(backup.table)} " +
                            "SELECT * FROM ${quoteSqlIdentifier(backup.temporaryTable)}"
                    )
                    database.execSQL("DROP TABLE ${quoteSqlIdentifier(backup.temporaryTable)}")
                }
            }
    
            private fun quoteSqlIdentifier(value: String): String =
                "\"${value.replace("\"", "\"\"")}\""
    
            private fun imageUrlMigrationSql(table: String, column: String): String = """
                UPDATE $table SET $column = CASE
                    WHEN instr($column, 'streamvault_provider_id=') > 0 THEN $column
                    ELSE $column || CASE WHEN instr($column, '?') = 0 THEN '?streamvault_provider_id=' ELSE '&streamvault_provider_id=' END || provider_id
                END
                WHERE $column IS NOT NULL
                  AND provider_id IN (SELECT id FROM providers WHERE type = 'JELLYFIN')
            """.trimIndent()

    val all: List<Migration> = listOf(
        MIGRATION_49_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
        MIGRATION_55_56,
        MIGRATION_56_57,
        MIGRATION_57_58,
        MIGRATION_58_59,
        MIGRATION_59_60,
        MIGRATION_60_61,
        MIGRATION_61_62,
        MIGRATION_62_63,
        MIGRATION_63_64,
        MIGRATION_64_65,
        MIGRATION_65_66,
        MIGRATION_66_67,
        MIGRATION_67_68,
        MIGRATION_68_69,
        MIGRATION_69_70,
        MIGRATION_70_71,
        MIGRATION_71_72,
        MIGRATION_72_73,
        MIGRATION_73_74,
        MIGRATION_74_75
    )
}
