package com.streamvault.data.sync

import com.streamvault.domain.util.KeyedMutexRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes catalog work per provider. Stalker guide work has its own lane so a long
 * catalog import cannot prevent guide downloads. Both lanes participate in maintenance admission.
 */
@Singleton
class ProviderWorkLockRegistry @Inject constructor() {
    private val admissionMutex = Mutex()
    private val providerMutexes = KeyedMutexRegistry<Long>()
    private val stalkerGuideMutexes = KeyedMutexRegistry<Long>()
    private val admittedCount = AtomicInteger(0)

    fun isAnyWorkActiveOrWaiting(): Boolean = admittedCount.get() > 0

    suspend fun <T> withProviderLock(providerId: Long, block: suspend () -> T): T =
        withAdmission(providerMutexes, providerId, block)

    suspend fun <T> withStalkerGuideLock(providerId: Long, block: suspend () -> T): T =
        withAdmission(stalkerGuideMutexes, providerId, block)

    private suspend fun <T> withAdmission(
        mutexes: KeyedMutexRegistry<Long>, providerId: Long, block: suspend () -> T
    ): T {
        require(providerId > 0L) { "Provider work requires a positive provider ID." }
        admissionMutex.withLock {
            admittedCount.incrementAndGet()
        }
        return try {
            mutexes.withLock(providerId, block)
        } finally {
            withContext(NonCancellable) {
                admissionMutex.withLock { admittedCount.decrementAndGet() }
            }
        }
    }

    suspend fun runWhenNoWorkActive(block: suspend () -> Boolean): Boolean =
        admissionMutex.withLock {
            if (admittedCount.get() > 0) {
                false
            } else {
                block()
            }
        }

    /** Removes idle provider state after durable provider deletion. */
    suspend fun forgetProvider(providerId: Long) {
        admissionMutex.withLock {
            providerMutexes.forget(providerId)
            stalkerGuideMutexes.forget(providerId)
        }
    }
}
