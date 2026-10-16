package com.streamvault.domain.manager

/** Resolves a persisted magnet link to a local HTTP stream for the download worker. */
fun interface TorrentDownloadStreamResolver {
    suspend fun resolve(magnetUri: String): String
}
