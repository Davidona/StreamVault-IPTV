package com.streamvault.torrent

import android.content.Context
import java.io.File

internal data class TorrentStorageLocation(
    val label: String,
    val directory: File
)

internal object TorrentCachePolicy {
    const val MAX_CACHE_BYTES = 8L * 1024L * 1024L * 1024L
    const val MIN_FREE_STORAGE_BYTES = 512L * 1024L * 1024L
}

internal object TorrentStorageLocations {
    private val cacheLock = Any()

    fun <T> withCacheLock(action: () -> T): T = synchronized(cacheLock, action)

    fun available(context: Context): List<TorrentStorageLocation> {
        val locations = mutableListOf(TorrentStorageLocation("Private app storage", context.filesDir))
        val seen = locations.mapTo(mutableSetOf()) { canonicalPath(it.directory) }
        context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { index, directory ->
            val canonical = canonicalPath(directory)
            if (seen.add(canonical)) {
                val label = if (index == 0) {
                    "Device storage (app-private)"
                } else {
                    "Removable storage ${index}"
                }
                locations += TorrentStorageLocation(label, directory)
            }
        }
        return locations
    }

    fun torrentDirectory(context: Context, selectedIndex: Int): File {
        val location = available(context).getOrNull(selectedIndex)
            ?: available(context).first()
        return File(location.directory, "torrent-cache").canonicalFile
    }

    fun cleanupOrphanedSessionDirectories(root: File): Boolean = withCacheLock {
        removeSessionDirectories(root)
    }

    fun clearSessionDirectories(root: File): Boolean = withCacheLock {
        removeSessionDirectories(root)
    }

    private fun removeSessionDirectories(root: File): Boolean {
        if (!root.exists() && !root.mkdirs()) return false
        val entries = root.listFiles() ?: return false
        var success = true
        entries
            .filter { it.isDirectory && TORRENT_DIRECTORY_NAME.matches(it.name) }
            .forEach { directory ->
                if (!directory.deleteRecursively()) success = false
            }
        return success
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private val TORRENT_DIRECTORY_NAME = Regex("[a-f0-9]{40}")
}
