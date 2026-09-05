package com.streamvault.domain.util

object ProviderInputSanitizer {
    const val MAX_PROVIDER_NAME_LENGTH = 80
    const val MAX_URL_LENGTH = 2048
    const val MAX_USERNAME_LENGTH = 128
    const val MAX_PASSWORD_LENGTH = 256
    const val MAX_HTTP_USER_AGENT_LENGTH = 256
    const val MAX_HTTP_HEADERS_LENGTH = 1024
    const val MAX_MAC_ADDRESS_LENGTH = 17
    const val MAX_DEVICE_PROFILE_LENGTH = 32
    const val MAX_TIMEZONE_LENGTH = 64
    const val MAX_LOCALE_LENGTH = 16
    const val MAX_STALKER_SERIAL_LENGTH = 64
    const val MAX_STALKER_DEVICE_ID_LENGTH = 128
    const val MAX_STALKER_SIGNATURE_LENGTH = 128

    fun sanitizeProviderNameForEditing(input: String): String = sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)

    fun sanitizeUrlForEditing(input: String): String = sanitizeRaw(input, MAX_URL_LENGTH)

    fun sanitizeUsernameForEditing(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH)

    fun sanitizePasswordForEditing(input: String): String = sanitizeRaw(input, MAX_PASSWORD_LENGTH)

    fun sanitizeHttpUserAgentForEditing(input: String): String = sanitizeSingleLine(input, MAX_HTTP_USER_AGENT_LENGTH)

    fun sanitizeHttpHeadersForEditing(input: String): String = sanitizeSingleLine(input, MAX_HTTP_HEADERS_LENGTH)

    fun sanitizeMacAddressForEditing(input: String): String = sanitizeSingleLine(input.uppercase(), MAX_MAC_ADDRESS_LENGTH)

    fun sanitizeDeviceProfileForEditing(input: String): String = sanitizeSingleLine(input, MAX_DEVICE_PROFILE_LENGTH)

    fun sanitizeTimezoneForEditing(input: String): String = sanitizeSingleLine(input, MAX_TIMEZONE_LENGTH)

    fun sanitizeLocaleForEditing(input: String): String = sanitizeSingleLine(input, MAX_LOCALE_LENGTH)

    fun sanitizeStalkerSerialForEditing(input: String): String = sanitizeSingleLine(input.uppercase(), MAX_STALKER_SERIAL_LENGTH)

    fun sanitizeStalkerDeviceIdForEditing(input: String): String = sanitizeSingleLine(input.uppercase(), MAX_STALKER_DEVICE_ID_LENGTH)

    fun sanitizeStalkerSignatureForEditing(input: String): String = sanitizeSingleLine(input.uppercase(), MAX_STALKER_SIGNATURE_LENGTH)

    fun normalizeProviderName(input: String): String =
        sanitizeSingleLine(input, MAX_PROVIDER_NAME_LENGTH)
            .trim()
            .replace(WHITESPACE_REGEX, " ")

    fun normalizeUrl(input: String): String {
        val raw = sanitizeRaw(input, MAX_URL_LENGTH).trim()
        val protocolMatch = URL_PROTOCOL_REGEX.find(raw)
        if (protocolMatch != null) {
            val prefix = protocolMatch.value.lowercase()
            return prefix + raw.substring(protocolMatch.value.length)
        }
        return raw
    }

    fun normalizeUsername(input: String): String = sanitizeSingleLine(input, MAX_USERNAME_LENGTH).trim()

    fun normalizePassword(input: String): String = sanitizeRaw(input, MAX_PASSWORD_LENGTH)

    fun normalizeHttpUserAgent(input: String): String =
        sanitizeSingleLine(input, MAX_HTTP_USER_AGENT_LENGTH).trim()

    fun normalizeHttpHeaders(input: String): String =
        sanitizeSingleLine(input, MAX_HTTP_HEADERS_LENGTH)
            .split(HEADER_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" | ")

    fun normalizeMacAddress(input: String): String {
        val raw = sanitizeSingleLine(input, MAX_MAC_ADDRESS_LENGTH + 5)
            .uppercase()
            .filter { it.isLetterOrDigit() }
        if (raw.length != 12) {
            return sanitizeSingleLine(input.uppercase(), MAX_MAC_ADDRESS_LENGTH).trim()
        }
        return raw.chunked(2).joinToString(":")
    }

    fun normalizeDeviceProfile(input: String): String =
        sanitizeSingleLine(input, MAX_DEVICE_PROFILE_LENGTH).trim()

    fun normalizeTimezone(input: String): String =
        sanitizeSingleLine(input, MAX_TIMEZONE_LENGTH).trim()

    fun normalizeLocale(input: String): String =
        sanitizeSingleLine(input, MAX_LOCALE_LENGTH).trim()

    fun normalizeStalkerSerial(input: String): String =
        sanitizeSingleLine(input.uppercase(), MAX_STALKER_SERIAL_LENGTH).trim()

    fun normalizeStalkerDeviceId(input: String): String =
        sanitizeSingleLine(input.uppercase(), MAX_STALKER_DEVICE_ID_LENGTH).trim()

    fun normalizeStalkerSignature(input: String): String =
        sanitizeSingleLine(input.uppercase(), MAX_STALKER_SIGNATURE_LENGTH).trim()

    fun validateUrl(url: String): String? {
        return if (url.any(Char::isWhitespace)) {
            "URLs cannot contain spaces or line breaks."
        } else {
            null
        }
    }

    fun validateMacAddress(macAddress: String): String? {
        if (macAddress.isBlank()) {
            return "Please enter MAC address"
        }
        return if (MAC_ADDRESS_REGEX.matches(macAddress)) {
            null
        } else {
            "MAC address must be six hex pairs like 00:1A:79:12:34:56."
        }
    }

    fun validatePassword(password: String, allowBlank: Boolean): String? {
        if (!allowBlank && password.isBlank()) {
            return "Please enter password"
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            return "Password is too long"
        }
        if (password.any(Char::isISOControl)) {
            return "Password cannot contain control characters."
        }
        return null
    }

    private fun sanitizeSingleLine(input: String, maxLength: Int): String {
        return sanitizeRaw(input, maxLength).replace(WHITESPACE_REGEX, " ")
    }

    private fun sanitizeRaw(input: String, maxLength: Int): String {
        val sanitized = buildString(input.length.coerceAtMost(maxLength)) {
            input.forEach { char ->
                if (!char.isISOControl()) {
                    append(char)
                }
            }
        }
        return sanitized.take(maxLength)
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val HEADER_SEPARATOR_REGEX = Regex("\\s*\\|\\s*")
    private val MAC_ADDRESS_REGEX = Regex("^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$")
    private val URL_PROTOCOL_REGEX = Regex("^https?://", RegexOption.IGNORE_CASE)
}
