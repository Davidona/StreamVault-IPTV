package com.streamvault.app.tv

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.app.benchmark.XtreamSyncBenchmarkEntryPoint
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.SeriesEntity
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.PlaybackState
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Fresh dedicated TV AVD and the local port-8879 fixture used by ExternalLauncherJourneyTest. */
@RunWith(AndroidJUnit4::class)
class LauncherFavoritesJourneyTest {
    @Test fun favoritesPublishAndUpdateWithoutAFullRecommendationRefresh() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("launcherFavoritesFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        check(app.packageName.endsWith(".debug"))
        val graph = EntryPointAccessors.fromApplication(app, XtreamSyncBenchmarkEntryPoint::class.java)
        check(graph.providers().getProviders().first().isEmpty()) { "Use a dedicated empty fixture AVD" }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val directory = File(app.getExternalFilesDir(null), "launcher-favorites").apply { mkdirs() }
        val created = mutableListOf<Long>()
        suspend fun addProvider(suffix: String): Long {
            val result = graph.setup().addM3u(M3uProviderSetupCommand("$BASE/playlist.m3u?$suffix", "Favorites $suffix"))
            val id = when (result) {
                is ValidateAndAddProviderResult.Success -> result.provider.id
                is ValidateAndAddProviderResult.SavedWithWarning -> result.provider.id
                else -> error("Fixture provider setup failed")
            }
            created += id
            return id
        }
        try {
            val provider = addProvider("primary")
            check(graph.providers().setActiveProvider(provider).isSuccess)
            graph.preferences().setAppLanguage("en")
            ActivityScenario.launch(MainActivity::class.java).use {
                check(device.wait(Until.hasObject(By.pkg(app.packageName)), 30_000))
                delay(3_000)
            }
            val db = graph.database()
            val favorites = graph.launcherFavorites()
            val live = withTimeout(30_000) {
                var rows = db.channelDao().getByProviderSync(provider)
                while (rows.isEmpty()) { delay(100); rows = db.channelDao().getByProviderSync(provider) }
                rows.first()
            }
            db.categoryDao().insertAll(listOf(CategoryEntity(categoryId = 9001, name = "Favorite Movies",
                type = ContentType.MOVIE, providerId = provider)))
            val movie = db.movieDao().insert(MovieEntity(name = "Favorites Fixture Movie", providerId = provider,
                streamId = 9002, categoryId = 9001, streamUrl = "$BASE/movie.mp4", durationSeconds = 120, containerExtension = "mp4"))
            val show = db.seriesDao().insert(SeriesEntity(name = "Favorites Fixture Series", providerId = provider))
            val adult = db.movieDao().insert(MovieEntity(name = "Private Fixture", providerId = provider, streamId = 9003, isAdult = true))
            val manager = graph.launcherRecommendations()
            manager.refreshFavorites()
            check(favorites.addFavorite(provider, movie, ContentType.MOVIE).isSuccess)
            check(favorites.addFavorite(provider, show, ContentType.SERIES).isSuccess)
            check(favorites.addFavorite(provider, live.id, ContentType.LIVE).isSuccess)
            check(favorites.addFavorite(provider, adult, ContentType.MOVIE).isSuccess)
            suspend fun awaitRows(titles: List<String>): List<PublishedItem> = try {
                withTimeout(15_000) {
                    var rows = published(app)
                    while (rows.map { it.title } != titles) { delay(100); rows = published(app) }
                    rows
                }
            } catch (error: Exception) {
                val categoryState = ContentType.entries.flatMap { type ->
                    db.categoryDao().getByProviderAndTypeSync(provider, type.name)
                }.map { "${it.categoryId}:${it.type}:${it.isAdult}:${it.isUserProtected}" }
                val channelState = db.channelDao().getByProviderSync(provider)
                    .map { "${it.id}:${it.categoryId}:${it.name}:${it.isAdult}:${it.isUserProtected}" }
                val movieState = db.movieDao().getByProviderSync(provider)
                    .map { "${it.id}:${it.categoryId}:${it.name}:${it.isAdult}:${it.isUserProtected}" }
                val hiddenState = ContentType.entries.associateWith { graph.preferences().getHiddenCategoryIds(provider, it).first() }
                File(directory, "row-failure.txt").writeText("Expected=$titles\nActual=${published(app)}\nCategories=$categoryState\nChannels=$channelState\nMovies=$movieState\nHidden=$hiddenState\nFavorites=${favorites.getFavorites(provider).first()}\n")
                throw error
            }
            val titles = listOf("Favorites Fixture Movie", "Favorites Fixture Series", live.name)
            val original = awaitRows(titles)
            val channelId = original.first().channelId
            app.contentResolver.openInputStream(TvContract.buildChannelLogoUri(channelId))!!.use { input ->
                check(BitmapFactory.decodeStream(input) != null) { "Favorites channel has no readable logo" }
            }
            check(original.map { it.uri.host } == listOf("movie", "series", "live"))
            check(original.map { it.uri.getQueryParameter("action") } == listOf("resume", "resume", "play"))
            check(original.all { it.uri.getQueryParameter("provider") == provider.toString() && !it.intentUri.contains(BASE) })
            File(directory, "published.txt").writeText(original.joinToString("\n"))

            // Change ordering and privacy while only the lightweight observer is running.
            val order = favorites.getFavorites(provider).first().reversed()
            check(favorites.reorderFavorites(order).isSuccess)
            awaitRows(titles.reversed())
            graph.preferences().setCategoryHidden(provider, ContentType.MOVIE, 9001, true)
            awaitRows(listOf(live.name, "Favorites Fixture Series"))
            graph.preferences().setCategoryHidden(provider, ContentType.MOVIE, 9001, false)
            awaitRows(titles.reversed())
            check(graph.launcherCategories().setCategoryProtection(provider, 9001, ContentType.MOVIE, true).isSuccess)
            awaitRows(listOf(live.name, "Favorites Fixture Series"))
            check(graph.launcherCategories().setCategoryProtection(provider, 9001, ContentType.MOVIE, false).isSuccess)
            awaitRows(titles.reversed())
            check(favorites.removeFavorite(provider, live.id, ContentType.LIVE).isSuccess)
            awaitRows(listOf("Favorites Fixture Series", "Favorites Fixture Movie"))
            check(published(app).all { it.channelId == channelId })

            check(graph.launcherHistory().recordPlayback(PlaybackHistory(contentId = movie, providerId = provider,
                contentType = ContentType.MOVIE, title = "Favorites Fixture Movie", streamUrl = "$BASE/movie.mp4",
                resumePositionMs = 30_000, totalDurationMs = 120_000)).isSuccess)
            val engine = graph.launcherPlayerEngine()
            for (theme in listOf(AppTheme.CLASSIC_BLUE, AppTheme.LIGHT, AppTheme.AMOLED_BLACK)) {
                graph.preferences().setAppTheme(theme)
                for (item in published(app)) {
                    val intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
                    ActivityScenario.launch<MainActivity>(intent).use {
                        if (item.uri.host == "movie") {
                            withTimeout(30_000) {
                                while (engine.playbackState.value != PlaybackState.READY || !engine.isPlaying.value ||
                                    engine.currentPosition.value !in 30_000..90_000) delay(100)
                            }
                            delay(4_000)
                        } else check(device.wait(Until.hasObject(By.text("Favorites Fixture Series")), 15_000))
                        val stem = "${theme.storageValue}_${item.uri.host}"
                        check(device.takeScreenshot(File(directory, "${stem}_default.png")))
                        if (item.uri.host == "movie") { device.pressDPadCenter(); delay(200); device.pressDPadRight() }
                        else repeat(3) { device.pressDPadUp(); delay(100) }
                        delay(300)
                        check(device.takeScreenshot(File(directory, "${stem}_focused.png")))
                    }
                }
            }
            val translations = mutableListOf<String>()
            app.resources.getXml(R.xml.locales_config).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                        val locale = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                        val localized = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(locale))
                        })
                        val title = localized.getString(R.string.tv_channel_favorites_title)
                        val description = localized.getString(R.string.tv_channel_favorites_description)
                        check(title.isNotBlank() && description.isNotBlank())
                        if (locale != "en") check(title != "Favorites" && description != app.getString(R.string.tv_channel_favorites_description))
                        translations += "$locale: $title | $description"
                    }
                    parser.next()
                }
            }
            check(translations.size == 38)
            File(directory, "locales.txt").writeText(translations.joinToString("\n"))

            val second = addProvider("secondary")
            check(graph.providers().setActiveProvider(second).isSuccess)
            awaitRows(emptyList())
            val secondMovie = db.movieDao().insert(MovieEntity(name = "Second Provider Favorite", providerId = second))
            check(favorites.addFavorite(second, secondMovie, ContentType.MOVIE).isSuccess)
            check(awaitRows(listOf("Second Provider Favorite")).single().uri.getQueryParameter("provider") == second.toString())
            check(graph.providers().deleteProvider(second).isSuccess)
            withTimeout(15_000) { while (published(app).any { it.uri.getQueryParameter("provider") == second.toString() }) delay(100) }
            File(directory, "result.txt").writeText("PASS: mixed favorites, order, live updates, privacy, published resume links, provider switch/delete, 38 locales, 12 screenshots\n")
        } finally {
            graph.preferences().setAppTheme(AppTheme.CLASSIC_BLUE)
            created.asReversed().forEach { graph.providers().deleteProvider(it) }
        }
    }

    private fun published(app: Application): List<PublishedItem> = app.contentResolver.query(
        Uri.parse("content://android.media.tv/preview_program"),
        arrayOf("channel_id", "title", "internal_provider_id", "intent_uri", "weight", "searchable"), null, null, null
    )!!.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                if (!cursor.getString(2).startsWith("favorite:")) continue
                check(cursor.getInt(5) == 1)
                val intentUri = cursor.getString(3)
                add(PublishedItem(cursor.getLong(0), cursor.getString(1), intentUri,
                    checkNotNull(Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).data), cursor.getInt(4)))
            }
        }.sortedByDescending { it.weight }
    }

    private data class PublishedItem(val channelId: Long, val title: String, val intentUri: String, val uri: Uri, val weight: Int)
    private companion object { const val BASE = "http://10.0.2.2:8879" }
}
