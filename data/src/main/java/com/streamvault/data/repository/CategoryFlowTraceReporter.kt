package com.streamvault.data.repository

import android.os.Trace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits low-overhead trace sections for category-flow lifecycle measurements.
 *
 * The section is intentionally constant so macrobenchmarks can count upstream starts across
 * repeated route re-entry without exposing provider identifiers in the trace name.
 */
@Singleton
class CategoryFlowTraceReporter @Inject constructor() {

    fun onUpstreamStart(_providerId: Long) {
        Trace.beginSection(UPSTREAM_START_TRACE)
        Trace.endSection()
    }

    fun onUpstreamStop(_providerId: Long) {
        Trace.beginSection(UPSTREAM_STOP_TRACE)
        Trace.endSection()
    }

    companion object {
        const val UPSTREAM_START_TRACE = "StreamVault.CategoryFlow.UpstreamStart"
        const val UPSTREAM_STOP_TRACE = "StreamVault.CategoryFlow.UpstreamStop"
    }
}
