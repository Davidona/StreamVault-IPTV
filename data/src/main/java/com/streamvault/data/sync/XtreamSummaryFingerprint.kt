package com.streamvault.data.sync

import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import java.security.MessageDigest

/** Fingerprints only remote summary fields; hydration and local playback state are excluded. */
internal object XtreamSummaryFingerprint {
    private const val HEX_DIGITS = "0123456789abcdef"

    fun movie(movie: MovieEntity): String = digest(
        movie.streamId,
        movie.name,
        movie.posterUrl,
        movie.backdropUrl,
        movie.categoryId,
        movie.categoryName,
        movie.streamUrl,
        movie.containerExtension,
        movie.plot,
        movie.cast,
        movie.director,
        movie.genre,
        movie.releaseDate,
        movie.duration,
        movie.durationSeconds,
        movie.rating,
        movie.year,
        movie.tmdbId,
        movie.youtubeTrailer,
        movie.isAdult,
        movie.addedAt
    )

    fun series(series: SeriesEntity): String = digest(
        series.seriesId,
        series.providerSeriesId,
        series.name,
        series.posterUrl,
        series.backdropUrl,
        series.categoryId,
        series.categoryName,
        series.plot,
        series.cast,
        series.director,
        series.genre,
        series.releaseDate,
        series.rating,
        series.tmdbId,
        series.youtubeTrailer,
        series.episodeRunTime,
        series.lastModified,
        series.isAdult
    )

    private fun digest(vararg values: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            if (value == null) {
                digest.update(0)
            } else {
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                digest.update(1)
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
        }
        val hash = digest.digest()
        val encoded = CharArray(hash.size * 2)
        hash.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            encoded[index * 2] = HEX_DIGITS[unsigned ushr 4]
            encoded[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
        }
        return String(encoded)
    }
}
