package com.streamvault.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uSourceIdentityTest {

    @Test
    fun `tvg id is the stable identity across title and URL refresh changes`() {
        val first = entry(
            tvgId = "movie-42",
            name = "Movie 42",
            url = "https://cdn.example.com/movie/42?token=old"
        )
        val refreshed = entry(
            tvgId = "MOVIE-42",
            name = "Movie 42 (HD)",
            url = "https://cdn.example.com/movie/42?token=new"
        )

        assertThat(M3uSourceIdentity.fromEntry(7L, first))
            .isEqualTo(M3uSourceIdentity.fromEntry(7L, refreshed))
        assertThat(M3uSourceIdentity.stableLongId(7L, first))
            .isEqualTo(M3uSourceIdentity.stableLongId(7L, refreshed))
    }

    @Test
    fun `different providers do not share source identities`() {
        val item = entry(tvgId = "same-id", name = "Same title", url = "https://cdn.example.com/item")

        assertThat(M3uSourceIdentity.fromEntry(7L, item))
            .isNotEqualTo(M3uSourceIdentity.fromEntry(8L, item))
    }

    @Test
    fun `same tvg id with different URLs keeps multi-source entries distinct`() {
        val primary = entry(
            tvgId = "news-hd",
            name = "News HD",
            url = "https://primary.example.com/live/news.m3u8?token=first"
        )
        val backup = entry(
            tvgId = "news-hd",
            name = "News HD backup",
            url = "https://backup.example.com/live/news.m3u8?token=second"
        )

        assertThat(M3uSourceIdentity.fromEntry(7L, primary))
            .isNotEqualTo(M3uSourceIdentity.fromEntry(7L, backup))
        assertThat(M3uSourceIdentity.stableLongId(7L, primary))
            .isNotEqualTo(M3uSourceIdentity.stableLongId(7L, backup))
    }

    @Test
    fun `digit-leading acestream hashes remain part of source identity`() {
        val first = entry(
            tvgId = "ace-live",
            name = "Ace Live",
            url = "acestream://1234567890abcdef1234567890abcdef12345678"
        )
        val second = entry(
            tvgId = "ace-live",
            name = "Ace Live backup",
            url = "acestream://2234567890abcdef1234567890abcdef12345678"
        )

        assertThat(M3uSourceIdentity.fromEntry(7L, first))
            .isNotEqualTo(M3uSourceIdentity.fromEntry(7L, second))
        assertThat(M3uSourceIdentity.stableLongId(7L, first))
            .isNotEqualTo(M3uSourceIdentity.stableLongId(7L, second))
    }

    private fun entry(tvgId: String?, name: String, url: String) = M3uParser.M3uEntry(
        name = name,
        groupTitle = "General",
        tvgId = tvgId,
        tvgName = null,
        tvgLogo = null,
        tvgChno = null,
        tvgLanguage = null,
        tvgCountry = null,
        catchUp = null,
        catchUpDays = null,
        catchUpSource = null,
        timeshift = null,
        url = url
    )
}
