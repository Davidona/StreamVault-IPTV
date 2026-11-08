package com.streamvault.player.tracks

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClosedCaptionNameTest {
    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    @Test
    fun `cea-608 and cea-708 formats are closed captions`() {
        assertThat(isClosedCaptionFormat(format(MimeTypes.APPLICATION_CEA608))).isTrue()
        assertThat(isClosedCaptionFormat(format(MimeTypes.APPLICATION_CEA708))).isTrue()
    }

    @Test
    fun `non caption subtitle formats are not renamed`() {
        assertThat(isClosedCaptionFormat(format(MimeTypes.APPLICATION_SUBRIP))).isFalse()
    }

    @Test
    fun `a single caption track is not numbered`() {
        assertThat(closedCaptionNumber(captionIndex = 0, captionCount = 1)).isNull()
    }

    @Test
    fun `multiple caption tracks are numbered by caption position`() {
        assertThat(closedCaptionNumber(captionIndex = 0, captionCount = 2)).isEqualTo(1)
        assertThat(closedCaptionNumber(captionIndex = 1, captionCount = 2)).isEqualTo(2)
    }

    @Test
    fun `a non-caption position is not numbered`() {
        assertThat(closedCaptionNumber(captionIndex = -1, captionCount = 2)).isNull()
    }
}
