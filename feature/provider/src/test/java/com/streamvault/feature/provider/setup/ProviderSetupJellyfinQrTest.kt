package com.streamvault.feature.provider.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderSetupJellyfinQrTest {
    @Test
    fun `builds the current Jellyfin Web Quick Connect route`() {
        assertThat(buildJellyfinQuickConnectUrl("https://jellyfin.example/", "AB 12"))
            .isEqualTo("https://jellyfin.example/web/#/quickconnect?code=AB+12")
    }

    @Test
    fun `encodes QR code query values`() {
        assertThat(buildJellyfinQuickConnectUrl("http://192.168.1.10:8096", "A&B"))
            .isEqualTo("http://192.168.1.10:8096/web/#/quickconnect?code=A%26B")
    }
}
