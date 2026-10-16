package com.streamvault.data.sync

import com.streamvault.data.remote.xtream.XtreamAuthenticationException
import com.streamvault.data.remote.xtream.XtreamRequestException

/** Classifies durable Xtream index failures for process-death and WorkManager recovery. */
internal object XtreamIndexRecoveryPolicy {
    fun isAuthenticationFailure(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any { it is XtreamAuthenticationException }

    fun failureState(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        if (chain.any { it is XtreamAuthenticationException }) {
            return "FAILED_PERMANENT"
        }
        val requestFailure = chain.filterIsInstance<XtreamRequestException>().firstOrNull()
        return if (requestFailure?.statusCode == 401 || requestFailure?.statusCode == 403) {
            "FAILED_PERMANENT"
        } else {
            "FAILED_RETRYABLE"
        }
    }
}
