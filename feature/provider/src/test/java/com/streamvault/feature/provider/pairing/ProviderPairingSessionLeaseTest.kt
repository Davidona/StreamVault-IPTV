package com.streamvault.feature.provider.pairing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderPairingSessionLeaseTest {
    @Test
    fun `accepts only its token before expiry`() {
        val lease = ProviderPairingSessionLease(token = "one-time-token", expiresAtMs = 500L)

        assertThat(lease.isValid("one-time-token", nowMs = 499L)).isTrue()
        assertThat(lease.isValid("wrong-token", nowMs = 499L)).isFalse()
        assertThat(lease.isValid("one-time-token", nowMs = 500L)).isFalse()
    }

    @Test
    fun `allows only one concurrent submission and can retry after failure`() {
        val lease = ProviderPairingSessionLease(token = "one-time-token", expiresAtMs = 500L)

        assertThat(lease.tryBeginSubmission("one-time-token", nowMs = 100L)).isTrue()
        assertThat(lease.tryBeginSubmission("one-time-token", nowMs = 100L)).isFalse()

        lease.endSubmission()

        assertThat(lease.tryBeginSubmission("one-time-token", nowMs = 100L)).isTrue()
    }

    @Test
    fun `invalidation prevents future validation and submissions`() {
        val lease = ProviderPairingSessionLease(token = "one-time-token", expiresAtMs = 500L)
        lease.invalidate()

        assertThat(lease.isValid("one-time-token", nowMs = 100L)).isFalse()
        assertThat(lease.tryBeginSubmission("one-time-token", nowMs = 100L)).isFalse()
    }
}
