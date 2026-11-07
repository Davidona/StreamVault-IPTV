package com.streamvault.data.repository

import com.streamvault.data.local.dao.CombinedM3uProfileDao
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.repository.LiveChannelOrderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Store redirects are installed before publishing the replacement Room identity. */
@Singleton
class LegacyCombinedIdentityMigrator @Inject constructor(
    private val profiles: CombinedM3uProfileDao,
    private val preferences: PreferencesRepository,
    private val orders: LiveChannelOrderRepository
) {
    private val mutex = Mutex()

    suspend fun migrate() = mutex.withLock {
        val legacy = profiles.getAll().first().filter { it.identityKey.matches(Regex("legacy-combined-[0-9]+")) }
        val duplicateIdentities = legacy.groupingBy { it.identityKey }.eachCount().filterValues { it > 1 }.keys
        legacy.filterNot { it.identityKey in duplicateIdentities }.forEach { profile ->
            val old = profile.identityKey
            val replacement = preferences.prepareCombinedIdentityMigration(old)
            orders.migrateSourceIdentity("combined:$old", "combined:$replacement")
            val updated = profiles.replaceIdentityIfUnchanged(profile.id, old, replacement)
            if (updated != 1) {
                val current = profiles.getById(profile.id)
                check(current == null || current.identityKey == replacement) { "Combined profile identity changed during migration" }
            }
        }
    }
}
