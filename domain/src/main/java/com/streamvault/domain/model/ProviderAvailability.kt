package com.streamvault.domain.model

/** A recent observation of whether a provider can currently be reached and used. */
enum class ProviderAvailabilityStatus {
    ONLINE,
    UNAVAILABLE,
    AUTHENTICATION_REQUIRED,
    UNKNOWN
}

data class ProviderAvailabilityResult(
    val status: ProviderAvailabilityStatus,
    val checkedAt: Long
)
