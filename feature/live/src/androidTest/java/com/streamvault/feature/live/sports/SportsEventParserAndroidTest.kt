package com.streamvault.feature.live.sports

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises static regex initialization on Android's ICU engine, not the desktop JVM engine. */
@RunWith(AndroidJUnit4::class)
class SportsEventParserAndroidTest {
    @Test
    fun brazilianAnnouncementsInitializeAndParse() {
        assertEquals(
            "Vasco" to "Corinthians",
            SportsEventParser.extractMatchup("Hoje a partir das 18:30 - Vasco x Corinthians"),
        )
        assertEquals(
            "São Bernardo FC" to "Vila Nova",
            SportsEventParser.extractMatchup("Dia 24/10 - São Bernardo FC x Vila Nova"),
        )
    }
}
