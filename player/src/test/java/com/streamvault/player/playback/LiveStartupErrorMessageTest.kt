package com.streamvault.player.playback

import android.content.res.Configuration
import android.os.LocaleList
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LiveStartupErrorMessageTest {
    @Test
    fun `message follows selected app locale for every advertised locale without mutating context`() {
        val repositoryRoot = findRepositoryRoot()
        val advertisedLocales = readAdvertisedLocales(
            repositoryRoot.resolve("app/src/main/res/xml/locales_config.xml")
        )
        assertThat(advertisedLocales).hasSize(38)

        val applicationContext = RuntimeEnvironment.getApplication()
        val originalLocale = Locale.getDefault()
        val originalApplicationConfiguration = Configuration(applicationContext.resources.configuration)
        val englishConfiguration = Configuration(originalApplicationConfiguration).apply {
            setLocales(LocaleList.forLanguageTags("en-US"))
        }
        val englishContext = applicationContext.createConfigurationContext(englishConfiguration)
        val englishContextConfiguration = Configuration(englishContext.resources.configuration)
        val englishMessage = englishContext.getString(com.streamvault.player.R.string.player_error_live_startup_timeout)

        try {
            for (languageTag in advertisedLocales) {
                val selectedLocale = Locale.forLanguageTag(languageTag)
                Locale.setDefault(selectedLocale)

                val expectedXmlMessage = readExpectedString(
                    repositoryRoot.resolve("player/src/main/res"),
                    languageTag,
                    "player_error_live_startup_timeout"
                )
                val actualMessage = liveStartupTimeoutMessage(englishContext)

                assertWithMessage("message for advertised locale $languageTag")
                    .that(actualMessage)
                    .isEqualTo(expectedXmlMessage)
                if (languageTag != "en") {
                    assertWithMessage("localized message for $languageTag")
                        .that(actualMessage)
                        .isNotEqualTo(englishMessage)
                }
                assertWithMessage("caller context configuration after $languageTag lookup")
                    .that(englishContext.resources.configuration)
                    .isEqualTo(englishContextConfiguration)
                assertWithMessage("application context configuration after $languageTag lookup")
                    .that(applicationContext.resources.configuration)
                    .isEqualTo(originalApplicationConfiguration)
                assertWithMessage("process default locale after $languageTag lookup")
                    .that(Locale.getDefault())
                    .isEqualTo(selectedLocale)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    private fun findRepositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var current = File(workingDirectory).absoluteFile
        while (true) {
            if (File(current, "app/src/main/res/xml/locales_config.xml").isFile &&
                File(current, "player/src/main/res").isDirectory
            ) {
                return current
            }
            current = current.parentFile ?: error("Could not find repository root from $workingDirectory")
        }
    }

    private fun readAdvertisedLocales(localesConfig: File): List<String> {
        require(localesConfig.isFile) { "Missing locale config: $localesConfig" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(localesConfig)
        val localeNodes = document.getElementsByTagName("locale")
        return (0 until localeNodes.length).map { index ->
            localeNodes.item(index).attributes.getNamedItem("android:name")?.nodeValue
                ?: error("Locale entry has no android:name in $localesConfig")
        }
    }

    private fun readExpectedString(resourcesRoot: File, languageTag: String, resourceName: String): String {
        val valuesDirectory = when {
            languageTag == "en" -> "values"
            languageTag == "zh-TW" -> "values-zh-rTW"
            else -> "values-$languageTag"
        }
        val stringsFile = File(resourcesRoot, "$valuesDirectory/strings.xml")
        require(stringsFile.isFile) { "Missing $resourceName translation for $languageTag: $stringsFile" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(stringsFile)
        val nodes = document.getElementsByTagName("string")
        val matchingNode = (0 until nodes.length)
            .map(nodes::item)
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == resourceName }
            ?: error("Missing $resourceName in $stringsFile")
        return matchingNode.textContent
    }
}
