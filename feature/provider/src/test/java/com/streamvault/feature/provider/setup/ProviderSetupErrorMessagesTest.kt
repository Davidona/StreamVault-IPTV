package com.streamvault.feature.provider.setup

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.provider.ProviderSetupFailure
import com.streamvault.domain.provider.ProviderSetupFailureKind
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import org.junit.Test

class ProviderSetupErrorMessagesTest {
    @Test
    fun `xtream authentication failures use the credential guidance`() {
        val result = ValidateAndAddProviderResult.Error(
            message = "transport detail",
            exception = TestProviderSetupFailure(ProviderSetupFailureKind.AUTHENTICATION),
        )

        assertThat(ProviderSetupErrorMessages.xtream(result))
            .isEqualTo("Login failed - please check your credentials and server URL")
    }

    @Test
    fun `xtream request status preserves temporary server guidance`() {
        val result = ValidateAndAddProviderResult.Error(
            message = "HTTP 503",
            exception = TestProviderSetupFailure(
                kind = ProviderSetupFailureKind.REQUEST,
                statusCode = 503,
            ),
        )

        assertThat(ProviderSetupErrorMessages.xtream(result))
            .isEqualTo("Server is temporarily busy - try syncing again in a moment")
    }

    @Test
    fun `credential failures preserve their actionable message`() {
        val result = ValidateAndAddProviderResult.Error(
            message = "provider setup failed",
            exception = TestProviderSetupFailure(
                kind = ProviderSetupFailureKind.CREDENTIALS_UNREADABLE,
                message = "Re-enter these credentials.",
            ),
        )

        assertThat(ProviderSetupErrorMessages.jellyfin(result))
            .isEqualTo("Re-enter these credentials.")
    }

    private class TestProviderSetupFailure(
        override val kind: ProviderSetupFailureKind,
        override val statusCode: Int? = null,
        message: String = "fixture",
    ) : Exception(message), ProviderSetupFailure
}
