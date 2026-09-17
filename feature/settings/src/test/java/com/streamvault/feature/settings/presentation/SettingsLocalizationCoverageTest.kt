package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class SettingsLocalizationCoverageTest {
    @Test
    fun `every shipped locale covers the replacement navigation and copy`() {
        val resourceRoot = File("src/main/res")
        val required = resourceEntries(
            File(resourceRoot, "values/settings_navigation.xml"),
            File(resourceRoot, "values/settings_redesign.xml"),
        )
        val localeDirectories = resourceRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }

        assertThat(localeDirectories).isNotEmpty()
        localeDirectories.forEach { directory ->
            val localized = resourceEntries(File(directory, "settings_redesign.xml"))
            assertWithMessage(directory.name).that(localized.keys)
                .containsExactlyElementsIn(required.keys)
            required.forEach { (key, sourceValue) ->
                assertWithMessage("${directory.name}:$key")
                    .that(placeholders(localized.getValue(key)))
                    .containsExactlyElementsIn(placeholders(sourceValue))
            }
        }
    }

    private fun resourceEntries(vararg files: File): Map<String, String> = buildMap {
        files.forEach { file ->
            assertWithMessage(file.path).that(file.isFile).isTrue()
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.documentElement.childNodes
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> put(name, element.textContent)
                    "plurals" -> {
                        val items = element.childNodes
                        for (itemIndex in 0 until items.length) {
                            val item = items.item(itemIndex) as? Element ?: continue
                            if (item.tagName == "item") {
                                put("$name:${item.getAttribute("quantity")}", item.textContent)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun placeholders(value: String): List<String> =
        Regex("%\\d+\\$[a-zA-Z]").findAll(value).map { it.value }.sorted().toList()
}
