package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.*

/** Decode persisted learning only when every observation belongs to the requested generation. */
internal fun StalkerPortalStateEntity.toGenerationValidLearning(
    gson: Gson,
    expectedGeneration: Long
): StalkerPortalLearning? {
    if (configurationGeneration != expectedGeneration) return null
    if (learningJson.isNotBlank() && learningJson != "{}") {
        return runCatching { gson.fromJson(learningJson, StalkerPortalLearning::class.java) }
            .getOrNull()
            ?.takeIf { it.configurationGeneration == expectedGeneration }
            ?.let { learning ->
                // Older wildcard probes accidentally disabled the entire catalog capability.
                val capabilities = learning.capabilities.orEmpty().toMutableMap()
                fun repair(key: String, wildcardSupported: Boolean?) {
                    val observation = capabilities[key]
                    if (wildcardSupported == false && observation?.value == CapabilityState.UNSUPPORTED &&
                        observation.source == StalkerObservationSource.CATALOG) capabilities.remove(key)
                }
                repair("vod", movieWildcardSupported)
                repair("series", seriesWildcardSupported)
                runCatching {
                    learning.copy(capabilities = capabilities, discoveryEvidence = learning.discoveryEvidence.orEmpty())
                }.getOrNull()
            }
    }
    val source = runCatching { StalkerObservationSource.valueOf(observationSource) }
        .getOrDefault(StalkerObservationSource.DISCOVERY)
    val time = observedAt.takeIf { it > 0L } ?: validatedAt
    fun <T> observed(value: T) = StalkerObservation(value, expectedGeneration, source, time)
    val capabilities = buildMap {
        bulkLiveSupported?.let { put("live", observed(if (it) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED)) }
        movieWildcardSupported?.let { put("vod_wildcard", observed(if (it) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED)) }
        seriesWildcardSupported?.let { put("series_wildcard", observed(if (it) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED)) }
        epgSupported?.let { put("epg", observed(if (it) CapabilityState.SUPPORTED else CapabilityState.UNSUPPORTED)) }
    }
    return StalkerPortalLearning(
        configurationGeneration = expectedGeneration,
        workingEndpoint = workingEndpoint?.let(::observed),
        bootstrapRecipe = bootstrapRecipe
            ?.let { runCatching { StalkerBootstrapRecipe.valueOf(it) }.getOrNull() }
            ?.let(::observed),
        capabilities = capabilities
    )
}

internal fun StalkerPortalLearning.latestObservedAtForSnapshot(): Long = buildList {
    add(effectiveIdentity?.observedAt)
    add(profileId?.observedAt)
    add(portalProfile?.observedAt)
    add(portalFingerprint?.observedAt)
    add(magPreset?.observedAt)
    add(workingEndpoint?.observedAt)
    add(lastPlaybackMode?.observedAt)
    addAll(capabilities.values.map { it.observedAt })
    addAll(discoveryEvidence.map { it.observedAt })
}.filterNotNull().maxOrNull() ?: 0L

internal fun StalkerPortalLearning.latestSourceForSnapshot(): StalkerObservationSource = buildList {
    add(lastPlaybackMode)
    add(workingEndpoint)
    add(profileId)
    add(portalProfile)
    add(portalFingerprint)
    add(magPreset)
    addAll(capabilities.values)
    addAll(discoveryEvidence)
}.filterNotNull().maxByOrNull { it.observedAt }?.source ?: StalkerObservationSource.DISCOVERY
