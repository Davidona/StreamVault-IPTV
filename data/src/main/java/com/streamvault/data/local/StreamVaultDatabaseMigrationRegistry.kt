package com.streamvault.data.local

import androidx.room.migration.Migration

/**
 * The single migration topology consumed by production. Definitions are grouped into auditable
 * version-era files; this registry verifies that no adjacent release hop is missing or duplicated.
 */
object StreamVaultDatabaseMigrationRegistry {
    const val CURRENT_VERSION = STREAM_VAULT_DATABASE_VERSION

    val v1To24: List<Migration> = LegacyMigrationsV1To24.all
    val v24To49: List<Migration> = LegacyMigrationsV24To49.all
    val v49To75: List<Migration> = FeatureMigrationsV49To75.all
    val v75To76: List<Migration> = listOf(FeatureMigrationsV75To76.MIGRATION_75_76)
    val v76To77: List<Migration> = listOf(FeatureMigrationsV76To77.MIGRATION_76_77)
    val all: List<Migration> = (v1To24 + v24To49 + v49To75 + v75To76 + v76To77).also(::validate)

    private fun validate(migrations: List<Migration>) {
        require(migrations.map { it.startVersion }.distinct().size == migrations.size) {
            "Duplicate Room migration start version"
        }
        val expected = 1 until CURRENT_VERSION
        require(migrations.map { it.startVersion }.toSet() == expected.toSet()) {
            "Room migration registry is incomplete: expected versions $expected"
        }
        require(migrations.all { it.endVersion == it.startVersion + 1 }) {
            "Room migration registry must contain adjacent migrations only"
        }
        require(migrations.zipWithNext().all { (current, next) ->
            current.endVersion == next.startVersion
        }) {
            "Room migration registry is not ordered as one contiguous chain"
        }
    }
}
