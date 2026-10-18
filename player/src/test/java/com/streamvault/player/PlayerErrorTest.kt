package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class PlayerErrorTest {

    @Test
    fun `509 becomes source error instead of auth refresh network error`() {
        val error = PlayerError.fromException(IOException("HTTP 509"))

        assertThat(error).isInstanceOf(PlayerError.SourceError::class.java)
        assertThat(error.message).isEqualTo(
            "Provider rejected playback, likely max connections or bandwidth limit (HTTP 509)."
        )
    }

    @Test
    fun `404 becomes a source error that can enter alternate stream recovery`() {
        val error = PlayerError.fromException(IOException("HTTP 404"))

        assertThat(error).isInstanceOf(PlayerError.SourceError::class.java)
        assertThat(error.message).contains("HTTP 404")
    }
}
