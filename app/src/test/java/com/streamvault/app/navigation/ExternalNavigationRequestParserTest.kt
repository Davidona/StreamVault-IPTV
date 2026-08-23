package com.streamvault.app.navigation

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExternalNavigationRequestParserTest {
    private val context = RuntimeEnvironment.getApplication()
    private val parser = ExternalNavigationRequestParser(context)

    @Test
    fun searchIntentBecomesTypedSearchRequest() {
        val intent = Intent(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, " sports ")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.Search("sports"))
    }

    @Test
    fun malformedLegacyRouteFallsBackHome() {
        val intent = Intent().putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "bad/route")

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }

    @Test
    fun playlistMimeWithContentUriBecomesImportRequest() {
        val uri = Uri.parse("content://example/playlist")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/x-mpegurl")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportM3u(uri.toString()))
    }

    @Test
    fun playlistPathWithoutMimeBecomesImportRequest() {
        val uri = Uri.parse("file:///sdcard/playlist.m3u8")
        val intent = Intent(Intent.ACTION_VIEW).setData(uri)

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportM3u(uri.toString()))
    }

    @Test
    fun backupJsonStreamBecomesImportRequest() {
        val uri = Uri.parse("content://example/backup.json")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.ImportBackup(uri.toString()))
    }

    @Test
    fun playerExtraTakesPrecedence() {
        val request = PlayerNavigationRequest(
            streamUrl = "https://example.com/live.m3u8",
            title = "News"
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, request)
            .putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, "home")

        assertThat(parser.parse(intent))
            .isEqualTo(ExternalNavigationRequest.Player(request))
    }

    @Test
    fun typedDestinationExtraBecomesDestinationRequest() {
        val intent = Intent()
            .putExtra(
                MainActivity.EXTRA_EXTERNAL_DESTINATION,
                AppDestination.Settings(backupUri = "content://example/backup.json")
            )

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(
                AppDestination.Settings(backupUri = "content://example/backup.json")
            )
        )
    }

    @Test
    fun genericViewFallsBackHome() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://example.com/watch/42"))

        assertThat(parser.parse(intent)).isEqualTo(
            ExternalNavigationRequest.Destination(AppDestination.Home)
        )
    }

    @Test
    fun blankSearchAssistantAndVoiceQueriesProduceNoRequest() {
        assertThat(
            parser.parse(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "  "))
        ).isNull()
        assertThat(
            parser.parse(Intent(Intent.ACTION_ASSIST).putExtra(SearchManager.QUERY, ""))
        ).isNull()
        assertThat(
            parser.parse(
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE)
                    .putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(" "))
            )
        ).isNull()
    }
}
