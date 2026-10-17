package com.streamvault.domain.sports.provider

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

enum class ProviderTimestampFormat { ISO_OFFSET, UNIX_SECONDS, UNIX_MILLISECONDS }

/** No machine timezone, guessing seconds vs milliseconds, or timezone-less ISO dates. */
fun parseProviderTimestamp(value: String, format: ProviderTimestampFormat): Instant = when (format) {
    ProviderTimestampFormat.ISO_OFFSET -> OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    ProviderTimestampFormat.UNIX_SECONDS -> Instant.ofEpochSecond(value.toLong())
    ProviderTimestampFormat.UNIX_MILLISECONDS -> Instant.ofEpochMilli(value.toLong())
}

/**
 * Adapter mapping boundary. Skip malformed records without retaining source values or
 * exceptions. An entirely invalid nonempty response is a failure, not a valid empty feed.
 * Decode the outer JSON envelope through SportsProviderHttpClient before mapping items.
 */
fun <Raw, T : Any> mapProviderItems(
    records: Iterable<Raw>,
    maxItems: Int = 10_000,
    map: (Raw) -> T,
): SportsProviderResult<ProviderBatch<T>> {
    require(maxItems in 1..100_000)
    val items = mutableListOf<T>()
    var rejected = 0
    for (record in records) {
        if (items.size + rejected >= maxItems) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        }
        try {
            items.add(map(record))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            rejected++
        }
    }
    return if (items.isEmpty() && rejected > 0) {
        SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
    } else {
        SportsProviderResult.Success(ProviderBatch(items, rejected))
    }
}
