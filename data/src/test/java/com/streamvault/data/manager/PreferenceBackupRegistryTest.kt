package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class PreferenceBackupRegistryTest {
    @Test
    fun `every datastore preference has a classification and portable codec`() {
        val source = sequenceOf(
            File("src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt"),
            File("data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt")
        ).first { it.isFile }.readText()

        val literalKeys = Regex("(?:boolean|int|long|float|double|string)PreferencesKey\\(\"([^\"]+)\"\\)")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val dynamicSamples = setOf(
            "xtream_text_import_applied_generation_42",
            "last_live_category_id_42",
            "last_split_catalog_type_42",
            "remote_shortcut_live_red",
            "hidden_categories_42_LIVE",
            "hidden_channels_42",
            "pinned_categories_42_LIVE",
            "category_sort_42_LIVE",
            "aspect_ratio_123"
        )
        val allKeys = literalKeys + dynamicSamples
        val knownDynamicFactories = setOf(
            "xtreamTextImportAppliedGenerationKey(providerId",
            "remoteShortcutKey(profile, button",
            "hiddenCategoriesKey(providerId, type",
            "hiddenChannelsKey(providerId",
            "pinnedCategoriesKey(providerId, type",
            "categorySortModeKey(providerId, type"
        )
        val unknownDynamicFactories = Regex("(?:boolean|int|long|float|double|string)PreferencesKey\\(([^)\\r\\n]+)")
            .findAll(source)
            .map { it.groupValues[1].trim() }
            .filterNot { it.startsWith("\"") }
            .filterNot { it in knownDynamicFactories }
            .toSet()

        assertThat(unknownDynamicFactories).isEmpty()
        assertThat(allKeys.filter { PreferenceBackupRegistry.storageClassification(it) == null })
            .isEmpty()
        assertThat(allKeys.filter {
            PreferenceBackupRegistry.storageClassification(it) in setOf(
                PreferenceBackupClassification.PORTABLE_GLOBAL,
                PreferenceBackupClassification.PORTABLE_PROVIDER_CONTENT
            ) && PreferenceBackupRegistry.portableCodecForStorageKey(it) == null
        }).isEmpty()
    }
}
