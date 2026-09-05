package com.streamvault.data.remote.stalker

import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.google.gson.Gson
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class StalkerPortalStateStore @Inject constructor(
    private val dao: StalkerPortalStateDao,
    private val providerSnapshotDao: ProviderSnapshotDao? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val gson = Gson()

    suspend fun getValidated(providerId: Long, now: Long = System.currentTimeMillis()): StalkerPortalStateEntity? =
        dao.get(providerId)?.takeIf { state ->
            state.validatedAt > 0L && now - state.validatedAt <= VALIDATION_TTL_MILLIS
        }

    suspend fun get(providerId: Long): StalkerPortalStateEntity? = dao.get(providerId)

    /**
     * Durable session resurrected after a process restart. The portal token lives in
     * app-private storage (same trust level as the encrypted provider credentials) and is
     * only reused for the same configuration generation inside the local session age cap.
     * A server-side rejected token costs one cheap profile call: the normal authorization
     * retry path invalidates and falls back to a full handshake.
     */
    data class ResumedStalkerAuth(
        val session: StalkerSession,
        val profile: StalkerProviderProfile
    )

    suspend fun resumableAuth(
        providerId: Long,
        configurationGeneration: Long,
        now: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = STALKER_SESSION_MAX_AGE_MILLIS
    ): ResumedStalkerAuth? {
        if (providerId <= 0L) return null
        val state = dao.get(providerId) ?: return null
        if (state.configurationGeneration != configurationGeneration) return null
        val learning = runCatching {
            json.parseToJsonElement(state.learningJson.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return null
        if (learning["configurationGeneration"]?.jsonPrimitive?.longOrNull != configurationGeneration) {
            return null
        }
        val session = runCatching {
            gson.fromJson(learning["resumableSession"]?.jsonPrimitive?.content, StalkerSession::class.java)
        }.getOrNull() ?: return null
        val profile = runCatching {
            gson.fromJson(learning["resumableProfile"]?.jsonPrimitive?.content, StalkerProviderProfile::class.java)
        }.getOrNull() ?: return null
        if (session.token.isBlank()) return null
        if (profile.profileRevision != StalkerCompatibilityRegistry.REVISION) return null
        if (now - session.authenticatedAtMillis > maxAgeMillis) return null
        if (session.isExpired(now)) return null
        if (profile.expirationDate?.let { it <= now } == true) return null
        return ResumedStalkerAuth(session, profile)
    }

    /** Drops a persisted session (e.g. after the portal rejects its token) without touching hints. */
    suspend fun clearResumableAuth(providerId: Long) {
        if (providerId <= 0L) return
        update(providerId) { state ->
            val learning = mutableLearning(state.learningJson)
            if (learning.remove("resumableSession") == null && learning.remove("resumableProfile") == null) {
                return@update state
            }
            state.copy(learningJson = JsonObject(learning).toString())
        }
    }

    suspend fun recordAuthentication(
        providerId: Long,
        session: StalkerSession,
        profile: StalkerProviderProfile,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) {
        if (providerId <= 0L) return
        val existing = dao.get(providerId)
        persist(
            (existing ?: StalkerPortalStateEntity(providerId)).copy(
                workingEndpoint = session.loadUrl,
                bootstrapRecipe = profile.bootstrapRecipe.name,
                epgSupported = true.takeIf { profile.moduleNames.any { module ->
                    module.contains("epg", ignoreCase = true) || module.contains("itv", ignoreCase = true)
                } } ?: existing?.epgSupported,
                endpointHealthJson = clearHealthFailures(
                    existing?.endpointHealthJson,
                    endpointKey(session.loadUrl),
                    recipeKey(profile.bootstrapRecipe.name)
                ),
                endpointFailedUntil = 0L,
                validatedAt = now,
                configurationGeneration = configurationGeneration
                    ?: existing?.configurationGeneration
                    ?: 0L,
                learningJson = authenticationLearningJson(
                    existing?.learningJson,
                    configurationGeneration ?: existing?.configurationGeneration ?: 0L,
                    session,
                    profile,
                    now
                ),
                observationSource = "AUTHENTICATION",
                observedAt = now
            ),
            configurationGeneration
        )
        StalkerTelemetry.capabilityChanged(providerId, "AUTH_RECIPE", "VALIDATED")
    }

    suspend fun recordBulkLive(
        providerId: Long,
        supported: Boolean,
        categoryFidelity: Boolean? = null,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) = update(providerId, configurationGeneration) { state ->
        withCapability(state, "live", supported, configurationGeneration, now, "CATALOG").copy(
            bulkLiveSupported = supported,
            bulkLiveCategoryFidelity = categoryFidelity ?: state.bulkLiveCategoryFidelity,
            validatedAt = now
        )
    }.also {
        StalkerTelemetry.capabilityChanged(providerId, "BULK_LIVE", if (supported) "SUPPORTED" else "UNSUPPORTED")
    }

    suspend fun recordWildcard(
        providerId: Long,
        contentType: ContentType,
        supported: Boolean,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) = update(providerId, configurationGeneration) { state ->
        val capability = when (contentType) {
            ContentType.MOVIE -> "vod"
            ContentType.SERIES -> "series"
            else -> null
        }
        val learned = capability?.let {
            withCapability(state, it, supported, configurationGeneration, now, "CATALOG")
        } ?: state
        when (contentType) {
            ContentType.MOVIE -> learned.copy(movieWildcardSupported = supported, validatedAt = now)
            ContentType.SERIES -> learned.copy(seriesWildcardSupported = supported, validatedAt = now)
            else -> state
        }
    }.also {
        StalkerTelemetry.capabilityChanged(
            providerId,
            "${contentType.name}_WILDCARD",
            if (supported) "SUPPORTED" else "UNSUPPORTED"
        )
    }

    suspend fun recordEpg(
        providerId: Long,
        supported: Boolean,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) = update(providerId, configurationGeneration) { state ->
        withCapability(state, "epg", supported, configurationGeneration, now, "GUIDE")
            .copy(epgSupported = supported, validatedAt = now)
    }.also {
        StalkerTelemetry.capabilityChanged(providerId, "EPG", if (supported) "SUPPORTED" else "UNSUPPORTED")
    }

    suspend fun recordPlayback(
        providerId: Long,
        playbackMode: String,
        endpointPreference: StalkerEndpointPreference,
        cookieMode: StalkerCookieMode,
        backendHint: StalkerPlaybackBackendHint,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) = update(providerId, configurationGeneration) { state ->
        val generation = configurationGeneration ?: state.configurationGeneration
        val learning = mutableLearning(state.learningJson)
        learning["configurationGeneration"] = JsonPrimitive(generation)
        learning["lastPlaybackMode"] = observationJson(playbackMode, generation, now)
        learning["endpointPreference"] = observationJson(endpointPreference.name, generation, now)
        learning["cookieMode"] = observationJson(cookieMode.name, generation, now)
        learning["playbackBackendHint"] = observationJson(backendHint.name, generation, now)
        state.copy(
            configurationGeneration = generation,
            learningJson = JsonObject(learning).toString(),
            observationSource = "PLAYBACK",
            observedAt = now,
            validatedAt = maxOf(state.validatedAt, now)
        )
    }

    suspend fun recordStressCooldown(
        providerId: Long,
        cooldownUntil: Long,
        now: Long = System.currentTimeMillis()
    ) = update(providerId) { state ->
        state.copy(
            safeMetadataConcurrency = 1,
            stressCooldownUntil = cooldownUntil.coerceAtLeast(now),
            validatedAt = state.validatedAt.takeIf { it > 0L } ?: now
        )
    }.also {
        StalkerTelemetry.capabilityChanged(providerId, "METADATA_CONCURRENCY", "DOWNGRADED")
    }

    suspend fun recordRateLimitCooldown(
        providerId: Long,
        cooldownUntil: Long,
        now: Long = System.currentTimeMillis()
    ) = update(providerId) { state ->
        val health = decodeEndpointHealth(state.endpointHealthJson)
            .filterValues { expiry -> expiry > now }
            .toMutableMap()
        health[rateLimitKey()] = maxOf(health[rateLimitKey()] ?: 0L, cooldownUntil.coerceAtLeast(now))
        state.copy(
            endpointHealthJson = encodeBoundedHealth(health),
            validatedAt = state.validatedAt.takeIf { it > 0L } ?: now
        )
    }.also {
        StalkerTelemetry.capabilityChanged(providerId, "PROVIDER_RATE_LIMIT", "COOLDOWN")
    }

    suspend fun clearRateLimitCooldown(providerId: Long) {
        val state = dao.get(providerId) ?: return
        val health = decodeEndpointHealth(state.endpointHealthJson).toMutableMap()
        if (health.remove(rateLimitKey()) != null) {
            dao.upsert(state.copy(endpointHealthJson = json.encodeToString(health)))
            StalkerTelemetry.capabilityChanged(providerId, "PROVIDER_RATE_LIMIT", "RESTORED")
        }
    }

    fun rateLimitCooldownUntil(state: StalkerPortalStateEntity): Long =
        decodeEndpointHealth(state.endpointHealthJson)[rateLimitKey()] ?: 0L

    suspend fun recordHealthyMetadataProbe(
        providerId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val state = dao.get(providerId) ?: return
        if (state.safeMetadataConcurrency == 1 && state.stressCooldownUntil in 1..now) {
            dao.upsert(state.copy(safeMetadataConcurrency = 2, stressCooldownUntil = 0L))
            StalkerTelemetry.capabilityChanged(providerId, "METADATA_CONCURRENCY", "RESTORED")
        }
    }

    suspend fun markEndpointUnhealthy(
        providerId: Long,
        endpoint: String,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) {
        if (providerId <= 0L || endpoint.isBlank()) return
        val until = now + ENDPOINT_COOLDOWN_MILLIS
        update(providerId, configurationGeneration) { state ->
            val health = decodeEndpointHealth(state.endpointHealthJson)
                .filterValues { expiry -> expiry > now }
                .toMutableMap()
            health[endpointKey(endpoint)] = until
            val bounded = decodeEndpointHealth(encodeBoundedHealth(health))
            state.copy(
                endpointHealthJson = json.encodeToString(bounded),
                endpointFailedUntil = bounded.values.maxOrNull() ?: 0L
            )
        }
        StalkerTelemetry.capabilityChanged(providerId, "ENDPOINT", "COOLDOWN")
    }

    fun isEndpointHealthy(
        state: StalkerPortalStateEntity,
        endpoint: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = decodeEndpointHealth(state.endpointHealthJson)[endpointKey(endpoint)]?.let { it <= now } ?: true

    suspend fun markRecipeUnhealthy(
        providerId: Long,
        recipe: String,
        now: Long = System.currentTimeMillis(),
        configurationGeneration: Long? = null
    ) {
        if (providerId <= 0L || recipe.isBlank()) return
        val until = now + RECIPE_COOLDOWN_MILLIS
        update(providerId, configurationGeneration) { state ->
            val health = decodeEndpointHealth(state.endpointHealthJson)
                .filterValues { expiry -> expiry > now }
                .toMutableMap()
            health[recipeKey(recipe)] = until
            state.copy(
                endpointHealthJson = encodeBoundedHealth(health)
            )
        }
        StalkerTelemetry.capabilityChanged(providerId, "AUTH_RECIPE", "COOLDOWN")
    }

    fun isRecipeHealthy(
        state: StalkerPortalStateEntity,
        recipe: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = decodeEndpointHealth(state.endpointHealthJson)[recipeKey(recipe)]?.let { it <= now } ?: true

    suspend fun invalidateAuthentication(providerId: Long) = update(providerId) { state ->
        state.copy(workingEndpoint = null, bootstrapRecipe = null, validatedAt = 0L)
    }

    suspend fun invalidateCapabilities(providerId: Long) = update(providerId) { state ->
        state.copy(
            bulkLiveSupported = null,
            bulkLiveCategoryFidelity = null,
            movieWildcardSupported = null,
            seriesWildcardSupported = null,
            epgSupported = null,
            validatedAt = 0L
        )
    }

    suspend fun invalidate(providerId: Long) {
        if (providerId > 0L) dao.invalidate(providerId)
    }

    suspend fun restore(providerId: Long, state: StalkerPortalStateEntity?) {
        if (providerId <= 0L) return
        if (state == null) {
            dao.invalidate(providerId)
        } else {
            dao.upsert(state.copy(providerId = providerId))
        }
    }

    private suspend fun update(
        providerId: Long,
        configurationGeneration: Long? = null,
        transform: (StalkerPortalStateEntity) -> StalkerPortalStateEntity
    ) {
        if (providerId <= 0L) return
        val existing = dao.get(providerId)
        persist(
            transform(existing ?: StalkerPortalStateEntity(providerId)),
            configurationGeneration ?: existing?.configurationGeneration?.takeIf { it > 0L }
        )
    }

    private suspend fun persist(
        entity: StalkerPortalStateEntity,
        expectedGeneration: Long?
    ): Boolean {
        val snapshotDao = providerSnapshotDao
        if (snapshotDao == null) {
            dao.upsert(entity)
            return true
        }
        val currentGeneration = snapshotDao.getConfig(entity.providerId)?.configurationGeneration ?: return false
        val generation = expectedGeneration ?: currentGeneration
        return snapshotDao.compareAndSetStalkerLearning(
            entity.copy(
                configurationGeneration = generation,
                observedAt = entity.observedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
    }

    private fun clearHealthFailures(jsonValue: String?, vararg keys: String): String {
        val health = decodeEndpointHealth(jsonValue).toMutableMap()
        keys.forEach(health::remove)
        return json.encodeToString(health)
    }

    private fun observationJson(
        value: Any,
        generation: Long,
        observedAt: Long,
        source: String = "PLAYBACK"
    ): JsonObject = JsonObject(
        mapOf(
            "value" to value.toJsonPrimitive(),
            "configurationGeneration" to JsonPrimitive(generation),
            "source" to JsonPrimitive(source),
            "observedAt" to JsonPrimitive(observedAt)
        )
    )

    private fun authenticationLearningJson(
        existingJson: String?,
        generation: Long,
        session: StalkerSession,
        profile: StalkerProviderProfile,
        observedAt: Long
    ): String {
        val learning = mutableLearning(existingJson)
        learning["configurationGeneration"] = JsonPrimitive(generation)
        learning["profileId"] = observationJson(profile.compatibilityProfileId, generation, observedAt, "AUTHENTICATION")
        learning["profileRevision"] = observationJson(profile.profileRevision, generation, observedAt, "AUTHENTICATION")
        learning["profileVerification"] = observationJson(profile.profileVerification.name, generation, observedAt, "AUTHENTICATION")
        learning["portalProfile"] = observationJson(profile.portalProfile.name, generation, observedAt, "AUTHENTICATION")
        learning["portalFingerprint"] = observationJson(profile.portalFingerprint.name, generation, observedAt, "AUTHENTICATION")
        learning["magPreset"] = observationJson(profile.magPreset.name, generation, observedAt, "AUTHENTICATION")
        learning["protocolFamily"] = observationJson(profile.protocolFamily.name, generation, observedAt, "AUTHENTICATION")
        learning["bootstrapRecipe"] = observationJson(profile.bootstrapRecipe.name, generation, observedAt, "AUTHENTICATION")
        learning["workingEndpoint"] = observationJson(session.loadUrl, generation, observedAt, "AUTHENTICATION")
        learning["resumableSession"] = JsonPrimitive(gson.toJson(session))
        learning["resumableProfile"] = JsonPrimitive(gson.toJson(profile))
        return JsonObject(learning).toString()
    }

    private fun withCapability(
        state: StalkerPortalStateEntity,
        key: String,
        supported: Boolean,
        generationOverride: Long?,
        observedAt: Long,
        source: String
    ): StalkerPortalStateEntity {
        val generation = generationOverride ?: state.configurationGeneration
        val learning = mutableLearning(state.learningJson)
        learning["configurationGeneration"] = JsonPrimitive(generation)
        val capabilities = (learning["capabilities"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        capabilities[key] = observationJson(
            if (supported) "SUPPORTED" else "UNSUPPORTED",
            generation,
            observedAt,
            source
        )
        learning["capabilities"] = JsonObject(capabilities)
        return state.copy(
            configurationGeneration = generation,
            learningJson = JsonObject(learning).toString(),
            observationSource = source,
            observedAt = observedAt
        )
    }

    private fun mutableLearning(value: String?): MutableMap<String, JsonElement> = runCatching {
        json.parseToJsonElement(value.orEmpty().ifBlank { "{}" }).let { it as JsonObject }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun Any.toJsonPrimitive(): JsonPrimitive = when (this) {
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private fun encodeBoundedHealth(health: Map<String, Long>): String {
        val rateKey = rateLimitKey()
        val rateEntry = health[rateKey]?.let { mapOf(rateKey to it) }.orEmpty()
        val ordinary = health.entries
            .asSequence()
            .filter { it.key != rateKey }
            .sortedByDescending { it.value }
            .take(MAX_ENDPOINT_HEALTH_ENTRIES - rateEntry.size)
            .associate { it.key to it.value }
        return json.encodeToString(rateEntry + ordinary)
    }

    private fun decodeEndpointHealth(value: String?): Map<String, Long> = runCatching {
        json.decodeFromString<Map<String, Long>>(value.orEmpty().ifBlank { "{}" })
    }.getOrDefault(emptyMap())

    private fun endpointKey(endpoint: String): String = healthKey("endpoint", StalkerUrlFactory.normalizePortalUrl(endpoint))

    private fun recipeKey(recipe: String): String = healthKey("recipe", recipe.uppercase())

    private fun rateLimitKey(): String = healthKey("provider", "rate_limit")

    private fun healthKey(kind: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$kind:$value".toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val VALIDATION_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val ENDPOINT_COOLDOWN_MILLIS = 10L * 60L * 1000L
        const val RECIPE_COOLDOWN_MILLIS = 10L * 60L * 1000L
        const val MAX_ENDPOINT_HEALTH_ENTRIES = 8
    }
}
