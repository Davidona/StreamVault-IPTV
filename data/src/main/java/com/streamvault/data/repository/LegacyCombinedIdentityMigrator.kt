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
        // Copy each shared state to all but one profile before redirecting the old identity.
        // If interrupted, completed profiles are no longer in this group and the copy markers
        // retain the same target for whichever profile was in flight.
        legacy.groupBy { it.identityKey }.forEach { (old, group) ->
            val ordered = group.sortedBy { it.id }
            ordered.dropLast(1).forEach { profile ->
                val replacement = preferences.prepareDuplicateCombinedIdentityMigration(old, profile.id)
                orders.copySourceIdentity("combined:$old", "combined:$replacement")
                replaceIdentity(profile.id, old, replacement)
            }
            ordered.lastOrNull()?.let { profile ->
                val replacement = preferences.prepareCombinedIdentityMigration(old)
                orders.migrateSourceIdentity("combined:$old", "combined:$replacement")
                replaceIdentity(profile.id, old, replacement)
            }
        }
    }

    private suspend fun replaceIdentity(profileId: Long, old: String, replacement: String) {
        val updated = profiles.replaceIdentityIfUnchanged(profileId, old, replacement)
        if (updated != 1) {
            val current = profiles.getById(profileId)
            check(current == null || current.identityKey == replacement) { "Combined profile identity changed during migration" }
        }
    }
}
