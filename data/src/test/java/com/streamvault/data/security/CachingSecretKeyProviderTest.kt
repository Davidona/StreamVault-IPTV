package com.streamvault.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class CachingSecretKeyProviderTest {

    @Test
    fun `sequential gets load the key once`() {
        val loads = AtomicInteger()
        val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
        val provider = CachingSecretKeyProvider {
            loads.incrementAndGet()
            key
        }

        assertThat(provider.get()).isSameInstanceAs(key)
        assertThat(provider.get()).isSameInstanceAs(key)
        assertThat(loads.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent gets load the key once`() {
        val loads = AtomicInteger()
        val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
        val provider = CachingSecretKeyProvider {
            loads.incrementAndGet()
            Thread.sleep(10)
            key
        }

        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map {
                executor.submit<SecretKey> { provider.get() }
            }.map { it.get() }
            assertThat(results).containsExactlyElementsIn(List(16) { key })
            assertThat(loads.get()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `loader failure is not cached`() {
        val loads = AtomicInteger()
        val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
        val provider = CachingSecretKeyProvider {
            if (loads.incrementAndGet() == 1) {
                throw IllegalStateException("keystore unavailable")
            }
            key
        }

        assertThrows(IllegalStateException::class.java) { provider.get() }
        assertThat(provider.get()).isSameInstanceAs(key)
        assertThat(loads.get()).isEqualTo(2)
    }
}
