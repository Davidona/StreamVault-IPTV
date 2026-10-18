package com.streamvault.torrent

import android.content.Context
import android.content.SharedPreferences

class TorrentPreferences(context: Context) {
    private val store: SharedPreferences = context.getSharedPreferences("torrent_playback", Context.MODE_PRIVATE)

    var downloadLimitBytesPerSecond: Int
        get() = store.getInt(KEY_DOWNLOAD_LIMIT, 0).coerceIn(0, MAX_SPEED_LIMIT_BYTES_PER_SECOND)
        set(value) = store.edit().putInt(
            KEY_DOWNLOAD_LIMIT,
            value.coerceIn(0, MAX_SPEED_LIMIT_BYTES_PER_SECOND)
        ).apply()

    var uploadLimitBytesPerSecond: Int
        get() = store.getInt(KEY_UPLOAD_LIMIT, 64 * 1024).coerceIn(0, MAX_SPEED_LIMIT_BYTES_PER_SECOND)
        set(value) = store.edit().putInt(
            KEY_UPLOAD_LIMIT,
            value.coerceIn(0, MAX_SPEED_LIMIT_BYTES_PER_SECOND)
        ).apply()

    var bufferMiB: Int
        get() = store.getInt(KEY_BUFFER_MIB, 32).coerceIn(MIN_BUFFER_MIB, MAX_BUFFER_MIB)
        set(value) = store.edit().putInt(KEY_BUFFER_MIB, value.coerceIn(MIN_BUFFER_MIB, MAX_BUFFER_MIB)).apply()

    var storageLocationIndex: Int
        get() = store.getInt(KEY_STORAGE_LOCATION, 0).coerceAtLeast(0)
        set(value) = store.edit().putInt(KEY_STORAGE_LOCATION, value.coerceAtLeast(0)).apply()

    companion object {
        const val MIN_BUFFER_MIB = 4
        const val MAX_BUFFER_MIB = 128
        const val SPEED_LIMIT_STEP_BYTES_PER_SECOND = 64 * 1024
        const val MAX_SPEED_LIMIT_BYTES_PER_SECOND = 100 * SPEED_LIMIT_STEP_BYTES_PER_SECOND

        private const val KEY_DOWNLOAD_LIMIT = "download_limit_bytes_per_second"
        private const val KEY_UPLOAD_LIMIT = "upload_limit_bytes_per_second"
        private const val KEY_BUFFER_MIB = "buffer_mib"
        private const val KEY_STORAGE_LOCATION = "storage_location_index"
    }
}
