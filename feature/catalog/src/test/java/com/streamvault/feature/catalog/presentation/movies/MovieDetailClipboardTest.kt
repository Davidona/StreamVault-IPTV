package com.streamvault.feature.catalog.presentation.movies

import android.content.ClipboardManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MovieDetailClipboardTest {

    @Test
    fun `copy URL writes the resolved stream URL to the primary clipboard`() {
        val application = RuntimeEnvironment.getApplication()
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val context: Context = mock()
        whenever(context.getString(any())).thenReturn("Stream URL")
        whenever(context.getSystemService(Context.CLIPBOARD_SERVICE)).thenReturn(clipboard)
        val streamUrl = "https://fixture.test/movie/1001.m3u8"
        copyStreamUrlToClipboard(context, streamUrl)

        assertThat(clipboard).isNotNull()
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            .isEqualTo(streamUrl)
    }
}
