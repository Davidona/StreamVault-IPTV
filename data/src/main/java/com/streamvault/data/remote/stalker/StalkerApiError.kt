package com.streamvault.data.remote.stalker

import java.io.IOException
import com.streamvault.domain.model.StalkerTransportChallenge

sealed class StalkerApiError(
    message: String,
    cause: Throwable? = null,
    open val httpStatus: Int? = null,
    open val portalReason: String? = null,
    open val retryAfterMillis: Long? = null
) : IOException(message, cause) {
    open class Authorization(
        message: String = "Portal authorization failed.",
        cause: Throwable? = null,
        override val httpStatus: Int? = null,
        override val portalReason: String? = null
    ) : StalkerApiError(message, cause, httpStatus = httpStatus, portalReason = portalReason)

    class SessionExpired(
        message: String = "The portal session expired; authentication is required again.",
        cause: Throwable? = null,
        httpStatus: Int? = null,
        reason: String? = null
    ) : Authorization(message, cause = cause, httpStatus = httpStatus, portalReason = reason)

    class PartialAuthorization(
        message: String = "Portal accepted the device profile but denied catalog access.",
        cause: Throwable? = null,
        reason: String? = null
    ) : Authorization(message, cause = cause, portalReason = reason)

    class InvalidMac(message: String, reason: String? = null) :
        Authorization(message, portalReason = reason)

    class TokenRejected(message: String, reason: String? = null) :
        Authorization(message, portalReason = reason)

    /**
     * The portal knows this MAC but has a different device identity (device_id/signature)
     * bound to it. Retrying with another recipe or endpoint cannot help; the user must
     * supply the registered device identity or ask the provider to unbind the device.
     */
    class DeviceConflict(message: String, reason: String? = null) :
        Authorization(message, portalReason = reason)

    /**
     * The portal answered with the Ministra `status:2` envelope: the device is not
     * registered/provisioned and must authenticate through the portal launcher first.
     */
    class DeviceNotRegistered(message: String, reason: String? = null) :
        Authorization(message, portalReason = reason)

    /**
     * The portal rejected the profile because the device clock is outside its tolerance.
     * Retrying is pointless until the device date/time is corrected.
     */
    class ClockSkew(message: String, reason: String? = null) :
        Authorization(message, portalReason = reason)

    class ModelRejected(message: String, override val portalReason: String? = null) :
        StalkerApiError(message, portalReason = portalReason)

    class AccountBlocked(message: String, override val portalReason: String? = null) :
        StalkerApiError(message, portalReason = portalReason)

    class RateLimited(
        message: String = "Portal rate limit reached.",
        override val httpStatus: Int? = 429,
        override val retryAfterMillis: Long? = null
    ) : StalkerApiError(message, httpStatus = httpStatus, retryAfterMillis = retryAfterMillis)

    class Server(
        message: String,
        override val httpStatus: Int? = null,
        override val portalReason: String? = null
    ) : StalkerApiError(message, httpStatus = httpStatus, portalReason = portalReason)

    class Transport(message: String, cause: Throwable? = null) : StalkerApiError(message, cause)
    class TransportConsentRequired(
        val challenge: StalkerTransportChallenge
    ) : StalkerApiError("Transport consent is required for ${challenge.displayHost}.")
    class Malformed(message: String, cause: Throwable? = null) : StalkerApiError(message, cause)
    class EmptyBody(message: String) : StalkerApiError(message)
    class ContentUnavailable(
        override val portalReason: String? = null,
        message: String = "The provider reported that this item is currently unavailable."
    ) : StalkerApiError(message, portalReason = portalReason)
    class UnsupportedProtocol(message: String) : StalkerApiError(message)
    class ResponseTooLarge(message: String) : StalkerApiError(message)
    class DiscoveryBudgetExceeded(message: String) : StalkerApiError(message)
    class ReadinessInconclusive(
        val evidenceCode: String,
        cause: Throwable
    ) : StalkerApiError(
        "Authentication succeeded, but Live TV readiness could not be verified.",
        cause
    )
    class CatalogTruncated(
        val advertisedTotalPages: Int,
        val pageLimit: Int,
        message: String = "Portal catalog exceeds the $pageLimit-page aggregate safety limit."
    ) : StalkerApiError(message)
    class BlockedOrConfiguration(message: String, override val portalReason: String? = null) :
        StalkerApiError(message, portalReason = portalReason)
}

internal fun Throwable.isStalkerAuthorizationFailure(): Boolean {
    if (this is StalkerApiError.Authorization) return true
    val value = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .lowercase()
    return listOf(
        "authorization failed",
        "not_valid_token",
        "invalid token",
        "empty token",
        "unauthorized",
        "not valid mac",
        "invalid mac",
        "access denied",
        "permission denied",
        "http 401",
        "http 403"
    ).any(value::contains)
}

internal fun isStalkerAuthorizationFailure(message: String, error: Throwable?): Boolean {
    if (error?.isStalkerAuthorizationFailure() == true) return true
    val normalized = message.lowercase()
    return listOf(
        "authorization failed",
        "not_valid_token",
        "invalid token",
        "empty token",
        "unauthorized",
        "not valid mac",
        "invalid mac",
        "access denied",
        "permission denied",
        "http 401",
        "http 403"
    ).any(normalized::contains)
}

/**
 * True when the portal is throttling us (hard 429 or a tokenless 200). Retrying with another
 * handshake immediately only converts soft throttles into hard ones; callers must back off.
 */
internal fun Throwable.isPortalThrottle(): Boolean =
    generateSequence(this) { it.cause }.any { it is StalkerApiError.RateLimited }
