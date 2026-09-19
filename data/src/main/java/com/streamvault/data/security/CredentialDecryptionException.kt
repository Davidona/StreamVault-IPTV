package com.streamvault.data.security

import com.streamvault.domain.provider.PlayerCredentialFailure
import com.streamvault.domain.provider.ProviderSetupFailure
import com.streamvault.domain.provider.ProviderSetupFailureKind

class CredentialDecryptionException(
    message: String = MESSAGE,
    cause: Throwable? = null
) : PlayerCredentialFailure(message, cause), ProviderSetupFailure {
    override val kind: ProviderSetupFailureKind = ProviderSetupFailureKind.CREDENTIALS_UNREADABLE

    companion object {
        const val MESSAGE = "Stored credentials are no longer readable. Please re-enter your provider credentials."
    }
}
