package com.streamvault.domain.model.stremio

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Stremio's anchored, zlib-compressed little-endian per-episode bitmap. */
object StremioWatchedField {
    private const val MAX_VIDEOS = 100_000

    fun orderedVideos(videos: List<StremioVideo>): List<StremioVideo> = videos.distinctBy { it.id }
        .sortedWith(compareBy({ it.season ?: Int.MIN_VALUE }, { it.episode ?: Int.MIN_VALUE },
            { it.releaseInstant()?.toEpochMilli() ?: Long.MIN_VALUE }))

    fun decode(value: String?, videoIds: List<String>): Set<String>? = runCatching {
        if (value.isNullOrBlank()) return@runCatching emptySet()
        require(value.length <= 100_000 && videoIds.size <= MAX_VIDEOS)
        val last = value.lastIndexOf(':')
        val middle = value.lastIndexOf(':', last - 1)
        require(middle > 0 && last > middle)
        val anchor = value.substring(0, middle)
        val length = value.substring(middle + 1, last).toInt()
        require(length in 1..MAX_VIDEOS)
        val index = videoIds.indexOf(anchor)
        if (index < 0) return@runCatching emptySet()
        val bytes = InflaterInputStream(Base64.getDecoder().decode(value.substring(last + 1)).inputStream()).use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= (MAX_VIDEOS + 7) / 8)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val offset = length - index - 1
        videoIds.filterIndexed { i, _ ->
            val bit = i + offset
            bit >= 0 && bit / 8 < bytes.size && (bytes[bit / 8].toInt() and (1 shl (bit % 8))) != 0
        }.toSet()
    }.getOrNull()

    fun encode(videoIds: List<String>, watchedIds: Set<String>): String {
        require(videoIds.isNotEmpty() && videoIds.size <= MAX_VIDEOS)
        val bytes = ByteArray((videoIds.size + 7) / 8)
        var last = 0
        videoIds.forEachIndexed { index, id ->
            if (id in watchedIds) {
                bytes[index / 8] = (bytes[index / 8].toInt() or (1 shl (index % 8))).toByte()
                last = index
            }
        }
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(bytes) }
        return "${videoIds[last]}:${last + 1}:${Base64.getEncoder().encodeToString(output.toByteArray())}"
    }
}
