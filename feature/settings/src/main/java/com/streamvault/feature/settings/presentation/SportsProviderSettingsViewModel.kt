package com.streamvault.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.sports.provider.SportsProvider
import com.streamvault.domain.sports.provider.SportsProviderAuth
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.SportsProviderConnectionTester
import com.streamvault.domain.sports.provider.SportsProviderCredentials
import com.streamvault.domain.sports.provider.SportsProviderDescriptor
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsProviderStore
import com.streamvault.domain.sports.provider.SportsProviderUsageProvider
import com.streamvault.domain.sports.provider.SportsProviderUsageSnapshot
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsEventSourceSettings
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import com.streamvault.domain.sports.provider.SportsScoreSourceSettings
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.SportsStandingsSourceSettings
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.domain.sports.provider.SportsMetadataSourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class SportsProviderConnectionState {
    TESTING, SUCCESS, FAILED, UNSUPPORTED, ADAPTER_UNAVAILABLE,
}

internal data class SportsProviderSettingsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val isSaving: Boolean = false,
    val configurations: List<SportsProviderConfig> = emptyList(),
    val descriptors: List<SportsProviderDescriptor> = emptyList(),
    val credentialedInstanceIds: Set<String> = emptySet(),
    val usageSnapshots: Map<String, SportsProviderUsageSnapshot> = emptyMap(),
    val eventSourcePreference: SportsEventSourcePreference = SportsEventSourcePreference.AUTOMATIC,
    val scoreSourcePreference: SportsScoreSourcePreference = SportsScoreSourcePreference.AUTOMATIC,
    val standingsSourcePreference: SportsStandingsSourcePreference = SportsStandingsSourcePreference.AUTOMATIC,
    val metadataSourcePreference: SportsMetadataSourcePreference = SportsMetadataSourcePreference.AUTOMATIC,
    val connectionStates: Map<String, SportsProviderConnectionState> = emptyMap(),
    val draftConnectionState: SportsProviderConnectionState? = null,
    val busyInstances: Set<String> = emptySet(),
    val operationFailed: Boolean = false,
    val validationFailed: Boolean = false,
    val lastSavedInstanceId: String? = null,
    val lastRemovedInstanceId: String? = null,
)

