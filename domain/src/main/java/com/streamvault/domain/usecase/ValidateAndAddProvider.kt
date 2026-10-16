package com.streamvault.domain.usecase

import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.ProviderSavedWithSyncErrorException
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerReadinessInconclusiveException
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportConsentRequiredException
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.ProviderSetupRequest
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import com.streamvault.domain.model.JellyfinConfig
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

data class XtreamProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val xtreamFastSyncEnabled: Boolean = false,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val existingProviderId: Long? = null
)

data class M3uProviderSetupCommand(
    val url: String,
    val name: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val m3uVodClassificationEnabled: Boolean = false,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val existingProviderId: Long? = null
)

data class StalkerProviderSetupCommand(
    val portalUrl: String,
    val macAddress: String,
    val name: String,
    val authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val username: String = "",
    val password: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val deviceProfile: String = "",
    val timezone: String = "",
    val locale: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val transportGrant: StalkerTransportGrant? = null,
    val saveWithoutVerification: Boolean = false,
    val repairConnection: Boolean = false,
    val requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val catalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val existingProviderId: Long? = null
)

data class JellyfinProviderSetupCommand(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val existingProviderId: Long? = null
)

data class JellyfinQuickConnectProviderSetupCommand(
    val serverUrl: String,
    val name: String,
    val existingProviderId: Long? = null
)

sealed class ValidateAndAddProviderResult {
    data class Success(val provider: Provider) : ValidateAndAddProviderResult()
    data class SavedWithWarning(val provider: Provider, val warning: String) : ValidateAndAddProviderResult()
    data class ValidationError(val message: String) : ValidateAndAddProviderResult()
    data class TransportConsentRequired(
        val challenge: StalkerTransportChallenge
    ) : ValidateAndAddProviderResult()
    data class VerificationInconclusive(
        val message: String
    ) : ValidateAndAddProviderResult()
    data class Error(val message: String, val exception: Throwable? = null) : ValidateAndAddProviderResult()
}

