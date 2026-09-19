package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmptyGuideKeyCacheTest {
    @Test
    fun decodePrunesExpiredEntriesAndBoundsNewestEntries() {
        val encoded = buildString {
            append("expired\t100\n")
            repeat(EmptyGuideKeyCache.MAX_ENTRIES + 3) { index ->
                append("key-$index\t${1_000L + index}\n")
            }
        }

        val decoded = decodeEmptyGuideKeys(
            encoded = encoded,
            now = 1_000L + EmptyGuideKeyCache.TTL_MILLIS
        )

        assertThat(decoded).doesNotContainKey("expired")
        assertThat(decoded).hasSize(EmptyGuideKeyCache.MAX_ENTRIES)
        assertThat(decoded).containsKey("key-${EmptyGuideKeyCache.MAX_ENTRIES + 2}")
        assertThat(decoded).doesNotContainKey("key-0")
    }

    @Test
    fun encodeUsesStableNewestFirstRepresentation() {
        val encoded = encodeEmptyGuideKeys(
            mapOf("older" to 10L, "newer" to 20L)
        )

        assertThat(encoded).isEqualTo("newer\t20\nolder\t10")
    }
}
