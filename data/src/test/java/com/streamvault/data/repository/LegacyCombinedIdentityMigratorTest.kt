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
    @Test fun `duplicate identities copy shared order before redirecting last profile and retry safely`() = runTest {
        val profiles: CombinedM3uProfileDao = mock()
        val preferences: PreferencesRepository = mock()
        val orders: LiveChannelOrderRepository = mock()
        val old = "legacy-combined-1"
        whenever(profiles.getAll()).thenReturn(flowOf(listOf(
            CombinedM3uProfileEntity(id = 2, identityKey = old, name = "Two"),
            CombinedM3uProfileEntity(id = 1, identityKey = old, name = "One")
        )))
        whenever(preferences.prepareDuplicateCombinedIdentityMigration(old, 1)).thenReturn("copy-target")
        whenever(preferences.prepareCombinedIdentityMigration(old)).thenReturn("last-target")
        whenever(orders.copySourceIdentity(any(), any())).thenThrow(IllegalStateException("interrupted"))
            .thenReturn(Unit)
        whenever(profiles.replaceIdentityIfUnchanged(1, old, "copy-target")).thenReturn(1)
        whenever(profiles.replaceIdentityIfUnchanged(2, old, "last-target")).thenReturn(1)
        val migrator = LegacyCombinedIdentityMigrator(profiles, preferences, orders)
        assertThat(runCatching { migrator.migrate() }.isFailure).isTrue()
        verify(profiles, never()).replaceIdentityIfUnchanged(any(), any(), any())
        migrator.migrate()
        val sequence = inOrder(orders, profiles)
        sequence.verify(orders, times(2)).copySourceIdentity("combined:$old", "combined:copy-target")
        sequence.verify(profiles).replaceIdentityIfUnchanged(1, old, "copy-target")
        sequence.verify(orders).migrateSourceIdentity("combined:$old", "combined:last-target")
        sequence.verify(profiles).replaceIdentityIfUnchanged(2, old, "last-target")
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
