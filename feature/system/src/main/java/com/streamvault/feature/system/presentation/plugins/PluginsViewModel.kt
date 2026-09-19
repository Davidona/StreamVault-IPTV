package com.streamvault.feature.system.presentation.plugins

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginConfigurationAction
import com.streamvault.feature.system.api.PluginConfigurationField
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.StreamVaultPluginOwner
import com.streamvault.feature.system.api.owner
import com.streamvault.feature.system.api.SystemPluginManagementPort
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.ProviderSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PluginsUiState(
    val plugins: List<InstalledStreamVaultPlugin> = emptyList(),
    val providerSources: List<ProviderSource> = emptyList(),
    val installUrl: String = "",
    val isLoading: Boolean = false,
    val isInstalling: Boolean = false,
    val isConfigurationLoading: Boolean = false,
    val activePluginOwner: StreamVaultPluginOwner? = null,
    val configuration: ActivePluginConfiguration? = null,
    val syncProgress: String? = null,
    val userMessage: String? = null
)

data class ActivePluginConfiguration(
    val plugin: InstalledStreamVaultPlugin,
    val schema: PluginConfigurationSchema,
    val values: JsonObject,
    val draftValues: Map<String, String>,
    val validationErrors: Map<String, String> = emptyMap(),
    val isSaving: Boolean = false,
    val runningActionId: String? = null
) {
    val isDirty: Boolean
        get() = draftValues != schema.toDraftValues(values)
}

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val pluginManagement: SystemPluginManagementPort
) : ViewModel() {
    private val _uiState = MutableStateFlow(PluginsUiState(isLoading = true))
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    init {
        refreshPlugins()
    }

    fun updateInstallUrl(value: String) {
        _uiState.update { it.copy(installUrl = value) }
    }

    fun refreshPlugins() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, syncProgress = null) }
            val result = runCatching { pluginManagement.discoverPlugins() }
            val sources = runCatching { pluginManagement.providerSources() }
            _uiState.update { state ->
                state.copy(
                    plugins = result.getOrDefault(emptyList()),
                    providerSources = sources.getOrElse { state.providerSources },
                    isLoading = false,
                    configuration = state.configuration?.let { configuration ->
                        val refreshedPlugin = result.getOrDefault(emptyList())
                            .firstOrNull { plugin -> plugin.owner == configuration.plugin.owner }
                            ?: configuration.plugin
                        configuration.copy(plugin = refreshedPlugin)
                    },
                    userMessage = result.exceptionOrNull()?.message ?: sources.exceptionOrNull()?.message
                )
            }
        }
    }

    fun installFromUrl() {
        val url = _uiState.value.installUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(userMessage = "Enter a plugin APK URL first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, userMessage = "Preparing plugin installer...") }
            val result = pluginManagement.installApkFromUrl(url)
            _uiState.update {
                it.copy(
                    isInstalling = false,
                    userMessage = result.messageOr("Plugin installer opened")
                )
            }
            refreshPlugins()
        }
    }

    fun installFromLocalUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, userMessage = "Preparing selected APK...") }
            val result = pluginManagement.installApkFromUri(uri)
            _uiState.update {
                it.copy(
                    isInstalling = false,
                    userMessage = result.messageOr("Plugin installer opened")
                )
            }
            refreshPlugins()
        }
    }

    fun setPluginEnabled(plugin: InstalledStreamVaultPlugin, enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activePluginOwner = plugin.owner,
                    syncProgress = if (enabled) "Activating ${plugin.displayName}..." else "Deactivating ${plugin.displayName}...",
                    userMessage = null
                )
            }
            val result = pluginManagement.setPluginEnabled(plugin, enabled) { progress ->
                _uiState.update { it.copy(syncProgress = progress) }
            }
            val refreshed = runCatching { pluginManagement.discoverPlugins() }.getOrDefault(_uiState.value.plugins)
            val refreshedSources = runCatching { pluginManagement.providerSources() }
                .getOrDefault(_uiState.value.providerSources)
            _uiState.update {
                it.copy(
                    plugins = refreshed,
                    providerSources = refreshedSources,
                    activePluginOwner = null,
                    syncProgress = null,
                    userMessage = result.message
                )
            }
        }
    }

    fun openPluginConfiguration(plugin: InstalledStreamVaultPlugin) {
        if (plugin.manifest.usesActivityConfiguration || !plugin.manifest.supportsHostRenderedConfiguration) {
            val result = pluginManagement.openPluginConfiguration(plugin)
            _uiState.update { it.copy(userMessage = result.message) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConfigurationLoading = true,
                    activePluginOwner = plugin.owner,
                    userMessage = null
                )
            }
            when (val result = pluginManagement.loadPluginConfiguration(plugin)) {
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isConfigurationLoading = false,
                            activePluginOwner = null,
                            userMessage = result.message
                        )
                    }
                }
                Result.Loading -> Unit
                is Result.Success -> {
                    val snapshot = result.data
                    _uiState.update {
                        it.copy(
                            isConfigurationLoading = false,
                            activePluginOwner = null,
                            configuration = ActivePluginConfiguration(
                                plugin = snapshot.plugin,
                                schema = snapshot.schema,
                                values = snapshot.values,
                                draftValues = snapshot.schema.toDraftValues(snapshot.values)
                            )
                        )
                    }
                }
            }
        }
    }

    fun closePluginConfiguration() {
        _uiState.update { it.copy(configuration = null, isConfigurationLoading = false) }
    }

    fun updateConfigurationValue(key: String, value: String) {
        _uiState.update { state ->
            val configuration = state.configuration ?: return@update state
            state.copy(
                configuration = configuration.copy(
                    draftValues = configuration.draftValues + (key to value),
                    validationErrors = configuration.validationErrors - key
                )
            )
        }
    }

    fun savePluginConfiguration() {
        val configuration = _uiState.value.configuration ?: return
        val validationErrors = configuration.schema.validate(configuration.draftValues)
        if (validationErrors.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    configuration = configuration.copy(validationErrors = validationErrors),
                    userMessage = "Review the highlighted plugin settings"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.updateConfiguration { it.copy(isSaving = true, validationErrors = emptyMap()) }
            val valuesJson = configuration.schema.toValuesJson(configuration.draftValues).toString()
            val result = pluginManagement.savePluginConfiguration(configuration.plugin, valuesJson)
            val refreshedValues = if (result.success) {
                pluginManagement.loadPluginConfigurationValues(configuration.plugin).getOrNull()
            } else {
                null
            }
            _uiState.updateConfiguration { current ->
                val values = refreshedValues ?: current.values
                current.copy(
                    values = values,
                    draftValues = if (refreshedValues != null) current.schema.toDraftValues(values) else current.draftValues,
                    isSaving = false
                )
            }
            _uiState.update { it.copy(userMessage = result.message) }
        }
    }

    fun refreshPluginConfiguration() {
        val configuration = _uiState.value.configuration ?: return
        viewModelScope.launch {
            _uiState.updateConfiguration { it.copy(isSaving = true, validationErrors = emptyMap()) }
            when (val result = pluginManagement.loadPluginConfiguration(configuration.plugin)) {
                is Result.Error -> {
                    _uiState.updateConfiguration { it.copy(isSaving = false) }
                    _uiState.update { it.copy(userMessage = result.message) }
                }
                Result.Loading -> Unit
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            configuration = ActivePluginConfiguration(
                                plugin = result.data.plugin,
                                schema = result.data.schema,
                                values = result.data.values,
                                draftValues = result.data.schema.toDraftValues(result.data.values)
                            )
                        )
                    }
                }
            }
        }
    }

    fun runConfigurationAction(action: PluginConfigurationAction) {
        val configuration = _uiState.value.configuration ?: return
        viewModelScope.launch {
            _uiState.updateConfiguration { it.copy(runningActionId = action.id) }
            val result = pluginManagement.runPluginConfigurationAction(configuration.plugin, action.id)
            if (result.success && action.refreshAfterRun) {
                pluginManagement.loadPluginConfiguration(configuration.plugin).getOrNull()?.let { snapshot ->
                    _uiState.update {
                        it.copy(
                            configuration = ActivePluginConfiguration(
                                plugin = snapshot.plugin,
                                schema = snapshot.schema,
                                values = snapshot.values,
                                draftValues = snapshot.schema.toDraftValues(snapshot.values)
                            )
                        )
                    }
                }
            }
            _uiState.updateConfiguration { it.copy(runningActionId = null) }
            _uiState.update { it.copy(userMessage = result.message) }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun Result<Unit>.messageOr(successMessage: String): String = when (this) {
        is Result.Error -> message
        Result.Loading -> "Plugin operation is still running"
        is Result.Success -> successMessage
    }
}

private fun MutableStateFlow<PluginsUiState>.updateConfiguration(
    transform: (ActivePluginConfiguration) -> ActivePluginConfiguration
) {
    update { state ->
        val configuration = state.configuration ?: return@update state
        state.copy(configuration = transform(configuration))
    }
}

private fun PluginConfigurationSchema.fields(): List<PluginConfigurationField> =
    sections.flatMap { it.fields }

private fun PluginConfigurationSchema.toDraftValues(values: JsonObject): Map<String, String> =
    fields().associate { field ->
        field.key to (values[field.key] ?: field.defaultValue).toUiString()
    }

private fun PluginConfigurationSchema.validate(draftValues: Map<String, String>): Map<String, String> =
    fields()
        .filter { field ->
            field.required &&
                !field.readOnly &&
                field.type != PluginConfigurationField.TYPE_INFO &&
                draftValues[field.key].orEmpty().isBlank()
        }
        .associate { it.key to "${it.label} is required" }

private fun PluginConfigurationSchema.toValuesJson(draftValues: Map<String, String>): JsonObject =
    buildJsonObject {
        fields()
            .filter { !it.readOnly && it.type != PluginConfigurationField.TYPE_INFO }
            .forEach { field ->
                val value = draftValues[field.key].orEmpty()
                when (field.type) {
                    PluginConfigurationField.TYPE_BOOLEAN -> put(field.key, value.equals("true", ignoreCase = true))
                    PluginConfigurationField.TYPE_NUMBER -> {
                        val longValue = value.toLongOrNull()
                        val doubleValue = value.toDoubleOrNull()
                        when {
                            longValue != null -> put(field.key, longValue)
                            doubleValue != null -> put(field.key, doubleValue)
                            else -> put(field.key, value)
                        }
                    }
                    else -> put(field.key, value)
                }
            }
    }

private fun JsonElement?.toUiString(): String {
    val primitive = this as? JsonPrimitive ?: return ""
    return primitive.booleanOrNull?.toString()
        ?: primitive.longOrNull?.toString()
        ?: primitive.doubleOrNull?.toString()
        ?: primitive.contentOrNull.orEmpty()
}
