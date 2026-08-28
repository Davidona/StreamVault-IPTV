package com.streamvault.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MigrationSupportTest {
    @Test
    fun `legacy provider aliases are canonicalized before snapshot backfill`() {
        assertThat(canonicalLegacyProviderType("xtream")).isEqualTo("XTREAM_CODES")
        assertThat(canonicalLegacyProviderType(" XTREAM_CODES_API ")).isEqualTo("XTREAM_CODES")
        assertThat(canonicalLegacyProviderType("stalker")).isEqualTo("STALKER_PORTAL")
        assertThat(canonicalLegacyProviderType("STB")).isEqualTo("STALKER_PORTAL")
        assertThat(canonicalLegacyProviderType("playlist")).isEqualTo("M3U")
        assertThat(canonicalLegacyProviderType("jellyfin")).isEqualTo("JELLYFIN")
    }

    @Test
    fun `unknown legacy provider type follows the runtime M3U fallback`() {
        assertThat(canonicalLegacyProviderType("vendor_specific_playlist"))
            .isEqualTo("M3U")
    }

    @Test
    fun `migration identity hashes canonical components with an unambiguous separator`() {
        assertThat(
            migrationIdentityKey(listOf("XTREAM_CODES", "https://host:443", "alice"))
        ).isEqualTo("771e8fe912c9f1e70aba7cef599dd4c38f68cb208c52174b1774f0ecbe9c3414")
    }

    @Test
    fun `duplicate migration identity is stable and provider specific`() {
        val canonical = migrationIdentityKey(listOf("M3U", "https://host/list.m3u"))

        val first = disambiguatedMigrationIdentityKey(canonical, providerId = 41)
        val same = disambiguatedMigrationIdentityKey(canonical, providerId = 41)
        val otherProvider = disambiguatedMigrationIdentityKey(canonical, providerId = 42)

        assertThat(first).isEqualTo(same)
        assertThat(first).hasLength(64)
        assertThat(first).isNotEqualTo(canonical)
        assertThat(first).isNotEqualTo(otherProvider)
    }
}