class ValidateAndAddProvider @Inject constructor(
    private val providerSetupInputValidator: ProviderSetupInputValidator,
    private val providerRepository: ProviderRepository
) {
    companion object {
        private const val MAX_XTREAM_PLAYLIST_USERNAME_LENGTH = 128
        private const val MAX_XTREAM_PLAYLIST_PASSWORD_LENGTH = 256
        private val STALKER_MAC_ADDRESS = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$", RegexOption.IGNORE_CASE)
        private val STALKER_MAC_IN_PLAYBACK_ROUTE =
            Regex("live\\.php\\?mac=([0-9a-f]{2}(?::[0-9a-f]{2}){5})", RegexOption.IGNORE_CASE)
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [loginXtream].
     */
    fun validateXtreamInput(command: XtreamProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateXtream(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [addM3u].
     */
    fun validateM3uInput(command: M3uProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateM3u(
                url = command.url,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    /**
     * Performs local input validation only — no network calls, no persistence, no side effects.
     * Returns the [ValidateAndAddProviderResult.ValidationError] if the input is invalid, or
     * `null` if the input passed all local checks and is ready for [loginStalker].
     */
    fun validateStalkerInput(command: StalkerProviderSetupCommand): ValidateAndAddProviderResult.ValidationError? {
        return when (
            val result = providerSetupInputValidator.validateStalker(
                portalUrl = command.portalUrl,
                macAddress = command.macAddress,
                name = command.name,
                authMode = command.authMode,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders,
                deviceProfile = command.deviceProfile,
                timezone = command.timezone,
                locale = command.locale,
                serialNumber = command.serialNumber,
                deviceId = command.deviceId,
                deviceId2 = command.deviceId2,
                signature = command.signature,
                stalkerAdvancedOptionsJson = command.stalkerAdvancedOptionsJson
            )
        ) {
            is Result.Error -> ValidateAndAddProviderResult.ValidationError(result.message)
            else -> null
        }
    }

    suspend fun loginXtream(
        command: XtreamProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateXtream(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Success -> providerRepository.setupProvider(
                ProviderSetupRequest.Configured(
                    name = validated.data.name,
                    configuration = XtreamConfig(
                        serverUrl = validated.data.serverUrl,
                        username = validated.data.username,
                        password = validated.data.password,
                        httpUserAgent = validated.data.httpUserAgent,
                        httpHeaders = validated.data.httpHeaders,
                        fastSyncEnabled = command.xtreamFastSyncEnabled,
                        epgSyncMode = command.epgSyncMode,
                        liveSyncMode = command.xtreamLiveSyncMode,
                        guideSourcePolicy = command.guideSourcePolicy,
                        channelLogoSourcePolicy = command.channelLogoSourcePolicy
                    ),
                    existingProviderId = command.existingProviderId
                ),
                onProgress = onProgress
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun addM3u(
        command: M3uProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateM3u(
                url = command.url,
                name = command.name,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders
            )
        ) {
            is Result.Success -> {
                val validatedInput = validated.data
                when (val parsedStalker = parseStalkerPlaylistUrl(validatedInput.url)) {
                    is ParsedStalkerUrlResult.ValidationError ->
                        return ValidateAndAddProviderResult.ValidationError(parsedStalker.message)

                    is ParsedStalkerUrlResult.Success -> return loginStalker(
                        StalkerProviderSetupCommand(
                            portalUrl = parsedStalker.portalUrl,
                            macAddress = parsedStalker.macAddress,
                            name = validatedInput.name,
                            authMode = StalkerAuthMode.AUTO,
                            httpUserAgent = validatedInput.httpUserAgent,
                            httpHeaders = validatedInput.httpHeaders,
                            epgSyncMode = command.epgSyncMode,
                            guideSourcePolicy = command.guideSourcePolicy,
                            channelLogoSourcePolicy = command.channelLogoSourcePolicy,
                            existingProviderId = command.existingProviderId
                        ),
                        onProgress = onProgress
                    )

                    ParsedStalkerUrlResult.NotStalkerUrl -> Unit
                }
                when (val parsedXtream = parseXtreamPlaylistUrl(validatedInput.url)) {
                    is ParsedXtreamPlaylistUrlResult.ValidationError ->
                        ValidateAndAddProviderResult.ValidationError(parsedXtream.message)

                    is ParsedXtreamPlaylistUrlResult.Success -> {
                        providerRepository.setupProvider(
                            ProviderSetupRequest.Configured(
                                name = validatedInput.name,
                                configuration = XtreamConfig(
                                    serverUrl = parsedXtream.serverUrl,
                                    username = parsedXtream.username,
                                    password = parsedXtream.password,
                                    httpUserAgent = validatedInput.httpUserAgent,
                                    httpHeaders = validatedInput.httpHeaders,
                                    fastSyncEnabled = false,
                                    epgSyncMode = command.epgSyncMode,
                                    liveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                                    guideSourcePolicy = command.guideSourcePolicy,
                                    channelLogoSourcePolicy = command.channelLogoSourcePolicy,
                                    preferredLiveStreamFormat = parsedXtream.preferredLiveStreamFormat
                                ),
                                existingProviderId = command.existingProviderId
                            ),
                            onProgress = onProgress
                        ).toUseCaseResult()
                    }

                    ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist -> {
                        providerRepository.setupProvider(
                            ProviderSetupRequest.Configured(
                                name = validatedInput.name,
                                configuration = M3uConfig(
                                    playlistUrl = validatedInput.url,
                                    httpUserAgent = validatedInput.httpUserAgent,
                                    httpHeaders = validatedInput.httpHeaders,
                                    epgSyncMode = command.epgSyncMode,
                                    vodClassificationEnabled = command.m3uVodClassificationEnabled,
                                    guideSourcePolicy = command.guideSourcePolicy,
                                    channelLogoSourcePolicy = command.channelLogoSourcePolicy
                                ),
                                existingProviderId = command.existingProviderId
                            ),
                            onProgress = onProgress
                        ).toUseCaseResult()
                    }
                }
            }

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginStalker(
        command: StalkerProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateStalker(
                portalUrl = command.portalUrl,
                macAddress = command.macAddress,
                name = command.name,
                authMode = command.authMode,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                httpUserAgent = command.httpUserAgent,
                httpHeaders = command.httpHeaders,
                deviceProfile = command.deviceProfile,
                timezone = command.timezone,
                locale = command.locale,
                serialNumber = command.serialNumber,
                deviceId = command.deviceId,
                deviceId2 = command.deviceId2,
                signature = command.signature,
                stalkerAdvancedOptionsJson = command.stalkerAdvancedOptionsJson
            )
        ) {
            is Result.Success -> providerRepository.setupProvider(
                ProviderSetupRequest.Configured(
                    name = validated.data.name,
                    configuration = StalkerConfig(
                        portalUrl = validated.data.portalUrl,
                        device = StalkerDeviceIdentity(
                            macAddress = validated.data.macAddress,
                            deviceProfile = validated.data.deviceProfile,
                            timezone = validated.data.timezone,
                            locale = validated.data.locale,
                            serialNumber = validated.data.serialNumber,
                            deviceId = validated.data.deviceId,
                            deviceId2 = validated.data.deviceId2,
                            signature = validated.data.signature
                        ),
                        authMode = validated.data.authMode,
                        username = validated.data.username,
                        password = validated.data.password,
                        httpUserAgent = validated.data.httpUserAgent,
                        httpHeaders = validated.data.httpHeaders,
                        advancedOptionsJson = validated.data.stalkerAdvancedOptionsJson,
                        protocolPreference = command.protocolPreference,
                        transportGrant = command.transportGrant,
                        requestedProfileId = command.requestedProfileId,
                        epgSyncMode = command.epgSyncMode,
                        catalogMode = command.catalogMode,
                        guideSourcePolicy = command.guideSourcePolicy,
                        channelLogoSourcePolicy = command.channelLogoSourcePolicy
                    ),
                    existingProviderId = command.existingProviderId,
                    saveWithoutVerification = command.saveWithoutVerification,
                    repairConnection = command.repairConnection
                ),
                onProgress = onProgress
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginJellyfin(
        command: JellyfinProviderSetupCommand,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateJellyfin(
                serverUrl = command.serverUrl,
                username = command.username,
                password = command.password,
                allowBlankPassword = command.existingProviderId != null,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.setupProvider(
                ProviderSetupRequest.Configured(
                    name = validated.data.name,
                    configuration = JellyfinConfig(
                        serverUrl = validated.data.serverUrl,
                        username = validated.data.username,
                        credential = validated.data.password
                    ),
                    existingProviderId = command.existingProviderId
                ),
                onProgress = onProgress
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    suspend fun loginJellyfinQuickConnect(
        command: JellyfinQuickConnectProviderSetupCommand,
        onCode: ((String) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null
    ): ValidateAndAddProviderResult {
        return when (
            val validated = providerSetupInputValidator.validateJellyfinQuickConnect(
                serverUrl = command.serverUrl,
                name = command.name
            )
        ) {
            is Result.Success -> providerRepository.setupProvider(
                ProviderSetupRequest.JellyfinQuickConnect(
                    serverUrl = validated.data.serverUrl,
                    name = validated.data.name,
                    existingProviderId = command.existingProviderId
                ),
                onProgress = onProgress,
                onCode = onCode
            ).toUseCaseResult()

            is Result.Error -> ValidateAndAddProviderResult.ValidationError(validated.message)
            is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
        }
    }

    private fun Result<Provider>.toUseCaseResult(): ValidateAndAddProviderResult = when (this) {
        is Result.Success -> ValidateAndAddProviderResult.Success(data)
        is Result.Error -> {
            val transportConsent = generateSequence(exception) { it.cause }
                .filterIsInstance<StalkerTransportConsentRequiredException>()
                .firstOrNull()
            if (transportConsent != null) {
                ValidateAndAddProviderResult.TransportConsentRequired(
                    transportConsent.challenge
                )
            } else {
                val readinessInconclusive = generateSequence(exception) { it.cause }
                    .filterIsInstance<StalkerReadinessInconclusiveException>()
                    .firstOrNull()
                if (readinessInconclusive != null) {
                    ValidateAndAddProviderResult.VerificationInconclusive(
                        readinessInconclusive.message
                            ?: "Authentication succeeded, but Live TV could not be verified."
                    )
                } else {
                    val savedWithWarning = exception as? ProviderSavedWithSyncErrorException
                    if (savedWithWarning != null) {
                        ValidateAndAddProviderResult.SavedWithWarning(
                            provider = savedWithWarning.provider,
                            warning = savedWithWarning.message ?: message
                        )
                    } else {
                        ValidateAndAddProviderResult.Error(message, exception)
                    }
                }
            }
        }
        is Result.Loading -> ValidateAndAddProviderResult.Error("Unexpected loading state")
    }

    private sealed interface ParsedXtreamPlaylistUrlResult {
        data object NotXtreamPlaylist : ParsedXtreamPlaylistUrlResult
        data class ValidationError(val message: String) : ParsedXtreamPlaylistUrlResult
        data class Success(
            val serverUrl: String,
            val username: String,
            val password: String,
            val preferredLiveStreamFormat: LiveStreamFormatMode?
        ) : ParsedXtreamPlaylistUrlResult
    }

    private sealed interface ParsedStalkerUrlResult {
        data object NotStalkerUrl : ParsedStalkerUrlResult
        data class ValidationError(val message: String) : ParsedStalkerUrlResult
        data class Success(val portalUrl: String, val macAddress: String) : ParsedStalkerUrlResult
    }

    /**
     * Some Stalker portals publish an Xtream-shaped get.php URL whose password contains the
     * actual Stalker live.php?mac=... identity. Detect that signature before the Xtream parser
     * sees it, then set up the portal with the MAC instead of treating the playback route as a
     * Xtream password.
     */
    private fun parseStalkerPlaylistUrl(url: String): ParsedStalkerUrlResult {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return ParsedStalkerUrlResult.NotStalkerUrl
        val scheme = uri.scheme?.lowercase()
            ?.takeIf { it == "http" || it == "https" }
            ?: return ParsedStalkerUrlResult.NotStalkerUrl

        val path = uri.path.orEmpty().lowercase()
        val query = parseQueryParameters(uri.rawQuery)
        val password = query["password"].orEmpty()
        val nestedPlaybackQuery = password.substringAfter('?', missingDelimiterValue = "")
        val nestedQuery = parseQueryParameters(nestedPlaybackQuery)
        val macAddress = query["mac"] ?: nestedQuery["mac"]
            ?: STALKER_MAC_IN_PLAYBACK_ROUTE.find(password)?.groupValues?.getOrNull(1)
        val hasStreamRoute = (!query["stream"].isNullOrBlank() || !nestedQuery["stream"].isNullOrBlank()) &&
            (!query["extension"].isNullOrBlank() || !nestedQuery["extension"].isNullOrBlank())
        val isStalkerGetPhpExport = path.endsWith("/get.php") &&
            query["username"].isNullOrBlank().not() &&
            query["type"]?.lowercase() in setOf("m3u", "m3u_plus") &&
            password.startsWith("live.php?", ignoreCase = true) &&
            hasStreamRoute
        val isStalkerPlaybackUrl = path.endsWith("/play/live.php") && hasStreamRoute
        if (!isStalkerGetPhpExport && !isStalkerPlaybackUrl) {
            return ParsedStalkerUrlResult.NotStalkerUrl
        }

        val validatedMacAddress = macAddress?.takeIf(STALKER_MAC_ADDRESS::matches)
            ?: return ParsedStalkerUrlResult.ValidationError(
                "Stalker URL must include a valid MAC address."
            )
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return ParsedStalkerUrlResult.ValidationError("Stalker URL must include a host.")
        val portalPath = when {
            path.endsWith("/get.php") -> uri.path.orEmpty().dropLast("/get.php".length).trimEnd('/')
            path.endsWith("/play/live.php") -> uri.path.orEmpty().dropLast("/play/live.php".length).trimEnd('/')
            else -> ""
        }
        val portalUrl = buildString {
            append(scheme)
            append("://")
            append(host)
            if (uri.port != -1) {
                append(":")
                append(uri.port)
            }
            append(portalPath)
        }
        return ParsedStalkerUrlResult.Success(portalUrl, validatedMacAddress)
    }

    private fun parseXtreamPlaylistUrl(url: String): ParsedXtreamPlaylistUrlResult {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val scheme = uri.scheme?.lowercase() ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        if (scheme != "http" && scheme != "https") return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        val normalizedPath = uri.path.orEmpty().lowercase()
        if (!normalizedPath.endsWith("/get.php")) return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        val query = parseQueryParameters(uri.rawQuery)
        val username = query["username"]?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val password = query["password"]?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        val type = query["type"]?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist
        if (type != "m3u" && type != "m3u_plus") return ParsedXtreamPlaylistUrlResult.NotXtreamPlaylist

        if (username.length > MAX_XTREAM_PLAYLIST_USERNAME_LENGTH) {
            return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist username is too long.")
        }
        if (password.length > MAX_XTREAM_PLAYLIST_PASSWORD_LENGTH) {
            return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist password is too long.")
        }

        if (!uri.userInfo.isNullOrBlank()) {
            return ParsedXtreamPlaylistUrlResult.ValidationError(
                "Playlist sources must not include embedded credentials in the URL authority."
            )
        }

        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return ParsedXtreamPlaylistUrlResult.ValidationError("Playlist sources must include a host.")
        val authority = buildString {
            append(host.asXtreamServerHost())
            if (uri.port != -1) {
                append(":")
                append(uri.port)
            }
        }
        return ParsedXtreamPlaylistUrlResult.Success(
            serverUrl = "$scheme://$authority",
            username = username,
            password = password,
            preferredLiveStreamFormat = when (query["output"]?.lowercase()) {
                "ts" -> LiveStreamFormatMode.MPEG_TS
                "m3u8" -> LiveStreamFormatMode.HLS
                else -> null
            }
        )
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val value = part.substringAfter('=', "")
                decodeQueryComponent(key) to decodeQueryComponent(value)
            }
            .toMap()
    }

    private fun decodeQueryComponent(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun String.asXtreamServerHost(): String =
        if (contains(':') && !startsWith("[")) "[$this]" else this
}
