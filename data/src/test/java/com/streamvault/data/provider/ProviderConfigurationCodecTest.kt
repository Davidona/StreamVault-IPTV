package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.*
import org.junit.Test

class ProviderConfigurationCodecTest {
    private val crypto = object : CredentialCrypto {
        override fun encryptIfNeeded(value: String) = if (value.isBlank() || value.startsWith("enc:test:")) value else "enc:test:$value"
        override fun decryptIfNeeded(value: String) = value.removePrefix("enc:test:")
    }
    private val codec = ProviderConfigurationCodec(Gson(), crypto)

    @Test
    fun `round trips every subtype without plaintext credentials`() {
        val configurations = listOf<ProviderConfiguration>(
            XtreamConfig(
                "https://x.test",
                "alice",
                "secret",
                preferredLiveStreamFormat = LiveStreamFormatMode.MPEG_TS
            ),
            M3uConfig("https://m.test/list.m3u", epgUrl = "https://m.test/epg.xml"),
            StalkerConfig(
                portalUrl = "https://s.test",
                device = StalkerDeviceIdentity("00:11:22:33:44:55", serialNumber = "serial"),
                username = "bob",
                password = "secret2"
            ),
            JellyfinConfig("https://j.test", "carol", "token")
        )

        configurations.forEach { configuration ->
            val encoded = codec.encode(configuration)
            if (configuration !is M3uConfig) assertThat(encoded).doesNotContain(when (configuration) {
                is XtreamConfig -> "\"password\":\"secret\""
                is StalkerConfig -> "\"password\":\"secret2\""
                is JellyfinConfig -> "\"credential\":\"token\""
                else -> error("unreachable")
            })
            assertThat(codec.decode(configuration.type, encoded)).isEqualTo(configuration)
        }
    }

    @Test
    fun `identity key is stable across secrets and cosmetic origin changes`() {
        val first = XtreamConfig("HTTPS://Example.COM", "alice", "one")
        val second = XtreamConfig("https://example.com:443/", "alice", "two")
        assertThat(codec.identityKey(first)).isEqualTo(codec.identityKey(second))
        assertThat(codec.identityKey(first)).hasLength(64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects stored type mismatch`() {
        codec.decode(ProviderType.M3U, codec.encode(XtreamConfig("https://x.test", "u", "p")))
    }
}
