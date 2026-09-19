package com.streamvault.data.validation

import com.streamvault.domain.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedJellyfinProviderInput
import com.streamvault.domain.manager.ValidatedJellyfinQuickConnectProviderInput
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedStalkerProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.data.remote.stalker.StalkerAdvancedOptionsCodec
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Headers

@Singleton
class ProviderSetupInputValidatorImpl @Inject constructor() : ProviderSetupInputValidator {

    override fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedXtreamProviderInput> {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedPassword = ProviderInputSanitizer.normalizePassword(password)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedHttpUserAgent = ProviderInputSanitizer.normalizeHttpUserAgent(httpUserAgent)
        val normalizedHttpHeaders = ProviderInputSanitizer.normalizeHttpHeaders(httpHeaders)

        if (normalizedServerUrl.isBlank()) {
            return Result.error("Please enter server URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        if (normalizedUsername.isBlank()) {
            return Result.error("Please enter username")
        }
        ProviderInputSanitizer.validatePassword(password, allowBlankPassword)?.let { message ->
            return Result.error(message)
        }
        validateHttpOverrides(normalizedHttpUserAgent, normalizedHttpHeaders)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedXtreamProviderInput(
                serverUrl = normalizedServerUrl,
                username = normalizedUsername,
                password = normalizedPassword,
                name = normalizedName,
                httpUserAgent = normalizedHttpUserAgent,
                httpHeaders = normalizedHttpHeaders
            )
        )
    }

