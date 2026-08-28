package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.PluginProviderOwnershipEntity
import org.junit.Test

class StreamVaultPluginOwnerTest {

    @Test
    fun `plugin owner remains distinct when packages reuse a manifest ID`() {
        val first = StreamVaultPluginOwner("com.example.first", "FirstService", "shared-id")
        val second = StreamVaultPluginOwner("com.example.second", "SecondService", "shared-id")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `plugin owner remains distinct for two services in one package`() {
        val first = StreamVaultPluginOwner("com.example.plugin", "FirstService", "shared-id")
        val second = StreamVaultPluginOwner("com.example.plugin", "SecondService", "shared-id")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `plugin owner lazy list key is a bundle safe string`() {
        val owner = StreamVaultPluginOwner(
            "com.streamvault.plugin.adaptivebridge",
            "com.streamvault.plugin.adaptivebridge.StreamVaultAdaptiveBridgePluginService",
            "com.streamvault.plugins.adaptivebridge"
        )

        val key: String = owner.toBundleSafeKey()

        assertThat(key).isNotEmpty()
        assertThat(key).isNotEqualTo(owner.copy(manifestId = "com.streamvault.plugins.other").toBundleSafeKey())
    }

    @Test
    fun `manifest rename retains sole provider owned by the same component`() {
        val ownership = ownership(
            packageName = "com.example.plugin",
            serviceClassName = "PluginService",
            manifestId = "old-id",
            providerId = 42L
        )

        val selected = selectPluginOwnership(
            StreamVaultPluginOwner("com.example.plugin", "PluginService", "new-id"),
            listOf(ownership)
        )

        assertThat(selected).isEqualTo(ownership)
    }

    @Test
    fun `manifest rename never adopts an ambiguous component mapping`() {
        val owner = StreamVaultPluginOwner("com.example.plugin", "PluginService", "new-id")
        val ownerships = listOf(
            ownership(owner.packageName, owner.serviceClassName, "old-a", 41L),
            ownership(owner.packageName, owner.serviceClassName, "old-b", 42L)
        )

        assertThat(selectPluginOwnership(owner, ownerships)).isNull()
    }

    @Test
    fun `reconciliation ignores manifest IDs while installed component remains`() {
        val ownership = ownership(
            packageName = "com.example.plugin",
            serviceClassName = "PluginService",
            manifestId = "manifest-unavailable-or-renamed",
            providerId = 42L
        )

        val orphaned = orphanedPluginOwnerships(
            listOf(ownership),
            setOf(StreamVaultPluginComponent(ownership.packageName, ownership.serviceClassName))
        )

        assertThat(orphaned).isEmpty()
    }

    @Test
    fun `reconciliation removes only ownership for an absent component`() {
        val installed = ownership("com.example.installed", "PluginService", "shared-id", 41L)
        val removed = ownership("com.example.removed", "PluginService", "shared-id", 42L)

        val orphaned = orphanedPluginOwnerships(
            listOf(installed, removed),
            setOf(StreamVaultPluginComponent(installed.packageName, installed.serviceClassName))
        )

        assertThat(orphaned).containsExactly(removed)
    }

    private fun ownership(
        packageName: String,
        serviceClassName: String,
        manifestId: String,
        providerId: Long
    ) = PluginProviderOwnershipEntity(
        packageName = packageName,
        serviceClassName = serviceClassName,
        manifestId = manifestId,
        providerId = providerId,
        createdAt = 1L
    )
}
