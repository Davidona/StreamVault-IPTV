package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test

class PlayerNavigationRequestTest {

    @Test
    fun `safePlayerNavigationRequest rejects missing request`() {
        assertThat(safePlayerNavigationRequest(null)).isNull()
    }

    @Test
    fun `safePlayerNavigationRequest rejects unsafe stream url`() {
        val request = PlayerNavigationRequest(
            streamUrl = "javascript:alert(1)",
            title = "Unsafe"
        )

        assertThat(safePlayerNavigationRequest(request)).isNull()
    }

    @Test
    fun `safePlayerNavigationRequest accepts app supported stream schemes`() {
        val request = PlayerNavigationRequest(
            streamUrl = "xtream://1/series/10?ext=mkv",
            title = "Episode"
        )

        assertThat(safePlayerNavigationRequest(request)).isSameInstanceAs(request)
    }
}
