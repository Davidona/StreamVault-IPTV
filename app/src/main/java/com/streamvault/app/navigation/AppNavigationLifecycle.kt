package com.streamvault.app.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun Lifecycle.awaitResumed() {
    if (currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    suspendCancellableCoroutine { continuation ->
        lateinit var observer: LifecycleEventObserver
        observer = LifecycleEventObserver { _, _ ->
            when {
                currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                    removeObserver(observer)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                currentState == Lifecycle.State.DESTROYED -> {
                    removeObserver(observer)
                    continuation.cancel()
                }
            }
        }
        addObserver(observer)
        continuation.invokeOnCancellation { removeObserver(observer) }
    }
}
