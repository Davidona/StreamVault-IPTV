package com.streamvault.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Shared, reviewable assertions and deterministic backfill helpers for versioned migrations. */
/**
 * Checks FK integrity for the specified tables only.
 * Always pass the tables the migration actually wrote to — never call with no arguments,
 * as that would check the entire database and can crash on pre-existing violations in
 * unrelated tables that the migration didn't touch.
 */
internal fun validateForeignKeys(database: SupportSQLiteDatabase, vararg tableNames: String) {
    for (table in tableNames) {
        database.query("PRAGMA foreign_key_check($table)").use { cursor ->
            if (cursor.moveToFirst()) {
                val tbl = if (!cursor.isNull(0)) cursor.getString(0) else "<unknown>"
                val rowId = if (!cursor.isNull(1)) cursor.getLong(1) else -1L
                val parent = if (!cursor.isNull(2)) cursor.getString(2) else "<unknown>"
                throw IllegalStateException(
                    "Foreign key violation after migration: table=$tbl rowId=$rowId parent=$parent"
                )
            }
        }
    }
}

/**
 * Migration 1 → 2: no-op stub.
 * v1 databases had the same table structure as v2; this migration prevents Room
 * from crashing with an "unsatisfied migration" exception on very early installs.
 */



internal fun legacyStalkerLearning(
    cursor: android.database.Cursor,
    generation: Long,
    observedAt: Long
): JSONObject {
    fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
    fun int(name: String): Int = cursor.getInt(cursor.getColumnIndexOrThrow(name))
    fun observation(value: Any): JSONObject = JSONObject()
        .put("value", value)
        .put("configurationGeneration", generation)
        .put("source", "DISCOVERY")
        .put("observedAt", observedAt)
    val identity = JSONObject()
        .put("macAddress", text("stalker_mac_address"))
        .put("deviceProfile", text("stalker_device_profile"))
        .put("timezone", text("stalker_device_timezone"))
        .put("locale", text("stalker_device_locale"))
        .put("serialNumber", text("stalker_serial_number"))
        .put("deviceId", text("stalker_device_id"))
        .put("deviceId2", text("stalker_device_id2"))
        .put("signature", text("stalker_signature"))
    return JSONObject()
        .put("configurationGeneration", generation)
        .put("effectiveIdentity", observation(identity))
        .apply {
            text("stalker_learned_profile_id").takeIf(String::isNotBlank)
                ?.let { put("profileId", observation(it)) }
            put("profileRevision", observation(int("stalker_profile_revision")))
            put("profileVerification", observation(text("stalker_profile_verification")))
            put("portalProfile", observation(text("stalker_portal_profile")))
            put("portalFingerprint", observation(text("stalker_portal_fingerprint")))
            put("magPreset", observation(text("stalker_mag_preset")))
            put("protocolFamily", observation(text("stalker_protocol_family")))
            put("bootstrapRecipe", observation(text("stalker_last_bootstrap_recipe")))
            put("endpointPreference", observation(text("stalker_endpoint_preference")))
            put("cookieMode", observation(text("stalker_cookie_mode")))
            put("playbackBackendHint", observation(text("stalker_playback_backend_hint")))
            val lastPlaybackModeIndex = cursor.getColumnIndexOrThrow("stalker_last_playback_mode")
            if (!cursor.isNull(lastPlaybackModeIndex)) {
                put("lastPlaybackMode", observation(cursor.getString(lastPlaybackModeIndex)))
            }
            put("capabilities", JSONObject())
            put("discoveryEvidence", org.json.JSONArray())
        }
}

internal fun legacyTransportGrant(cursor: android.database.Cursor): Any {
    fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
    val mode = text("stalker_transport_mode")
    val consentedAt = cursor.getLong(cursor.getColumnIndexOrThrow("stalker_transport_consent_at"))
    if (mode == "AUTO_STRICT" || consentedAt <= 0L) return JSONObject.NULL
    val originValue = text("stalker_transport_origin")
    val uri = runCatching { URI(originValue) }.getOrNull() ?: return JSONObject.NULL
    val scheme = uri.scheme?.lowercase() ?: return JSONObject.NULL
    val host = uri.host ?: return JSONObject.NULL
    val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    return JSONObject()
        .put("mode", mode)
        .put("origin", JSONObject().put("scheme", scheme).put("host", host).put("port", port))
        .put("spkiSha256", text("stalker_tls_spki_sha256").takeIf { it.isNotBlank() })
        .put("consentedAt", consentedAt)
}

internal fun migrationNormalizeOrigin(value: String): String = runCatching {
    val uri = URI(value.trim())
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val port = uri.port.takeIf { it >= 0 } ?: when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }
    "$scheme://$host:$port${uri.path.orEmpty().trimEnd('/')}"
}.getOrElse { value.trim().trimEnd('/').lowercase() }

/** Maps every provider spelling accepted by the legacy runtime to the persisted enum name. */
internal fun canonicalLegacyProviderType(value: String): String = when (value.trim().uppercase()) {
    "XTREAM_CODES", "XTREAM", "XTREAM_CODES_API" -> "XTREAM_CODES"
    "STALKER_PORTAL", "STALKER", "STB" -> "STALKER_PORTAL"
    "JELLYFIN" -> "JELLYFIN"
    "M3U", "PLAYLIST" -> "M3U"
    // ProviderTypeConverter historically treated unrecognized values as playlist providers.
    else -> "M3U"
}

internal fun migrationIdentityKey(parts: List<String>): String =
    MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Keeps a legacy duplicate addressable without violating the new unique identity index. */
internal fun disambiguatedMigrationIdentityKey(canonicalKey: String, providerId: Long): String =
    migrationIdentityKey(listOf(canonicalKey, "legacy-duplicate", providerId.toString()))

internal fun tableHasColumn(
    database: SupportSQLiteDatabase,
    tableName: String,
    columnName: String
): Boolean {
    database.query("PRAGMA table_info($tableName)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                return true
            }
        }
    }
    return false
}
