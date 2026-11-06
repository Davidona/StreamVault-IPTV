package com.streamvault.player.tracks

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClosedCaptionNameTest {
    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    @Test
    fun `single cea-608 track is named Closed Captions`() {
        assertThat(closedCaptionName(format(MimeTypes.APPLICATION_CEA608), 0, 1))
            .isEqualTo("Closed Captions")
    }

    @Test
    fun `single cea-708 track is named Closed Captions`() {
        assertThat(closedCaptionName(format(MimeTypes.APPLICATION_CEA708), 0, 1))
            .isEqualTo("Closed Captions")
    }

    @Test
    fun `multiple caption tracks are numbered from one`() {
        assertThat(closedCaptionName(format(MimeTypes.APPLICATION_CEA608), 1, 2))
            .isEqualTo("Closed Captions 2")
    }

    @Test
    fun `non caption subtitle formats are not renamed`() {
        assertThat(closedCaptionName(format(MimeTypes.APPLICATION_SUBRIP), 0, 1)).isNull()
    }
}
