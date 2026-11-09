package com.streamvault.feature.live.phone

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneSessionGuardTest {
    private var now = 0L
    private fun guard(level: Int = 1) = PhoneSessionGuard(7, "192.168.1.2:1234", level, { now })
    private val host = "192.168.1.2:1234"
    private val origin = "http://192.168.1.2:1234"

    @Test fun `pairing is single use and requests require host origin cookie and csrf`() {
        val guard = guard()
        val code = guard.pairingCode()!!
        assertThat(guard.pair(host, "http://evil.test", code, 1)).isNull()
        val credentials = guard.pair(host, origin, code, 1)!!
        assertThat(guard.pair(host, origin, code, 1)).isNull()
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 1)).isTrue()
        assertThat(guard.authorize("evil.test", origin, credentials.cookie, credentials.csrf, 1)).isFalse()
        assertThat(guard.authorize(host, origin, credentials.cookie, "wrong", 1)).isFalse()
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 2)).isFalse()
        guard.stop()
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 1)).isFalse()
    }

    @Test fun `pairing and idle sessions expire`() {
        val unpaired = guard()
        val code = unpaired.pairingCode()!!
        now = 300_000
        assertThat(unpaired.pair(host, origin, code, 1)).isNull()
        now = 0
        val paired = guard()
        val credentials = paired.pair(host, origin, paired.pairingCode()!!, 1)!!
        now = 300_000
        assertThat(paired.authorize(host, origin, credentials.cookie, credentials.csrf, 1)).isFalse()
    }

    @Test fun `activity cannot extend the hard session limit`() {
        val guard = guard(level = 0)
        val credentials = guard.pair(host, origin, guard.pairingCode()!!, 0)!!
        repeat(9) {
            now += 180_000
            assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 0)).isTrue()
        }
        now = 1_800_000
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 0)).isFalse()
    }

    @Test fun `parental authorization expires even with activity`() {
        val guard = guard()
        val credentials = guard.pair(host, origin, guard.pairingCode()!!, 1)!!
        now = 240_000
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 1)).isTrue()
        now = 300_000
        assertThat(guard.authorize(host, origin, credentials.cookie, credentials.csrf, 1)).isFalse()
    }
}
