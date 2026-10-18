package com.streamvault.data.remote.xtream

import java.io.Reader
import com.streamvault.domain.provider.ProviderSetupFailureKind

/** Bounds token allocation before JsonReader can build a very large string/tree. */
internal class XtreamLiveRowBudgetReader(private val delegate: Reader) : Reader() {
    private var remaining: Int? = null

    fun beginRow() { remaining = MAX_ROW_CHARS }
    fun endRow() { remaining = null }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val budget = remaining
        if (budget != null && budget <= 0) throw XtreamLiveRowTooLargeException()
        val read = delegate.read(buffer, offset, minOf(length, budget ?: length))
        if (read > 0 && budget != null) remaining = budget - read
        return read
    }

    override fun close() = delegate.close()

    companion object {
        // A JSON row (including skipped metadata) may retain up to JsonReader's
        // 1024-character read-ahead in addition to this budget. No silent trim.
        const val MAX_ROW_CHARS = 1024 * 1024
    }
}

class XtreamLiveRowTooLargeException : XtreamApiException("Live JSON row exceeds token allocation budget") {
    override val kind = ProviderSetupFailureKind.RESPONSE_TOO_LARGE
}
