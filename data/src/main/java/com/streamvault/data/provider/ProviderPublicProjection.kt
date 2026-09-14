package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Provider as StableProvider
import com.streamvault.domain.model.ProviderAccountRuntime
import com.streamvault.domain.model.ProviderType

internal data class RedactedProviderConfigurationProjection(
    val providerType: ProviderType,
    val configurationGeneration: Long,
    val providerTemplate: Provider
)

internal interface ProviderPublicProjection {
    fun decode(entity: ProviderConfigEntity): RedactedProviderConfigurationProjection

    fun assemble(
        identity: ProviderEntity,
        configuration: RedactedProviderConfigurationProjection?,
        runtime: ProviderAccountRuntimeEntity?,
        portalState: StalkerPortalStateEntity?
    ): Provider
}

internal class DefaultProviderPublicProjection(
    private val codec: ProviderConfigurationCodec,
    private val gson: Gson
) : ProviderPublicProjection {

    override fun decode(entity: ProviderConfigEntity): RedactedProviderConfigurationProjection {
        val configuration = codec.decode(entity.type, entity.encryptedConfigJson)
        val templateIdentity = StableProvider(
            id = entity.providerId,
            name = PROJECTION_TEMPLATE_NAME,
            type = entity.type
        )
        val template = configuration
            .toLegacyProvider(templateIdentity, ProviderAccountRuntime())
            .redactedCredentials()
        return RedactedProviderConfigurationProjection(
            providerType = entity.type,
            configurationGeneration = entity.configurationGeneration,
            providerTemplate = template
        )
    }

    override fun assemble(
        identity: ProviderEntity,
        configuration: RedactedProviderConfigurationProjection?,
        runtime: ProviderAccountRuntimeEntity?,
        portalState: StalkerPortalStateEntity?
    ): Provider {
        configuration ?: return identity.toPublicDomain()
        check(identity.type == configuration.providerType) {
            "Provider/configuration type mismatch for ${identity.id}"
        }

        val accountRuntime = runtime?.toDomainRuntime(gson) ?: ProviderAccountRuntime()
        val learning = if (identity.type == ProviderType.STALKER_PORTAL) {
            portalState?.toGenerationValidLearning(gson, configuration.configurationGeneration)
        } else {
            null
        }
        return configuration.providerTemplate.copy(
            id = identity.id,
            name = identity.name,
            type = identity.type,
            isActive = identity.isActive,
            status = identity.status,
            lastSyncedAt = identity.lastSyncedAt,
            createdAt = identity.createdAt,
            maxConnections = accountRuntime.maxConnections,
            expirationDate = accountRuntime.expirationDate,
            apiVersion = accountRuntime.apiVersion,
            allowedOutputFormats = accountRuntime.allowedOutputFormats,
            catalogLayout = accountRuntime.catalogLayout,
            catalogLayoutDetectionVersion = accountRuntime.catalogLayoutDetectionVersion,
            stalkerConfigurationGeneration = if (identity.type == ProviderType.STALKER_PORTAL) {
                configuration.configurationGeneration
            } else {
                configuration.providerTemplate.stalkerConfigurationGeneration
            },
            stalkerLearnedProfileId = learning?.profileId?.value
                ?: configuration.providerTemplate.stalkerLearnedProfileId,
            stalkerProfileRevision = learning?.profileRevision?.value
                ?: configuration.providerTemplate.stalkerProfileRevision,
            stalkerProfileVerification = learning?.profileVerification?.value
                ?: configuration.providerTemplate.stalkerProfileVerification,
            stalkerPortalProfile = learning?.portalProfile?.value
                ?: configuration.providerTemplate.stalkerPortalProfile,
            stalkerPortalFingerprint = learning?.portalFingerprint?.value
                ?: configuration.providerTemplate.stalkerPortalFingerprint,
            stalkerMagPreset = learning?.magPreset?.value
                ?: configuration.providerTemplate.stalkerMagPreset,
            stalkerProtocolFamily = learning?.protocolFamily?.value
                ?: configuration.providerTemplate.stalkerProtocolFamily,
            stalkerLastBootstrapRecipe = learning?.bootstrapRecipe?.value
                ?: configuration.providerTemplate.stalkerLastBootstrapRecipe,
            stalkerEndpointPreference = learning?.endpointPreference?.value
                ?: configuration.providerTemplate.stalkerEndpointPreference,
            stalkerCookieMode = learning?.cookieMode?.value
                ?: configuration.providerTemplate.stalkerCookieMode,
            stalkerPlaybackBackendHint = learning?.playbackBackendHint?.value
                ?: configuration.providerTemplate.stalkerPlaybackBackendHint,
            stalkerLastPlaybackMode = learning?.lastPlaybackMode?.value
                ?: configuration.providerTemplate.stalkerLastPlaybackMode,
            password = ""
        )
    }

    private companion object {
        const val PROJECTION_TEMPLATE_NAME = "__provider_projection__"
    }
}

internal fun ProviderAccountRuntimeEntity.toDomainRuntime(gson: Gson): ProviderAccountRuntime =
    ProviderAccountRuntime(
        maxConnections = maxConnections,
        expirationDate = expirationDate,
        apiVersion = apiVersion,
        allowedOutputFormats = runCatching {
            gson.fromJson(allowedOutputFormatsJson, Array<String>::class.java).toList()
        }.getOrDefault(emptyList()),
        catalogLayout = catalogLayout,
        catalogLayoutDetectionVersion = catalogLayoutDetectionVersion,
        observedAt = observedAt
    )

internal fun ProviderEntity.toPublicDomain(): Provider = toDomain().copy(password = "")

/** Public provider projections must never expose decrypted account credentials. */
internal fun Provider.redactedCredentials(): Provider = copy(password = "")
