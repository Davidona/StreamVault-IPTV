package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.XtreamConfig
import org.junit.Test

class ProviderConfigurationCodecMigrationTest {
    private val gson = Gson()
    private val crypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String) = value
        override fun decryptIfNeeded(value: String) = value
    }
    private val codec = ProviderConfigurationCodec(gson, crypto)

    @Test
    fun `decode accepts migration payloads without embedded type`() {
        val configurations = listOf<ProviderConfiguration>(
            XtreamConfig("https://x.test", "alice", "secret"),
            M3uConfig("https://m.test/list.m3u"),
            StalkerConfig(
                portalUrl = "https://s.test",
                device = StalkerDeviceIdentity("00:11:22:33:44:55"),
                username = "bob",
                password = "secret2"
            ),
            JellyfinConfig("https://j.test", "carol", "token")
        )

        configurations.forEach { configuration ->
            val migratedPayload = gson.toJsonTree(configuration).asJsonObject.apply { remove("type") }.toString()
            assertThat(codec.decode(configuration.type, migratedPayload)).isEqualTo(configuration)
        }
    }
}