@HiltViewModel
internal class SportsProviderSettingsViewModel @Inject constructor(
    private val store: SportsProviderStore,
    private val registry: SportsProviderRegistry,
    private val eventSourceSettings: SportsEventSourceSettings,
    private val scoreSourceSettings: SportsScoreSourceSettings,
    private val standingsSourceSettings: SportsStandingsSourceSettings,
    private val metadataSourceSettings: SportsMetadataSourceSettings,
) : ViewModel() {
    private var draftTestGeneration = 0L
    private val mutableState = MutableStateFlow(
        SportsProviderSettingsUiState(descriptors = registry.descriptors),
    )
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val configurations = store.configurations()
                val credentialedInstanceIds = store.credentialedInstanceIds()
                val usageSnapshots = buildMap {
                    configurations.forEach { config ->
                        usageSnapshotFor(config)?.let { put(config.instanceId, it) }
                    }
                }
                val eventSourcePreference = eventSourceSettings.eventSourcePreference.first()
                val scoreSourcePreference = scoreSourceSettings.scoreSourcePreference.first()
                val standingsSourcePreference = standingsSourceSettings.standingsSourcePreference.first()
                val metadataSourcePreference = metadataSourceSettings.metadataSourcePreference.first()
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        loadFailed = false,
                        configurations = configurations,
                        usageSnapshots = usageSnapshots,
                        descriptors = registry.descriptors,
                        credentialedInstanceIds = credentialedInstanceIds,
                        eventSourcePreference = eventSourcePreference,
                        scoreSourcePreference = scoreSourcePreference,
                        standingsSourcePreference = standingsSourcePreference,
                        metadataSourcePreference = metadataSourcePreference,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    fun saveProvider(
        descriptor: SportsProviderDescriptor,
        existing: SportsProviderConfig?,
        baseUrl: String,
        username: String,
        secret: String,
        eventLeagueIds: List<String> = emptyList(),
        customDefinitionJson: String? = null,
    ) {
        if (mutableState.value.isSaving ||
            mutableState.value.draftConnectionState == SportsProviderConnectionState.TESTING
        ) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isSaving = true,
                    operationFailed = false,
                    validationFailed = false,
                    lastSavedInstanceId = null,
                    draftConnectionState = null,
                )
            }
            try {
                val normalizedBaseUrl = if (descriptor.supportsCustomBaseUrl) {
                    baseUrl.trim().takeIf(String::isNotEmpty) ?: descriptor.defaultBaseUrl
                } else {
                    descriptor.defaultBaseUrl
                }
                val config = existing?.copy(
                    configurationVersion = descriptor.configurationVersion,
                    cacheRevision = existing.cacheRevision + 1,
                    baseUrl = normalizedBaseUrl,
                    eventLeagueIds = eventLeagueIds,
                    customDefinitionJson = customDefinitionJson.takeIf {
                        descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID
                    },
                ) ?: SportsProviderConfig(
                    instanceId = UUID.randomUUID().toString(),
                    typeId = descriptor.typeId,
                    configurationVersion = descriptor.configurationVersion,
                    baseUrl = normalizedBaseUrl,
                    eventLeagueIds = eventLeagueIds,
                    customDefinitionJson = customDefinitionJson.takeIf {
                        descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID
                    },
                )
                val credentials = if (descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID) {
                    customCredentialsFor(existing, username, secret)
                } else {
                    credentialsFor(descriptor.auth, existing, username, secret)
                }
                val prepared = registry.prepare(config)
                store.save(prepared, credentials)
                mutableState.update {
                    it.copy(
                        isSaving = false,
                        configurations = it.configurations
                            .filterNot { saved -> saved.instanceId == prepared.instanceId } + prepared,
                        credentialedInstanceIds = when {
                            credentials != null -> it.credentialedInstanceIds + prepared.instanceId
                            existing == null -> it.credentialedInstanceIds - prepared.instanceId
                            else -> it.credentialedInstanceIds
                        },
                        usageSnapshots = it.usageSnapshots - prepared.instanceId,
                        operationFailed = false,
                        validationFailed = false,
                        lastSavedInstanceId = prepared.instanceId,
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (_: IllegalArgumentException) {
                mutableState.update {
                    it.copy(isSaving = false, validationFailed = true, operationFailed = false)
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(isSaving = false, operationFailed = true, validationFailed = false)
                }
            }
        }
    }

    fun setEnabled(config: SportsProviderConfig, enabled: Boolean) {
        if (config.instanceId in mutableState.value.busyInstances) return
        val updated = config.copy(enabled = enabled)
        mutableState.update {
            it.copy(
                configurations = it.configurations.map {
                    if (it.instanceId == config.instanceId) updated else it
                },
                busyInstances = it.busyInstances + config.instanceId,
                operationFailed = false,
            )
        }
        viewModelScope.launch {
            try {
                store.save(updated)
                if (!enabled) {
                    if (mutableState.value.eventSourcePreference.providerInstanceId == config.instanceId) {
                        eventSourceSettings.setEventSourcePreference(SportsEventSourcePreference.AUTOMATIC)
                        mutableState.update {
                            it.copy(eventSourcePreference = SportsEventSourcePreference.AUTOMATIC)
                        }
                    }
                    if (mutableState.value.scoreSourcePreference.providerInstanceId == config.instanceId) {
                        scoreSourceSettings.setScoreSourcePreference(SportsScoreSourcePreference.AUTOMATIC)
                        mutableState.update {
                            it.copy(scoreSourcePreference = SportsScoreSourcePreference.AUTOMATIC)
                        }
                    }
                    if (mutableState.value.standingsSourcePreference.providerInstanceId == config.instanceId) {
                        standingsSourceSettings.setStandingsSourcePreference(
                            SportsStandingsSourcePreference.AUTOMATIC,
                        )
                        mutableState.update {
                            it.copy(standingsSourcePreference = SportsStandingsSourcePreference.AUTOMATIC)
                        }
                    }
                    if (mutableState.value.metadataSourcePreference.providerInstanceId == config.instanceId) {
                        metadataSourceSettings.setMetadataSourcePreference(
                            SportsMetadataSourcePreference.AUTOMATIC,
                        )
                        mutableState.update {
                            it.copy(metadataSourcePreference = SportsMetadataSourcePreference.AUTOMATIC)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        configurations = it.configurations.map {
                            if (it.instanceId == config.instanceId) config else it
                        },
                        operationFailed = true,
                    )
                }
            } finally {
                mutableState.update { it.copy(busyInstances = it.busyInstances - config.instanceId) }
            }
        }
    }

    fun remove(instanceId: String) {
        if (instanceId in mutableState.value.busyInstances) return
        mutableState.update {
            it.copy(
                busyInstances = it.busyInstances + instanceId,
                operationFailed = false,
                lastRemovedInstanceId = null,
            )
        }
        viewModelScope.launch {
            try {
                store.remove(instanceId)
                if (mutableState.value.eventSourcePreference.providerInstanceId == instanceId) {
                    eventSourceSettings.setEventSourcePreference(SportsEventSourcePreference.AUTOMATIC)
                    mutableState.update { it.copy(eventSourcePreference = SportsEventSourcePreference.AUTOMATIC) }
                }
                if (mutableState.value.scoreSourcePreference.providerInstanceId == instanceId) {
                    scoreSourceSettings.setScoreSourcePreference(SportsScoreSourcePreference.AUTOMATIC)
                    mutableState.update { it.copy(scoreSourcePreference = SportsScoreSourcePreference.AUTOMATIC) }
                }
                if (mutableState.value.standingsSourcePreference.providerInstanceId == instanceId) {
                    standingsSourceSettings.setStandingsSourcePreference(SportsStandingsSourcePreference.AUTOMATIC)
                    mutableState.update {
                        it.copy(standingsSourcePreference = SportsStandingsSourcePreference.AUTOMATIC)
                    }
                }
                if (mutableState.value.metadataSourcePreference.providerInstanceId == instanceId) {
                    metadataSourceSettings.setMetadataSourcePreference(SportsMetadataSourcePreference.AUTOMATIC)
                    mutableState.update {
                        it.copy(metadataSourcePreference = SportsMetadataSourcePreference.AUTOMATIC)
                    }
                }
                mutableState.update {
                    it.copy(
                        configurations = it.configurations.filterNot { config -> config.instanceId == instanceId },
                        credentialedInstanceIds = it.credentialedInstanceIds - instanceId,
                        connectionStates = it.connectionStates - instanceId,
                        usageSnapshots = it.usageSnapshots - instanceId,
                        lastRemovedInstanceId = instanceId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            } finally {
                mutableState.update { it.copy(busyInstances = it.busyInstances - instanceId) }
            }
        }
    }

    fun testConnection(config: SportsProviderConfig) {
        if (config.instanceId in mutableState.value.busyInstances) return
        mutableState.update {
            it.copy(
                busyInstances = it.busyInstances + config.instanceId,
                connectionStates = it.connectionStates +
                    (config.instanceId to SportsProviderConnectionState.TESTING),
            )
        }
        viewModelScope.launch {
            var usageSnapshot: SportsProviderUsageSnapshot? = null
            val status = try {
                if (registry.descriptors.none { it.typeId == config.typeId }) {
                    SportsProviderConnectionState.ADAPTER_UNAVAILABLE
                } else {
                    val provider: SportsProvider = registry.create(config)
                    val tester = provider as? SportsProviderConnectionTester
                    val credentials = store.credentials(config.instanceId)
                    if (tester == null) {
                        SportsProviderConnectionState.UNSUPPORTED
                    } else {
                        val result = tester.testConnection(credentials)
                        usageSnapshot = (provider as? SportsProviderUsageProvider)?.usageSnapshot(credentials)
                        when (result) {
                            is SportsProviderResult.Success -> SportsProviderConnectionState.SUCCESS
                            is SportsProviderResult.Failure -> SportsProviderConnectionState.FAILED
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SportsProviderConnectionState.FAILED
            } finally {
                mutableState.update { it.copy(busyInstances = it.busyInstances - config.instanceId) }
            }
            mutableState.update {
                it.copy(
                    connectionStates = it.connectionStates + (config.instanceId to status),
                    usageSnapshots = usageSnapshot?.let { snapshot ->
                        it.usageSnapshots + (config.instanceId to snapshot)
                    } ?: it.usageSnapshots,
                )
            }
        }
    }

    private suspend fun usageSnapshotFor(config: SportsProviderConfig): SportsProviderUsageSnapshot? {
        val provider = runCatching { registry.create(config) }.getOrNull() as? SportsProviderUsageProvider
            ?: return null
        val credentials = store.credentials(config.instanceId)
        return provider.usageSnapshot(credentials)
    }

    fun testDraftConnection(
        descriptor: SportsProviderDescriptor,
        existing: SportsProviderConfig?,
        baseUrl: String,
        username: String,
        secret: String,
        eventLeagueIds: List<String> = emptyList(),
        customDefinitionJson: String? = null,
    ) {
        if (mutableState.value.draftConnectionState == SportsProviderConnectionState.TESTING) return
        val generation = ++draftTestGeneration
        mutableState.update { it.copy(draftConnectionState = SportsProviderConnectionState.TESTING) }
        viewModelScope.launch {
            val status = try {
                val normalizedBaseUrl = if (descriptor.supportsCustomBaseUrl) {
                    baseUrl.trim().takeIf(String::isNotEmpty) ?: descriptor.defaultBaseUrl
                } else {
                    descriptor.defaultBaseUrl
                }
                val config = existing?.copy(
                    configurationVersion = descriptor.configurationVersion,
                    baseUrl = normalizedBaseUrl,
                    eventLeagueIds = eventLeagueIds,
                    customDefinitionJson = customDefinitionJson.takeIf {
                        descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID
                    },
                ) ?: SportsProviderConfig(
                    instanceId = UUID.randomUUID().toString(),
                    typeId = descriptor.typeId,
                    configurationVersion = descriptor.configurationVersion,
                    baseUrl = normalizedBaseUrl,
                    eventLeagueIds = eventLeagueIds,
                    customDefinitionJson = customDefinitionJson.takeIf {
                        descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID
                    },
                )
                val prepared = registry.prepare(config)
                val provider: SportsProvider = registry.create(prepared)
                val tester = provider as? SportsProviderConnectionTester
                if (tester == null) {
                    SportsProviderConnectionState.UNSUPPORTED
                } else {
                    val draftCredentials = if (descriptor.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID) {
                        customCredentialsFor(existing, username, secret) ?: existing?.let {
                            store.credentials(it.instanceId)
                        }
                    } else {
                        credentialsFor(descriptor.auth, existing, username, secret)
                            ?: existing?.let { store.credentials(it.instanceId) }
                    }
                    when (tester.testConnection(draftCredentials)) {
                        is SportsProviderResult.Success -> SportsProviderConnectionState.SUCCESS
                        is SportsProviderResult.Failure -> SportsProviderConnectionState.FAILED
                    }
                }
            } catch (cancelled: CancellationException) {
                if (generation == draftTestGeneration) {
                    mutableState.update { it.copy(draftConnectionState = null) }
                }
                throw cancelled
            } catch (_: IllegalArgumentException) {
                mutableState.update { it.copy(validationFailed = true) }
                SportsProviderConnectionState.FAILED
            } catch (_: Exception) {
                SportsProviderConnectionState.FAILED
            }
            if (generation == draftTestGeneration) {
                mutableState.update { it.copy(draftConnectionState = status) }
            }
        }
    }

    fun clearOperationFailure() {
        draftTestGeneration++
        mutableState.update {
            it.copy(operationFailed = false, validationFailed = false, draftConnectionState = null)
        }
    }

    fun setEventSourcePreference(preference: SportsEventSourcePreference) {
        val current = mutableState.value
        if (current.isSaving) return
        if (preference.providerInstanceId != null) {
            val supported = registry.eligible(current.configurations, SportsCapability.EVENTS)
                .any { it.instanceId == preference.providerInstanceId }
            if (!supported) return
        }
        viewModelScope.launch {
            try {
                eventSourceSettings.setEventSourcePreference(preference)
                mutableState.update { it.copy(eventSourcePreference = preference, operationFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }

    fun setScoreSourcePreference(preference: SportsScoreSourcePreference) {
        val current = mutableState.value
        if (current.isSaving) return
        if (preference.providerInstanceId != null) {
            val eventAccounts = registry.eligible(current.configurations, SportsCapability.EVENTS)
                .mapTo(mutableSetOf(), SportsProviderConfig::instanceId)
            val supported = registry.eligible(current.configurations, SportsCapability.LIVE_SCORES)
                .any { it.instanceId == preference.providerInstanceId && it.instanceId in eventAccounts }
            if (!supported) return
        }
        viewModelScope.launch {
            try {
                scoreSourceSettings.setScoreSourcePreference(preference)
                mutableState.update { it.copy(scoreSourcePreference = preference, operationFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }

    fun setStandingsSourcePreference(preference: SportsStandingsSourcePreference) {
        val current = mutableState.value
        if (current.isSaving) return
        if (preference.providerInstanceId != null && registry.eligible(
                current.configurations,
                SportsCapability.STANDINGS,
            ).none { it.instanceId == preference.providerInstanceId }
        ) return
        viewModelScope.launch {
            try {
                standingsSourceSettings.setStandingsSourcePreference(preference)
                mutableState.update {
                    it.copy(standingsSourcePreference = preference, operationFailed = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }

    fun setMetadataSourcePreference(preference: SportsMetadataSourcePreference) {
        val current = mutableState.value
        if (current.isSaving) return
        if (preference.providerInstanceId != null) {
            val supported = current.configurations.any { config ->
                config.enabled && config.instanceId == preference.providerInstanceId &&
                    registry.capabilities(config).any {
                        it == SportsCapability.TEAM_METADATA || it == SportsCapability.LEAGUE_METADATA
                    }
            }
            if (!supported) return
        }
        viewModelScope.launch {
            try {
                metadataSourceSettings.setMetadataSourcePreference(preference)
                mutableState.update {
                    it.copy(metadataSourcePreference = preference, operationFailed = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(operationFailed = true) }
            }
        }
    }

    fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> = registry.capabilities(config)

    fun displayNameFor(config: SportsProviderConfig): String? = registry.displayName(config)

    private suspend fun customCredentialsFor(
        existing: SportsProviderConfig?,
        username: String,
        secret: String,
    ): SportsProviderCredentials? {
        val trimmedUsername = username.trim()
        if (existing != null && trimmedUsername.isEmpty() && secret.isBlank()) return null
        if (trimmedUsername.isEmpty() && secret.isBlank()) return null
        val stored = existing?.let { store.credentials(it.instanceId) }
        if (secret.isBlank()) {
            return stored?.let {
                SportsProviderCredentials(
                    secret = it.secret,
                    username = trimmedUsername.ifBlank { it.username },
                )
            }
        }
        return SportsProviderCredentials(
            secret = secret,
            username = trimmedUsername.ifBlank { stored?.username },
        )
    }

    private fun credentialsFor(
        auth: SportsProviderAuth,
        existing: SportsProviderConfig?,
        username: String,
        secret: String,
    ): SportsProviderCredentials? {
        val trimmedUsername = username.trim()
        val hasUsername = trimmedUsername.isNotEmpty()
        val hasSecret = secret.isNotBlank()
        return when (auth) {
            SportsProviderAuth.None, is SportsProviderAuth.OAuth2 -> null
            SportsProviderAuth.Basic -> {
                if (existing != null && !hasUsername && !hasSecret) null else {
                    require(hasUsername && hasSecret)
                    SportsProviderCredentials(secret = secret, username = trimmedUsername)
                }
            }
            is SportsProviderAuth.ApiKeyHeader,
            is SportsProviderAuth.ApiKeyQuery,
            SportsProviderAuth.Bearer -> {
                if (existing != null && !hasSecret) null else {
                    require(hasSecret)
                    SportsProviderCredentials(secret = secret)
                }
            }
        }
    }
}
