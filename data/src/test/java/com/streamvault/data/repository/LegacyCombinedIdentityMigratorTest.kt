package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.CombinedM3uProfileDao
import com.streamvault.data.local.entity.CombinedM3uProfileEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.repository.LiveChannelOrderRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class LegacyCombinedIdentityMigratorTest {
    @Test fun `duplicate legacy identities are not guessed or overwritten`() = runTest {
        val profiles: CombinedM3uProfileDao = mock()
        val preferences: PreferencesRepository = mock()
        val orders: LiveChannelOrderRepository = mock()
        whenever(profiles.getAll()).thenReturn(flowOf(listOf(
            CombinedM3uProfileEntity(id = 1, identityKey = "legacy-combined-1", name = "One"),
            CombinedM3uProfileEntity(id = 2, identityKey = "legacy-combined-1", name = "Two")
        )))
        LegacyCombinedIdentityMigrator(profiles, preferences, orders).migrate()
        verify(preferences, never()).prepareCombinedIdentityMigration(any())
        verify(profiles, never()).replaceIdentityIfUnchanged(any(), any(), any())
    }

    @Test fun `interrupted migration retries same target before updating profile`() = runTest {
        val profiles: CombinedM3uProfileDao = mock()
        val preferences: PreferencesRepository = mock()
        val orders: LiveChannelOrderRepository = mock()
        val old = CombinedM3uProfileEntity(id = 1, identityKey = "legacy-combined-1", name = "Family")
        whenever(profiles.getAll()).thenReturn(flowOf(listOf(old)))
        whenever(preferences.prepareCombinedIdentityMigration(old.identityKey)).thenReturn("stable-target")
        whenever(orders.migrateSourceIdentity(any(), any())).thenThrow(IllegalStateException("interrupted"))
            .thenReturn(Unit)
        whenever(profiles.replaceIdentityIfUnchanged(1, old.identityKey, "stable-target")).thenReturn(1)
        val migrator = LegacyCombinedIdentityMigrator(profiles, preferences, orders)
        assertThat(runCatching { migrator.migrate() }.isFailure).isTrue()
        verify(profiles, never()).replaceIdentityIfUnchanged(any(), any(), any())
        migrator.migrate()
        verify(profiles).replaceIdentityIfUnchanged(1, old.identityKey, "stable-target")
        verify(preferences, times(2)).prepareCombinedIdentityMigration(old.identityKey)
    }
}
