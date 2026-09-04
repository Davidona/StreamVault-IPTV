package com.streamvault.feature.system.api

import com.google.common.truth.Truth.assertThat
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

}
