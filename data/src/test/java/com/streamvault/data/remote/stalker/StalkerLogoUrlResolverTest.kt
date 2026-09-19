package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StalkerLogoUrlResolverTest {

    @Test
    fun `resolves bare filename against portal install root`() {
        val result = StalkerLogoUrlResolver.resolveChannelLogoUrl(
            portalUrl = "http://portal.example/stalker_portal/server/load.php",
            url = "536.png"
        )

        assertThat(result).isEqualTo(
            "http://portal.example/stalker_portal/misc/logos/120/536.png"
        )
    }

    @Test
    fun `resolves directory relative path against portal install root`() {
        val result = StalkerLogoUrlResolver.resolveChannelLogoUrl(
            portalUrl = "https://portal.example/stalker_portal/c/",
            url = "misc/logos/120/536.png"
        )

        assertThat(result).isEqualTo(
            "https://portal.example/stalker_portal/misc/logos/120/536.png"
        )

        assertThat(
            StalkerLogoUrlResolver.resolveChannelLogoUrl(
                portalUrl = "https://portal.example/stalker_portal/c/",
                url = "../outside.png"
            )
        ).isEqualTo("../outside.png")
    }

    @Test
    fun `keeps unsupported portal scheme and malformed portal URL unchanged`() {
        assertThat(
            StalkerLogoUrlResolver.resolveChannelLogoUrl(
                portalUrl = "ftp://portal.example/stalker_portal/",
                url = "536.png"
            )
        ).isEqualTo("536.png")

        assertThat(
            StalkerLogoUrlResolver.resolvePortalUrl(
                portalUrl = "not a url",
                url = "/stalker_portal/misc/logos/120/536.png"
            )
        ).isEqualTo("/stalker_portal/misc/logos/120/536.png")
    }

    @Test
    fun `preserves absolute and root relative URLs`() {
        assertThat(
            StalkerLogoUrlResolver.resolveChannelLogoUrl(
                portalUrl = "http://portal.example/stalker_portal/server/load.php",
                url = "https://cdn.example/536.png"
            )
        ).isEqualTo("https://cdn.example/536.png")

        assertThat(
            StalkerLogoUrlResolver.resolveChannelLogoUrl(
                portalUrl = "http://portal.example/stalker_portal/server/load.php",
                url = "/stalker_portal/misc/logos/120/536.png"
            )
        ).isEqualTo("http://portal.example/stalker_portal/misc/logos/120/536.png")
    }
}
