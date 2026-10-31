package com.streamvault.app.tvinput

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.ProviderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.mockito.kotlin.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvInputProgramQueryTest {
    private lateinit var provider: SelectionRejectingTvProgramsProvider
    private lateinit var manager: TvInputChannelSyncManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        provider = SelectionRejectingTvProgramsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = TvContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(TvContract.AUTHORITY, provider)
        manager = TvInputChannelSyncManager(
            context = context,
            providerRepository = mock<ProviderRepository>(),
            channelRepository = mock<ChannelRepository>(),
            epgRepository = mock<EpgRepository>()
        )
    }

    @Test
    fun loadExistingPrograms_usesNullSelectionAndKeepsOnlyEligibleRowsWithFingerprints() {
        provider.rows = listOf(
            ProgramRow(id = 11L, channelId = 5L, fingerprint = "eligible-5"),
            ProgramRow(id = 12L, channelId = 7L, fingerprint = null),
            ProgramRow(id = 13L, channelId = 999L, fingerprint = "other-channel"),
            ProgramRow(id = 14L, channelId = 7L, fingerprint = "eligible-7")
        )

        val actual = manager.loadExistingProgramsForTest(setOf(5L, 7L))

        assertEquals(mapOf("eligible-5" to 11L, "eligible-7" to 14L), actual)
        assertEquals(1, provider.queryCount)
        assertNull(provider.lastSelection)
        assertEquals(
            listOf(BaseColumns._ID, "channel_id", "internal_provider_data"),
            provider.lastProjection?.toList()
        )
    }

    @Test
    fun loadExistingPrograms_emptyChannelSetSkipsProviderQuery() {
        assertEquals(emptyMap<String, Long>(), manager.loadExistingProgramsForTest(emptySet()))
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun loadExistingPrograms_manyChannelIdsStillUsesOneSelectionFreeQuery() {
        provider.rows = listOf(
            ProgramRow(id = 1L, channelId = 1L, fingerprint = "first"),
            ProgramRow(id = 2L, channelId = 1200L, fingerprint = "last"),
            ProgramRow(id = 3L, channelId = 1201L, fingerprint = "outside")
        )

        val actual = manager.loadExistingProgramsForTest((1L..1200L).toSet())

        assertEquals(mapOf("first" to 1L, "last" to 2L), actual)
        assertEquals(1, provider.queryCount)
        assertNull(provider.lastSelection)
    }

    private fun TvInputChannelSyncManager.loadExistingProgramsForTest(
        channelIds: Set<Long>
    ): Map<String, Long> {
        val method = javaClass.getDeclaredMethod("loadExistingPrograms", Set::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(this, channelIds) as Map<String, Long>
    }

    private data class ProgramRow(
        val id: Long,
        val channelId: Long,
        val fingerprint: String?
    )

    /** Models TvProvider's rejection of SQL selection on this public programs URI. */
    private class SelectionRejectingTvProgramsProvider : ContentProvider() {
        var rows: List<ProgramRow> = emptyList()
        var queryCount: Int = 0
        var lastSelection: String? = null
        var lastProjection: Array<out String>? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            queryCount++
            lastSelection = selection
            lastProjection = projection
            if (selection != null) {
                throw SecurityException("Selection not allowed for $uri")
            }
            val columns = projection ?: arrayOf(
                BaseColumns._ID,
                "channel_id",
                "internal_provider_data"
            )
            return MatrixCursor(columns).apply {
                rows.forEach { row ->
                    addRow(arrayOf(row.id, row.channelId, row.fingerprint))
                }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            error("Insert is not used by this query regression test")

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Delete is not used by this query regression test")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = error("Update is not used by this query regression test")
    }
}
