package com.streamvault.data.remote.stalker

import android.util.Log
import com.google.gson.JsonObject as GsonJsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.DiscoveryBudget
import com.streamvault.domain.util.StreamEntryUrlPolicy
import com.streamvault.data.util.runSuspendCatching
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class OkHttpStalkerApiService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val transportFactory: StalkerTransportFactory = StalkerTransportFactory(okHttpClient),
    private val requestCoordinator: StalkerRequestCoordinator = StalkerRequestCoordinator()
) : StalkerApiService {
    private class DirectCreateLinkResponse(val playbackUrl: String) : IOException()

    private data class SessionScope(
        val cookieJar: InMemoryStalkerCookieJar = InMemoryStalkerCookieJar(),
        @Volatile var macQueryRequired: Boolean = false,
        @Volatile var lastAccessAt: Long = System.currentTimeMillis()
    )

    private val sessionScopes = ConcurrentHashMap<String, SessionScope>()
    private val stalkerHttpClients = ConcurrentHashMap<String, OkHttpClient>()
    private val resolvedLoadUrls = ConcurrentHashMap<String, String>()
    private val scopeAliases = ConcurrentHashMap<String, String>()
    private val nextAuthEpoch = AtomicLong(System.currentTimeMillis())

    override suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>> {
        profile.discoveryRuntime.begin()
        val authProfile = profile.copy(authEpoch = nextAuthEpoch.incrementAndGet())
        return try {
            // Each network call is capped by the remaining wall-time and consumeRequest checks
            // elapsed time before dispatch. Avoid a second coroutine timer here: virtual-time
            // dispatchers can otherwise race an already-completed OkHttp callback.
            authenticateWithinBudget(authProfile).also { result ->
                if (result is Result.Success) {
                    scopeAliases[sessionScopeAliasKey(profile)] = result.data.first.sessionScopeKey
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: StalkerApiError) {
            Result.error(error.message.orEmpty(), error)
        } catch (error: IOException) {
            Result.error(error.message ?: "Failed to connect to portal.", error)
        } finally {
            profile.discoveryRuntime.end()
        }
    }

    private suspend fun authenticateWithinBudget(
        profile: StalkerDeviceProfile
    ): Result<Pair<StalkerSession, StalkerProviderProfile>> {
        var lastError: Throwable? = null
        val failedHandshakeAttempts = mutableSetOf<String>()
        val hintedLoadUrls = probePortalBaseHintCandidates(profile)
        val authModes = candidateAuthModes(profile)
        for (effectiveAuthMode in authModes) {
            val attempts = ArrayDeque(candidateAuthAttempts(profile, effectiveAuthMode, hintedLoadUrls))
            val retriedPartialAuthorization = mutableSetOf<String>()
            while (attempts.isNotEmpty()) {
                val attempt = attempts.removeFirst()
                val recipeIndex = attempt.recipeIndex
                val recipe = attempt.recipe
                var loadUrl = attempt.loadUrl
                var endpointFamily = endpointPreferenceFor(loadUrl).name
                val profileLabel = StalkerCompatibilityRegistry.find(recipe.compatibilityProfileId)
                    ?.displayName ?: recipe.compatibilityProfileId
                profile.onProgress?.invoke("Trying $profileLabel")
                StalkerTelemetry.authenticationAttempt(
                    profile.providerId,
                    recipe.compatibilityProfileId,
                    endpointFamily,
                    "HANDSHAKE",
                    "STARTED"
                )
                val handshakeAttemptKey = listOf(
                    loadUrl,
                    effectiveAuthMode.name,
                    recipe.recipe.name,
                    recipe.compatibilityProfileId,
                    profile.cookieMode.name,
                    profile.endpointPreference.name,
                    hashDiscoveryHeaderPolicy(profile),
                    profile.transportGrant?.origin?.authority.orEmpty(),
                    profile.transportGrant?.spkiSha256.orEmpty(),
                    profile.transportGrant?.consentedAt?.toString().orEmpty()
                ).joinToString("|")
                if (handshakeAttemptKey in failedHandshakeAttempts) {
                    continue
                }
                    var referer = StalkerUrlFactory.portalReferer(loadUrl)
                    val evidence = mutableListOf<String>()
                    val recipeEvidence = mutableListOf("recipe:${recipe.recipe.name}", "preset:${recipe.magPreset.name}")
                    val attemptProfile = profile.withRecipe(recipe, effectiveAuthMode)
                    val sessionScope = sessionScopeFor(attemptProfile)
                    sessionScope.cookieJar.clear()
                    val cookieJar = sessionScope.cookieJar
                    // Compatibility discovery is per authentication scope. A portal may
                    // change its request contract between profiles/endpoints, so do not
                    // carry a MAC-query decision across a fresh authentication attempt.
                    sessionScope.macQueryRequired = false
                    val handshakePayload = runSuspendCatching {
                        requestJson(
                            url = loadUrl,
                            profile = attemptProfile,
                            referer = referer,
                            query = mapOf(
                                "type" to "stb",
                                "action" to "handshake",
                                "token" to "",
                                "JsHttpRequest" to "1-xml"
                            )
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        failedHandshakeAttempts += handshakeAttemptKey
                        StalkerTelemetry.authenticationAttempt(
                            profile.providerId,
                            recipe.compatibilityProfileId,
                            endpointFamily,
                            "HANDSHAKE",
                            authenticationFailureOutcome(error)
                        )
                        if (error.isTerminalStalkerDiscoveryFailure()) return Result.error(error.message.orEmpty(), error)
                        lastError = preferredAuthenticationFailure(lastError, error)
                        continue
                    }
                    val token = handshakePayload.findString("token")
                        ?.takeIf { it.isNotBlank() }
                        ?: run {
                            lastError = IOException("Portal handshake did not return a token.")
                            continue
                        }
                    val handshakeRandom = handshakePayload.findString("random").orEmpty()
                    resolvedLoadUrl(loadUrl, attemptProfile)?.let { redirectedLoadUrl ->
                        loadUrl = redirectedLoadUrl
                        referer = StalkerUrlFactory.portalReferer(redirectedLoadUrl)
                        endpointFamily = endpointPreferenceFor(redirectedLoadUrl).name
                        evidence += "endpoint_redirect"
                    }
                    evidence += "handshake"
                    profile.onProgress?.invoke("Handshake accepted")
                    StalkerTelemetry.authenticationAttempt(
                        profile.providerId,
                        recipe.compatibilityProfileId,
                        endpointFamily,
                        "HANDSHAKE",
                        "ACCEPTED"
                    )

                    if (recipe.authMode.requiresCredentials()) {
                        if (attemptProfile.username.isBlank()) {
                            lastError = IOException("Portal requires account credentials for this connection.")
                            continue
                        }
                        val authPayload = runSuspendCatching {
                            requestCredentialAuth(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                allowAlternateEndpointRetry = false
                            )
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            StalkerTelemetry.authenticationAttempt(
                                profile.providerId,
                                recipe.compatibilityProfileId,
                                endpointFamily,
                                "CREDENTIALS",
                                authenticationFailureOutcome(error)
                            )
                            if (error.isTerminalStalkerDiscoveryFailure()) return Result.error(error.message.orEmpty(), error)
                            lastError = preferredAuthenticationFailure(lastError, error)
                            continue
                        }
                        authPayload.ensureNoPortalError()
                        evidence += "do_auth"
                    }

                    var session = StalkerSession(
                        loadUrl = loadUrl,
                        portalReferer = referer,
                        token = token,
                        authEpoch = attemptProfile.authEpoch,
                        sessionScopeKey = sessionScopeKey(attemptProfile)
                    )
                    if (recipe.preferLocalizationBeforeProfile) {
                        runSuspendCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_localization",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNullPreservingCancellation()?.let {
                            evidence += "get_localization"
                        }
                    }
                    val profilePayload = runSuspendCatching {
                        requestJson(
                            url = loadUrl,
                            profile = attemptProfile,
                            referer = referer,
                            token = token,
                            query = buildProfileQuery(
                                profile = attemptProfile,
                                handshakeRandom = handshakeRandom
                            )
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        StalkerTelemetry.authenticationAttempt(
                            profile.providerId,
                            recipe.compatibilityProfileId,
                            endpointFamily,
                            "PROFILE",
                            authenticationFailureOutcome(error)
                        )
                        if (error.isTerminalStalkerDiscoveryFailure()) return Result.error(error.message.orEmpty(), error)
                        lastError = preferredAuthenticationFailure(lastError, error)
                        continue
                    }
                    profilePayload.ensureNoPortalError()
                    evidence += "get_profile"
                    profile.onProgress?.invoke(
                        if (profile.requireCatalogValidation) {
                            "Profile accepted; checking catalog"
                        } else {
                            "Profile accepted"
                        }
                    )
                    StalkerTelemetry.authenticationAttempt(
                        profile.providerId,
                        recipe.compatibilityProfileId,
                        endpointFamily,
                        "PROFILE",
                        "ACCEPTED"
                    )

                    var providerProfile = profilePayload.toProviderProfile(profile.timezone)
                    var bootstrapStrategy = when (recipe.recipe) {
                        StalkerBootstrapRecipe.GENERIC_SAFE -> StalkerBootstrapStrategy.AUTO
                        StalkerBootstrapRecipe.LEGACY_MAG -> StalkerBootstrapStrategy.MAC_ONLY
                        StalkerBootstrapRecipe.STRICT_MAG,
                        StalkerBootstrapRecipe.PORTAL_PREFERRED,
                        StalkerBootstrapRecipe.LOCALIZATION_STRICT,
                        StalkerBootstrapRecipe.AUTH_ONLY,
                        StalkerBootstrapRecipe.AUTH_STRICT_MAG -> StalkerBootstrapStrategy.MAC_WITH_ACCOUNT_INFO
                        StalkerBootstrapRecipe.MODULE_GATED -> StalkerBootstrapStrategy.MAC_WITH_MODULES
                    }
                    if (recipe.requestAccountInfo || providerProfile.shouldRequestAccountInfo()) {
                        runSuspendCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "account_info",
                                    "action" to "get_main_info",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNullPreservingCancellation()?.let { accountInfoPayload ->
                            providerProfile = providerProfile.merge(accountInfoPayload.toProviderProfile(profile.timezone))
                            bootstrapStrategy = StalkerBootstrapStrategy.MAC_WITH_ACCOUNT_INFO
                            evidence += "get_account_info"
                        }
                    }
                    if (recipe.requestLocalization && "get_localization" !in evidence) {
                        runSuspendCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_localization",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNullPreservingCancellation()?.let {
                            evidence += "get_localization"
                        }
                    }
                    if (recipe.requestModules || providerProfile.shouldRequestModules()) {
                        runSuspendCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_modules",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNullPreservingCancellation()?.let { modulesPayload ->
                            val modules = modulesPayload.toModuleNames()
                            if (modules.isNotEmpty()) {
                                bootstrapStrategy = StalkerBootstrapStrategy.MAC_WITH_MODULES
                                evidence += "get_modules"
                            }
                            providerProfile = providerProfile.copy(moduleNames = modules)
                        }
                    }
                    if ((recipe.strictIdentityRequired || recipe.playbackBackendHint == StalkerPlaybackBackendHint.TEMP_LINK_STRICT) &&
                        "get_events" !in evidence
                    ) {
                        runSuspendCatching {
                            requestJson(
                                url = loadUrl,
                                profile = attemptProfile,
                                referer = referer,
                                token = token,
                                query = mapOf(
                                    "type" to "stb",
                                    "action" to "get_events",
                                    "JsHttpRequest" to "1-xml"
                                )
                            )
                        }.getOrNullPreservingCancellation()?.let {
                            evidence += "get_events"
                        }
                    }

                    if (profile.requireCatalogValidation) {
                        val catalogEvidence = runSuspendCatching {
                            validateCatalogAcceptance(session, attemptProfile)
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            if (error is StalkerApiError.PartialAuthorization) {
                                val retryKey = "${effectiveAuthMode.name}|${attempt.recipe.compatibilityProfileId}|$loadUrl"
                                val reportedProfile = StalkerCompatibilityRegistry.findVerifiedByModelSignal(
                                    providerProfile.reportedStbType
                                )
                                if (profile.allowCompatibilityDiscovery &&
                                    reportedProfile != null &&
                                    reportedProfile.id != recipe.compatibilityProfileId
                                ) {
                                    promoteCompatibilityAttempt(
                                        attempts = attempts,
                                        compatibilityProfileId = reportedProfile.id,
                                        loadUrl = attempt.loadUrl
                                    )
                                    profile.onProgress?.invoke(
                                        "Portal reports ${reportedProfile.displayName}; validating that profile"
                                    )
                                    StalkerTelemetry.authenticationAttempt(
                                        profile.providerId,
                                        recipe.compatibilityProfileId,
                                        endpointFamily,
                                        "CATALOG",
                                        "MODEL_SIGNAL"
                                    )
                                    lastError = preferredAuthenticationFailure(lastError, error)
                                    continue
                                }
                                if (error.requiresFreshSessionRetry() &&
                                    retriedPartialAuthorization.add(retryKey)
                                ) {
                                    attempts.addFirst(attempt)
                                    profile.onProgress?.invoke("Catalog authorization rejected; retrying fresh session")
                                    StalkerTelemetry.authenticationAttempt(
                                        profile.providerId,
                                        recipe.compatibilityProfileId,
                                        endpointFamily,
                                        "CATALOG",
                                        "FRESH_SESSION_RETRY"
                                    )
                                    lastError = preferredAuthenticationFailure(lastError, error)
                                    continue
                                }
                                Log.w(
                                    TAG,
                                    "Stalker partial authorization host=${runCatching { URI(loadUrl).host }.getOrNull().orEmpty()} " +
                                        "profile=${attempt.recipe.compatibilityProfileId}; fresh-session retry rejected"
                                )
                                StalkerTelemetry.authenticationAttempt(
                                    profile.providerId,
                                    recipe.compatibilityProfileId,
                                    endpointFamily,
                                    "CATALOG",
                                    "PARTIAL_AUTHORIZATION"
                                )
                                lastError = preferredAuthenticationFailure(lastError, error)
                                if (profile.allowCompatibilityDiscovery) {
                                    profile.onProgress?.invoke(
                                        "Catalog rejected $profileLabel; trying another compatibility profile"
                                    )
                                    continue
                                }
                                return Result.error(error.message.orEmpty(), error)
                            }
                            if (error.isInconclusiveLiveReadinessFailure()) {
                                val inconclusive = StalkerApiError.ReadinessInconclusive(
                                    evidenceCode = error.liveReadinessEvidenceCode(),
                                    cause = error
                                )
                                return Result.error(inconclusive.message.orEmpty(), inconclusive)
                            }
                            if (error.isDefinitiveLiveReadinessFailure()) {
                                return Result.error(error.message.orEmpty(), error)
                            }
                            if (error.isTerminalStalkerDiscoveryFailure()) {
                                return Result.error(error.message.orEmpty(), error)
                            }
                            lastError = preferredAuthenticationFailure(lastError, error)
                            continue
                        }
                        evidence += catalogEvidence
                        StalkerTelemetry.authenticationAttempt(
                            profile.providerId,
                            recipe.compatibilityProfileId,
                            endpointFamily,
                            "CATALOG",
                            "ACCEPTED"
                        )
                    }

                    val fingerprint = detectPortalFingerprint(
                        profile = providerProfile,
                        effectiveAuthMode = effectiveAuthMode,
                        selectedPreset = recipe.magPreset,
                        selectedRecipe = recipe.recipe
                    )
                    val portalProfile = profileForFingerprint(fingerprint)
                    val ambiguousState = providerProfile.isAmbiguousAccountState()
                    val credentialRequired = portalProfile == StalkerPortalProfile.AUTH_REQUIRED ||
                        portalProfile == StalkerPortalProfile.AUTH_PLUS_MAG
                    val macRequired = portalProfile != StalkerPortalProfile.AUTH_REQUIRED
                    if (effectiveAuthMode == StalkerAuthMode.MAC_ONLY &&
                        attemptProfile.username.isNotBlank() &&
                        ambiguousState
                    ) {
                        lastError = IOException("Portal partially accepted MAC identity; retrying credential-backed auth.")
                        continue
                    }
                    if (effectiveAuthMode == StalkerAuthMode.MAC_ONLY && credentialRequired) {
                        lastError = IOException("Portal requires account credentials for this connection.")
                        continue
                    }
                    val fallbackRecipeUsed = recipeIndex > 0
                    val rediscoveryAttempted = fallbackRecipeUsed || profile.bootstrapRecipe != StalkerBootstrapRecipe.GENERIC_SAFE
                    if (fallbackRecipeUsed) {
                        recipeEvidence += "fallback_recipe"
                    }
                    if (rediscoveryAttempted) {
                        recipeEvidence += "rediscovery_attempted"
                    }
                    val fingerprintEvidence = StalkerFingerprintEvidence(
                        endpointPreference = endpointPreferenceFor(loadUrl),
                        cookieMode = resolveCookieMode(
                            base = attemptProfile.cookieMode,
                            serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                            recipe = recipe
                        ),
                        playbackBackendHint = attemptProfile.playbackBackendHint,
                        localizationRequired = "get_localization" in evidence,
                        modulesRequired = "get_modules" in evidence,
                        alternateEndpointAccepted = loadUrl != StalkerUrlFactory.loadUrlCandidates(profile.portalUrl).firstOrNull(),
                        genericPresetRejected = fallbackRecipeUsed && recipe.magPreset != StalkerMagPreset.GENERIC_SAFE,
                        strictPresetAccepted = recipe.magPreset != StalkerMagPreset.GENERIC_SAFE,
                        archiveViaCreateLink = recipe.playbackBackendHint != StalkerPlaybackBackendHint.DIRECT ||
                            providerProfile.portalCapabilities.archiveAvailable ||
                            providerProfile.portalCapabilities.allowLocalTimeshift ||
                            providerProfile.portalCapabilities.allowLocalPvr ||
                            providerProfile.portalCapabilities.allowRemotePvr,
                        archiveViaDirectUrl = recipe.playbackBackendHint == StalkerPlaybackBackendHint.DIRECT,
                        archiveRequiresBootstrapPrep = "get_localization" in evidence || "get_modules" in evidence,
                        archiveRequiresStrictCookies = resolveCookieMode(
                            base = attemptProfile.cookieMode,
                            serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                            recipe = recipe
                        ) in setOf(StalkerCookieMode.PLAYBACK, StalkerCookieMode.BOTH),
                        archiveEndpointPreference = endpointPreferenceFor(loadUrl)
                    )
                    providerProfile = providerProfile.copy(
                        bootstrapStrategy = bootstrapStrategy,
                        effectiveAuthMode = effectiveAuthMode,
                        portalProfile = portalProfile,
                        portalFingerprint = fingerprint,
                        magPreset = recipe.magPreset,
                        bootstrapRecipe = recipe.recipe,
                        fingerprintEvidence = fingerprintEvidence,
                        credentialRequired = credentialRequired,
                        macRequired = macRequired,
                        bootstrapEvidence = evidence.toList(),
                        recipeEvidence = recipeEvidence.toList(),
                        strictFingerprintRequired = recipe.strictIdentityRequired,
                        fallbackRecipeUsed = fallbackRecipeUsed,
                        rediscoveryAttempted = rediscoveryAttempted,
                        portalCapabilities = providerProfile.portalCapabilities.copy(
                            bootstrapStrategy = bootstrapStrategy,
                            moduleRestricted = providerProfile.moduleNames.isNotEmpty(),
                            ambiguousAccountState = ambiguousState
                        ),
                        ambiguousState = ambiguousState,
                        compatibilityProfileId = recipe.compatibilityProfileId,
                        profileRevision = StalkerCompatibilityRegistry.REVISION,
                        profileVerification = StalkerCompatibilityRegistry.find(recipe.compatibilityProfileId)
                            ?.verification ?: com.streamvault.domain.model.StalkerProfileVerification.UNVERIFIED,
                        protocolFamily = com.streamvault.domain.model.StalkerProtocolFamily.CLASSIC_MAG
                    )
                    session = session.copy(
                        serverCookieHeader = cookieJar.cookieHeaderFor(loadUrl),
                        authenticatedAtMillis = System.currentTimeMillis(),
                        expiresAtMillis = listOfNotNull(
                            System.currentTimeMillis() + STALKER_SESSION_MAX_AGE_MILLIS,
                            providerProfile.expirationDate?.takeIf { it > System.currentTimeMillis() }
                        ).minOrNull(),
                        effectiveAuthMode = effectiveAuthMode,
                        portalProfile = portalProfile,
                        portalFingerprint = fingerprint,
                        magPreset = recipe.magPreset,
                        bootstrapRecipe = recipe.recipe,
                        fingerprintEvidence = fingerprintEvidence,
                        bootstrapEvidence = evidence.toList(),
                        recipeEvidence = recipeEvidence.toList(),
                        rediscoveryAttempted = rediscoveryAttempted,
                        compatibilityProfileId = recipe.compatibilityProfileId
                    )
                    Log.i(
                        TAG,
                        "Stalker auth success host=${runCatching { URI(loadUrl).host }.getOrNull().orEmpty()} " +
                            "auth=${effectiveAuthMode.name} recipe=${recipe.recipe.name} preset=${recipe.magPreset.name} " +
                            "profile=${attemptProfile.deviceProfile} endpoint=${endpointPreferenceFor(loadUrl).name} " +
                            "cookie=${fingerprintEvidence.cookieMode.name} backend=${fingerprintEvidence.playbackBackendHint.name} " +
                            "bootstrap=${evidence.joinToString(",")}"
                    )
                    return Result.success(session to providerProfile)
            }
        }

        Log.w(
            TAG,
            "Stalker auth failed host=${runCatching { URI(profile.portalUrl).host }.getOrNull().orEmpty()} " +
                "hintRecipe=${profile.bootstrapRecipe.name} hintPreset=${profile.magPreset.name} " +
                "hintFingerprint=${profile.portalFingerprint.name} error=${lastError?.message.orEmpty()}"
        )
        return Result.error(
            lastError?.message ?: "Failed to connect to portal.",
            lastError
        )
    }

    override suspend fun getLiveCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load live categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_genres",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load live channels") {
        if (categoryId.isNullOrBlank()) {
            fetchAllLiveChannels(
                session = session,
                profile = profile
            )?.let { return@runApiCall it }
        }

        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "itv")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                put("force_ch_link_check", "0")
                put("fav", "0")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            }
        )
    }

    override suspend fun streamLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load live channels") {
        requestStreamingItems(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_all_channels",
                "JsHttpRequest" to "1-xml"
            ),
            onItem = onItem
        )
    }

    override suspend fun getVodCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load movie categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "vod",
                "action" to "get_categories",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getVodStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load movies") {
        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "vod")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        )
    }

    override suspend fun getVodStreamsPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems> = runApiCall("Failed to load movies") {
        fetchPagedItemPage(
            session = session,
            profile = profile,
            page = page,
            baseQuery = buildMap {
                put("type", "vod")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        )
    }

    override suspend fun getSeriesCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>> = runApiCall("Failed to load series categories") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "series",
                "action" to "get_categories",
                "JsHttpRequest" to "1-xml"
            )
        ).toCategoryRecords()
    }

    override suspend fun getSeries(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>> = runApiCall("Failed to load series") {
        fetchPagedItems(
            session = session,
            profile = profile,
            baseQuery = buildMap {
                put("type", "series")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        )
    }

    override suspend fun getSeriesPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems> = runApiCall("Failed to load series") {
        fetchPagedItemPage(
            session = session,
            profile = profile,
            page = page,
            baseQuery = buildMap {
                put("type", "series")
                put("action", "get_ordered_list")
                put("JsHttpRequest", "1-xml")
                categoryId?.takeIf { it.isNotBlank() }?.let { put("category", it) }
            }
        )
    }

    override suspend fun getSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails> = getSeriesDetailsForType(
        session = session,
        profile = profile,
        seriesId = seriesId,
        contentType = "series"
    )

    override suspend fun getVodSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails> = getSeriesDetailsForType(
        session = session,
        profile = profile,
        seriesId = seriesId,
        contentType = "vod"
    )

    private suspend fun getSeriesDetailsForType(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String,
        contentType: String
    ): Result<StalkerSeriesDetails> = runApiCall("Failed to load series details") {
        val seriesPayload = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to contentType,
                "action" to "get_ordered_list",
                "JsHttpRequest" to "1-xml",
                "movie_id" to seriesId,
                "season_id" to "0",
                "episode_id" to "0"
            )
        )
        val seedEntries = seriesPayload.extractItemEntries().toMutableList()
        if (contentType == "vod") {
            val seedFingerprints = mutableSetOf(
                seedEntries.joinToString("|") { it.findString("id").orEmpty() }
            )
            val seedTotalPages = seriesPayload.totalPages()
            if (seedTotalPages > MAX_PAGE_COUNT) {
                throw StalkerApiError.CatalogTruncated(
                    advertisedTotalPages = seedTotalPages,
                    pageLimit = MAX_PAGE_COUNT
                )
            }
            for (page in 2..seedTotalPages) {
                val pagePayload = requestJson(
                    url = session.loadUrl,
                    profile = profile,
                    referer = session.portalReferer,
                    token = session.token,
                    query = mapOf(
                        "type" to contentType,
                        "action" to "get_ordered_list",
                        "JsHttpRequest" to "1-xml",
                        "movie_id" to seriesId,
                        "season_id" to "0",
                        "episode_id" to "0",
                        "p" to page.toString()
                    )
                )
                val pageEntries = pagePayload.extractItemEntries()
                val fingerprint = pageEntries.joinToString("|") { it.findString("id").orEmpty() }
                if (fingerprint.isNotEmpty() && !seedFingerprints.add(fingerprint)) {
                    throw StalkerApiError.Malformed(
                        "Portal repeated a series-detail page while loading page $page."
                    )
                }
                if (pageEntries.isEmpty() && page < seedTotalPages) {
                    throw StalkerApiError.Malformed(
                        "Portal returned an empty series-detail page before its advertised end."
                    )
                }
                seedEntries += pageEntries
            }
        }
        val seriesItems = seriesPayload.toItemRecords(profile.timezone.toPortalZoneId())
        val series = seriesItems.firstOrNull { item -> !item.looksLikeSeasonShell() }
            ?: StalkerItemRecord(
                id = seriesId,
                name = ""
            )
        val seasonRows = seedEntries
            .mapNotNull { entry ->
                val seasonId = if (contentType == "vod") {
                    entry.findString("id")?.takeIf { entry.looksLikeVodSeasonShell() }
                } else {
                    entry.findString("season_id")?.takeIf { it.isNotBlank() && it != "0" }
                }
                seasonId?.let { it to entry }
            }
            .distinctBy { it.first }
        val shellSeasonRows = seedEntries.mapIndexedNotNull { index, entry ->
            entry.toSeasonShellRecord(index + 1)
                ?.let { season -> season.seasonNumber.toString() to entry }
        }

        val paginationEvidence = mutableListOf<StalkerEpisodePaginationEvidence>()
        val seasons = if (seasonRows.isNotEmpty()) {
            seasonRows.mapIndexed { index, (seasonId, entry) ->
                val pages = fetchSeriesEpisodePages(
                    session = session,
                    profile = profile,
                    contentType = contentType,
                    seriesId = seriesId,
                    seasonSelector = seasonId
                )
                if (pages.evidence.pageLimitReached) {
                    throw StalkerApiError.CatalogTruncated(
                        advertisedTotalPages = pages.evidence.advertisedTotalPages ?: (MAX_PAGE_COUNT + 1),
                        pageLimit = MAX_PAGE_COUNT
                    )
                }
                if (pages.evidence.repeatedPageDetected || pages.evidence.malformedPagination) {
                    throw StalkerApiError.Malformed(
                        "Portal returned non-progressing or inconsistent episode pagination for season $seasonId."
                    )
                }
                paginationEvidence += pages.evidence
                entry.toSeasonRecord(
                    episodeEntries = pages.entries,
                    fallbackSeasonNumber = entry.findString("season_number")?.toIntOrNull()
                        ?: entry.findString("season_id")?.toIntOrNull()
                        ?: index + 1
                )
            }
        } else if (shellSeasonRows.isNotEmpty()) {
            shellSeasonRows.map { (seasonId, entry) ->
                val episodesPayload = requestJson(
                    url = session.loadUrl,
                    profile = profile,
                    referer = session.portalReferer,
                    token = session.token,
                    query = mapOf(
                        "type" to contentType,
                        "action" to "get_ordered_list",
                        "JsHttpRequest" to "1-xml",
                        "movie_id" to seriesId,
                        "season_id" to seasonId,
                        "episode_id" to "0"
                    )
                )
                entry.toSeasonRecord(
                    episodeEntries = episodesPayload.extractItemEntries(),
                    fallbackSeasonNumber = seasonId.toIntOrNull()
                )
            }
        } else {
            listOf(
                StalkerSeasonRecord(
                    seasonNumber = 1,
                    name = "Season 1",
                    episodes = seedEntries.mapIndexedNotNull { index, entry ->
                        entry.toEpisodeRecord(index + 1, 1)
                    }
                )
            ).filter { it.episodes.isNotEmpty() }
        }

        StalkerSeriesDetails(
            series = series,
            seasons = seasons,
            paginationEvidence = paginationEvidence
        )
    }

    private data class EpisodePages(
        val entries: List<JsonObject>,
        val evidence: StalkerEpisodePaginationEvidence
    )

    private suspend fun fetchSeriesEpisodePages(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        contentType: String,
        seriesId: String,
        seasonSelector: String
    ): EpisodePages {
        val entries = mutableListOf<JsonObject>()
        val fingerprints = mutableSetOf<String>()
        var page = 1
        var successfulPages = 0
        var advertisedPages: Int? = null
        var repeated = false
        var malformed = false
        var pageLimitReached = false
        while (page <= MAX_PAGE_COUNT) {
            val payload = requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = mapOf(
                    "type" to contentType,
                    "action" to "get_ordered_list",
                    "JsHttpRequest" to "1-xml",
                    "movie_id" to seriesId,
                    "season_id" to seasonSelector,
                    "episode_id" to "0",
                    "p" to page.toString()
                )
            )
            val pageEntries = payload.extractItemEntries()
            val fingerprint = pageEntries.joinToString("|") { entry ->
                listOf(
                    entry.findString("id").orEmpty(),
                    entry.findString("series_number").orEmpty(),
                    entry.findString("name").orEmpty()
                ).joinToString(":")
            }
            if (page > 1 && fingerprint.isNotEmpty() && !fingerprints.add(fingerprint)) {
                repeated = true
                break
            }
            if (fingerprint.isNotEmpty()) fingerprints += fingerprint
            entries += pageEntries
            successfulPages += 1
            val reported = payload.advertisedTotalPages()
            if (reported != null) {
                if (advertisedPages != null && advertisedPages != reported) malformed = true
                advertisedPages = maxOf(advertisedPages ?: 1, reported)
            }
            if (pageEntries.isEmpty()) {
                if (page < (advertisedPages ?: 1)) malformed = true
                break
            }
            if (advertisedPages != null && page >= advertisedPages!!) break
            if (page == MAX_PAGE_COUNT) {
                pageLimitReached = true
                break
            }
            page += 1
        }
        if (page > MAX_PAGE_COUNT) pageLimitReached = true
        return EpisodePages(
            entries = entries,
            evidence = StalkerEpisodePaginationEvidence(
                seasonSelector = seasonSelector,
                attemptedPages = page,
                successfulPages = successfulPages,
                advertisedTotalPages = advertisedPages,
                repeatedPageDetected = repeated,
                malformedPagination = malformed,
                pageLimitReached = pageLimitReached
            )
        )
    }

    override suspend fun getShortEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        limit: Int
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load EPG") {
        requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = mapOf(
                "type" to "itv",
                "action" to "get_short_epg",
                "JsHttpRequest" to "1-xml",
                "ch_id" to channelId,
                "size" to limit.coerceAtLeast(1).toString()
            )
        ).toProgramRecords(channelId, profile.timezone)
    }

    override suspend fun getEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load EPG") {
        // Legacy buffered API kept for compatibility; route through the streaming path
        // and cap the in-memory list so a misbehaving portal cannot OOM the caller.
        val buffer = ArrayList<StalkerProgramRecord>()
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = channelId,
            query = perChannelEpgQuery(channelId, periodHours = 6)
        ) { program ->
            if (buffer.size < MAX_INLINE_EPG_RECORDS) {
                buffer.add(program)
            }
        }
        buffer
    }

    override suspend fun getBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int
    ): Result<List<StalkerProgramRecord>> = runApiCall("Failed to load bulk EPG") {
        // Legacy buffered API kept for compatibility; route through the streaming path
        // and cap the in-memory list so a misbehaving portal cannot OOM the caller.
        val buffer = ArrayList<StalkerProgramRecord>()
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = null,
            query = bulkEpgQuery(periodHours)
        ) { program ->
            if (buffer.size < MAX_INLINE_EPG_RECORDS) {
                buffer.add(program)
            }
        }
        buffer
    }

    override suspend fun streamBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load bulk EPG") {
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = null,
            query = bulkEpgQuery(periodHours),
            onProgram = onProgram
        )
    }

    override suspend fun streamEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        periodHours: Int,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int> = runApiCall("Failed to load EPG") {
        requestStreamingPrograms(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            channelIdOverride = channelId,
            query = perChannelEpgQuery(channelId, periodHours),
            onProgram = onProgram
        )
    }

    private fun bulkEpgQuery(periodHours: Int): Map<String, String> = mapOf(
        "type" to "itv",
        "action" to "get_epg_info",
        "JsHttpRequest" to "1-xml",
        "period" to periodHours.coerceAtLeast(1).toString()
    )

    private fun perChannelEpgQuery(channelId: String, periodHours: Int): Map<String, String> = mapOf(
        "type" to "itv",
        "action" to "get_epg_info",
        "JsHttpRequest" to "1-xml",
        "ch_id" to channelId,
        "period" to periodHours.coerceAtLeast(1).toString()
    )

    override suspend fun createLink(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int?,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?
    ): Result<String> = runApiCall("Failed to resolve playback link") {
        val type = when (kind) {
            StalkerStreamKind.LIVE,
            StalkerStreamKind.ARCHIVE -> "itv"
            StalkerStreamKind.MOVIE, StalkerStreamKind.EPISODE -> "vod"
        }
        val seriesSelector = if (kind == StalkerStreamKind.EPISODE) {
            seriesNumber?.takeIf { it > 0 }?.toString() ?: "0"
        } else {
            "0"
        }
        val forcedStorage = when (kind) {
            StalkerStreamKind.LIVE,
            StalkerStreamKind.ARCHIVE -> "undefined"
            StalkerStreamKind.MOVIE,
            StalkerStreamKind.EPISODE -> "0"
        }
        val playbackLoadUrl = createLinkLoadUrl(session)
        val payload = try {
            requestJson(
                url = playbackLoadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = mapOf(
                    "type" to type,
                    "action" to "create_link",
                    "JsHttpRequest" to "1-xml",
                    "cmd" to cmd,
                    "series" to seriesSelector,
                    "forced_storage" to forcedStorage,
                    "disable_ad" to "0",
                    "download" to "0"
                )
            )
        } catch (direct: DirectCreateLinkResponse) {
            return@runApiCall direct.playbackUrl
        }
        val portalError = payload.findString("error")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("false", ignoreCase = true) && it != "0" }
        if (portalError != null) {
            val normalized = portalError.lowercase(Locale.ROOT)
            if (normalized == "nothing_to_play" || normalized.contains("nothing to play")) {
                throw StalkerApiError.ContentUnavailable(portalReason = "nothing_to_play")
            }
            if (isStalkerAuthorizationFailure(portalError, null)) {
                throw StalkerApiError.Authorization(portalReason = normalized)
            }
            throw StalkerApiError.Malformed("Portal returned a playback error.")
        }
        payload.findString("cmd")
            ?.substringAfter(' ', missingDelimiterValue = payload.findString("cmd").orEmpty())
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { resolved ->
                val playbackUrl = if (kind == StalkerStreamKind.ARCHIVE) {
                    appendArchiveWindow(
                        url = resolved,
                        startSeconds = archiveStartSeconds,
                        endSeconds = archiveEndSeconds
                    ) ?: resolved
                } else {
                    resolved
                }
                if (!StreamEntryUrlPolicy.isAllowed(playbackUrl)) {
                    throw StalkerApiError.UnsupportedProtocol(
                        "Portal returned an unsupported playback URL scheme."
                    )
                }
                playbackUrl
            }
            ?: throw StalkerApiError.Malformed("Portal did not return a playable URL.")
    }

    private fun createLinkLoadUrl(session: StalkerSession): String {
        return session.loadUrl
    }

    override fun currentCookieHeader(session: StalkerSession): String =
        sessionScopes[session.sessionScopeKey]
            ?.also { scope -> scope.lastAccessAt = System.currentTimeMillis() }
            ?.cookieJar
            ?.cookieHeaderFor(session.loadUrl)
            .orEmpty()
            .ifBlank { session.serverCookieHeader }

    override fun invalidateSessionScopes(providerId: Long) {
        val prefix = "provider:$providerId|"
        sessionScopes.keys.filter { it.startsWith(prefix) }.forEach { key ->
            sessionScopes.remove(key)
            stalkerHttpClients.keys.removeIf { clientKey -> clientKey.startsWith("$key|") }
            resolvedLoadUrls.keys.removeIf { resolvedKey -> resolvedKey.startsWith("$key|") }
        }
        scopeAliases.keys.filter { it.startsWith(prefix) }.forEach(scopeAliases::remove)
    }

    private suspend fun fetchPagedItems(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        baseQuery: Map<String, String>
    ): List<StalkerItemRecord> {
        val items = mutableListOf<StalkerItemRecord>()
        val zoneId = profile.timezone.toPortalZoneId()
        val fingerprints = mutableSetOf<String>()
        var page = 1
        var advertisedTotalPages: Int? = null
        var reachedEnd = false

        while (page <= MAX_PAGE_COUNT) {
            val pagePayload = requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = baseQuery + ("p" to page.toString())
            )
            val pageEntries = pagePayload.extractItemEntries()
            val fingerprint = pageEntries.joinToString("|") { entry ->
                listOf(
                    entry.findString("id").orEmpty(),
                    entry.findString("ch_id").orEmpty(),
                    entry.findString("video_id").orEmpty(),
                    entry.findString("name").orEmpty()
                ).joinToString(":")
            }
            if (page > 1 && fingerprint.isNotEmpty() && !fingerprints.add(fingerprint)) {
                throw StalkerApiError.Malformed(
                    "Portal repeated a catalog page while loading page $page."
                )
            }
            if (fingerprint.isNotEmpty()) fingerprints += fingerprint

            val reportedTotalPages = pagePayload.advertisedTotalPages()
            if (advertisedTotalPages != null && reportedTotalPages != null &&
                advertisedTotalPages != reportedTotalPages
            ) {
                throw StalkerApiError.Malformed(
                    "Portal changed its advertised catalog page count while loading page $page."
                )
            }
            advertisedTotalPages = advertisedTotalPages ?: reportedTotalPages
            if (pageEntries.isEmpty() && reportedTotalPages != null && page < reportedTotalPages) {
                throw StalkerApiError.Malformed(
                    "Portal returned an empty catalog page before its advertised end."
                )
            }

            items += pageEntries.mapNotNull { it.toItemRecord(zoneId) }
            reachedEnd = when {
                pageEntries.isEmpty() -> true
                reportedTotalPages != null -> page >= reportedTotalPages
                else -> false
            }
            if (reachedEnd) break
            page += 1
        }

        if (!reachedEnd) {
            throw StalkerApiError.CatalogTruncated(
                advertisedTotalPages = (advertisedTotalPages ?: page).coerceAtLeast(page),
                pageLimit = MAX_PAGE_COUNT
            )
        }
        return items
    }

    private suspend fun fetchPagedItemPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        baseQuery: Map<String, String>,
        page: Int
    ): StalkerPagedItems {
        // The aggregate safety limit belongs to bulk loads. A single-page request must preserve
        // the requested cursor so a resumed catalog can move past the historical page-200 cap.
        val safePage = page.coerceAtLeast(1)
        val payload = requestJson(
            url = session.loadUrl,
            profile = profile,
            referer = session.portalReferer,
            token = session.token,
            query = baseQuery + ("p" to safePage.toString())
        )
        val items = payload.toItemRecords(profile.timezone.toPortalZoneId())
        val pageSize = payload.pageSize(items.size)
        val advertisedTotalItems = payload.advertisedTotalItems()
        val advertisedTotalPages = payload.advertisedTotalPages()
        if (items.isEmpty() && advertisedTotalPages != null && safePage < advertisedTotalPages) {
            throw StalkerApiError.Malformed(
                "Portal returned an empty catalog page before its advertised end."
            )
        }
        return StalkerPagedItems(
            items = items,
            page = safePage,
            totalPages = payload.totalPages(safePage),
            pageSize = pageSize,
            advertisedTotalItems = advertisedTotalItems,
            advertisedTotalPages = advertisedTotalPages,
            hasAdvertisedTotal = advertisedTotalPages != null
        )
    }

    private suspend fun fetchAllLiveChannels(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): List<StalkerItemRecord>? {
        return runSuspendCatching {
            requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = mapOf(
                    "type" to "itv",
                    "action" to "get_all_channels",
                    "JsHttpRequest" to "1-xml"
                )
            ).toItemRecords(profile.timezone.toPortalZoneId())
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Stalker get_all_channels failed; falling back to paged live catalog", error)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun requestJson(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String? = null,
        allowAlternateEndpointRetry: Boolean = false,
        method: String = "GET",
        body: String? = null
    ): JsonElement = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val requestBody = body?.toRequestBody(FORM_URL_ENCODED_MEDIA_TYPE)
        val action = effectiveQuery["action"]
        val sessionScope = sessionScopeFor(profile)

        suspend fun executeRequest(requestQuery: Map<String, String>): JsonElement {
            val fullUrl = buildUrl(url, requestQuery)
            val requestBuilder = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", profile.userAgent)
                .header("X-User-Agent", profile.xUserAgent)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .header("Cookie", buildCookieHeader(fullUrl, profile))
                .apply {
                    token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                }
                .applyStalkerHeaderOverrides(
                    headerOverrides = profile.headerOverrides,
                    preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
                )
            val request = requestBuilder
                .method(method, requestBody)
                .build()

            return runSuspendCatching {
                executeJsonRequest(request, action, profile)
            }.recoverCatching { error ->
                if (error is CancellationException) throw error
                if (!allowAlternateEndpointRetry) throw error
                val alternateUrl = siblingLoadUrl(url)
                    ?.takeIf { it != url }
                    ?: throw error
                Log.w(
                    TAG,
                    "Retrying Stalker ${action.orEmpty()} via alternate endpoint $alternateUrl after ${error.message}"
                )
                val alternateFullUrl = buildUrl(alternateUrl, requestQuery)
                val alternateRequest = request.newBuilder()
                    .url(alternateFullUrl)
                    .header("Referer", StalkerUrlFactory.portalReferer(alternateUrl))
                    .header("Cookie", buildCookieHeader(alternateFullUrl, profile))
                    .applyStalkerHeaderOverrides(
                        headerOverrides = profile.headerOverrides,
                        preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
                    )
                    .method(method, requestBody)
                    .build()
                executeJsonRequest(alternateRequest, action, profile)
            }.getOrElse { throw it }
        }

        val initialQuery = if (
            sessionScope.macQueryRequired &&
            profile.macAddress.isNotBlank() &&
            !effectiveQuery.containsKey("mac")
        ) {
            effectiveQuery.withMacQuery(profile)
        } else {
            effectiveQuery
        }

        try {
            executeRequest(initialQuery)
        } catch (error: StalkerApiError.EmptyBody) {
            // A few MAG/Ministra deployments reject the normal MAG cookie-only form by
            // returning HTTP 200 with an empty body, while accepting the same request when
            // the MAC is also present as a query parameter. Retry only this unambiguous
            // signal, learn it for the current auth scope, and leave other portal behavior
            // untouched.
            if (
                profile.macAddress.isBlank() ||
                initialQuery.containsKey("mac") ||
                sessionScope.macQueryRequired ||
                !shouldRetryWithMacQuery(action)
            ) {
                throw error
            }
            sessionScope.macQueryRequired = true
            Log.i(
                TAG,
                "Stalker portal requires MAC query authentication; retrying action=${action.orEmpty()}"
            )
            executeRequest(effectiveQuery.withMacQuery(profile))
        }
    }

    private fun Map<String, String>.withMacQuery(profile: StalkerDeviceProfile): Map<String, String> =
        LinkedHashMap(this).apply { put("mac", profile.macAddress) }

    private fun shouldRetryWithMacQuery(action: String?): Boolean =
        action.equals("handshake", ignoreCase = true) ||
            action.equals("get_profile", ignoreCase = true) ||
            action.equals("get_account_info", ignoreCase = true) ||
            action.equals("do_auth", ignoreCase = true) ||
            action.equals("create_link", ignoreCase = true)

    private suspend fun executeJsonRequest(request: Request, action: String?, profile: StalkerDeviceProfile): JsonElement {
        profile.discoveryRuntime.consumeRequest()
        val startedAt = System.currentTimeMillis()
        return withTransportAwareStalkerCall(request, profile) { response ->
            if (!response.isSuccessful) {
                StalkerTelemetry.httpResponse(
                    providerId = profile.providerId,
                    endpointFamily = endpointFamily(request),
                    action = action,
                    durationMillis = System.currentTimeMillis() - startedAt,
                    responseBytes = response.body?.contentLength()?.coerceAtLeast(0L) ?: 0L,
                    status = response.code,
                    outcome = "HTTP_ERROR"
                )
                // A 401/403 served as an HTML page comes from an edge/WAF (e.g. Cloudflare)
                // or a misconfigured endpoint, not from the portal's JSON auth layer; do not
                // classify it as a portal authorization verdict.
                val errorBody = runCatching {
                    response.peekBody(HTML_ERROR_SNIFF_BYTES).string()
                }.getOrDefault("")
                val htmlErrorPage = looksLikeHtml(errorBody)
                response.body?.close()
                throw response.toStalkerHttpError(htmlErrorPage, errorBody)
            }
            val responseBody = response.body
                ?: throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            if (responseBody.contentLength() == 0L) {
                responseBody.close()
                throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            }
            if (action.equals("create_link", ignoreCase = true) && response.isDirectMediaResponse()) {
                Log.i(
                    TAG,
                    "Stalker create_link returned a direct media response; handing the authenticated endpoint to playback " +
                        "type=${response.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "unknown" }}"
                )
                throw DirectCreateLinkResponse(request.url.toString())
            }
            val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            if (action.equals("create_link", ignoreCase = true)) {
                // Some Ministra portals put the small, authoritative `js` result first and
                // then append a very large diagnostic `text` field (stack traces, storage
                // timeout details, and repeated backend output). Parsing the entire document
                // both delays playback resolution and can hide `nothing_to_play` behind the
                // generic response-size guard. Read only the `js` value and close the response;
                // the trailing diagnostic text is not part of the playback contract.
                val parsed = parseCreateLinkEnvelope(responseBody.byteStream(), charset.name(), action)
                parsed.ensureNoPortalError()
                recordResolvedLoadUrl(request, response, profile)
                captureResponseCookies(response, profile)
                StalkerTelemetry.httpResponse(
                    providerId = profile.providerId,
                    endpointFamily = endpointFamily(request),
                    action = action,
                    durationMillis = System.currentTimeMillis() - startedAt,
                    responseBytes = responseBody.contentLength().coerceAtLeast(0L),
                    status = response.code,
                    outcome = "SUCCESS"
                )
                return@withTransportAwareStalkerCall parsed
            }
            val raw = readBodyBounded(
                stream = responseBody.byteStream(),
                charsetName = charset.name(),
                maxBytes = maxBodyBytesForAction(action),
                action = action
            )
            if (raw.isBlank()) {
                throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            }
            val parsed = parsePortalJson(raw, action)
            parsed.ensureNoPortalError()
            recordResolvedLoadUrl(request, response, profile)
            captureResponseCookies(response, profile)
            StalkerTelemetry.httpResponse(
                providerId = profile.providerId,
                endpointFamily = endpointFamily(request),
                action = action,
                durationMillis = System.currentTimeMillis() - startedAt,
                responseBytes = raw.toByteArray(charset).size.toLong(),
                status = response.code,
                outcome = "SUCCESS"
            )
            parsed
        }
    }

    private fun parseCreateLinkEnvelope(
        stream: InputStream,
        charsetName: String,
        action: String?
    ): JsonElement {
        val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }
            .getOrDefault(Charsets.UTF_8)
        val reader = JsonReader(InputStreamReader(stream, charset)).apply { isLenient = true }
        try {
            if (reader.peek() == JsonToken.END_DOCUMENT) {
                throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw StalkerApiError.Malformed(
                    "Portal returned an unreadable playback response${actionSuffix(action)}."
                )
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "js" -> {
                        val jsValue = JsonParser.parseReader(reader)
                        return parsePortalJson("{\"js\":${jsValue}}", action)
                    }
                    "error" -> {
                        val rawError = reader.nextStringOrSkip()
                        rawError?.let(::portalError)?.let { throw it }
                    }
                    "not_valid_token" -> {
                        val marker = reader.nextStringOrSkip()
                        if (marker != null && !isPlaceholderErrorValue(marker)) {
                            throw invalidTokenError()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            throw StalkerApiError.Malformed("Portal did not return a playback result.")
        } catch (error: StalkerApiError) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw StalkerApiError.Malformed(
                "Portal returned an unreadable playback response${actionSuffix(action)}."
            )
        }
    }

    /**
     * Reads [stream] into a String, throwing [IOException] if the body exceeds [maxBytes].
     * This prevents unbounded heap allocation when portals return unexpectedly large responses.
     */
    private fun readBodyBounded(
        stream: InputStream,
        charsetName: String,
        maxBytes: Long,
        action: String?
    ): String {
        val out = ByteArrayOutputStream(16_384)
        var totalRead = 0L
        val chunk = ByteArray(8_192)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            totalRead += n
            if (totalRead > maxBytes) {
                val limitKb = maxBytes / 1024
                throw StalkerApiError.ResponseTooLarge(
                    "Portal response for '${action ?: "request"}' exceeded the ${limitKb}KB limit; " +
                        "portal may be misbehaving."
                )
            }
            out.write(chunk, 0, n)
        }
        return try {
            out.toString(charsetName)
        } catch (_: java.io.UnsupportedEncodingException) {
            out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Returns an appropriate response-body size ceiling for the given Stalker [action].
     * Smaller endpoints (auth, create_link) get tight limits; large catalog endpoints get
     * a generous but still bounded ceiling to prevent OOM from misbehaving portals.
     */
    private fun maxBodyBytesForAction(action: String?): Long = when (action?.trim()) {
        "handshake", "get_profile", "get_account_info" -> 512L * 1024          // 512 KB
        "create_link"                                   -> 64L * 1024           // 64 KB
        "get_genres", "get_vod_categories", "get_series_categories",
        "get_categories", "get_live_categories"         -> 2L * 1024 * 1024    // 2 MB
        "get_ordered_list", "get_vod_list", "get_series",
        "get_seasons", "get_series_info", "get_vod_info" -> 8L * 1024 * 1024  // 8 MB
        else                                             -> 4L * 1024 * 1024   // 4 MB default
    }

    private suspend fun requestStreamingItems(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val action = effectiveQuery["action"]
        val fullUrl = buildUrl(url, effectiveQuery)
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", buildCookieHeader(fullUrl, profile))
            .header("Authorization", "Bearer $token")
            .applyStalkerHeaderOverrides(
                headerOverrides = profile.headerOverrides,
                preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
            )
            .get()
            .build()

        executeStreamingRequest(request, action, profile, onItem)
    }

    private suspend fun executeStreamingRequest(
        request: Request,
        action: String?,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        profile.discoveryRuntime.consumeRequest()
        val startedAt = System.currentTimeMillis()
        return withTransportAwareStalkerCall(request, profile) { response ->
            if (!response.isSuccessful) {
                throw response.toStalkerHttpError()
            }
            val body = response.body ?: throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val reader = JsonReader(InputStreamReader(body.byteStream(), charset))
            reader.isLenient = true
            try {
                streamStalkerItems(reader, profile.timezone.toPortalZoneId(), onItem).also { itemCount ->
                    recordResolvedLoadUrl(request, response, profile)
                    captureResponseCookies(response, profile)
                    StalkerTelemetry.httpResponse(
                        providerId = profile.providerId,
                        endpointFamily = endpointFamily(request),
                        action = action,
                        durationMillis = System.currentTimeMillis() - startedAt,
                        responseBytes = body.contentLength().coerceAtLeast(0L),
                        status = response.code,
                        outcome = if (itemCount > 0) "SUCCESS" else "EMPTY"
                    )
                }
            } catch (error: IllegalStateException) {
                throw IOException("Portal returned unreadable JSON${actionSuffix(action)}.", error)
            }
        }
    }

    private suspend fun streamStalkerItems(
        reader: JsonReader,
        zoneId: ZoneId,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> streamItemArray(reader, zoneId, onItem)
            JsonToken.BEGIN_OBJECT -> streamItemObject(reader, zoneId, onItem)
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private suspend fun streamItemObject(
        reader: JsonReader,
        zoneId: ZoneId,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "error" -> {
                    val error = reader.nextStringOrSkip()
                    error?.let(::portalError)?.let { throw it }
                }
                "not_valid_token" -> {
                    val marker = reader.nextStringOrSkip()
                    if (marker != null && !isPlaceholderErrorValue(marker)) {
                        throw invalidTokenError()
                    }
                }
                "js", "data", "items" -> count += streamStalkerItems(reader, zoneId, onItem)
                else -> {
                    // Object-keyed catalogs (e.g. `{"data":{"100":{...},"200":{...}}}`) use
                    // numeric string keys for each item. Attempt to parse any object value as
                    // an item record before falling back to skip, so these catalogs are not
                    // silently dropped on the streaming path.
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        val element = JsonParser.parseReader(reader)
                        val item = element.asJsonObjectOrNull()?.toItemRecord(zoneId)
                        if (item != null) {
                            onItem(item)
                            count++
                        }
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
        reader.endObject()
        return count
    }

    private suspend fun streamItemArray(
        reader: JsonReader,
        zoneId: ZoneId,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                val element = JsonParser.parseReader(reader)
                val item = element.asJsonObjectOrNull()?.toItemRecord(zoneId)
                if (item != null) {
                    onItem(item)
                    count++
                }
            } else {
                count += streamStalkerItems(reader, zoneId, onItem)
            }
        }
        reader.endArray()
        return count
    }

    /**
     * Issues a streamed Stalker request that emits [StalkerProgramRecord]s instead of building
     * a Gson tree of the entire response. This is the heap-safe path used for `get_epg_info`,
     * which on some portals returns >30 MB of JSON regardless of the requested period.
     */
    private suspend fun requestStreamingPrograms(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        query: Map<String, String>,
        token: String,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val effectiveQuery = prepareQuery(profile, query)
        val action = effectiveQuery["action"]
        val fullUrl = buildUrl(url, effectiveQuery)
        val request = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-User-Agent", profile.xUserAgent)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Cookie", buildCookieHeader(fullUrl, profile))
            .header("Authorization", "Bearer $token")
            .applyStalkerHeaderOverrides(
                headerOverrides = profile.headerOverrides,
                preserveUserAgent = profile.advancedOptions.apiUserAgent.isNotBlank()
            )
            .get()
            .build()

        executeStreamingPrograms(request, action, profile, channelIdOverride, onProgram)
    }

    private suspend fun executeStreamingPrograms(
        request: Request,
        action: String?,
        profile: StalkerDeviceProfile,
        channelIdOverride: String?,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        profile.discoveryRuntime.consumeRequest()
        return withTransportAwareStalkerCall(request, profile) { response ->
            if (!response.isSuccessful) {
                throw response.toStalkerHttpError()
            }
            val body = response.body ?: throw StalkerApiError.EmptyBody("Portal returned an empty response${actionSuffix(action)}.")
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val limited = ByteSizeLimitInputStream(
                delegate = body.byteStream(),
                maxBytes = MAX_EPG_BYTES,
                onOverflow = {
                    "Portal EPG payload exceeded ${MAX_EPG_BYTES} bytes${actionSuffix(action)}."
                }
            )
            val reader = JsonReader(InputStreamReader(limited, charset))
            reader.isLenient = true
            try {
                streamStalkerPrograms(reader, channelIdOverride, profile.timezone.toPortalZoneId(), onProgram).also {
                    recordResolvedLoadUrl(request, response, profile)
                    captureResponseCookies(response, profile)
                }
            } catch (error: IllegalStateException) {
                throw IOException("Portal returned unreadable JSON${actionSuffix(action)}.", error)
            }
        }
    }

    private suspend fun streamStalkerPrograms(
        reader: JsonReader,
        channelIdOverride: String?,
        zoneId: ZoneId,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> streamProgramArray(reader, channelIdOverride, zoneId, onProgram)
            JsonToken.BEGIN_OBJECT -> streamProgramObject(reader, channelIdOverride, zoneId, onProgram)
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> {
                reader.skipValue()
                0
            }
        }
    }

    private suspend fun streamProgramObject(
        reader: JsonReader,
        channelIdOverride: String?,
        zoneId: ZoneId,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginObject()
        var sawEnvelope = false
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "error" -> {
                    val error = reader.nextStringOrSkip()
                    error?.let(::portalError)?.let { throw it }
                }
                "not_valid_token" -> {
                    val marker = reader.nextStringOrSkip()
                    if (marker != null && !isPlaceholderErrorValue(marker)) {
                        throw invalidTokenError()
                    }
                }
                "js", "data", "items" -> {
                    sawEnvelope = true
                    count += streamStalkerPrograms(reader, channelIdOverride, zoneId, onProgram)
                }
                else -> {
                    // Some portals return the bulk EPG as an object whose keys are channel IDs
                    // mapped to arrays of program objects. When we have not already descended
                    // into an envelope key and there is no caller-supplied override, treat the
                    // key as the channel ID and walk the array.
                    if (!sawEnvelope && channelIdOverride == null && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        count += streamProgramArray(reader, name, zoneId, onProgram)
                    } else {
                        reader.skipValue()
                    }
                }
            }
        }
        reader.endObject()
        return count
    }

    private suspend fun streamProgramArray(
        reader: JsonReader,
        channelIdOverride: String?,
        zoneId: ZoneId,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                val element = JsonParser.parseReader(reader)
                val program = element.asJsonObjectOrNull()?.toProgramRecord(channelIdOverride, zoneId)
                if (program != null) {
                    onProgram(program)
                    count++
                }
            } else {
                count += streamStalkerPrograms(reader, channelIdOverride, zoneId, onProgram)
            }
        }
        reader.endArray()
        return count
    }

    private fun GsonJsonObject.toProgramRecord(channelIdOverride: String?, zoneId: ZoneId): StalkerProgramRecord? {
        val resolvedChannelId = channelIdOverride
            ?: findString("ch_id")
            ?: findString("channel_id")
            ?: findString("id_channel")
            ?: findString("xmltv_id")
            ?: findString("epg_id")
            ?: return null
        val startMillis = findString("start_timestamp")?.toLongOrNull()?.times(1000L)
            ?: findString("time")?.let { parseDateTime(it, zoneId) }
            ?: return null
        val endMillis = findString("stop_timestamp")?.toLongOrNull()?.times(1000L)
            ?: findString("time_to")?.let { parseDateTime(it, zoneId) }
            ?: (startMillis + (findString("duration")?.toLongOrNull()?.times(60_000L) ?: DEFAULT_PROGRAM_DURATION_MILLIS))
        val title = findString("name") ?: findString("title") ?: return null
        return StalkerProgramRecord(
            id = findString("id") ?: "$resolvedChannelId:$startMillis",
            channelId = resolvedChannelId,
            title = title,
            description = findString("descr") ?: findString("description") ?: "",
            startTimeMillis = startMillis,
            endTimeMillis = endMillis,
            hasArchive = findBoolean("has_archive") == true || findString("has_archive") == "1",
            isNowPlaying = findBoolean("now_playing") == true || findString("now_playing") == "1"
        )
    }

    private fun siblingLoadUrl(url: String): String? {
        val normalized = StalkerUrlFactory.normalizePortalUrl(url)
        val lower = normalized.lowercase(Locale.ROOT)
        return when {
            lower.endsWith("/server/load.php") -> normalized.removeSuffix("/server/load.php") + "/portal.php"
            lower.endsWith("/portal.php") -> normalized.removeSuffix("/portal.php") + "/server/load.php"
            else -> null
        }
    }

    private fun parsePortalJson(body: String, action: String?): JsonElement {
        val normalized = sanitizePortalResponseBody(body)
        runCatching { json.parseToJsonElement(normalized) }
            .getOrNull()
            ?.let { return it }

        if (looksLikeHtml(normalized)) {
            val lower = normalized.lowercase(Locale.ROOT)
            if (lower.contains("access denied") || lower.contains("forbidden")) {
                throw StalkerApiError.Authorization(
                    message = "Portal denied the request${actionSuffix(action)}.",
                    portalReason = "access denied"
                )
            }
        }

        authorizationMessage(normalized)?.let { throw it }

        extractEmbeddedJson(normalized)?.let { candidate ->
            runCatching { json.parseToJsonElement(candidate) }
                .getOrNull()
                ?.let { return it }
        }

        throw runCatching { json.parseToJsonElement(normalized) }
            .fold(
                onSuccess = { StalkerApiError.Malformed("Portal returned unreadable JSON${actionSuffix(action)}.") },
                onFailure = { error -> StalkerApiError.Malformed("Portal returned unreadable JSON${actionSuffix(action)}.", error) }
            )
    }

    private fun JsonElement.ensureNoPortalError() {
        if (findBoolean("not_valid_token") == true) {
            throw invalidTokenError()
        }
        // Some Ministra/Stalker families return workflow failures as a scalar `js`
        // payload instead of the usual `{ "js": { "error": ... } }` envelope.
        // Treat that scalar as a portal outcome so create_link cannot degrade
        // `nothing_to_play` into the misleading "no playable URL" error.
        rootObjectOrNull()
            ?.get("js")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.let { portalError(it)?.let { error -> throw error } }
        val raw = rootObjectOrNull()?.findString("error")
            ?: findString("error")
        raw?.let { portalError(it)?.let { error -> throw error } }

        statusEnvelopeError()?.let { throw it }

        val message = rootObjectOrNull()?.findString("msg")
            ?: findString("msg")
        message?.let { authorizationMessage(it)?.let { error -> throw error } }
    }

    /**
     * Ministra reports workflow failures as HTTP 200 with a `status`/`msg`/`block_msg`
     * envelope instead of an HTTP error or an `error` field. Well-known examples:
     * `status:1` + "Device conflict - device_id mismatch" (MAC locked to another device
     * identity), `status:1` + "Time out of sync" (device clock outside tolerance) and
     * `status:2` + "Authentication request" (device not registered; launcher auth required).
     * These must never be treated as accepted payloads.
     */
    private fun JsonElement.statusEnvelopeError(): StalkerApiError? {
        val payload = payloadObjectOrNull() ?: return null
        val status = payload.findInt("status") ?: return null
        if (status == 0) return null
        val msg = payload.findString("msg")
        val blockMsg = payload.findString("block_msg")?.let(::stripPortalHtml)
        if (msg == null && blockMsg == null) return null
        val detail = listOfNotNull(msg, blockMsg)
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val normalizedMsg = (msg ?: "").lowercase(Locale.ROOT)
        return when {
            status == 2 -> StalkerApiError.DeviceNotRegistered(
                message = "Portal does not recognize this device (registration is required). " +
                    "Portal message: $detail",
                reason = msg
            )
            normalizedMsg.contains("device conflict") ||
                normalizedMsg.contains("device_id mismatch") ||
                normalizedMsg.contains("device id mismatch") ->
                StalkerApiError.DeviceConflict(
                    message = "Portal reports \"$detail\". This MAC is locked to a different " +
                        "device identity; enter the registered serial number, device IDs and " +
                        "signature, or ask your provider to reset the device binding.",
                    reason = msg
                )
            normalizedMsg.contains("time out of sync") ||
                normalizedMsg.contains("not synchronized") ->
                StalkerApiError.ClockSkew(
                    message = "Portal rejected the login because this device's clock is out of " +
                        "sync. Correct the device date/time and retry. Portal message: $detail",
                    reason = msg
                )
            normalizedMsg.contains("not valid mac") || normalizedMsg.contains("invalid mac") ->
                StalkerApiError.InvalidMac(
                    detail.ifBlank { "Portal reported an invalid MAC address." },
                    msg
                )
            normalizedMsg.contains("not_valid_token") ||
                normalizedMsg.contains("invalid token") ||
                normalizedMsg.contains("empty token") ->
                StalkerApiError.TokenRejected(detail, msg)
            normalizedMsg.contains("blocked") ||
                normalizedMsg.contains("disabled") ||
                normalizedMsg.contains("banned") ||
                normalizedMsg.contains("not allowed") ->
                StalkerApiError.AccountBlocked(detail, msg)
            else -> StalkerApiError.Authorization(
                message = "Portal rejected the device profile: $detail",
                portalReason = msg
            )
        }
    }

    private fun stripPortalHtml(raw: String): String {
        val withBreaks = raw.replace(Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE), " ")
        val withoutTags = withBreaks.replace(Regex("<[^>]*>"), " ")
        val decoded = withoutTags
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        return decoded.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * A handshake/profile HTTP 200 is not acceptance. Add-provider discovery requires the
     * minimum workflow the app actually needs: live genres, a non-empty channel page, and either
     * an already resolved stream URL or a successful create_link response.
     */
    private suspend fun validateCatalogAcceptance(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): List<String> {
        profile.onProgress?.invoke("Validating Live TV categories")
        val categoriesPayload = runSuspendCatching {
            requestJson(
                url = session.loadUrl,
                profile = profile,
                referer = session.portalReferer,
                token = session.token,
                query = mapOf(
                    "type" to "itv",
                    "action" to "get_genres",
                    "JsHttpRequest" to "1-xml"
                )
            ).also { payload ->
                payload.ensureNoPortalError()
                if (!payload.isRecognizedCatalogPayload()) {
                    throw StalkerApiError.Malformed(
                        "Portal returned a malformed live category wrapper."
                    )
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            throwCatalogValidationFailure(error)
        }
        val categories = categoriesPayload.toCategoryRecords()
        if (categories.isEmpty()) {
            throw IOException("Portal accepted the profile but returned no live channel categories.")
        }

        profile.onProgress?.invoke("Validating Live TV channel list")
        var lastPageFailure: Throwable? = null
        var channels = emptyList<StalkerItemRecord>()
        val categoryStrategies = buildList<String?> {
            add(null) // Bulk list without a category filter.
            add("*") // Common Ministra wildcard accepted by some classic portals.
            addAll(
                sampleLiveDiscoveryCategories(categories, MAX_DISCOVERY_CATEGORY_PROBES)
                    .map(StalkerCategoryRecord::id)
            )
        }.distinct()
        for (categoryId in categoryStrategies) {
            val pagePayload = runSuspendCatching {
                val query = linkedMapOf(
                    "type" to "itv",
                    "action" to "get_ordered_list",
                    "p" to "1",
                    "JsHttpRequest" to "1-xml"
                )
                categoryId?.let { query["genre"] = it }
                requestJson(
                    url = session.loadUrl,
                    profile = profile,
                    referer = session.portalReferer,
                    token = session.token,
                    query = query
                ).also { payload ->
                    payload.ensureNoPortalError()
                    if (!payload.isRecognizedCatalogPayload()) {
                        throw StalkerApiError.Malformed(
                            "Portal returned a malformed live channel page."
                        )
                    }
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (error.isStalkerAuthorizationFailure()) throwCatalogValidationFailure(error)
                lastPageFailure = error
                continue
            }
            channels = pagePayload.toItemRecords(profile.timezone.toPortalZoneId())
            if (channels.isNotEmpty()) break
        }
        if (channels.isEmpty()) {
            throw IOException(
                "Portal accepted the profile but returned no live channels in the validation pages.",
                lastPageFailure
            )
        }

        val playable = channels.firstOrNull { channel ->
            channel.cmd?.isNotBlank() == true ||
                channel.streamUrl?.isNotBlank() == true ||
                channel.commandVariants.any { it.cmd.isNotBlank() }
        } ?: throw IOException("Portal returned live channels without a playback command.")
        val command = playable.cmd
            ?.takeIf(String::isNotBlank)
            ?: playable.commandVariants.firstOrNull { it.cmd.isNotBlank() }?.cmd
            ?: playable.streamUrl.orEmpty()

        profile.onProgress?.invoke("Validating Live TV playback link")
        val playbackEvidence = if (command.isResolvedPlaybackUrl()) {
            "playback:live:direct"
        } else {
            when (
                val result = createLink(
                    session = session,
                    profile = profile,
                    kind = StalkerStreamKind.LIVE,
                    cmd = command
                )
            ) {
                is Result.Success -> "playback:live:create_link"
                is Result.Error -> {
                    val cause = result.exception ?: IOException(result.message)
                    if (cause.isStalkerAuthorizationFailure()) throwCatalogValidationFailure(cause)
                    throw IOException("Portal returned channels but could not resolve a live playback link.", cause)
                }
                is Result.Loading -> throw IOException("Unexpected loading state while validating playback.")
            }
        }

        return listOf(
            "catalog:itv:categories_valid",
            "catalog:itv:page_valid",
            playbackEvidence
        )
    }

    private fun throwCatalogValidationFailure(cause: Throwable): Nothing {
        if (cause.isStalkerAuthorizationFailure()) {
            throw StalkerApiError.PartialAuthorization(
                cause = cause,
                reason = (cause as? StalkerApiError)?.portalReason
            )
        }
        throw cause
    }

    private fun String.isResolvedPlaybackUrl(): Boolean {
        val candidate = trim().substringAfter(' ', missingDelimiterValue = trim()).trim()
        if (!StreamEntryUrlPolicy.isAllowed(candidate)) return false
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host in setOf("", "localhost", "127.0.0.1", "::1")) return false
        return !uri.path.orEmpty().contains("/ch/")
    }

    private fun authorizationMessage(raw: String): StalkerApiError.Authorization? {
        val normalized = raw.trim().lowercase(Locale.ROOT)
        val isAuthorizationFailure = listOf(
            "authorization failed",
            "unauthorized",
            "not valid mac",
            "invalid mac",
            "not_valid_token",
            "invalid token",
            "empty token",
            "access denied",
            "permission denied"
        ).any(normalized::contains)
        return if (isAuthorizationFailure) {
            val message = raw.trim().ifBlank { "Portal authorization failed." }
            val reason = raw.trim().takeIf(String::isNotBlank)
            when {
                normalized.contains("mac") -> StalkerApiError.InvalidMac(message, reason)
                normalized.contains("token") -> StalkerApiError.TokenRejected(message, reason)
                else -> StalkerApiError.Authorization(message = message, portalReason = reason)
            }
        } else {
            null
        }
    }

    private fun invalidTokenError(): StalkerApiError.Authorization =
        StalkerApiError.TokenRejected(
            message = "Portal token is invalid.",
            reason = "not_valid_token"
        )

    private fun portalError(raw: String): StalkerApiError? {
        if (raw.isBlank() || isPlaceholderErrorValue(raw)) return null
        val normalized = raw.lowercase(Locale.ROOT)
        return when {
            normalized == "nothing_to_play" || normalized.contains("nothing to play") ->
                StalkerApiError.ContentUnavailable(portalReason = "nothing_to_play")
            listOf("not valid mac", "invalid mac").any(normalized::contains) ->
                StalkerApiError.InvalidMac(raw, raw)
            listOf("not_valid_token", "invalid token", "empty token").any(normalized::contains) ->
                StalkerApiError.TokenRejected(raw, raw)
            listOf("unsupported model", "invalid model", "device rejected", "stb type").any(normalized::contains) ->
                StalkerApiError.ModelRejected(raw, raw)
            listOf("access denied", "authorization", "unauthorized").any(normalized::contains) ->
                StalkerApiError.Authorization(message = raw, portalReason = raw)
            listOf("blocked", "disabled", "banned", "not allowed").any(normalized::contains) ->
                StalkerApiError.AccountBlocked(message = raw, portalReason = raw)
            else -> StalkerApiError.Server(message = raw, portalReason = raw)
        }
    }

    /**
     * Returns `true` for portal error strings that are not real errors.
     * Common examples: `"null"`, `"0"`, `"false"`, `"ok"`, empty strings.
     */
    private fun isPlaceholderErrorValue(value: String): Boolean {
        return when (value.lowercase(Locale.ROOT).trim()) {
            "null", "0", "false", "ok", "" -> true
            else -> false
        }
    }

    private suspend inline fun <T> runApiCall(
        message: String,
        crossinline block: suspend () -> T
    ): Result<T> {
        var attempt = 0
        while (true) {
            try {
                return Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val typed = when (error) {
                    is StalkerApiError -> error
                    is IOException -> StalkerApiError.Transport(error.message ?: message, error)
                    else -> error
                }
                attempt += 1
                // A 429 opens the provider-wide circuit breaker. Retrying inside the
                // individual API operation would defeat that cooldown.
                val retryable = typed is StalkerApiError.Transport ||
                    (typed is StalkerApiError.Server && (typed.httpStatus ?: 500) >= 500)
                if (!retryable || attempt >= MAX_OPERATION_ATTEMPTS) {
                    return Result.error(typed.message ?: message, typed)
                }
                val exponential = 250L shl (attempt - 1)
                val jitter = kotlin.random.Random.nextLong(100L, 401L)
                delay((exponential + jitter).coerceIn(100L, MAX_RETRY_DELAY_MILLIS))
            }
        }
    }

    private fun Throwable.isTerminalStalkerDiscoveryFailure(): Boolean =
        this is StalkerApiError.AccountBlocked ||
            this is StalkerApiError.InvalidMac ||
            this is StalkerApiError.DeviceConflict ||
            this is StalkerApiError.DeviceNotRegistered ||
            this is StalkerApiError.ClockSkew ||
            this is StalkerApiError.RateLimited ||
            this is StalkerApiError.TransportConsentRequired ||
            this is StalkerApiError.DiscoveryBudgetExceeded

    private fun Throwable.isInconclusiveLiveReadinessFailure(): Boolean {
        val chain = generateSequence<Throwable>(this) { it.cause }.toList()
        if (chain.any {
                it is StalkerApiError.Authorization ||
                    it is StalkerApiError.AccountBlocked ||
                    it is StalkerApiError.ModelRejected ||
                    it is StalkerApiError.BlockedOrConfiguration ||
                    it is StalkerApiError.TransportConsentRequired ||
                    it is StalkerApiError.RateLimited
            }
        ) {
            return false
        }
        val normalizedMessage = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val definitiveEmptyEvidence = listOf(
            "returned no live channel categories",
            "returned no live channels",
            "live channels without a playback command"
        ).any(normalizedMessage::contains)
        return !definitiveEmptyEvidence && chain.any {
            it is IOException ||
                it is StalkerApiError.Server ||
                it is StalkerApiError.Malformed ||
                it is StalkerApiError.ResponseTooLarge ||
                it is StalkerApiError.DiscoveryBudgetExceeded
        }
    }

    private fun Throwable.isDefinitiveLiveReadinessFailure(): Boolean {
        val normalizedMessage = generateSequence<Throwable>(this) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return listOf(
            "returned no live channel categories",
            "returned no live channels",
            "live channels without a playback command"
        ).any(normalizedMessage::contains)
    }

    private fun Throwable.liveReadinessEvidenceCode(): String {
        val chain = generateSequence<Throwable>(this) { it.cause }.toList()
        return when {
            chain.any { it is StalkerApiError.DiscoveryBudgetExceeded } -> "LIVE_BUDGET_EXHAUSTED"
            chain.any { it is StalkerApiError.ResponseTooLarge } -> "LIVE_RESPONSE_TOO_LARGE"
            chain.any { it is StalkerApiError.Malformed } -> "LIVE_MALFORMED_RESPONSE"
            chain.any { it is StalkerApiError.Server } -> "LIVE_SERVER_ERROR"
            chain.any { it is StalkerApiError.Transport } -> "LIVE_TRANSPORT_ERROR"
            else -> "LIVE_INCONCLUSIVE"
        }
    }

    private fun StalkerApiError.PartialAuthorization.requiresFreshSessionRetry(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { cause ->
                cause is StalkerApiError.TokenRejected ||
                    cause.message.orEmpty().contains("token", ignoreCase = true)
            }

    private fun preferredAuthenticationFailure(current: Throwable?, candidate: Throwable): Throwable =
        if (current == null || authenticationFailureRank(candidate) > authenticationFailureRank(current)) {
            candidate
        } else {
            current
        }

    private fun authenticationFailureRank(error: Throwable): Int = when (error) {
        is StalkerApiError.DeviceConflict -> 115
        is StalkerApiError.DeviceNotRegistered -> 110
        is StalkerApiError.ClockSkew -> 105
        is StalkerApiError.PartialAuthorization -> 100
        is StalkerApiError.AccountBlocked -> 95
        is StalkerApiError.RateLimited -> 90
        is StalkerApiError.TokenRejected -> 85
        is StalkerApiError.InvalidMac -> 80
        is StalkerApiError.Authorization -> 75
        is StalkerApiError.ModelRejected -> 60
        is StalkerApiError.Server -> 50
        is StalkerApiError.Transport -> 40
        is StalkerApiError.Malformed -> 30
        else -> 20
    }

    private fun authenticationFailureOutcome(error: Throwable): String = when (error) {
        is StalkerApiError.DeviceConflict -> "DEVICE_CONFLICT"
        is StalkerApiError.DeviceNotRegistered -> "DEVICE_NOT_REGISTERED"
        is StalkerApiError.ClockSkew -> "CLOCK_SKEW"
        is StalkerApiError.PartialAuthorization -> "PARTIAL_AUTHORIZATION"
        is StalkerApiError.AccountBlocked -> "ACCOUNT_BLOCKED"
        is StalkerApiError.RateLimited -> "RATE_LIMITED"
        is StalkerApiError.TokenRejected -> "TOKEN_REJECTED"
        is StalkerApiError.InvalidMac -> "INVALID_MAC"
        is StalkerApiError.Authorization -> "AUTHORIZATION_REJECTED"
        is StalkerApiError.ModelRejected -> "MODEL_REJECTED"
        is StalkerApiError.Server -> "SERVER_ERROR"
        is StalkerApiError.Transport -> "TRANSPORT_ERROR"
        is StalkerApiError.EmptyBody -> "EMPTY_BODY"
        is StalkerApiError.TransportConsentRequired -> "TRANSPORT_CONSENT"
        is StalkerApiError.Malformed -> "MALFORMED_RESPONSE"
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS_ERROR"
        is IOException -> "IO_ERROR"
        else -> "FAILED"
    }

    private fun endpointFamily(request: Request): String = when {
        request.url.encodedPath.endsWith("/portal.php", ignoreCase = true) -> "PORTAL_PHP"
        request.url.encodedPath.endsWith("/server/load.php", ignoreCase = true) -> "SERVER_LOAD"
        else -> "OTHER"
    }

    private fun buildUrl(baseUrl: String, query: Map<String, String>): String {
        val encodedQuery = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "${baseUrl.trimEnd('/')}?$encodedQuery"
    }

    private fun prepareQuery(
        profile: StalkerDeviceProfile,
        query: Map<String, String>
    ): LinkedHashMap<String, String> {
        val action = query["action"].orEmpty()
        val matchingRule = profile.advancedOptions.requestRules
            .firstOrNull { it.action.trim() == action }
        if (matchingRule?.blockRequest == true) {
            throw IOException("Stalker request '$action' was blocked by advanced settings.")
        }
        val prepared = LinkedHashMap<String, String>()
        query.forEach { (key, value) -> prepared[key] = value }
        matchingRule?.paramOverrides.orEmpty().forEach { override ->
            val name = override.name.trim()
            if (name.isBlank()) return@forEach
            val value = override.value
            if (value.isBlank()) {
                prepared.remove(name)
            } else {
                prepared[name] = value
            }
        }
        if (action == "get_profile" && prepared.containsKey("JsHttpRequest")) {
            val jsValue = prepared.remove("JsHttpRequest")
            if (jsValue != null) {
                prepared["JsHttpRequest"] = jsValue
            }
        }
        return prepared
    }

    private suspend fun <T> withTransportAwareStalkerCall(
        request: Request,
        profile: StalkerDeviceProfile,
        block: suspend (Response) -> T
    ): T {
        val requestPriority = when {
            request.url.queryParameter("action").equals("create_link", ignoreCase = true) ->
                StalkerNetworkPriority.INTERACTIVE
            currentCoroutineContext()[StalkerRequestPriorityContext]?.priority in setOf(
                com.streamvault.domain.model.StalkerRequestPriority.EPG,
                com.streamvault.domain.model.StalkerRequestPriority.BACKGROUND_INDEX
            ) -> StalkerNetworkPriority.BACKGROUND
            currentCoroutineContext()[StalkerRequestPriorityContext]?.priority ==
                com.streamvault.domain.model.StalkerRequestPriority.VISIBLE_PREVIEW ->
                StalkerNetworkPriority.PREFETCH
            request.url.queryParameter("action").equals("get_epg_info", ignoreCase = true) ->
                StalkerNetworkPriority.BACKGROUND
            else -> StalkerNetworkPriority.FOREGROUND
        }
        val permit = requestCoordinator.acquireNetworkPermit(profile.providerId, requestPriority)
        var nonRateLimitedResponseObserved = false
        return try {
            val baseClient = stalkerHttpClientFor(profile, request.url.toString())
            val remainingMillis = profile.discoveryRuntime.remainingMillis()
            val client = if (remainingMillis == Long.MAX_VALUE) {
                baseClient
            } else {
                baseClient.newBuilder()
                    .callTimeout(remainingMillis, TimeUnit.MILLISECONDS)
                    .build()
            }
            withCancellableStalkerCall(client.newCall(request)) { response ->
                if (response.code != 429) {
                    nonRateLimitedResponseObserved = true
                    requestCoordinator.recordNetworkSuccess(permit)
                }
                block(response)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!nonRateLimitedResponseObserved) {
                requestCoordinator.recordNetworkFailure(permit, error)
            }
            if (error is StalkerApiError.TransportConsentRequired) throw error
            transportFactory.challengeForTlsFailure(request.url.toString(), error)?.let { challenge ->
                throw StalkerApiError.TransportConsentRequired(challenge)
            }
            throw error
        } finally {
            requestCoordinator.releaseNetworkPermit(permit)
        }
    }

    private fun Response.isDirectMediaResponse(): Boolean {
        val prefix = runCatching { peekBody(CREATE_LINK_SNIFF_BYTES).bytes() }.getOrDefault(byteArrayOf())
        val firstNonWhitespace = prefix.firstOrNull { byte ->
            byte.toInt().toChar() !in setOf(' ', '\t', '\r', '\n')
        }?.toInt()?.and(0xff)
        // Incorrect application/octet-stream headers are common. JSON, XML, and HTML
        // envelopes must still go through the semantic parser.
        if (firstNonWhitespace in setOf('{'.code, '['.code, '<'.code)) return false

        val mime = header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime in setOf(
                "application/octet-stream",
                "application/vnd.apple.mpegurl",
                "application/x-mpegurl"
            )
        ) return true

        fun startsWith(vararg expected: Int): Boolean = expected.indices.all { index ->
            prefix.getOrNull(index)?.toInt()?.and(0xff) == expected[index]
        }
        val mpegProgramStream = startsWith(0x00, 0x00, 0x01, 0xba)
        val matroska = startsWith(0x1a, 0x45, 0xdf, 0xa3)
        val mp4 = prefix.size >= 8 && prefix.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
        val transportStream = prefix.firstOrNull()?.toInt()?.and(0xff) == 0x47 &&
            (prefix.getOrNull(188)?.toInt()?.and(0xff) == 0x47 || prefix.size < 189)
        return mpegProgramStream || matroska || mp4 || transportStream
    }

    private fun stalkerHttpClientFor(profile: StalkerDeviceProfile, url: String): OkHttpClient {
        val proxy = profile.advancedOptions.proxy
        val scopeKey = sessionScopeKey(profile)
        val grantKey = profile.transportGrant?.let { grant ->
            "${grant.mode}:${grant.origin.authority}:${grant.spkiSha256.orEmpty()}"
        } ?: "AUTO_STRICT"
        val key = "$scopeKey|$grantKey|${proxy?.let { "${it.host}:${it.port}" } ?: "<direct>"}"
        val transportClient = transportFactory.clientFor(url, profile.transportGrant)
        return stalkerHttpClients.computeIfAbsent(key) {
            transportClient.newBuilder()
                // Stalker requests need one deterministic Cookie header containing both
                // durable MAG identity cookies and server-issued affinity/session cookies.
                // OkHttp's automatic cookie bridge would replace that merged header.
                .cookieJar(CookieJar.NO_COOKIES)
                .apply {
                    if (proxy != null) {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
                    }
                }
                .build()
        }
    }

    private fun buildProfileQuery(
        profile: StalkerDeviceProfile,
        handshakeRandom: String
    ): Map<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val preset = stalkerMagPresetSpec(profile.magPreset)
        val metrics = buildMetricsJson(profile, handshakeRandom)
        val hwVersion = profile.advancedOptions.hwVersion.trim().ifBlank { preset.hwVersion }
        return linkedMapOf(
            "type" to "stb",
            "action" to "get_profile",
            "hd" to "1",
            "ver" to preset.versionString,
            "sn" to profile.serialNumber,
            "stb_type" to profile.deviceProfile,
            "client_type" to "STB",
            "image_version" to preset.imageVersion,
            "video_out" to "hdmi",
            "device_id" to profile.deviceId,
            "device_id2" to profile.deviceId2,
            "signature" to profile.signature,
            "auth_second_step" to if (profile.authMode.requiresCredentials()) "1" else "0",
            "hw_version" to hwVersion,
            "not_valid_token" to "0",
            "metrics" to metrics,
            // Real MAG hardware computes this with gSTB.GetHashVersion1(metrics, random).
            // An empty value is truthful; substituting the hardware version is not a valid hash.
            "hw_version_2" to "",
            "timestamp" to timestamp,
            "api_signature" to preset.apiSignature,
            "prehash" to "false",
            "num_banks" to "2",
            "player_version" to preset.imageVersion,
            "stb_lang" to profile.locale.ifBlank { preset.localization.substringBefore('.') },
            "locale" to preset.localization,
            "JsHttpRequest" to "1-xml"
        )
    }

    private fun buildCookieHeader(url: String, profile: StalkerDeviceProfile): String {
        val cookies = linkedMapOf<String, String>()
        profile.macAddress.takeIf { it.isNotBlank() }?.let { cookies["mac"] = encode(it) }
        profile.locale.takeIf { it.isNotBlank() }?.let { cookies["stb_lang"] = encode(it) }
        profile.timezone.takeIf { it.isNotBlank() }?.let { cookies["timezone"] = encode(it) }
        cookieJarFor(profile).cookieHeaderFor(url).split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                val value = part.substringAfter('=', missingDelimiterValue = "").trim()
                key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
        }.forEach { (key, value) ->
            cookies.putIfAbsent(key, value)
        }
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private suspend fun requestCredentialAuth(
        url: String,
        profile: StalkerDeviceProfile,
        referer: String,
        token: String,
        allowAlternateEndpointRetry: Boolean = false
    ): JsonElement {
        val formBody = listOf(
            "login" to profile.username,
            "password" to profile.password
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val query = mapOf(
            "type" to "stb",
            "action" to "do_auth",
            "JsHttpRequest" to "1-xml"
        )
        return runSuspendCatching {
            requestJson(
                url = url,
                profile = profile,
                referer = referer,
                token = token,
                allowAlternateEndpointRetry = allowAlternateEndpointRetry,
                query = query,
                method = "POST",
                body = formBody
            )
        }.getOrElse {
            requestJson(
                url = url,
                profile = profile,
                referer = referer,
                token = token,
                allowAlternateEndpointRetry = allowAlternateEndpointRetry,
                query = query + mapOf(
                    "login" to profile.username,
                    "password" to profile.password
                )
            )
        }
    }

    private fun buildMetricsJson(
        profile: StalkerDeviceProfile,
        handshakeRandom: String
    ): String = JsonObject(
        linkedMapOf(
            "mac" to JsonPrimitive(profile.macAddress),
            "sn" to JsonPrimitive(profile.serialNumber),
            "model" to JsonPrimitive(profile.deviceProfile),
            "type" to JsonPrimitive("STB"),
            "uid" to JsonPrimitive(profile.deviceId2),
            "random" to JsonPrimitive(handshakeRandom)
        )
    ).toString()

    private fun captureResponseCookies(response: Response, profile: StalkerDeviceProfile) {
        val cookies = Cookie.parseAll(response.request.url, response.headers)
        if (cookies.isNotEmpty()) {
            cookieJarFor(profile).saveFromResponse(response.request.url, cookies)
        }
    }

    private fun recordResolvedLoadUrl(request: Request, response: Response, profile: StalkerDeviceProfile) {
        val requested = request.url.newBuilder().query(null).fragment(null).build().toString()
        val resolved = response.request.url.newBuilder().query(null).fragment(null).build().toString()
        if (requested == resolved) return
        resolvedLoadUrls[resolvedLoadUrlKey(requested, profile)] = resolved
        if (resolvedLoadUrls.size > MAX_RESOLVED_LOAD_URLS) {
            resolvedLoadUrls.keys.firstOrNull()?.let(resolvedLoadUrls::remove)
        }
    }

    private fun resolvedLoadUrl(loadUrl: String, profile: StalkerDeviceProfile): String? =
        resolvedLoadUrls[resolvedLoadUrlKey(loadUrl, profile)]

    private fun resolvedLoadUrlKey(loadUrl: String, profile: StalkerDeviceProfile): String =
        "${sessionScopeKey(profile)}|${StalkerUrlFactory.normalizePortalUrl(loadUrl)}"

    private fun Response.toStalkerHttpError(
        htmlErrorPage: Boolean = false,
        bodySnippet: String = ""
    ): StalkerApiError {
        val retryAfterMillis = header("Retry-After")
            ?.trim()
            ?.let { raw ->
                raw.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?.times(1000L)
                    ?: runCatching {
                        (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant()
                            .toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
                    }.getOrNull()
            }
        return when (code) {
            401 -> StalkerApiError.SessionExpired(
                message = "Portal session expired with HTTP 401.",
                httpStatus = 401,
                reason = "http_401"
            )
            403 -> if (htmlErrorPage || !bodySnippet.isSessionAuthorizationBody()) {
                StalkerApiError.BlockedOrConfiguration(
                    message = "Portal request was blocked with HTTP $code.",
                    portalReason = if (htmlErrorPage) "html_error_page" else "http_403_forbidden"
                )
            } else {
                StalkerApiError.SessionExpired(
                    message = "Portal session expired with HTTP 403.",
                    httpStatus = code
                )
            }
            429 -> StalkerApiError.RateLimited(
                httpStatus = code,
                retryAfterMillis = retryAfterMillis
            )
            in 500..599 -> StalkerApiError.Server(
                message = "Portal request failed with HTTP $code.",
                httpStatus = code
            )
            else -> StalkerApiError.BlockedOrConfiguration(
                message = "Portal request failed with HTTP $code."
            )
        }
    }

    private fun String.isSessionAuthorizationBody(): Boolean {
        val normalized = lowercase(Locale.ROOT)
        return listOf(
            "not_valid_token",
            "invalid token",
            "token expired",
            "session expired",
            "authorization failed",
            "unauthorized"
        ).any(normalized::contains)
    }

    private fun sessionScopeFor(profile: StalkerDeviceProfile): SessionScope {
        val now = System.currentTimeMillis()
        val key = if (profile.authEpoch > 0L) {
            sessionScopeKey(profile)
        } else {
            scopeAliases[sessionScopeAliasKey(profile)] ?: sessionScopeKey(profile)
        }
        val scope = sessionScopes.computeIfAbsent(key) { SessionScope(lastAccessAt = now) }
        scope.lastAccessAt = now
        if (sessionScopes.size > MAX_SESSION_SCOPES) {
            val excess = sessionScopes.size - MAX_SESSION_SCOPES
            sessionScopes.entries
                .filter { it.key != key }
                .sortedWith(
                    compareBy<Map.Entry<String, SessionScope>>(
                        { now - it.value.lastAccessAt <= SESSION_SCOPE_IDLE_MILLIS },
                        { it.value.lastAccessAt }
                    )
                )
                .take(excess)
                .forEach { (staleKey, _) ->
                    sessionScopes.remove(staleKey)
                    stalkerHttpClients.keys.removeIf { clientKey -> clientKey.startsWith("$staleKey|") }
                }
        }
        return scope
    }

    private fun cookieJarFor(profile: StalkerDeviceProfile): InMemoryStalkerCookieJar =
        sessionScopeFor(profile).cookieJar

    private fun sessionScopeKey(profile: StalkerDeviceProfile): String {
        val normalized = listOf(
            StalkerUrlFactory.normalizePortalUrl(profile.portalUrl),
            profile.macAddress.trim().uppercase(Locale.ROOT),
            profile.username.trim(),
            profile.password,
            profile.deviceProfile.trim(),
            profile.deviceId.trim(),
            profile.deviceId2.trim(),
            profile.serialNumber.trim(),
            profile.compatibilityProfileId,
            profile.advancedOptions.proxy?.let { "${it.host}:${it.port}" }.orEmpty()
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "provider:${profile.providerId}|epoch:${profile.authEpoch}|$digest"
    }

    private fun sessionScopeAliasKey(profile: StalkerDeviceProfile): String {
        val normalized = listOf(
            profile.providerId,
            StalkerUrlFactory.normalizePortalUrl(profile.portalUrl),
            profile.macAddress.trim().uppercase(Locale.ROOT),
            profile.username.trim(),
            profile.password,
            profile.deviceProfile.trim(),
            profile.deviceId.trim(),
            profile.deviceId2.trim(),
            profile.serialNumber.trim(),
            profile.compatibilityProfileId,
            profile.advancedOptions.proxy?.let { "${it.host}:${it.port}" }.orEmpty()
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "provider:${profile.providerId}|alias|$digest"
    }

    private fun hashDiscoveryHeaderPolicy(profile: StalkerDeviceProfile): String {
        val policy = buildString {
            append(profile.httpUserAgent)
            append('\u001f')
            append(profile.httpHeaders)
            append('\u001f')
            profile.headerOverrides.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (name, value) ->
                    append(name.lowercase(Locale.ROOT))
                    append('=')
                    append(value.orEmpty())
                    append('\u001e')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(policy.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun JsonElement.totalPages(fallbackPage: Int = 1): Int {
        return (advertisedTotalPages() ?: fallbackPage).coerceAtLeast(1)
    }

    private fun JsonElement.advertisedTotalPages(): Int? {
        val totalItems = advertisedTotalItems() ?: return null
        val fallbackSize = payloadObjectOrNull()?.get("data")?.jsonArrayOrNull()?.size ?: 0
        val pageSize = pageSize(fallbackSize).takeIf { it > 0 } ?: return null
        return ((totalItems + pageSize - 1) / pageSize).coerceAtLeast(1)
    }

    private fun JsonElement.advertisedTotalItems(): Int? = payloadObjectOrNull()
        ?.get("total_items")
        ?.primitiveContentOrNull()
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

    private fun JsonElement.pageSize(fallback: Int): Int {
        val payload = payloadObjectOrNull() ?: return fallback
        return payload["max_page_items"]?.primitiveContentOrNull()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: payload["data"]?.jsonArrayOrNull()?.size?.takeIf { it > 0 }
            ?: fallback
    }

    private fun JsonElement.toProviderProfile(timezone: String): StalkerProviderProfile {
        val payload = payloadObjectOrNull()
        return StalkerProviderProfile(
            accountId = payload?.findString("id"),
            accountName = payload?.findString("name")
                ?: payload?.findString("account")
                ?: payload?.findString("login"),
            maxConnections = payload?.findString("max_online")
                ?.toIntOrNull()
                ?: payload?.findString("max_connections")?.toIntOrNull(),
            expirationDate = payload?.findString("expire_billing_date")
                ?.let { parseExpirationDate(it, timezone.toPortalZoneId()) }
                ?: payload?.findString("end_date")?.let { parseExpirationDate(it, timezone.toPortalZoneId()) },
            statusLabel = payload?.findString("status"),
            authAccess = payload?.findBoolean("auth_access"),
            reportedStbType = payload?.findString("stb_type")
                ?: payload?.findString("device_model")
                ?: payload?.findString("model")
        )
    }

    private fun StalkerProviderProfile.merge(other: StalkerProviderProfile): StalkerProviderProfile =
        copy(
            accountId = other.accountId ?: accountId,
            accountName = other.accountName ?: accountName,
            maxConnections = other.maxConnections ?: maxConnections,
            expirationDate = other.expirationDate ?: expirationDate,
            statusLabel = other.statusLabel ?: statusLabel,
            authAccess = other.authAccess ?: authAccess,
            reportedStbType = other.reportedStbType ?: reportedStbType,
            moduleNames = if (other.moduleNames.isNotEmpty()) other.moduleNames else moduleNames,
            bootstrapStrategy = if (other.bootstrapStrategy != StalkerBootstrapStrategy.AUTO) {
                other.bootstrapStrategy
            } else {
                bootstrapStrategy
            },
            effectiveAuthMode = if (other.effectiveAuthMode != StalkerAuthMode.AUTO) {
                other.effectiveAuthMode
            } else {
                effectiveAuthMode
            },
            portalProfile = if (other.portalProfile != StalkerPortalProfile.MAG_BASIC) {
                other.portalProfile
            } else {
                portalProfile
            },
            portalCapabilities = portalCapabilities.copy(
                bootstrapStrategy = other.portalCapabilities.bootstrapStrategy
                    .takeUnless { it == StalkerBootstrapStrategy.AUTO }
                    ?: portalCapabilities.bootstrapStrategy,
                useHttpTemporaryLink = portalCapabilities.useHttpTemporaryLink || other.portalCapabilities.useHttpTemporaryLink,
                nginxSecureLink = portalCapabilities.nginxSecureLink || other.portalCapabilities.nginxSecureLink,
                flussonicTemporaryLink = portalCapabilities.flussonicTemporaryLink || other.portalCapabilities.flussonicTemporaryLink,
                wowzaTemporaryLink = portalCapabilities.wowzaTemporaryLink || other.portalCapabilities.wowzaTemporaryLink,
                useLoadBalancing = portalCapabilities.useLoadBalancing || other.portalCapabilities.useLoadBalancing,
                allowLocalTimeshift = portalCapabilities.allowLocalTimeshift || other.portalCapabilities.allowLocalTimeshift,
                allowLocalPvr = portalCapabilities.allowLocalPvr || other.portalCapabilities.allowLocalPvr,
                allowRemotePvr = portalCapabilities.allowRemotePvr || other.portalCapabilities.allowRemotePvr,
                archiveAvailable = portalCapabilities.archiveAvailable || other.portalCapabilities.archiveAvailable,
                moduleRestricted = portalCapabilities.moduleRestricted || other.portalCapabilities.moduleRestricted,
                ambiguousAccountState = portalCapabilities.ambiguousAccountState || other.portalCapabilities.ambiguousAccountState
            ),
            credentialRequired = credentialRequired || other.credentialRequired,
            macRequired = macRequired && other.macRequired,
            bootstrapEvidence = (bootstrapEvidence + other.bootstrapEvidence).distinct(),
            ambiguousState = ambiguousState || other.ambiguousState
        )

    private fun StalkerProviderProfile.shouldRequestAccountInfo(): Boolean =
        authAccess == false || isAmbiguousAccountState()

    private fun StalkerProviderProfile.shouldRequestModules(): Boolean =
        authAccess == false || isAmbiguousAccountState()

    private fun StalkerProviderProfile.isAmbiguousAccountState(): Boolean {
        val normalizedStatus = statusLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normalizedAccountId = accountId?.trim().orEmpty()
        val normalizedAccountName = accountName?.trim().orEmpty()
        return normalizedStatus == "0" ||
            authAccess == false ||
            normalizedAccountId.isBlank() ||
            normalizedAccountId == "0" ||
            normalizedAccountName.isBlank() ||
            normalizedAccountName == "0"
    }

    private fun JsonElement.toModuleNames(): List<String> {
        val payload = payloadObjectOrNull()
        val modulesValue = payload?.get("modules")
            ?: rootObjectOrNull()?.get("js")
            ?: return emptyList()
        return when (modulesValue) {
            is JsonArray -> modulesValue.mapNotNull { element ->
                element.primitiveContentOrNull()
                    ?: element.jsonObjectOrNull()?.findString("id")
                    ?: element.jsonObjectOrNull()?.findString("name")
            }
            is JsonObject -> modulesValue.keys.toList()
            is JsonPrimitive -> listOfNotNull(modulesValue.contentOrNull?.trim()?.takeIf { it.isNotBlank() })
            else -> emptyList()
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun JsonElement.toCategoryRecords(): List<StalkerCategoryRecord> {
        return extractListElements().mapNotNull { entry ->
            val id = entry.findString("id")
                ?: entry.findString("genre_id")
                ?: entry.findString("category_id")
                ?: return@mapNotNull null
            val name = entry.findString("title")
                ?: entry.findString("name")
                ?: return@mapNotNull null
            StalkerCategoryRecord(
                id = id,
                name = name,
                alias = entry.findString("alias"),
                advertisedItemCount = entry.findString("count")?.toIntOrNull()
                    ?: entry.findString("items")?.toIntOrNull()
                    ?: entry.findString("total")?.toIntOrNull()
            )
        }
    }

    private fun JsonElement.toItemRecords(zoneId: ZoneId): List<StalkerItemRecord> =
        extractItemEntries().mapNotNull { entry -> entry.toItemRecord(zoneId) }

    private fun JsonElement.extractItemEntries(): List<JsonObject> =
        extractListElements().mapNotNull { it.jsonObjectOrNull() }

    private fun JsonObject.toItemRecord(zoneId: ZoneId): StalkerItemRecord? {
        val id = findString("id")
            ?: findString("ch_id")
            ?: findString("video_id")
            ?: findString("series_id")
            ?: return null
        val name = findString("name")
            ?: findString("title")
            ?: return null
        val capabilities = StalkerPortalCapabilities(
            useHttpTemporaryLink = findBoolean("use_http_tmp_link") == true || findString("use_http_tmp_link") == "1",
            nginxSecureLink = findBoolean("nginx_secure_link") == true || findString("nginx_secure_link") == "1",
            flussonicTemporaryLink = findBoolean("flussonic_tmp_link") == true || findString("flussonic_tmp_link") == "1",
            wowzaTemporaryLink = findBoolean("wowza_tmp_link") == true || findString("wowza_tmp_link") == "1",
            useLoadBalancing = findBoolean("use_load_balancing") == true || findString("use_load_balancing") == "1",
            allowLocalTimeshift = findBoolean("allow_local_timeshift") == true || findString("allow_local_timeshift") == "1",
            allowLocalPvr = findBoolean("allow_local_pvr") == true || findString("allow_local_pvr") == "1",
            allowRemotePvr = findBoolean("allow_remote_pvr") == true || findString("allow_remote_pvr") == "1",
            archiveAvailable = findBoolean("archive") == true || findString("archive") == "1"
        )
        val alternateCommands = extractAlternateCommands()
        val descriptor = buildStalkerPlaybackDescriptor(
            primaryCmd = findString("cmd"),
            alternateCommands = alternateCommands,
            capabilities = capabilities
        )
        return StalkerItemRecord(
            id = id,
            name = name,
            categoryId = findString("tv_genre_id")
                ?: findString("category_id")
                ?: findString("genre_id"),
            categoryName = findString("category_name"),
            number = findString("number")?.toIntOrNull() ?: 0,
            logoUrl = sanitizeUrl(findString("logo"))
                ?: sanitizeUrl(findString("screenshot_uri"))
                ?: sanitizeUrl(findString("cover")),
            epgChannelId = findString("xmltv_id") ?: findString("epg_id"),
            cmd = findString("cmd"),
            streamUrl = sanitizeUrl(findString("cmd")),
            playbackDescriptor = descriptor,
            commandVariants = descriptor?.candidates.orEmpty(),
            portalCapabilities = capabilities,
            mcCmd = findString("mc_cmd"),
            useHttpTemporaryLink = capabilities.useHttpTemporaryLink,
            nginxSecureLink = capabilities.nginxSecureLink,
            flussonicTemporaryLink = capabilities.flussonicTemporaryLink,
            wowzaTemporaryLink = capabilities.wowzaTemporaryLink,
            useLoadBalancing = capabilities.useLoadBalancing,
            allowLocalTimeshift = capabilities.allowLocalTimeshift,
            allowLocalPvr = capabilities.allowLocalPvr,
            allowRemotePvr = capabilities.allowRemotePvr,
            archiveAvailable = capabilities.archiveAvailable,
            plot = findString("description") ?: findString("plot"),
            cast = findString("censored")?.takeIf { false } ?: findString("actors"),
            director = findString("director"),
            genre = findString("genres_str") ?: findString("genre"),
            releaseDate = findString("year")
                ?.takeIf { it.length == 4 }
                ?: findString("released") ?: findString("added"),
            rating = findString("rating_imdb")?.toFloatOrNull()
                ?: findString("rating")?.toFloatOrNull()
                ?: 0f,
            tmdbId = findString("tmdb_id")?.toLongOrNull(),
            youtubeTrailer = findString("trailer_url"),
            backdropUrl = sanitizeUrl(findString("backdrop_path")),
            containerExtension = extractContainerExtension(
                findString("cmd"),
                findString("container_extension")
            ),
            addedAt = parseDateTime(findString("added"), zoneId) ?: 0L,
            isAdult = findBoolean("censored") == true,
            isSeries = findBoolean("is_series") == true || findString("is_series") == "1",
            hasSeriesMarker = findString("is_series")?.trim()?.lowercase() in
                setOf("0", "1", "true", "false")
        )
    }

    private fun GsonJsonObject.toItemRecord(zoneId: ZoneId): StalkerItemRecord? {
        val id = findString("id")
            ?: findString("ch_id")
            ?: findString("video_id")
            ?: findString("series_id")
            ?: return null
        val name = findString("name")
            ?: findString("title")
            ?: return null
        val capabilities = StalkerPortalCapabilities(
            useHttpTemporaryLink = findBoolean("use_http_tmp_link") == true || findString("use_http_tmp_link") == "1",
            nginxSecureLink = findBoolean("nginx_secure_link") == true || findString("nginx_secure_link") == "1",
            flussonicTemporaryLink = findBoolean("flussonic_tmp_link") == true || findString("flussonic_tmp_link") == "1",
            wowzaTemporaryLink = findBoolean("wowza_tmp_link") == true || findString("wowza_tmp_link") == "1",
            useLoadBalancing = findBoolean("use_load_balancing") == true || findString("use_load_balancing") == "1",
            allowLocalTimeshift = findBoolean("allow_local_timeshift") == true || findString("allow_local_timeshift") == "1",
            allowLocalPvr = findBoolean("allow_local_pvr") == true || findString("allow_local_pvr") == "1",
            allowRemotePvr = findBoolean("allow_remote_pvr") == true || findString("allow_remote_pvr") == "1",
            archiveAvailable = findBoolean("archive") == true || findString("archive") == "1"
        )
        val alternateCommands = extractAlternateCommands()
        val descriptor = buildStalkerPlaybackDescriptor(
            primaryCmd = findString("cmd"),
            alternateCommands = alternateCommands,
            capabilities = capabilities
        )
        return StalkerItemRecord(
            id = id,
            name = name,
            categoryId = findString("tv_genre_id")
                ?: findString("category_id")
                ?: findString("genre_id"),
            categoryName = findString("category_name"),
            number = findString("number")?.toIntOrNull() ?: 0,
            logoUrl = sanitizeUrl(findString("logo"))
                ?: sanitizeUrl(findString("screenshot_uri"))
                ?: sanitizeUrl(findString("cover")),
            epgChannelId = findString("xmltv_id") ?: findString("epg_id"),
            cmd = findString("cmd"),
            streamUrl = sanitizeUrl(findString("cmd")),
            playbackDescriptor = descriptor,
            commandVariants = descriptor?.candidates.orEmpty(),
            portalCapabilities = capabilities,
            mcCmd = findString("mc_cmd"),
            useHttpTemporaryLink = capabilities.useHttpTemporaryLink,
            nginxSecureLink = capabilities.nginxSecureLink,
            flussonicTemporaryLink = capabilities.flussonicTemporaryLink,
            wowzaTemporaryLink = capabilities.wowzaTemporaryLink,
            useLoadBalancing = capabilities.useLoadBalancing,
            allowLocalTimeshift = capabilities.allowLocalTimeshift,
            allowLocalPvr = capabilities.allowLocalPvr,
            allowRemotePvr = capabilities.allowRemotePvr,
            archiveAvailable = capabilities.archiveAvailable,
            plot = findString("description") ?: findString("plot"),
            cast = findString("actors"),
            director = findString("director"),
            genre = findString("genres_str") ?: findString("genre"),
            releaseDate = findString("year")
                ?.takeIf { it.length == 4 }
                ?: findString("released") ?: findString("added"),
            rating = findString("rating_imdb")?.toFloatOrNull()
                ?: findString("rating")?.toFloatOrNull()
                ?: 0f,
            tmdbId = findString("tmdb_id")?.toLongOrNull(),
            youtubeTrailer = findString("trailer_url"),
            backdropUrl = sanitizeUrl(findString("backdrop_path")),
            containerExtension = extractContainerExtension(
                findString("cmd"),
                findString("container_extension")
            ),
            addedAt = parseDateTime(findString("added"), zoneId) ?: 0L,
            isAdult = findBoolean("censored") == true,
            isSeries = findBoolean("is_series") == true || findString("is_series") == "1",
            hasSeriesMarker = findString("is_series")?.trim()?.lowercase() in
                setOf("0", "1", "true", "false")
        )
    }

    private fun JsonObject.extractAlternateCommands(): List<Pair<String, String>> {
        val directVariants = buildList {
            listOf("cmd_1", "cmd_2", "cmd_3", "mc_cmd").forEach { key ->
                findString(key)?.takeIf { it.isNotBlank() }?.let { add(key to it) }
            }
        }
        val commandArrayVariants = (this["cmds"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val entry = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
            val cmd = entry.findString("url")
                ?: entry.findString("cmd")
                ?: return@mapIndexedNotNull null
            "cmds[$index]" to cmd
        }
        return (directVariants + commandArrayVariants).distinctBy { it.second.trim() }
    }

    private fun GsonJsonObject.extractAlternateCommands(): List<Pair<String, String>> {
        val directVariants = buildList {
            listOf("cmd_1", "cmd_2", "cmd_3", "mc_cmd").forEach { key ->
                findString(key)?.takeIf { it.isNotBlank() }?.let { add(key to it) }
            }
        }
        val commandArrayVariants = getAsJsonArray("cmds")
            ?.mapIndexedNotNull { index, element ->
                val entry = element.asJsonObjectOrNull() ?: return@mapIndexedNotNull null
                val cmd = entry.findString("url")
                    ?: entry.findString("cmd")
                    ?: return@mapIndexedNotNull null
                "cmds[$index]" to cmd
            }
            .orEmpty()
        return (directVariants + commandArrayVariants).distinctBy { it.second.trim() }
    }

    private fun StalkerItemRecord.looksLikeSeasonShell(): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.startsWith("Season ", ignoreCase = true)) {
            return true
        }
        return id.contains(':') && isSeries.not() && cmd.isNullOrBlank().not()
    }

    private fun JsonObject.toSeasonRecord(
        episodeEntries: List<JsonObject>,
        fallbackSeasonNumber: Int? = null
    ): StalkerSeasonRecord {
        val seasonNumber = findString("season_number")?.toIntOrNull()
            ?: findString("season_id")?.toIntOrNull()
            ?: extractSeasonNumberFromCmd(findString("cmd"))
            ?: fallbackSeasonNumber
            ?: 1
        val seasonName = findString("title")
            ?: findString("name")
            ?: "Season $seasonNumber"
        val explicitEpisodes = episodeEntries
            .filterNot { entry -> entry.looksLikeSeasonShellRow() }
            .mapIndexedNotNull { index, entry ->
                entry.toEpisodeRecord(index + 1, seasonNumber)?.copy(seasonNumber = seasonNumber)
            }
        return StalkerSeasonRecord(
            seasonNumber = seasonNumber,
            name = seasonName,
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            cmd = findString("cmd"),
            episodes = explicitEpisodes.takeUnless { it.isEmpty() || it.isSeasonShellOnly() }
                ?: buildEpisodesFromSeriesShell(seasonNumber, seasonName)
        )
    }

    private fun JsonObject.toSeasonShellRecord(fallbackSeasonNumber: Int): StalkerSeasonRecord? {
        val episodeNumbers = extractSeasonShellEpisodeNumbers()
        if (episodeNumbers.isEmpty()) {
            return null
        }
        val seasonNumber = findString("season_id")?.toIntOrNull()
            ?: findString("season_number")?.toIntOrNull()
            ?: extractSeasonNumberFromCmd(findString("cmd"))
            ?: findString("name")?.filter(Char::isDigit)?.toIntOrNull()
            ?: fallbackSeasonNumber
        val seasonName = findString("title")
            ?: findString("name")
            ?: "Season $seasonNumber"
        return StalkerSeasonRecord(
            seasonNumber = seasonNumber,
            name = seasonName,
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            cmd = findString("cmd"),
            episodes = buildEpisodesFromSeriesShell(seasonNumber, seasonName)
        )
    }

    private fun JsonObject.toEpisodeRecord(
        fallbackEpisodeNumber: Int,
        fallbackSeasonNumber: Int
    ): StalkerEpisodeRecord? {
        val id = findString("id")
            ?: findString("series_id")
            ?: findString("video_id")
            ?: return null
        val title = findString("name")
            ?: findString("title")
            ?: "Episode $fallbackEpisodeNumber"
        return StalkerEpisodeRecord(
            id = id,
            title = title,
            episodeNumber = findString("series_number")?.toIntOrNull()
                ?: findString("episode_number")?.toIntOrNull()
                ?: fallbackEpisodeNumber,
            seasonNumber = findString("season_id")?.toIntOrNull()
                ?: findString("season_number")?.toIntOrNull()
                ?: fallbackSeasonNumber,
            cmd = findString("cmd"),
            playbackSelector = findString("series_number")?.toIntOrNull()
                ?: findString("episode_number")?.toIntOrNull(),
            coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
            plot = findString("description") ?: findString("plot"),
            durationSeconds = findString("duration")?.toIntOrNull() ?: 0,
            releaseDate = findString("added"),
            rating = findString("rating_imdb")?.toFloatOrNull()
                ?: findString("rating")?.toFloatOrNull()
                ?: 0f,
            containerExtension = extractContainerExtension(findString("cmd"), findString("container_extension"))
        )
    }

    private fun JsonObject.looksLikeSeasonShellRow(): Boolean {
        val episodeNumber = findString("series_number")?.toIntOrNull()
            ?: findString("episode_number")?.toIntOrNull()
        if (episodeNumber != null) {
            return false
        }
        if (extractSeasonShellEpisodeNumbers().isNotEmpty()) {
            return true
        }
        val title = findString("name") ?: findString("title") ?: ""
        if (title.trim().startsWith("Season ", ignoreCase = true)) {
            return true
        }
        return extractSeasonNumberFromCmd(findString("cmd")) != null
    }

    private fun JsonObject.looksLikeVodSeasonShell(): Boolean {
        if (findBoolean("is_season") == true || findString("is_season") == "1") return true
        if (findString("video_id") != null && findString("series_number") == null) return true
        return looksLikeSeasonShellRow()
    }

    private fun JsonObject.buildEpisodesFromSeriesShell(
        fallbackSeasonNumber: Int,
        fallbackSeasonName: String
    ): List<StalkerEpisodeRecord> {
        val episodeNumbers = extractSeasonShellEpisodeNumbers()
        if (episodeNumbers.isEmpty()) {
            return emptyList()
        }
        val seasonName = fallbackSeasonName.ifBlank { "Season $fallbackSeasonNumber" }
        return episodeNumbers.map { episodeNumber ->
            StalkerEpisodeRecord(
                id = "${findString("id") ?: findString("series_id") ?: "season:$fallbackSeasonNumber"}:$episodeNumber",
                title = "$seasonName Episode $episodeNumber",
                episodeNumber = episodeNumber,
                seasonNumber = fallbackSeasonNumber,
                cmd = findString("cmd"),
                coverUrl = sanitizeUrl(findString("screenshot_uri")) ?: sanitizeUrl(findString("cover")),
                plot = findString("description") ?: findString("plot"),
                durationSeconds = findString("duration")?.toIntOrNull() ?: 0,
                releaseDate = findString("added"),
                rating = findString("rating_imdb")?.toFloatOrNull()
                    ?: findString("rating")?.toFloatOrNull()
                    ?: 0f,
                containerExtension = extractContainerExtension(findString("cmd"), findString("container_extension"))
            )
        }
    }

    private fun JsonObject.extractSeasonShellEpisodeNumbers(): List<Int> {
        val seriesArray = this["series"]?.jsonArrayOrNull() ?: return emptyList()
        return seriesArray.mapIndexedNotNull { index, element ->
            element.primitiveContentOrNull()?.toIntOrNull()
                ?: element.jsonObjectOrNull()?.findString("series_number")?.toIntOrNull()
                ?: element.jsonObjectOrNull()?.findString("episode_number")?.toIntOrNull()
                ?: (index + 1)
        }
    }

    private fun List<StalkerEpisodeRecord>.isSeasonShellOnly(): Boolean {
        if (size != 1) {
            return false
        }
        val entry = first()
        return entry.episodeNumber <= 1 && entry.title.startsWith("Season ", ignoreCase = true)
    }

    private fun extractSeasonNumberFromCmd(cmd: String?): Int? {
        val decoded = decodeBase64JsonPayload(cmd) ?: return null
        return decoded.findString("season_num")?.toIntOrNull()
            ?: decoded.findString("season_id")?.toIntOrNull()
    }

    private fun decodeBase64JsonPayload(cmd: String?): GsonJsonObject? {
        val value = cmd?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            JsonParser.parseString(decoded).asJsonObject
        }.getOrNull()
    }

    private fun JsonElement.toProgramRecords(channelId: String? = null, timezone: String): List<StalkerProgramRecord> {
        val zoneId = timezone.toPortalZoneId()
        return extractListElements().mapNotNull { entry ->
            val resolvedChannelId = channelId ?: entry.findProgramChannelId() ?: return@mapNotNull null
            val startMillis = entry.findString("start_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time")?.let { parseDateTime(it, zoneId) }
                ?: return@mapNotNull null
            val endMillis = entry.findString("stop_timestamp")?.toLongOrNull()?.times(1000L)
                ?: entry.findString("time_to")?.let { parseDateTime(it, zoneId) }
                ?: startMillis + (entry.findString("duration")?.toLongOrNull()?.times(60_000L) ?: DEFAULT_PROGRAM_DURATION_MILLIS)
            StalkerProgramRecord(
                id = entry.findString("id") ?: "$resolvedChannelId:$startMillis",
                channelId = resolvedChannelId,
                title = entry.findString("name")
                    ?: entry.findString("title")
                    ?: return@mapNotNull null,
                description = entry.findString("descr")
                    ?: entry.findString("description")
                    ?: "",
                startTimeMillis = startMillis,
                endTimeMillis = endMillis,
                hasArchive = entry.findBoolean("has_archive") == true || entry.findString("has_archive") == "1",
                isNowPlaying = entry.findBoolean("now_playing") == true || entry.findString("now_playing") == "1"
            )
        }
    }

    private fun JsonElement.findProgramChannelId(): String? {
        val payload = payloadObjectOrNull() ?: return null
        return payload.findString("ch_id")
            ?: payload.findString("channel_id")
            ?: payload.findString("id_channel")
            ?: payload.findString("xmltv_id")
            ?: payload.findString("epg_id")
    }

    private fun JsonElement.extractListElements(): List<JsonElement> {
        val jsValue = rootObjectOrNull()?.get("js") ?: this
        return when {
            jsValue is JsonArray -> jsValue.toList()
            jsValue is JsonObject && jsValue["data"] is JsonArray -> jsValue["data"]!!.jsonArray.toList()
            jsValue is JsonObject && jsValue["items"] is JsonArray -> jsValue["items"]!!.jsonArray.toList()
            // Object-keyed catalogs: {"js":{"data":{"100":{...}}}} — use map values
            jsValue is JsonObject && jsValue["data"] is JsonObject -> jsValue["data"]!!.jsonObject.values.toList()
            jsValue is JsonObject && jsValue["items"] is JsonObject -> jsValue["items"]!!.jsonObject.values.toList()
            else -> emptyList()
        }
    }

    private fun JsonElement.isRecognizedCatalogPayload(): Boolean {
        if (this is JsonArray) return true
        val js = rootObjectOrNull()?.get("js") ?: return false
        if (js is JsonArray) return true
        val wrapper = js.jsonObjectOrNull() ?: return false
        return wrapper["data"] is JsonArray || wrapper["data"] is JsonObject ||
            wrapper["items"] is JsonArray || wrapper["items"] is JsonObject
    }

    private fun JsonElement.findString(key: String): String? {
        val payload = payloadObjectOrNull()
        return payload?.findString(key)
    }

    private fun JsonElement.findBoolean(key: String): Boolean? {
        val payload = payloadObjectOrNull()
        return payload?.findBoolean(key)
    }

    private fun JsonElement.payloadObjectOrNull(): JsonObject? =
        rootObjectOrNull()?.get("js")?.jsonObjectOrNull() ?: rootObjectOrNull()

    private fun JsonElement.rootObjectOrNull(): JsonObject? = when (this) {
        is JsonObject -> this
        else -> null
    }

    private fun JsonObject.findString(key: String): String? {
        val element = this[key] ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JsonObject.findBoolean(key: String): Boolean? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.booleanOrNull
            ?: when (element.contentOrNull?.trim()?.lowercase(Locale.ROOT)) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
    }

    private fun JsonObject.findInt(key: String): Int? {
        val element = this[key] as? JsonPrimitive ?: return null
        return element.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun GsonJsonObject.findString(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asJsonPrimitive
            .takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun GsonJsonObject.findBoolean(key: String): Boolean? {
        val value = findString(key)?.lowercase(Locale.ROOT) ?: return null
        return when (value) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    private fun com.google.gson.JsonElement.asJsonObjectOrNull(): GsonJsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonReader.nextStringOrSkip(): String? {
        return when (peek()) {
            JsonToken.STRING,
            JsonToken.NUMBER,
            JsonToken.BOOLEAN -> nextString()
            JsonToken.NULL -> {
                nextNull()
                null
            }
            else -> {
                skipValue()
                null
            }
        }
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = when (this) {
        is JsonArray -> this
        else -> null
    }

    private fun JsonElement.primitiveContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = (this as? JsonObject)

    private fun sanitizeUrl(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun sanitizePortalResponseBody(body: String): String {
        val withoutBom = body.trimStart('\uFEFF')
        val sanitizedControls = withoutBom.filter { char ->
            char == '\n' || char == '\r' || char == '\t' || char.code >= 0x20
        }
        return sanitizedControls.trim()
    }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true)
    }

    private fun extractEmbeddedJson(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        val callbackStart = trimmed.indexOf('(')
        val callbackEnd = trimmed.lastIndexOf(')')
        if (callbackStart in 1 until callbackEnd) {
            val callbackPayload = trimmed.substring(callbackStart + 1, callbackEnd).trim()
            if (callbackPayload.startsWith('{') || callbackPayload.startsWith('[')) {
                return callbackPayload
            }
        }

        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return trimmed.substring(objectStart, objectEnd + 1)
        }

        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1)
        }

        return null
    }

    private fun actionSuffix(action: String?): String =
        action?.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()

    private fun candidateAuthModes(profile: StalkerDeviceProfile): List<StalkerAuthMode> {
        return when (profile.authMode) {
            StalkerAuthMode.AUTO -> buildList {
                val hasMac = profile.macAddress.isNotBlank()
                val hasCredentials = profile.username.isNotBlank()
                if (hasMac) add(StalkerAuthMode.MAC_ONLY)
                if (hasMac && hasCredentials) add(StalkerAuthMode.MAC_PLUS_CREDENTIALS)
                if (hasCredentials) add(StalkerAuthMode.CREDENTIALS_ONLY)
                if (isEmpty()) add(StalkerAuthMode.MAC_ONLY)
            }
            else -> listOf(profile.authMode)
        }.distinct()
    }

    private fun candidateRecipes(
        profile: StalkerDeviceProfile,
        effectiveAuthMode: StalkerAuthMode
    ): List<StalkerRecipeSpec> {
        val requestedProfile = StalkerCompatibilityRegistry.find(profile.requestedProfileId)
        val manuallySelected = profile.requestedProfileId != StalkerCompatibilityProfileIds.AUTO
        if (manuallySelected && requestedProfile != null) {
            val base = StalkerCompatibilityRegistry.baseFingerprint(requestedProfile)
            val preset = base.preset ?: profile.magPreset
            val captured = fallbackRecipesFor(effectiveAuthMode).firstOrNull {
                it.recipe == base.bootstrapRecipe && it.magPreset == preset
            }
            return listOf(
                (captured ?: defaultRecipeFor(effectiveAuthMode, profile.portalFingerprint, preset)).copy(
                    recipe = base.bootstrapRecipe ?: profile.bootstrapRecipe,
                    magPreset = preset,
                    compatibilityProfileId = requestedProfile.id
                )
            )
        }
        if (!profile.allowCompatibilityDiscovery) {
            val learnedProfile = StalkerCompatibilityRegistry.find(profile.compatibilityProfileId)
                ?: StalkerCompatibilityRegistry.find(
                    StalkerCompatibilityRegistry.idForLegacyPreset(profile.magPreset)
                )
            learnedProfile?.let { learned ->
                val base = StalkerCompatibilityRegistry.baseFingerprint(learned)
                val preset = base.preset ?: profile.magPreset
                val captured = fallbackRecipesFor(effectiveAuthMode).firstOrNull {
                    it.recipe == base.bootstrapRecipe && it.magPreset == preset
                }
                return listOf(
                    (captured ?: defaultRecipeFor(effectiveAuthMode, profile.portalFingerprint, preset)).copy(
                        recipe = base.bootstrapRecipe ?: profile.bootstrapRecipe,
                        magPreset = preset,
                        compatibilityProfileId = learned.id
                    )
                )
            }
            return listOf(
                defaultRecipeFor(effectiveAuthMode, profile.portalFingerprint, profile.magPreset).copy(
                    recipe = profile.bootstrapRecipe,
                    magPreset = profile.magPreset,
                    compatibilityProfileId = profile.compatibilityProfileId
                )
            )
        }
        val defaultRecipe = defaultRecipeFor(
            authMode = effectiveAuthMode,
            fingerprintHint = profile.portalFingerprint,
            presetHint = profile.magPreset
        ).copy(
            recipe = profile.bootstrapRecipe.takeUnless { it == StalkerBootstrapRecipe.GENERIC_SAFE }
                ?: defaultRecipeFor(
                    authMode = effectiveAuthMode,
                    fingerprintHint = profile.portalFingerprint,
                    presetHint = profile.magPreset
                ).recipe,
            magPreset = profile.magPreset.takeUnless { it == StalkerMagPreset.GENERIC_SAFE }
                ?: defaultRecipeFor(
                    authMode = effectiveAuthMode,
                    fingerprintHint = profile.portalFingerprint,
                    presetHint = profile.magPreset
                ).magPreset
        )
        if (effectiveAuthMode != StalkerAuthMode.MAC_ONLY) {
            return (listOf(defaultRecipe) + fallbackRecipesFor(effectiveAuthMode))
                .distinctBy { "${it.recipe}:${it.magPreset}:${it.authMode}" }
                .take(StalkerCompatibilityRegistry.MAX_AUTOMATIC_ATTEMPTS)
        }
        val preferred = when {
            profile.portalFingerprint != StalkerPortalFingerprint.BASIC_MAC ->
                StalkerCompatibilityRegistry.idForLegacyPreset(defaultRecipe.magPreset)
            profile.compatibilityProfileId == StalkerCompatibilityProfileIds.AUTO -> null
            profile.compatibilityProfileId == StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC &&
                profile.magPreset != StalkerMagPreset.GENERIC_SAFE ->
                StalkerCompatibilityRegistry.idForLegacyPreset(profile.magPreset)
            else -> profile.compatibilityProfileId
        }
        return StalkerCompatibilityRegistry.classicAutomaticOrder(preferred).map { candidate ->
            val base = StalkerCompatibilityRegistry.baseFingerprint(candidate)
            val preset = base.preset ?: profile.magPreset
            val capturedRecipe = fallbackRecipesFor(effectiveAuthMode).firstOrNull {
                it.recipe == base.bootstrapRecipe && it.magPreset == preset
            } ?: defaultRecipeFor(effectiveAuthMode, profile.portalFingerprint, preset).copy(
                recipe = base.bootstrapRecipe ?: defaultRecipe.recipe,
                magPreset = preset
            )
            val selectedRecipe = if (candidate.id == preferred &&
                    (profile.portalFingerprint != StalkerPortalFingerprint.BASIC_MAC ||
                        profile.bootstrapRecipe != StalkerBootstrapRecipe.GENERIC_SAFE ||
                        defaultRecipe.recipe != StalkerBootstrapRecipe.GENERIC_SAFE)
                ) {
                    defaultRecipe
                } else {
                    capturedRecipe
                }
            selectedRecipe.copy(
                compatibilityProfileId = candidate.id
            )
        }.ifEmpty { listOf(defaultRecipe) }
            .distinctBy { it.compatibilityProfileId }
            .take(StalkerCompatibilityRegistry.MAX_AUTOMATIC_ATTEMPTS)
    }

    private fun candidateAuthAttempts(
        profile: StalkerDeviceProfile,
        effectiveAuthMode: StalkerAuthMode,
        hintedLoadUrls: List<String> = emptyList()
    ): List<StalkerAuthAttempt> {
        val recipes = candidateRecipes(profile, effectiveAuthMode)
            .take(profile.discoveryBudget.maxIdentityProfiles)
        val defaultRecipe = recipes.firstOrNull() ?: return emptyList()
        if (!profile.allowCompatibilityDiscovery) {
            val loadUrl = orderedLoadUrlCandidates(profile, defaultRecipe, hintedLoadUrls).firstOrNull()
                ?: return emptyList()
            return recipes.mapIndexed { recipeIndex, recipe ->
                StalkerAuthAttempt(recipeIndex, recipe, loadUrl)
            }
        }
        val hasLearnedEndpointPreference = profile.endpointPreference != StalkerEndpointPreference.AUTO ||
            defaultRecipe.endpointPreference != StalkerEndpointPreference.AUTO
        return if (hasLearnedEndpointPreference) {
            recipes.flatMapIndexed { recipeIndex, recipe ->
                orderedLoadUrlCandidates(profile, recipe, hintedLoadUrls)
                    .take(profile.discoveryBudget.maxEndpointCandidates)
                    .map { loadUrl ->
                    StalkerAuthAttempt(recipeIndex, recipe, loadUrl)
                }
            }
        } else {
            orderedLoadUrlCandidates(profile, defaultRecipe, hintedLoadUrls)
                .take(profile.discoveryBudget.maxEndpointCandidates)
                .flatMap { loadUrl ->
                recipes.mapIndexed { recipeIndex, recipe ->
                    StalkerAuthAttempt(recipeIndex, recipe, loadUrl)
                }
            }
        }
    }

    private fun promoteCompatibilityAttempt(
        attempts: ArrayDeque<StalkerAuthAttempt>,
        compatibilityProfileId: String,
        loadUrl: String
    ) {
        val queued = attempts.toList()
        val promoted = queued.firstOrNull { candidate ->
            candidate.recipe.compatibilityProfileId == compatibilityProfileId &&
                candidate.loadUrl == loadUrl
        } ?: return
        attempts.clear()
        attempts.addLast(promoted)
        queued.filterNot { it === promoted }.forEach(attempts::addLast)
    }

    /**
     * Best-effort discovery of the portal's install path. Many portals serve the API from a
     * non-root base (e.g. `/stalker_portal/`); for those, a bare `GET /` typically redirects
     * to `<base>/c/`. Following that redirect once (same-origin only, enforced by the
     * transport layer) reveals the base path and lets discovery try the correct
     * `server/load.php` first instead of burning the request budget on 404/403 probes.
     *
     * Sends no MAC/cookies/identity headers and never throws: any failure simply yields no
     * hint. Runs only for compatibility discovery (add-provider / repair), where endpoint
     * order matters; saved providers reuse their learned endpoint.
     */
    private suspend fun probePortalBaseHintCandidates(profile: StalkerDeviceProfile): List<String> {
        if (!profile.allowCompatibilityDiscovery) return emptyList()
        if (!StalkerUrlFactory.isBarePortalBase(profile.portalUrl)) return emptyList()
        val normalized = StalkerUrlFactory.normalizePortalUrl(profile.portalUrl)
        val baseUrl = "$normalized/"
        return runCatching {
            val remaining = profile.discoveryRuntime.remainingMillis()
            val timeoutMillis = minOf(PORTAL_BASE_HINT_TIMEOUT_MILLIS, remaining)
            if (timeoutMillis <= 0L) return emptyList()
            val client = transportFactory.clientFor(baseUrl, profile.transportGrant)
                .newBuilder()
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(baseUrl)
                .header("User-Agent", profile.userAgent)
                .header("Accept", "*/*")
                .build()
            withCancellableStalkerCall(client.newCall(request)) { response ->
                response.use {
                    val finalUrl = response.request.url
                    val path = finalUrl.encodedPath.trimEnd('/')
                    if (!path.endsWith("/c", ignoreCase = true)) return@withCancellableStalkerCall emptyList()
                    val portalBase = path.dropLast(2)
                    if (portalBase.isNotEmpty() &&
                        (!portalBase.startsWith("/") || portalBase.substringAfterLast('/').isEmpty())
                    ) {
                        return@withCancellableStalkerCall emptyList()
                    }
                    val origin = "${finalUrl.scheme}://${finalUrl.host}" +
                        if (finalUrl.port != defaultPortForScheme(finalUrl.scheme)) ":${finalUrl.port}" else ""
                    listOf(
                        "$origin$portalBase/server/load.php",
                        "$origin$portalBase/portal.php"
                    )
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }.also { hinted ->
            if (hinted.isNotEmpty()) {
                Log.d(TAG, "Stalker portal base hint ${runCatching { URI(baseUrl).host }.getOrNull().orEmpty()} resolved=${hinted.first()}")
            }
        }
    }

    private fun defaultPortForScheme(scheme: String): Int =
        if (scheme.equals("https", ignoreCase = true)) 443 else 80

    private fun orderedLoadUrlCandidates(
        profile: StalkerDeviceProfile,
        recipe: StalkerRecipeSpec,
        hintedLoadUrls: List<String> = emptyList()
    ): List<String> {
        val baseCandidates = (hintedLoadUrls + StalkerUrlFactory.loadUrlCandidates(profile.portalUrl)).distinct()
        when (profile.endpointPreference) {
            StalkerEndpointPreference.PORTAL -> {
                return baseCandidates.filter { candidate ->
                    candidate.lowercase(Locale.ROOT).endsWith("/portal.php")
                }.ifEmpty { baseCandidates }
            }
            StalkerEndpointPreference.SERVER_LOAD -> {
                return baseCandidates.filter { candidate ->
                    candidate.lowercase(Locale.ROOT).endsWith("/server/load.php")
                }.ifEmpty { baseCandidates }
            }
            StalkerEndpointPreference.AUTO -> Unit
        }
        val preferred = recipe.endpointPreference.takeUnless { it == StalkerEndpointPreference.AUTO }
        return when (preferred) {
            StalkerEndpointPreference.PORTAL ->
                baseCandidates.sortedByDescending { candidate -> candidate.lowercase(Locale.ROOT).endsWith("/portal.php") }
            StalkerEndpointPreference.SERVER_LOAD ->
                baseCandidates.sortedByDescending { candidate -> candidate.lowercase(Locale.ROOT).endsWith("/server/load.php") }
            StalkerEndpointPreference.AUTO,
            null -> baseCandidates
        }
    }

    private data class StalkerAuthAttempt(
        val recipeIndex: Int,
        val recipe: StalkerRecipeSpec,
        val loadUrl: String
    )

    private fun endpointPreferenceFor(loadUrl: String): StalkerEndpointPreference =
        when {
            loadUrl.lowercase(Locale.ROOT).endsWith("/portal.php") -> StalkerEndpointPreference.PORTAL
            loadUrl.lowercase(Locale.ROOT).endsWith("/server/load.php") -> StalkerEndpointPreference.SERVER_LOAD
            else -> StalkerEndpointPreference.AUTO
        }

    private fun appendArchiveWindow(
        url: String,
        startSeconds: Long?,
        endSeconds: Long?
    ): String? {
        if (startSeconds == null || startSeconds <= 0L) {
            return url
        }
        val endValue = endSeconds?.takeIf { it > 0L } ?: startSeconds
        val separator = if ('?' in url) '&' else '?'
        val existingUtc = Regex("""([?&])utc=\d+""")
        val existingLutc = Regex("""([?&])lutc=\d+""")
        val withUtc = if (existingUtc.containsMatchIn(url)) {
            existingUtc.replace(url) { match ->
                "${match.groupValues[1]}utc=$startSeconds"
            }
        } else {
            "$url${separator}utc=$startSeconds"
        }
        return if (existingLutc.containsMatchIn(withUtc)) {
            existingLutc.replace(withUtc) { match ->
                "${match.groupValues[1]}lutc=$endValue"
            }
        } else {
            val lutcSeparator = if ('?' in withUtc) '&' else '?'
            "$withUtc${lutcSeparator}lutc=$endValue"
        }
    }

    private fun resolveCookieMode(
        base: StalkerCookieMode,
        serverCookieHeader: String,
        recipe: StalkerRecipeSpec
    ): StalkerCookieMode {
        val hasServerCookies = serverCookieHeader.isNotBlank()
        if (!hasServerCookies) return base
        return when {
            recipe.playbackBackendHint in setOf(
                StalkerPlaybackBackendHint.PLAY_LIVE,
                StalkerPlaybackBackendHint.PLAY_MOVIE,
                StalkerPlaybackBackendHint.TEMP_LINK,
                StalkerPlaybackBackendHint.TEMP_LINK_STRICT
            ) && base == StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            recipe.playbackBackendHint != StalkerPlaybackBackendHint.AUTO -> StalkerCookieMode.PLAYBACK
            else -> StalkerCookieMode.CREATE_LINK
        }
    }

    private fun StalkerAuthMode.requiresCredentials(): Boolean =
        this == StalkerAuthMode.CREDENTIALS_ONLY || this == StalkerAuthMode.MAC_PLUS_CREDENTIALS

    private fun resolvePortalProfile(
        authMode: StalkerAuthMode,
        providerProfile: StalkerProviderProfile,
        requestedProfile: StalkerDeviceProfile
    ): StalkerPortalProfile {
        val hasCredentials = requestedProfile.username.isNotBlank()
        val modulesRestricted = providerProfile.moduleNames.isNotEmpty()
        val strictFingerprint = requestedProfile.serialNumber.isNotBlank() ||
            requestedProfile.deviceId.isNotBlank() ||
            requestedProfile.deviceId2.isNotBlank() ||
            requestedProfile.signature.isNotBlank()
        return when {
            modulesRestricted -> StalkerPortalProfile.MODULE_GATED
            authMode == StalkerAuthMode.MAC_PLUS_CREDENTIALS -> StalkerPortalProfile.AUTH_PLUS_MAG
            authMode == StalkerAuthMode.CREDENTIALS_ONLY -> StalkerPortalProfile.AUTH_REQUIRED
            providerProfile.authAccess == false && hasCredentials -> StalkerPortalProfile.AUTH_PLUS_MAG
            providerProfile.authAccess == false && requestedProfile.macAddress.isBlank() -> StalkerPortalProfile.AUTH_REQUIRED
            providerProfile.portalCapabilities.usesTemporaryLinks && strictFingerprint -> StalkerPortalProfile.MAG_STRICT
            else -> StalkerPortalProfile.MAG_BASIC
        }
    }

    private fun extractContainerExtension(cmd: String?, fallback: String?): String? {
        fallback?.trim()?.removePrefix(".")?.takeIf { it.isNotBlank() }?.let { return it.lowercase(Locale.ROOT) }
        val path = runCatching { URI(cmd).path }.getOrNull() ?: cmd
        val extension = path?.substringAfterLast('.', "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return extension.lowercase(Locale.ROOT)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val TAG = "OkHttpStalkerApi"
        private const val MAX_PAGE_COUNT = 200
        private const val MAX_DISCOVERY_CATEGORY_PROBES = 8
        private const val MAX_RESOLVED_LOAD_URLS = 64
        private const val MAX_SESSION_SCOPES = 16
        private const val MAX_OPERATION_ATTEMPTS = 3
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private const val HTML_ERROR_SNIFF_BYTES = 2_048L
        private const val CREATE_LINK_SNIFF_BYTES = 512L
        private const val PORTAL_BASE_HINT_TIMEOUT_MILLIS = 8_000L
        private const val SESSION_SCOPE_IDLE_MILLIS = 30 * 60_000L
        private const val DEFAULT_PROGRAM_DURATION_MILLIS = 30 * 60_000L
        /** Hard ceiling for the streamed `get_epg_info` body; anything larger is treated as a portal fault. */
        private const val MAX_EPG_BYTES = 64L * 1024L * 1024L
        /** Records retained by the legacy buffered [getBulkEpg]/[getEpg] APIs to keep callers heap-safe. */
        private const val MAX_INLINE_EPG_RECORDS = 1500
        private val FORM_URL_ENCODED_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private const val DEFAULT_VERSION_STRING =
            "ImageDescription: 0.2.18-r19-pub-250; ImageDate: Mon Jun 12 11:04:49 EEST 2017; PORTAL version: 5.6.10; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x23"
    }
}

internal fun <T> sampleDiscoveryCategories(categories: List<T>, limit: Int): List<T> {
    if (limit <= 0 || categories.isEmpty()) return emptyList()
    if (categories.size <= limit) return categories
    if (limit == 1) return listOf(categories.first())
    val lastIndex = categories.lastIndex
    return (0 until limit)
        .map { sampleIndex -> (sampleIndex * lastIndex) / (limit - 1) }
        .distinct()
        .map(categories::get)
}

internal fun sampleLiveDiscoveryCategories(
    categories: List<StalkerCategoryRecord>,
    limit: Int
): List<StalkerCategoryRecord> {
    if (limit <= 0 || categories.isEmpty()) return emptyList()
    if (categories.size <= limit) {
        return categories.sortedWith(
            compareByDescending<StalkerCategoryRecord> { it.advertisedItemCount ?: -1 }
                .thenBy { categories.indexOf(it) }
        )
    }
    val prioritized = categories.withIndex()
        .filter { (_, category) -> (category.advertisedItemCount ?: 0) > 0 }
        .sortedWith(
            compareByDescending<IndexedValue<StalkerCategoryRecord>> {
                it.value.advertisedItemCount ?: 0
            }.thenBy(IndexedValue<StalkerCategoryRecord>::index)
        )
        .map(IndexedValue<StalkerCategoryRecord>::value)
    return buildList {
        prioritized.forEach { category ->
            if (size < limit && category !in this) add(category)
        }
        // Preserve coverage at both ends even when high-confidence categories consume part of
        // the sample budget.
        listOf(categories.first(), categories.last()).forEach { category ->
            if (size < limit && category !in this) add(category)
        }
        sampleDiscoveryCategories(categories, limit).forEach { category ->
            if (size < limit && category !in this) add(category)
        }
        categories.forEach { category ->
            if (size < limit && category !in this) add(category)
        }
    }
}

private fun <T> kotlin.Result<T>.getOrNullPreservingCancellation(): T? = fold(
    onSuccess = { it },
    onFailure = { error ->
        if (error is CancellationException) throw error
        null
    }
)

internal fun buildStalkerDeviceProfile(
    portalUrl: String,
    macAddress: String,
    authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    magPresetHint: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    portalFingerprintHint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    bootstrapRecipeHint: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    endpointPreferenceHint: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    cookieModeHint: StalkerCookieMode = StalkerCookieMode.NONE,
    playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    username: String = "",
    password: String = "",
    deviceProfile: String,
    timezone: String,
    locale: String,
    httpUserAgentOverride: String = "",
    httpHeadersOverride: String = "",
    serialNumberOverride: String = "",
    deviceIdOverride: String = "",
    deviceId2Override: String = "",
    signatureOverride: String = "",
    stalkerAdvancedOptionsJson: String = "",
    protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    transportGrant: StalkerTransportGrant? = null,
    requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    learnedProfileId: String = "",
    requireCatalogValidation: Boolean = false,
    allowCompatibilityDiscovery: Boolean = true,
    discoveryBudget: DiscoveryBudget = DiscoveryBudget(),
    discoveryRuntime: StalkerDiscoveryRuntime = StalkerDiscoveryRuntime(discoveryBudget),
    onProgress: ((String) -> Unit)? = null
): StalkerDeviceProfile {
    val advancedOptions = StalkerAdvancedOptionsCodec.decode(stalkerAdvancedOptionsJson)
    val requestedCompatibilityProfile = StalkerCompatibilityRegistry.find(requestedProfileId)
    val selectedCompatibilityProfile = requestedCompatibilityProfile
        ?: StalkerCompatibilityRegistry.find(learnedProfileId)
    val activeCompatibilityProfile = selectedCompatibilityProfile
        ?: StalkerCompatibilityRegistry.find(StalkerCompatibilityRegistry.idForLegacyPreset(magPresetHint))
    val baseFingerprint = activeCompatibilityProfile?.let(StalkerCompatibilityRegistry::baseFingerprint)
    val effectivePreset = baseFingerprint?.preset ?: magPresetHint
    val preset = stalkerMagPresetSpec(effectivePreset)
    val normalizedInputProfile = deviceProfile.trim()
    val normalizedProfile = when {
        selectedCompatibilityProfile != null && requestedProfileId != StalkerCompatibilityProfileIds.CUSTOM ->
            selectedCompatibilityProfile.model
        normalizedInputProfile.isBlank() -> preset.defaultDeviceProfile
        magPresetHint != StalkerMagPreset.GENERIC_SAFE && normalizedInputProfile.equals("MAG250", ignoreCase = true) ->
            preset.defaultDeviceProfile
        else -> normalizedInputProfile
    }
    val normalizedTimezone = timezone.ifBlank { java.util.TimeZone.getDefault().id }
    val normalizedLocale = locale.ifBlank { Locale.getDefault().language.ifBlank { "en" } }
    val normalizedMac = macAddress.uppercase(Locale.ROOT)
    val normalizedUsername = username.trim()
    val normalizedHttpUserAgent = httpUserAgentOverride.trim()
    val normalizedHttpHeaders = httpHeadersOverride.trim()
    val headerOverrides = parseStalkerHeaderOverrides(normalizedHttpHeaders)
    val effectiveAuthMode = sanitizeStalkerAuthMode(
        requested = authMode,
        normalizedMac = normalizedMac,
        normalizedUsername = normalizedUsername
    )
    val defaultUserAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) $normalizedProfile stbapp ver: 2 rev: ${preset.imageVersion} Safari/533.3"
    val apiUserAgent = advancedOptions.apiUserAgent.trim().ifBlank {
        resolveStalkerUserAgent(
            defaultUserAgent = defaultUserAgent,
            httpUserAgentOverride = normalizedHttpUserAgent,
            headerOverrides = headerOverrides
        ).orEmpty()
    }
    val playerUserAgent = advancedOptions.playerUserAgent.trim()
    return StalkerDeviceProfile(
        portalUrl = portalUrl,
        macAddress = normalizedMac,
        authMode = effectiveAuthMode,
        magPreset = effectivePreset,
        portalFingerprint = portalFingerprintHint,
        bootstrapRecipe = bootstrapRecipeHint,
        endpointPreference = endpointPreferenceHint,
        cookieMode = cookieModeHint,
        playbackBackendHint = playbackBackendHint,
        username = normalizedUsername,
        password = password,
        deviceProfile = normalizedProfile,
        timezone = normalizedTimezone,
        locale = normalizedLocale,
        serialNumber = serialNumberOverride.trim().uppercase(Locale.ROOT),
        deviceId = deviceIdOverride.trim().uppercase(Locale.ROOT),
        deviceId2 = deviceId2Override.trim().uppercase(Locale.ROOT),
        signature = signatureOverride.trim().uppercase(Locale.ROOT),
        userAgent = apiUserAgent,
        playerUserAgent = playerUserAgent,
        xUserAgent = "Model: $normalizedProfile; Link: ${advancedOptions.normalizedLink}",
        httpUserAgent = normalizedHttpUserAgent,
        httpHeaders = normalizedHttpHeaders,
        headerOverrides = headerOverrides,
        advancedOptions = advancedOptions,
        protocolPreference = protocolPreference,
        transportGrant = transportGrant,
        requestedProfileId = requestedProfileId,
        compatibilityProfileId = activeCompatibilityProfile?.id
            ?: StalkerCompatibilityRegistry.idForLegacyPreset(effectivePreset),
        requireCatalogValidation = requireCatalogValidation,
        allowCompatibilityDiscovery = allowCompatibilityDiscovery,
        discoveryBudget = discoveryBudget,
        discoveryRuntime = discoveryRuntime,
        onProgress = onProgress
    )
}

private fun sanitizeStalkerAuthMode(
    requested: StalkerAuthMode,
    normalizedMac: String,
    normalizedUsername: String
): StalkerAuthMode {
    val hasMac = normalizedMac.isNotBlank()
    val hasCredentials = normalizedUsername.isNotBlank()
    return when (requested) {
        StalkerAuthMode.AUTO -> StalkerAuthMode.AUTO
        StalkerAuthMode.MAC_ONLY -> when {
            hasMac -> StalkerAuthMode.MAC_ONLY
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            else -> StalkerAuthMode.AUTO
        }
        StalkerAuthMode.MAC_PLUS_CREDENTIALS -> when {
            hasMac && hasCredentials -> StalkerAuthMode.MAC_PLUS_CREDENTIALS
            hasMac -> StalkerAuthMode.MAC_ONLY
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            else -> StalkerAuthMode.AUTO
        }
        StalkerAuthMode.CREDENTIALS_ONLY -> when {
            hasCredentials -> StalkerAuthMode.CREDENTIALS_ONLY
            hasMac -> StalkerAuthMode.MAC_ONLY
            else -> StalkerAuthMode.AUTO
        }
    }
}

private fun StalkerDeviceProfile.withRecipe(
    recipe: StalkerRecipeSpec,
    effectiveAuthMode: StalkerAuthMode
): StalkerDeviceProfile {
    return buildStalkerDeviceProfile(
        portalUrl = portalUrl,
        macAddress = macAddress,
        authMode = effectiveAuthMode,
        magPresetHint = recipe.magPreset,
        portalFingerprintHint = portalFingerprint,
        bootstrapRecipeHint = recipe.recipe,
        endpointPreferenceHint = recipe.endpointPreference.takeUnless {
            it == StalkerEndpointPreference.AUTO
        } ?: endpointPreference,
        cookieModeHint = recipe.cookieMode.takeUnless {
            it == StalkerCookieMode.NONE
        } ?: cookieMode,
        playbackBackendHint = recipe.playbackBackendHint.takeUnless {
            it == StalkerPlaybackBackendHint.AUTO
        } ?: playbackBackendHint,
        username = username,
        password = password,
        deviceProfile = deviceProfile,
        timezone = timezone,
        locale = locale,
        httpUserAgentOverride = httpUserAgent,
        httpHeadersOverride = httpHeaders,
        serialNumberOverride = serialNumber,
        deviceIdOverride = deviceId,
        deviceId2Override = deviceId2,
        signatureOverride = signature,
        stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(advancedOptions),
        protocolPreference = protocolPreference,
        transportGrant = transportGrant,
        requestedProfileId = recipe.compatibilityProfileId,
        learnedProfileId = recipe.compatibilityProfileId,
        requireCatalogValidation = requireCatalogValidation,
        allowCompatibilityDiscovery = allowCompatibilityDiscovery,
        discoveryBudget = discoveryBudget,
        discoveryRuntime = discoveryRuntime,
        onProgress = onProgress
    ).copy(
        providerId = providerId,
        authEpoch = authEpoch
    )
}

private fun resolveStalkerUserAgent(
    defaultUserAgent: String,
    httpUserAgentOverride: String,
    headerOverrides: Map<String, String?>
): String? {
    val overriddenUserAgent = headerOverrides.entries.firstOrNull { (name, _) ->
        name.equals("User-Agent", ignoreCase = true)
    }?.value
    return overriddenUserAgent ?: httpUserAgentOverride.ifBlank { defaultUserAgent }
}

private fun Request.Builder.applyStalkerHeaderOverrides(
    headerOverrides: Map<String, String?>,
    preserveUserAgent: Boolean = false
): Request.Builder = apply {
    headerOverrides.forEach { (name, value) ->
        if (preserveUserAgent && name.equals("User-Agent", ignoreCase = true)) {
            return@forEach
        }
        if (value == null) {
            removeHeader(name)
        } else {
            header(name, value)
        }
    }
}

internal fun parseExpirationDate(raw: String?, zoneId: ZoneId = ZoneOffset.UTC): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { numeric ->
        return if (numeric >= 1_000_000_000_000L) numeric else numeric * 1000L
    }
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    STALKER_DATE_TIME_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDateTime.parse(value, formatter).atZone(zoneId).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
    }
    STALKER_DATE_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDate.parse(value, formatter).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
    }
    return null
}

private fun parseDateTime(raw: String?, zoneId: ZoneId = ZoneOffset.UTC): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return parseExpirationDate(value, zoneId)
}

private fun String.toPortalZoneId(): ZoneId =
    runCatching { ZoneId.of(trim()) }.getOrDefault(ZoneOffset.UTC)

private val STALKER_DATE_TIME_FORMATTERS: List<DateTimeFormatter> = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy/MM/dd HH:mm:ss",
    "yyyy/MM/dd HH:mm"
).map { pattern ->
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
}

private val STALKER_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("yyyy/MM/dd")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
)


/**
 * InputStream wrapper that throws [IOException] when more than [maxBytes] bytes are read from
 * [delegate]. Used to bound the streamed get_epg_info response so a misbehaving portal
 * cannot push the device into low-memory or OOM territory.
 */
private class ByteSizeLimitInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val onOverflow: () -> String
) : InputStream() {
    private var bytesRead: Long = 0

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            bytesRead += 1
            checkLimit()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            bytesRead += n
            checkLimit()
        }
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()

    private fun checkLimit() {
        if (bytesRead > maxBytes) {
            throw StalkerApiError.ResponseTooLarge(onOverflow())
        }
    }
}

internal suspend fun <T> withCancellableStalkerCall(
    call: Call,
    block: suspend (Response) -> T
): T = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                launch {
                    try {
                        val result = response.use { received -> block(received) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
}

internal class InMemoryStalkerCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = url.topPrivateDomain() ?: url.host
        val now = System.currentTimeMillis()
        store.compute(key) { _, current ->
            val updated = current.orEmpty().filterTo(mutableListOf()) { existing ->
                cookies.none { incoming ->
                    existing.name == incoming.name &&
                        existing.domain == incoming.domain &&
                        existing.path == incoming.path
                } && existing.expiresAt > now
            }
            cookies.forEach { incoming ->
                if (incoming.expiresAt > now) updated += incoming
            }
            updated.takeIf { it.isNotEmpty() }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val key = url.topPrivateDomain() ?: url.host
        val now = System.currentTimeMillis()
        val current = store.computeIfPresent(key) { _, existing ->
            existing.filter { cookie -> cookie.expiresAt > now }.takeIf { it.isNotEmpty() }
        }.orEmpty()
        return current.filter { cookie -> cookie.matches(url) }
    }

    fun cookieHeaderFor(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(httpUrl)
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    fun clear() {
        store.clear()
    }
}
