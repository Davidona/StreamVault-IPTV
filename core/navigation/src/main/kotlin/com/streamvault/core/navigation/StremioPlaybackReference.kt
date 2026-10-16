package com.streamvault.core.navigation

import java.io.Serializable

/** Stable content and addon provenance carried from a Stremio stream choice into the player. */
data class StremioPlaybackReference(
    val mediaType: String,
    val exactVideoId: String,
    val parentSeriesId: String? = null,
    val catalogOriginKey: String,
    val metaOriginKey: String? = null,
    val streamOriginKey: String,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
) : Serializable

/** Ephemeral subtitle source passed into the player; never stored in Stremio history. */
data class StremioSubtitlePlaybackSource(
    val url: String,
    val language: String,
    val id: String? = null
) : Serializable

data class StremioPlayerHandoff(
    val streamUrl: String,
    val title: String,
    val artworkUrl: String? = null,
    val contentType: String,
    val reference: StremioPlaybackReference,
    /** Ephemeral only; never copied into addon history. */
    val mediaRequestHeaders: Map<String, String> = emptyMap(),
    /** Inline subtitle URLs live only for this player handoff and are never persisted. */
    val inlineSubtitles: List<StremioSubtitlePlaybackSource> = emptyList()
) : Serializable
