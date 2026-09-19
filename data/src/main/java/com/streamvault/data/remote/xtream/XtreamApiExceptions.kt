package com.streamvault.data.remote.xtream

import com.streamvault.domain.provider.ProviderSetupFailure
import com.streamvault.domain.provider.ProviderSetupFailureKind
import java.io.IOException

sealed class XtreamApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause),
    ProviderSetupFailure

class XtreamNetworkException(message: String, cause: Throwable? = null) :
    IOException(message, cause),
    ProviderSetupFailure {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.NETWORK
}

class XtreamAuthenticationException(
    override val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : XtreamApiException(message, cause) {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.AUTHENTICATION
}

class XtreamParsingException(message: String, cause: Throwable? = null) : XtreamApiException(message, cause) {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.PARSING
}

class XtreamRequestException(
    override val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : XtreamApiException(message, cause) {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.REQUEST
}

class XtreamResponseTooLargeException(
    val hint: String,
    val observedBytes: Long,
    val maxAllowedBytes: Long,
    cause: Throwable? = null
) : XtreamApiException(
    "Response from $hint exceeded safe in-memory budget (${observedBytes}B > ${maxAllowedBytes}B)",
    cause
) {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.RESPONSE_TOO_LARGE
}
