package com.streamvault.app.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.PluginProviderOwnershipEntity
import com.streamvault.feature.system.api.StreamVaultPluginComponent
import com.streamvault.feature.system.api.StreamVaultPluginOwner
import org.junit.Test

class StreamVaultPluginOwnershipTest {
    @Test
    fun `manifest rename retains sole provider owned by the same component`() {
        val ownership = ownership(
            packageName = "com.example.plugin",
            serviceClassName = "PluginService",
            manifestId = "old-id",
            providerId = 42L
        )

        val selected = selectPluginOwnership(
            StreamVaultPluginOwner(
                "com.example.plugin",
                "PluginService",
                "new-id"
            ),
            listOf(ownership)
        )

        assertThat(selected).isEqualTo(ownership)
    }

    @Test
    fun `manifest rename never adopts an ambiguous component mapping`() {
        val owner = StreamVaultPluginOwner(
            "com.example.plugin",
            "PluginService",
            "new-id"
        )
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
            setOf(
                StreamVaultPluginComponent(
                    ownership.packageName,
                    ownership.serviceClassName
                )
            )
        )

        assertThat(orphaned).isEmpty()
    }

    @Test
    fun `reconciliation removes only ownership for an absent component`() {
        val installed = ownership("com.example.installed", "PluginService", "shared-id", 41L)
        val removed = ownership("com.example.removed", "PluginService", "shared-id", 42L)

        val orphaned = orphanedPluginOwnerships(
            listOf(installed, removed),
            setOf(
                StreamVaultPluginComponent(
                    installed.packageName,
                    installed.serviceClassName
                )
            )
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
