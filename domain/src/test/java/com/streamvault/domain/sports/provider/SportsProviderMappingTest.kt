package com.streamvault.domain.sports.provider

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class SportsProviderMappingTest {
    @Test fun `explicit offset seconds and milliseconds resolve to the same instant`() {
        val expected = Instant.parse("2026-10-17T18:00:00Z")
        assertThat(parseProviderTimestamp("2026-10-17T21:00:00+03:00", ProviderTimestampFormat.ISO_OFFSET))
            .isEqualTo(expected)
        assertThat(parseProviderTimestamp(expected.epochSecond.toString(), ProviderTimestampFormat.UNIX_SECONDS))
            .isEqualTo(expected)
        assertThat(parseProviderTimestamp(expected.toEpochMilli().toString(), ProviderTimestampFormat.UNIX_MILLISECONDS))
            .isEqualTo(expected)
    }

    @Test fun `timezone less timestamps are rejected rather than using device timezone`() {
        assertThrows(java.time.format.DateTimeParseException::class.java) {
            parseProviderTimestamp("2026-10-17T21:00:00", ProviderTimestampFormat.ISO_OFFSET)
        }
    }

    @Test fun `fractional seconds are retained`() {
        assertThat(parseProviderTimestamp("2026-10-17T21:00:00.123+03:00", ProviderTimestampFormat.ISO_OFFSET))
            .isEqualTo(Instant.parse("2026-10-17T18:00:00.123Z"))
    }

    @Test fun `malformed items do not discard valid peers or expose raw exceptions`() {
        val result = mapProviderItems(listOf("event-a", "bad-private-key", "event-b")) {
            require(!it.startsWith("bad")) { "secret echoed: $it" }
            ProviderEvent(it)
        }
        val batch = (result as SportsProviderResult.Success).value
        assertThat(batch.items.map { it.providerEventId }).containsExactly("event-a", "event-b").inOrder()
        assertThat(batch.rejectedItemCount).isEqualTo(1)
        assertThat(result.toString()).doesNotContain("private-key")
    }

    @Test fun `all invalid response differs from a valid empty feed`() {
        assertThat(mapProviderItems(listOf("bad")) { ProviderEvent("") })
            .isEqualTo(SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE))
        assertThat(mapProviderItems(emptyList<String>()) { ProviderEvent(it) })
            .isEqualTo(SportsProviderResult.Success(ProviderBatch<ProviderEvent>(emptyList())))
    }

    @Test fun `mapping cancellation is not counted as a rejected item`() {
        assertThrows(CancellationException::class.java) {
            mapProviderItems(listOf("a")) { throw CancellationException("cancelled") }
        }
    }

    @Test fun `item budget bounds valid and invalid mapping work`() {
        for (invalid in listOf(false, true)) {
            var calls = 0
            val result = mapProviderItems(listOf("a", "b", "c"), maxItems = 2) {
                calls++
                if (invalid) error("bad private payload")
                ProviderEvent(it)
            }
            assertThat(calls).isEqualTo(2)
            assertThat(result).isEqualTo(SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE))
        }
        assertThat(mapProviderItems(listOf("a", "b"), maxItems = 2) { ProviderEvent(it) })
            .isInstanceOf(SportsProviderResult.Success::class.java)
    }
}
