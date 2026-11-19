package com.streamvault.app.navigation

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalItemAction
import com.streamvault.core.navigation.ExternalItemLink
import com.streamvault.core.navigation.ExternalItemType
import com.streamvault.core.navigation.ExternalNavigationRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExternalItemLinkCodecTest {
    private val context = RuntimeEnvironment.getApplication()
    private val parser = ExternalNavigationRequestParser(context)

    @Test fun `all supported actions survive the actual launcher intent URI round trip`() {
        val links = listOf(
            ExternalItemLink(ExternalItemType.MOVIE, 42, 7, ExternalItemAction.DETAILS),
            ExternalItemLink(ExternalItemType.MOVIE, 42, 7, ExternalItemAction.PLAY),
            ExternalItemLink(ExternalItemType.MOVIE, 42, 7, ExternalItemAction.RESUME),
            ExternalItemLink(ExternalItemType.MOVIE, 42, 7, ExternalItemAction.START_OVER),
            ExternalItemLink(ExternalItemType.SERIES, 43, 7, ExternalItemAction.DETAILS),
            ExternalItemLink(ExternalItemType.SERIES, 43, 7, ExternalItemAction.RESUME),
            ExternalItemLink(ExternalItemType.EPISODE, 44, 7, ExternalItemAction.RESUME),
            ExternalItemLink(ExternalItemType.EPISODE, 44, 7, ExternalItemAction.START_OVER),
            ExternalItemLink(ExternalItemType.LIVE, 45, 7, ExternalItemAction.PLAY)
        )
        links.forEach { link ->
            val persisted = ExternalItemLinkCodec.intent(context, link).toUri(Intent.URI_INTENT_SCHEME)
            assertThat(parser.parse(Intent.parseUri(persisted, Intent.URI_INTENT_SCHEME)))
                .isEqualTo(ExternalNavigationRequest.Item(link))
            assertThat(persisted).doesNotContain("PLAYER_REQUEST")
        }
    }

    @Test fun `default actions are details for titles and resume or play for playable items`() {
        mapOf("movie" to ExternalItemAction.DETAILS, "series" to ExternalItemAction.DETAILS,
            "episode" to ExternalItemAction.RESUME, "live" to ExternalItemAction.PLAY).forEach { (type, action) ->
            assertThat(ExternalItemLinkCodec.decode(Uri.parse("streamvault://$type/42?provider=7"))?.action)
                .isEqualTo(action)
        }
    }

    @Test fun `malformed and ambiguous links fall back home without honoring playback extras`() {
        listOf("movie/0?provider=7", "movie/-1?provider=7", "movie/42", "movie/42?provider=0",
            "movie/42?provider=7&provider=8", "movie/42?provider=7&action=play&action=resume",
            "movie/42?provider=7&action=unknown", "movie/42?provider=7&url=https://example.com",
            "movie/9223372036854775808?provider=7", "movie/42?provider=9223372036854775808",
            "movie/42/extra?provider=7", "movie/%34%32?provider=7", "movie/42?provider=7#fragment",
            "user@movie/42?provider=7", "movie:123/42?provider=7", "series/42?provider=7&action=start_over",
            "episode/42?provider=7&action=details", "live/42?provider=7&action=resume",
            "unknown/42?provider=7", "movie/42?provider=7&action=${"a".repeat(2050)}").forEach { path ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("streamvault://$path"))
                .putExtra("com.streamvault.app.extra.EXTERNAL_ROUTE", "plugins")
            assertThat(parser.parse(intent)).isEqualTo(ExternalNavigationRequest.Destination(AppDestination.Home))
        }
    }

    @Test fun `manifest resolves public links without an explicit component`() {
        ExternalItemType.entries.forEach { type ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("streamvault://${type.path}/42?provider=7"))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertThat(handlers.map { it.activityInfo.name }).contains("com.streamvault.app.MainActivity")
        }
    }
}