    override fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String,
        httpHeaders: String
    ): Result<ValidatedM3uProviderInput> {
        val normalizedUrl = ProviderInputSanitizer.normalizeUrl(url)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedHttpUserAgent = ProviderInputSanitizer.normalizeHttpUserAgent(httpUserAgent)
        val normalizedHttpHeaders = ProviderInputSanitizer.normalizeHttpHeaders(httpHeaders)

        if (normalizedUrl.isBlank()) {
            return Result.error("Please enter M3U URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        validateHttpOverrides(normalizedHttpUserAgent, normalizedHttpHeaders)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedM3uProviderInput(
                url = normalizedUrl,
                name = normalizedName,
                httpUserAgent = normalizedHttpUserAgent,
                httpHeaders = normalizedHttpHeaders
            )
        )
    }

    override fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        allowBlankPassword: Boolean,
        httpUserAgent: String,
        httpHeaders: String,
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String,
        deviceId: String,
        deviceId2: String,
        signature: String,
        stalkerAdvancedOptionsJson: String
    ): Result<ValidatedStalkerProviderInput> {
        val normalizedPortalUrl = ProviderInputSanitizer.normalizeUrl(portalUrl)
        val normalizedMacAddress = ProviderInputSanitizer.normalizeMacAddress(macAddress)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedPassword = ProviderInputSanitizer.normalizePassword(password)
        val normalizedHttpUserAgent = ProviderInputSanitizer.normalizeHttpUserAgent(httpUserAgent)
        val normalizedHttpHeaders = ProviderInputSanitizer.normalizeHttpHeaders(httpHeaders)
        val normalizedDeviceProfile = ProviderInputSanitizer.normalizeDeviceProfile(deviceProfile)
        val normalizedTimezone = ProviderInputSanitizer.normalizeTimezone(timezone)
        val normalizedLocale = ProviderInputSanitizer.normalizeLocale(locale)
        val normalizedSerialNumber = ProviderInputSanitizer.normalizeStalkerSerial(serialNumber)
        val normalizedDeviceId = ProviderInputSanitizer.normalizeStalkerDeviceId(deviceId)
        val normalizedDeviceId2 = ProviderInputSanitizer.normalizeStalkerDeviceId(deviceId2)
        val normalizedSignature = ProviderInputSanitizer.normalizeStalkerSignature(signature)

        if (normalizedPortalUrl.isBlank()) {
            return Result.error("Please enter portal URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateStalkerPortalUrl(normalizedPortalUrl)?.let { message ->
            return Result.error(message)
        }

        when (authMode) {
            StalkerAuthMode.MAC_ONLY -> {
                ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
                    return Result.error(message)
                }
            }

            StalkerAuthMode.MAC_PLUS_CREDENTIALS -> {
                ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
                    return Result.error(message)
                }
                if (normalizedUsername.isBlank()) {
                    return Result.error("Please enter username")
                }
                ProviderInputSanitizer.validatePassword(normalizedPassword, allowBlankPassword)?.let { message ->
                    return Result.error(message)
                }
            }

            StalkerAuthMode.CREDENTIALS_ONLY -> {
                if (normalizedUsername.isBlank()) {
                    return Result.error("Please enter username")
                }
                ProviderInputSanitizer.validatePassword(normalizedPassword, allowBlankPassword)?.let { message ->
                    return Result.error(message)
                }
                if (normalizedMacAddress.isNotBlank()) {
                    ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
                        return Result.error(message)
                    }
                }
            }

            StalkerAuthMode.AUTO -> {
                val hasMac = normalizedMacAddress.isNotBlank()
                val hasUsername = normalizedUsername.isNotBlank()
                if (!hasMac && !hasUsername) {
                    return Result.error("Please enter a MAC address or username")
                }
                if (hasMac) {
                    ProviderInputSanitizer.validateMacAddress(normalizedMacAddress)?.let { message ->
                        return Result.error(message)
                    }
                }
                if (hasUsername || password.isNotBlank()) {
                    if (!hasUsername) {
                        return Result.error("Please enter username")
                    }
                    ProviderInputSanitizer.validatePassword(normalizedPassword, allowBlankPassword)?.let { message ->
                        return Result.error(message)
                    }
                }
            }
        }

        validateHttpOverrides(
            normalizedHttpUserAgent,
            normalizedHttpHeaders,
            allowBlankHeaderValues = true,
            allowRestrictedHeaderOverrides = true
        )?.let { message ->
            return Result.error(message)
        }

        // deviceProfile becomes the X-User-Agent model and part of the device signature. Blank
        // is fine (defaults to MAG250 internally); a non-blank value must be a safe ASCII token.
        if (normalizedDeviceProfile.isNotBlank() && !DEVICE_PROFILE_SAFE_REGEX.matches(normalizedDeviceProfile)) {
            return Result.error("Device profile must contain only letters, digits, dots, hyphens, and underscores (e.g. MAG250).")
        }

        // timezone is embedded literally in the Cookie header:
        //   Cookie: mac=...; stb_lang=...; timezone=<value>
        // A semicolon in the value would inject additional cookie pairs. An unknown zone ID
        // would silently cause MAG portals to reject authentication or use the wrong time.
        if (normalizedTimezone.isNotBlank()) {
            if (!COOKIE_VALUE_SAFE_REGEX.matches(normalizedTimezone)) {
                return Result.error("Timezone contains characters that are not allowed in this field.")
            }
            runCatching { ZoneId.of(normalizedTimezone) }.onFailure {
                return Result.error("Timezone is not a recognized identifier (e.g. America/New_York, Europe/London, UTC).")
            }
        }

        // locale is embedded in the Cookie header as stb_lang. Apply the same cookie-safety
        // check and require a conservative BCP-47 language-tag shape.
        if (normalizedLocale.isNotBlank()) {
            if (!LOCALE_SAFE_REGEX.matches(normalizedLocale)) {
                return Result.error("Locale must be a language tag like 'en' or 'en-US'.")
            }
        }

        validateOptionalIdentityToken(normalizedSerialNumber, "Serial number", allowHexOnly = false)?.let { message ->
            return Result.error(message)
        }
        validateOptionalIdentityToken(normalizedDeviceId, "Device ID")?.let { message ->
            return Result.error(message)
        }
        validateOptionalIdentityToken(normalizedDeviceId2, "Device ID2")?.let { message ->
            return Result.error(message)
        }
        validateOptionalIdentityToken(normalizedSignature, "Signature")?.let { message ->
            return Result.error(message)
        }
        validateStalkerAdvancedOptions(stalkerAdvancedOptionsJson)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedStalkerProviderInput(
                portalUrl = normalizedPortalUrl,
                macAddress = normalizedMacAddress,
                name = normalizedName,
                authMode = authMode,
                username = normalizedUsername,
                password = normalizedPassword,
                httpUserAgent = normalizedHttpUserAgent,
                httpHeaders = normalizedHttpHeaders,
                deviceProfile = normalizedDeviceProfile,
                timezone = normalizedTimezone,
                locale = normalizedLocale,
                serialNumber = normalizedSerialNumber,
                deviceId = normalizedDeviceId,
                deviceId2 = normalizedDeviceId2,
                signature = normalizedSignature,
                stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson.trim()
            )
        )
    }

    override fun validateJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        allowBlankPassword: Boolean
    ): Result<ValidatedJellyfinProviderInput> {
        val trimmedUrl = serverUrl.trim()
        if (trimmedUrl.isBlank()) return Result.error("Server URL is required")
        val url = if (trimmedUrl.contains("://")) trimmedUrl else "https://$trimmedUrl"
        val trimmedName = name.trim().ifBlank { url.substringAfter("//").substringBefore("/").ifBlank { "Jellyfin" } }
        val trimmedUser = username.trim()
        if (!allowBlankPassword && password.isBlank()) return Result.error("Password is required")
        return Result.success(ValidatedJellyfinProviderInput(serverUrl = url, username = trimmedUser, password = password.trim(), name = trimmedName))
    }

    override fun validateJellyfinQuickConnect(serverUrl: String, name: String): Result<ValidatedJellyfinQuickConnectProviderInput> {
        val trimmedUrl = serverUrl.trim()
        if (trimmedUrl.isBlank()) return Result.error("Server URL is required")
        val url = if (trimmedUrl.contains("://")) trimmedUrl else "https://$trimmedUrl"
        val trimmedName = name.trim().ifBlank { url.substringAfter("//").substringBefore("/").ifBlank { "Jellyfin" } }
        return Result.success(ValidatedJellyfinQuickConnectProviderInput(serverUrl = url, name = trimmedName))
    }

    private companion object {
        private val HEADER_SEPARATOR_REGEX = Regex("\\s*\\|\\s*|[\\r\\n]+")
        private val DISALLOWED_GENERIC_HEADER_NAMES = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
            "set-cookie",
            "user-agent"
        )

        // Safe ASCII token for device profiles (MAG250, MAG254, etc.). Excludes cookie
        // delimiters (;, ,, =, ") and HTTP header-breaking characters.
        private val DEVICE_PROFILE_SAFE_REGEX = Regex("^[A-Za-z0-9._-]+$")

        // Validates that a value can be safely embedded in a cookie without breaking the
        // Cookie: header. Semicolons, commas, double-quotes, and backslashes are all cookie
        // delimiters or quoting characters defined in RFC 6265 §4.1.1. Control characters
        // are already stripped by ProviderInputSanitizer before this point.
        private val COOKIE_VALUE_SAFE_REGEX = Regex("^[^;,\"\\\\]+$")

        // BCP-47 language tag: primary subtag (2–8 letters) optionally followed by
        // hyphen-separated extension subtags (1–8 alphanumeric characters each).
        private val LOCALE_SAFE_REGEX = Regex("^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$")
        private val IDENTITY_TOKEN_SAFE_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val HEX_TOKEN_SAFE_REGEX = Regex("^[A-F0-9]+$")
        private val ACTION_NAME_SAFE_REGEX = Regex("^[A-Za-z0-9_:-]+$")
        private val PARAM_NAME_SAFE_REGEX = Regex("^[A-Za-z0-9_.:-]+$")
    }

    private fun validateStalkerAdvancedOptions(raw: String): String? {
        if (raw.isBlank()) return null
        val options = runCatching { StalkerAdvancedOptionsCodec.decodeStrict(raw) }
            .getOrElse { return "Stalker advanced options could not be read." }
        if (options.proxyEnabled) {
            if (options.proxyHost.isBlank()) {
                return "Proxy host is required when HTTP proxy is enabled."
            }
            if (options.proxyHost.any { it.isWhitespace() }) {
                return "Proxy host must not contain spaces."
            }
            val port = options.proxyPort
            if (port == null || port !in 1..65535) {
                return "Proxy port must be between 1 and 65535."
            }
        }
        validateHttpOverrides(
            httpUserAgent = options.playerUserAgent.trim(),
            httpHeaders = options.playerHeaders.trim(),
            allowBlankHeaderValues = true,
            allowRestrictedHeaderOverrides = true
        )?.let { message ->
            return "Player headers: $message"
        }
        val actions = mutableSetOf<String>()
        options.requestRules.forEach { rule ->
            val action = rule.action.trim()
            if (action.isBlank()) {
                return "Each request rule needs an action name."
            }
            if (!ACTION_NAME_SAFE_REGEX.matches(action)) {
                return "Request rule action '$action' contains invalid characters."
            }
            if (!actions.add(action)) {
                return "Request rule action '$action' is duplicated."
            }
            val params = mutableSetOf<String>()
            rule.paramOverrides.forEach { override ->
                val name = override.name.trim()
                if (name.isBlank()) {
                    return "Request rule '$action' contains a blank parameter name."
                }
                if (!PARAM_NAME_SAFE_REGEX.matches(name)) {
                    return "Request rule parameter '$name' contains invalid characters."
                }
                if (!params.add(name)) {
                    return "Request rule '$action' contains duplicate parameter '$name'."
                }
            }
        }
        return null
    }

    private fun validateOptionalIdentityToken(
        value: String,
        label: String,
        allowHexOnly: Boolean = true
    ): String? {
        if (value.isBlank()) return null
        if (!COOKIE_VALUE_SAFE_REGEX.matches(value)) {
            return "$label contains characters that are not allowed in this field."
        }
        val validShape = if (allowHexOnly) HEX_TOKEN_SAFE_REGEX.matches(value) else IDENTITY_TOKEN_SAFE_REGEX.matches(value)
        return if (validShape) null else "$label contains unsupported characters."
    }

    private fun validateHttpOverrides(
        httpUserAgent: String,
        httpHeaders: String,
        allowBlankHeaderValues: Boolean = false,
        allowRestrictedHeaderOverrides: Boolean = false
    ): String? {
        if (httpUserAgent.any { it == '\r' || it == '\n' }) {
            return "User-Agent must stay on a single line."
        }
        if (httpHeaders.isBlank()) {
            return null
        }
        val entries = httpHeaders
            .split(HEADER_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.size > 12) {
            return "Too many custom headers. Limit the override to 12 entries."
        }
        val seenNames = linkedSetOf<String>()
        entries.forEach { entry ->
            val separatorIndex = entry.indexOf(':')
            if (separatorIndex <= 0 || (!allowBlankHeaderValues && separatorIndex == entry.lastIndex)) {
                return "Custom headers must use 'Header-Name: value' format. Separate multiple headers with '|'."
            }
            val name = entry.substring(0, separatorIndex).trim()
            val value = entry.substring(separatorIndex + 1).trim()
            if (name.isEmpty() || (!allowBlankHeaderValues && value.isEmpty())) {
                return "Custom headers must include both a header name and a value."
            }
            if (!allowRestrictedHeaderOverrides && name.lowercase() in DISALLOWED_GENERIC_HEADER_NAMES) {
                return if (name.equals("User-Agent", ignoreCase = true)) {
                    "Set the User-Agent in the dedicated field instead of custom headers."
                } else {
                    "$name cannot be overridden here."
                }
            }
            try {
                Headers.Builder().add(name, value.ifEmpty { "x" })
            } catch (_: IllegalArgumentException) {
                return "Invalid custom header: $name"
            }
            if (!seenNames.add(name.lowercase())) {
                return "Duplicate custom header: $name"
            }
        }
        return null
    }
}
