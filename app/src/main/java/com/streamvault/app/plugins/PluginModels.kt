package com.streamvault.app.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class StreamVaultPluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val versionName: String = "",
    val versionCode: Long = 0L,
    val description: String = "",
    val capabilities: List<String> = emptyList(),
    /** Explicit URL ownership for playback and cast hooks. `*` is an intentional catch-all. */
    val playbackUrlSchemes: List<String> = emptyList(),
    val playbackUrlHosts: List<String> = emptyList(),
    val playbackPriority: Int = 0,
    val configurationMode: String? = null,
    val configurationActivityAction: String? = null,
    val providerName: String? = null
) {
    fun hasCapability(capability: String): Boolean = capability in capabilities

    val supportsConfigurationActivity: Boolean
        get() = hasCapability(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_ACTIVITY) &&
            !configurationActivityAction.isNullOrBlank()

    val usesActivityConfiguration: Boolean
        get() = configurationMode == StreamVaultPluginContract.CONFIGURATION_MODE_ACTIVITY

    val supportsHostRenderedConfiguration: Boolean
        get() = configurationMode == StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA ||
            (configurationMode != StreamVaultPluginContract.CONFIGURATION_MODE_ACTIVITY &&
                hasCapability(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA))

    val canConfigure: Boolean
        get() = supportsHostRenderedConfiguration || supportsConfigurationActivity
}

data class InstalledStreamVaultPlugin(
    val packageName: String,
    val serviceClassName: String,
    val appLabel: String,
    val manifest: StreamVaultPluginManifest,
    val enabled: Boolean,
    val statusLabel: String = "",
    val lastMessage: String = "",
    val discoveryState: PluginDiscoveryState = PluginDiscoveryState.READY
) {
    val displayName: String
        get() = manifest.name.ifBlank { appLabel.ifBlank { packageName } }
}

enum class PluginDiscoveryState { LOADING, READY, PARTIAL, TIMED_OUT, ERROR }

data class PluginDiscoveryStatus(
    val state: PluginDiscoveryState,
    val message: String = ""
)


data class StreamVaultPluginOwner(
    val packageName: String,
    val serviceClassName: String,
    val manifestId: String
) {
    val component: StreamVaultPluginComponent
        get() = StreamVaultPluginComponent(packageName, serviceClassName)
}

/**
 * Stable Android-saveable identity for Compose item keys.
 *
 * Compose persists lazy-list keys through an Android Bundle. The owner itself is a Kotlin data
 * class and cannot be written to a Bundle, so encode each component into a String while keeping
 * the fields unambiguous even if plugin metadata contains separator characters.
 */
fun StreamVaultPluginOwner.toBundleSafeKey(): String = buildString {
    appendLengthPrefixed(packageName)
    appendLengthPrefixed(serviceClassName)
    appendLengthPrefixed(manifestId)
}

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
}

data class StreamVaultPluginComponent(
    val packageName: String,
    val serviceClassName: String
)

val InstalledStreamVaultPlugin.owner: StreamVaultPluginOwner
    get() = StreamVaultPluginOwner(packageName, serviceClassName, manifest.id)

data class PluginActionResult(
    val success: Boolean,
    val message: String
)

data class PluginConfigurationSnapshot(
    val plugin: InstalledStreamVaultPlugin,
    val schema: PluginConfigurationSchema,
    val values: JsonObject
)

@Serializable
data class PluginConfigurationSchema(
    val schemaVersion: Int = 1,
    val title: String = "",
    val description: String = "",
    val sections: List<PluginConfigurationSection> = emptyList(),
    val actions: List<PluginConfigurationAction> = emptyList()
)

@Serializable
data class PluginConfigurationSection(
    val id: String,
    val title: String,
    val description: String = "",
    val fields: List<PluginConfigurationField> = emptyList()
)

@Serializable
data class PluginConfigurationField(
    val key: String,
    val type: String = TYPE_TEXT,
    val label: String,
    val description: String = "",
    val placeholder: String = "",
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val secret: Boolean = false,
    val defaultValue: JsonElement? = null,
    val options: List<PluginConfigurationOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null
) {
    companion object {
        const val TYPE_INFO = "info"
        const val TYPE_TEXT = "text"
        const val TYPE_PASSWORD = "password"
        const val TYPE_URL = "url"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_SELECT = "select"
        const val TYPE_TEXTAREA = "textarea"
    }
}

@Serializable
data class PluginConfigurationOption(
    val value: String,
    val label: String,
    val description: String = ""
)

@Serializable
data class PluginConfigurationAction(
    val id: String,
    val label: String,
    val description: String = "",
    val destructive: Boolean = false,
    val refreshAfterRun: Boolean = true
)
