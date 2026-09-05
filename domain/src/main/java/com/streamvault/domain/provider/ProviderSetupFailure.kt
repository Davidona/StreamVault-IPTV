package com.streamvault.domain.provider

/** Stable error categories exposed by provider implementations to presentation code. */
enum class ProviderSetupFailureKind {
    AUTHENTICATION,
    REQUEST,
    NETWORK,
    RESPONSE_TOO_LARGE,
    PARSING,
    CREDENTIALS_UNREADABLE,
}

interface ProviderSetupFailure {
    val kind: ProviderSetupFailureKind
    val statusCode: Int?
        get() = null
}
