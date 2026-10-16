package com.streamvault.app.tv

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LauncherProviderInstrumentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contentResolver: ContentResolver = context.contentResolver

    @Test
    fun watchNextProgram_supportsInsertUpdateDeleteRoundTrip() {
        assumeTrue("Launcher provider tests require an Android TV device", isTelevisionDevice())

        val key = "watch-next-${UUID.randomUUID()}"
        val insertUri = try {
            contentResolver.insert(
                TvContract.WatchNextPrograms.CONTENT_URI,
                buildWatchNextValues(key, title = "Instrumentation Watch Next")
            )
        } catch (throwable: Throwable) {
            throw AssertionError(buildFailureMessage("Watch Next insert failed", throwable))
        }

        assertNotNull(buildFailureMessage("Watch Next insert returned null", null), insertUri)
        val rowUri = insertUri!!

        try {
            val inserted = querySingle(
                uri = rowUri,
                projection = arrayOf(COLUMN_TITLE, COLUMN_DESCRIPTION, COLUMN_INTERNAL_PROVIDER_ID),
                mapper = { cursor ->
                    Triple(
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DESCRIPTION)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID))
                    )
                }
            )

            assertEquals("Instrumentation Watch Next", inserted.first)
            assertEquals(key, inserted.third)

            val updateCount = contentResolver.update(
                rowUri,
                buildWatchNextValues(key, title = "Updated Watch Next"),
                null,
                null
            )
            assertTrue(buildFailureMessage("Watch Next update affected no rows", null), updateCount > 0)

            val updatedTitle = querySingle(
                uri = rowUri,
                projection = arrayOf(COLUMN_TITLE),
                mapper = { cursor -> cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)) }
            )
            assertEquals("Updated Watch Next", updatedTitle)
        } finally {
            contentResolver.delete(rowUri, null, null)
            assertRowMissing(rowUri, "Watch Next delete failed")
        }
    }

    @Test
    fun previewChannelAndProgram_supportRoundTrip() {
        assumeTrue("Launcher provider tests require an Android TV device", isTelevisionDevice())

        val key = "preview-channel-${UUID.randomUUID()}"
        val channelUri = try {
            contentResolver.insert(
                TvContract.Channels.CONTENT_URI,
                buildPreviewChannelValues(key, "Instrumentation Channel")
            )
        } catch (throwable: Throwable) {
            throw AssertionError(buildFailureMessage("Preview channel insert failed", throwable))
        }

        assertNotNull(buildFailureMessage("Preview channel insert returned null", null), channelUri)
        val resolvedChannelUri = channelUri!!
        val channelId = ContentUris.parseId(resolvedChannelUri)
        val programKey = "preview-program-${UUID.randomUUID()}"

        try {
            val programUri = try {
                contentResolver.insert(
                    TvContract.PreviewPrograms.CONTENT_URI,
                    buildPreviewProgramValues(channelId, programKey, "Instrumentation Program")
                )
            } catch (throwable: Throwable) {
                throw AssertionError(buildFailureMessage("Preview program insert failed", throwable))
            }

            assertNotNull(buildFailureMessage("Preview program insert returned null", null), programUri)
            val resolvedProgramUri = programUri!!

            try {
                val inserted = querySingle(
                    uri = resolvedProgramUri,
                    projection = arrayOf(COLUMN_TITLE, COLUMN_INTERNAL_PROVIDER_ID),
                    mapper = { cursor ->
                        Pair(
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
                            cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_INTERNAL_PROVIDER_ID))
                        )
                    }
                )
                assertEquals("Instrumentation Program", inserted.first)
                assertEquals(programKey, inserted.second)

                val updatedRows = contentResolver.update(
                    resolvedProgramUri,
                    buildPreviewProgramValues(channelId, programKey, "Updated Program"),
                    null,
                    null
                )
                assertTrue(buildFailureMessage("Preview program update affected no rows", null), updatedRows > 0)

                val updatedTitle = querySingle(
                    uri = resolvedProgramUri,
                    projection = arrayOf(COLUMN_TITLE),
                    mapper = { cursor -> cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)) }
                )
                assertEquals("Updated Program", updatedTitle)
            } finally {
                contentResolver.delete(resolvedProgramUri, null, null)
                assertRowMissing(resolvedProgramUri, "Preview program delete failed")
            }
        } finally {
            contentResolver.delete(resolvedChannelUri, null, null)
            assertRowMissing(resolvedChannelUri, "Preview channel delete failed")
        }
    }

    private fun buildWatchNextValues(key: String, title: String): ContentValues = ContentValues().apply {
        put(COLUMN_INTERNAL_PROVIDER_ID, key)
        put(COLUMN_TITLE, title)
        put(COLUMN_DESCRIPTION, "Instrumentation validation for Watch Next")
        put(COLUMN_POSTER_ART_URI, resourceArtworkUri().toString())
        put(COLUMN_INTENT_URI, mainActivityIntentUri())
        put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 30_000L)
        put(COLUMN_DURATION_MILLIS, 120_000L)
        put(COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
        put(COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
    }

    private fun buildPreviewChannelValues(key: String, title: String): ContentValues = ContentValues().apply {
        put(COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
        put(COLUMN_DISPLAY_NAME, title)
        put(COLUMN_DESCRIPTION, "Instrumentation validation for preview channels")
        put(COLUMN_INTERNAL_PROVIDER_ID, key)
        put(COLUMN_APP_LINK_INTENT_URI, mainActivityIntentUri())
    }

    private fun buildPreviewProgramValues(channelId: Long, key: String, title: String): ContentValues = ContentValues().apply {
        put(COLUMN_CHANNEL_ID, channelId)
        put(COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_MOVIE)
        put(COLUMN_TITLE, title)
        put(COLUMN_DESCRIPTION, "Instrumentation validation for preview programs")
        put(COLUMN_POSTER_ART_URI, resourceArtworkUri().toString())
        put(COLUMN_INTENT_URI, mainActivityIntentUri())
        put(COLUMN_WEIGHT, 100)
        put(COLUMN_INTERNAL_PROVIDER_ID, key)
        put(COLUMN_DURATION_MILLIS, 120_000L)
        put(COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 15_000L)
    }

    private fun resourceArtworkUri(): Uri =
        Uri.parse("android.resource://${context.packageName}/${com.streamvault.app.R.mipmap.ic_launcher}")

    private fun mainActivityIntentUri(): String =
        Intent(context, com.streamvault.app.MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .toUri(Intent.URI_INTENT_SCHEME)

    private fun isTelevisionDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun assertRowMissing(uri: Uri, prefix: String) {
        val remaining = contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)
            ?.use { cursor -> cursor.count }
            ?: 0
        assertEquals(buildFailureMessage(prefix, null), 0, remaining)
    }

    private fun <T> querySingle(uri: Uri, projection: Array<String>, mapper: (android.database.Cursor) -> T): T {
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            assertTrue(buildFailureMessage("Query returned no rows for $uri", null), cursor.moveToFirst())
            mapper(cursor)
        } ?: throw AssertionError(buildFailureMessage("Query returned null cursor for $uri", null))
    }

    private fun buildFailureMessage(message: String, throwable: Throwable?): String {
        val packageManager = context.packageManager
        val leanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val launcherPackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        ).joinToString { it.activityInfo.packageName }

        throwable?.let {
            Log.e(TAG, message, it)
        }

        val details = listOf(
            message,
            "manufacturer=${Build.MANUFACTURER}",
            "model=${Build.MODEL}",
            "sdk=${Build.VERSION.SDK_INT}",
            "leanback=$leanback",
            "homePackages=$launcherPackages",
            "watchNextAuthority=${TvContract.WatchNextPrograms.CONTENT_URI.authority}",
            "channelsAuthority=${TvContract.Channels.CONTENT_URI.authority}",
            throwable?.let { "error=${it::class.java.simpleName}:${it.message}" }
        ).filterNotNull()

        return details.joinToString(separator = " | ")
    }

    private companion object {
        const val TAG = "LauncherProviderTest"
        const val COLUMN_TYPE = "type"
        const val COLUMN_CHANNEL_ID = "channel_id"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_TITLE = "title"
        const val COLUMN_DESCRIPTION = "description"
        const val COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri"
        const val COLUMN_INTENT_URI = "intent_uri"
        const val COLUMN_POSTER_ART_URI = "poster_art_uri"
        const val COLUMN_WEIGHT = "weight"
        const val COLUMN_DURATION_MILLIS = "duration_millis"
        const val COLUMN_LAST_PLAYBACK_POSITION_MILLIS = "last_playback_position_millis"
        const val COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS = "last_engagement_time_utc_millis"
        const val COLUMN_WATCH_NEXT_TYPE = "watch_next_type"
    }
}