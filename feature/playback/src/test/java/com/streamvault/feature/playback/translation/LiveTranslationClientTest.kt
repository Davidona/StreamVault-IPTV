package com.streamvault.feature.playback.translation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// parseTranslationUpdate relies on org.json, which is only a throwing stub in
// plain JVM unit tests; Robolectric supplies a real implementation.
@RunWith(RobolectricTestRunner::class)
class LiveTranslationClientTest {

    @Test
    fun parseTranslationUpdate_mapsFinalUpdate() {
        val payload = """
            {
              "sessionId": "s1",
              "chunkId": 7,
              "isFinal": true,
              "text": "hello world",
              "sourceLanguage": "es"
            }
        """.trimIndent()

        val update = parseTranslationUpdate(payload)

        assertThat(update.chunkId).isEqualTo(7L)
        assertThat(update.isFinal).isTrue()
        assertThat(update.text).isEqualTo("hello world")
        assertThat(update.sourceLanguage).isEqualTo("es")
    }

    @Test
    fun parseTranslationUpdate_handlesEmptyAndPartial() {
        val payload = """
            {
              "sessionId": "s1",
              "chunkId": 8,
              "isFinal": false,
              "text": "   "
            }
        """.trimIndent()

        val update = parseTranslationUpdate(payload)

        assertThat(update.chunkId).isEqualTo(8L)
        assertThat(update.isFinal).isFalse()
        assertThat(update.text).isEqualTo("")
        assertThat(update.sourceLanguage).isNull()
    }
}
