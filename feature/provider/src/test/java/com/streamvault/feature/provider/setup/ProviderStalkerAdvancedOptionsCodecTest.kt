package com.streamvault.feature.provider.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderStalkerAdvancedOptionsCodecTest {
    @Test
    fun `loading order defaults to parallel and survives editing alongside portal options`() {
        assertThat(ProviderStalkerAdvancedOptionsCodec.decode("").catalogLoadOrder)
            .isEqualTo(com.streamvault.domain.model.StalkerCatalogLoadOrder.PARALLEL)
        for (order in com.streamvault.domain.model.StalkerCatalogLoadOrder.entries) {
            val options = ProviderStalkerAdvancedOptions(hwVersion = "custom", proxyHost = "proxy.example.com", catalogLoadOrder = order)
            val restored = ProviderStalkerAdvancedOptionsCodec.decode(ProviderStalkerAdvancedOptionsCodec.encode(options))
            assertThat(restored).isEqualTo(options)
            assertThat(restored.copy(playerUserAgent = "changed").catalogLoadOrder).isEqualTo(order)
        }
    }

    @Test
    fun `encode preserves the persisted Stalker options wire format`() {
        val encoded = ProviderStalkerAdvancedOptionsCodec.encode(
            ProviderStalkerAdvancedOptions(
                hwVersion = "2.20.07",
                xUserAgentLink = ProviderStalkerAdvancedOptions.LINK_WIFI,
                proxyEnabled = true,
                proxyHost = "proxy.example.com",
                proxyPort = 8080,
                requestRules = listOf(
                    ProviderStalkerRequestRule(
                        action = "get_profile",
                        blockRequest = true,
                        paramOverrides = listOf(ProviderStalkerParamOverride("ver", "ImageDescription")),
                    ),
                ),
            ),
        )

        assertThat(encoded).isEqualTo(
            """{"hwVersion":"2.20.07","apiUserAgent":"","playerUserAgent":"","playerHeaders":"","xUserAgentLink":"WiFi","proxyEnabled":true,"proxyHost":"proxy.example.com","proxyPort":8080,"requestRules":[{"action":"get_profile","blockRequest":true,"paramOverrides":[{"name":"ver","value":"ImageDescription"}]}]}""",
        )
    }

    @Test
    fun `decode accepts the persisted options wire format`() {
        val decoded = ProviderStalkerAdvancedOptionsCodec.decode(
            """{"hwVersion":"2.20.07","xUserAgentLink":"wifi","proxyEnabled":true,"proxyHost":" proxy.example.com ","proxyPort":8080,"requestRules":[{"action":"get_profile","blockRequest":true,"paramOverrides":[{"name":"ver","value":"ImageDescription"}]}]}""",
        )

        assertThat(decoded.normalizedLink).isEqualTo(ProviderStalkerAdvancedOptions.LINK_WIFI)
        assertThat(decoded.proxy).isEqualTo(ProviderStalkerHttpProxy("proxy.example.com", 8080))
        assertThat(decoded.requestRules).containsExactly(
            ProviderStalkerRequestRule(
                action = "get_profile",
                blockRequest = true,
                paramOverrides = listOf(ProviderStalkerParamOverride("ver", "ImageDescription")),
            ),
        )
    }

    @Test
    fun `legacy decoder accepts snake case edit fields`() {
        val decoded = ProviderStalkerAdvancedOptionsCodec.decodeLegacyEditFields(
            """{"serial_number":"SN-1","device_id":"D1","device_id2":"D2","hw_version":"2.20","api_user_agent":"MAG","x_user_agent_link":"WiFi","proxy_enabled":true,"proxy_host":"proxy.example.com","proxy_port":3128}""",
        )

        assertThat(decoded).isEqualTo(
            ProviderStalkerLegacyEditFields(
                serialNumber = "SN-1",
                deviceId = "D1",
                deviceId2 = "D2",
                hwVersion = "2.20",
                apiUserAgent = "MAG",
                xUserAgentLink = ProviderStalkerAdvancedOptions.LINK_WIFI,
                proxyEnabled = true,
                proxyHost = "proxy.example.com",
                proxyPort = 3128,
            ),
        )
    }
}
