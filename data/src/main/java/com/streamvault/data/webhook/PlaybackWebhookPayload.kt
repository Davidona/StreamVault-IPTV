package com.streamvault.data.webhook

import com.google.gson.JsonObject
import com.streamvault.domain.playback.events.*

object PlaybackWebhookPayload {
    fun encode(format: PlaybackWebhookFormat, event: PlaybackEvent, installationId: String): String? {
        if (format == PlaybackWebhookFormat.NATIVE) return NativePlaybackEventJson.encode(event)
        if (event.event !in setOf(PlaybackEventType.START, PlaybackEventType.STOP) ||
            event.media.type == PlaybackMediaType.LIVE) return null
        val media = event.media
        val movie = media.type == PlaybackMediaType.MOVIE
        if (format == PlaybackWebhookFormat.YAMTRACK) {
            // v0.26.3 resolves episodes by episode IMDb/TVDB identity, not show TMDB ID.
            if (movie && media.ids.tmdb == null && media.ids.imdb == null) return null
            if (!movie && media.ids.imdb == null && media.ids.tvdb == null) return null
        } else if ((if (movie) media.ids.tmdb else media.seriesIds.tmdb) == null) return null
        if (!movie && (media.season == null || media.episode == null)) return null
        val start = event.event == PlaybackEventType.START
        val ids = JsonObject().apply {
            media.ids.tmdb?.let { addProperty("Tmdb", it.toString()) }
            media.ids.imdb?.let { addProperty("Imdb", it) }
            media.ids.tvdb?.let { addProperty("Tvdb", it.toString()) }
        }
        if (format == PlaybackWebhookFormat.YAMTRACK) return JsonObject().apply {
            addProperty("Event", if (start) "Play" else "Stop")
            add("Item", JsonObject().apply {
                addProperty("Type", if (movie) "Movie" else "Episode")
                addProperty("Name", media.title)
                add("ProviderIds", ids)
                add("UserData", JsonObject().apply { addProperty("Played", event.watched) })
                if (!movie) {
                    addProperty("SeriesName", media.series)
                    addProperty("ParentIndexNumber", media.season)
                    addProperty("IndexNumber", media.episode)
                }
            })
            if (!movie) add("Series", JsonObject().apply {
                add("ProviderIds", JsonObject().apply { addProperty("Tmdb", media.seriesIds.tmdb.toString()) })
            })
        }.toString()
        return JsonObject().apply {
            addProperty("NotificationType", if (start) "PlaybackStart" else "PlaybackStop")
            addProperty("ServerId", installationId)
            addProperty("ServerName", "StreamVault")
            addProperty("ClientName", "StreamVault")
            addProperty("UtcTimestamp", event.timestamp.toString())
            addProperty("Name", media.title)
            addProperty("ItemType", if (movie) "Movie" else "Episode")
            addProperty("ItemId", if (movie) "movie-${media.ids.tmdb}"
                else "show-${media.seriesIds.tmdb}-${media.season}-${media.episode}")
            media.ids.tmdb?.let { addProperty("Provider_tmdb", it.toString()) }
            media.ids.imdb?.let { addProperty("Provider_imdb", it) }
            media.ids.tvdb?.let { addProperty("Provider_tvdb", it.toString()) }
            if (!movie) {
                addProperty("SeriesName", media.series)
                addProperty("SeriesId", "show-${media.seriesIds.tmdb}")
                addProperty("SeasonNumber", media.season)
                addProperty("EpisodeNumber", media.episode)
            }
            addProperty("PlaybackPositionTicks", event.positionMs.coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000)
            event.durationMs?.let { addProperty("RunTimeTicks", it.coerceAtMost(Long.MAX_VALUE / 10_000) * 10_000) }
            if (!start) addProperty("PlayedToCompletion", event.watched)
        }.toString()
    }
}
