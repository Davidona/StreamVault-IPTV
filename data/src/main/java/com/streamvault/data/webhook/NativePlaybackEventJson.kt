package com.streamvault.data.webhook

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.streamvault.domain.playback.events.PlaybackEvent
import com.streamvault.domain.playback.events.PlaybackExternalIds
import java.util.Locale

/** Explicit schema avoids leaking fields if internal playback models acquire new properties. */
object NativePlaybackEventJson {
    fun encode(event: PlaybackEvent): String = JsonObject().apply {
        addProperty("schema_version", PlaybackEvent.SCHEMA_VERSION)
        addProperty("event_id", event.eventId)
        addProperty("session_id", event.sessionId)
        addProperty("sequence", event.sequence)
        addProperty("event", event.event.name.lowercase(Locale.ROOT))
        addProperty("timestamp", event.timestamp.toString())
        addProperty("type", event.media.type.name.lowercase(Locale.ROOT))
        addProperty("title", event.media.title)
        event.media.series?.let { addProperty("series", it) }
        event.media.season?.let { addProperty("season", it) }
        event.media.episode?.let { addProperty("episode", it) }
        externalIds(event.media.ids)?.let { add("ids", it) }
        externalIds(event.media.seriesIds)?.let { add("series_ids", it) }
        addProperty("position_ms", event.positionMs)
        if (event.durationMs == null) add("duration_ms", JsonNull.INSTANCE)
        else addProperty("duration_ms", event.durationMs)
        if (event.progressPercent == null) add("progress_percent", JsonNull.INSTANCE)
        else addProperty("progress_percent", event.progressPercent)
        addProperty("played_ms", event.playedMs)
        addProperty("watched", event.watched)
        event.stopReason?.let { addProperty("stop_reason", it.name.lowercase(Locale.ROOT)) }
    }.toString()

    private fun externalIds(ids: PlaybackExternalIds): JsonObject? = JsonObject().apply {
        ids.tmdb?.let { addProperty("tmdb", it) }
        ids.imdb?.let { addProperty("imdb", it) }
        ids.tvdb?.let { addProperty("tvdb", it) }
    }.takeIf { it.size() > 0 }
}
