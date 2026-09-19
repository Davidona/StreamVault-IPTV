package com.streamvault.app.navigation

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import com.streamvault.app.MainActivity
import com.streamvault.app.backup.BackupFileBridge
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.PlayerNavigationRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

class ExternalNavigationRequestParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun parse(intent: Intent): ExternalNavigationRequest? {
        readPlayerRequestExtra(intent)?.let { return ExternalNavigationRequest.Player(it) }
        readExternalDestinationExtra(intent)?.let { return ExternalNavigationRequest.Destination(it) }

        intent.getStringExtra(MainActivity.EXTRA_EXTERNAL_ROUTE)
            ?.let(AppRouteCodec::decodeLegacyExternalRoute)
            ?.let { return ExternalNavigationRequest.Destination(it) }
        if (intent.hasExtra(MainActivity.EXTRA_EXTERNAL_ROUTE)) {
            return ExternalNavigationRequest.Destination(AppDestination.Home)
        }

        readImportedPlaylistUri(intent)?.let { return ExternalNavigationRequest.ImportM3u(it) }
        readImportedBackupUri(intent)?.let { return ExternalNavigationRequest.ImportBackup(it) }

        val query = when (intent.action) {
            Intent.ACTION_SEARCH,
            Intent.ACTION_ASSIST,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE -> {
                intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            }
            else -> null
        }?.trim().orEmpty()
        query.takeIf(String::isNotBlank)?.let { return ExternalNavigationRequest.Search(it) }

        return if (intent.action == Intent.ACTION_VIEW) {
            ExternalNavigationRequest.Destination(AppDestination.Home)
        } else {
            null
        }
    }

    private fun readImportedPlaylistUri(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val targetUri = intent.data ?: return null
        val normalizedPath = targetUri.toString().substringBefore('?').lowercase(Locale.ROOT)
        val mimeType = intent.type?.lowercase(Locale.ROOT).orEmpty()
        val isPlaylistMime = mimeType in setOf(
            "audio/x-mpegurl",
            "audio/mpegurl",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/mpegurl"
        )
        val isPlaylistPath = normalizedPath.endsWith(".m3u") || normalizedPath.endsWith(".m3u8")
        if (!isPlaylistMime && !isPlaylistPath) return null
        return targetUri.toString().takeIf {
            targetUri.scheme?.lowercase(Locale.ROOT) in setOf("content", "file")
        }
    }

    private fun readImportedBackupUri(intent: Intent): String? {
        val targetUri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> readStreamUriExtra(intent)
            else -> null
        } ?: return null
        if (!isBackupJsonCandidate(intent, targetUri)) return null
        return BackupFileBridge.copyToImportInbox(context, targetUri)?.toString()
            ?: targetUri.toString()
    }

    @Suppress("DEPRECATION")
    private fun readStreamUriExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    private fun isBackupJsonCandidate(intent: Intent, uri: Uri): Boolean {
        val normalizedPath = uri.toString().substringBefore('?').lowercase(Locale.ROOT)
        val mimeType = intent.type?.lowercase(Locale.ROOT).orEmpty()
        val isJsonMime = mimeType in setOf(
            "application/json",
            "text/json",
            "application/x-json",
            "application/octet-stream",
            "text/plain"
        )
        val isJsonPath = normalizedPath.endsWith(".json")
        if (!isJsonMime && !isJsonPath) return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("content", "file")
    }

    @Suppress("DEPRECATION")
    private fun readPlayerRequestExtra(intent: Intent): PlayerNavigationRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(
                MainActivity.EXTRA_PLAYER_REQUEST,
                PlayerNavigationRequest::class.java
            )
        } else {
            intent.getSerializableExtra(MainActivity.EXTRA_PLAYER_REQUEST) as? PlayerNavigationRequest
        }

    @Suppress("DEPRECATION")
    private fun readExternalDestinationExtra(intent: Intent): AppDestination? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(
                MainActivity.EXTRA_EXTERNAL_DESTINATION,
                AppDestination::class.java
            )
        } else {
            intent.getSerializableExtra(MainActivity.EXTRA_EXTERNAL_DESTINATION) as? AppDestination
        }
}
