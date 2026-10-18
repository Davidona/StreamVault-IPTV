package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderAccountRuntime
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import org.junit.Test

class LegacyProviderLiveFormatPreferenceTest {

    @Test
    fun `Xtream live format preference survives legacy repository projection`() {
        val configuration = XtreamConfig(
            serverUrl = "https://example.test",
            username = "user",
            password = "secret",
            preferredLiveStreamFormat = LiveStreamFormatMode.MPEG_TS
        )
        val provider = configuration.toLegacyProvider(
            identity = com.streamvault.domain.model.Provider(
                id = 12L,
                name = "Test provider",
                type = ProviderType.XTREAM_CODES
            ),
            runtime = ProviderAccountRuntime()
        )

        assertThat(provider.toTypedConfiguration())
            .isEqualTo(configuration)
    }
}
