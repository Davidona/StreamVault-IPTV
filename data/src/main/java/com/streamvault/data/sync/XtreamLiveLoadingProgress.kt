package com.streamvault.data.sync

import java.text.NumberFormat

/** Unknown-total progress for the setup callback; readiness belongs to catalog promotion. */
internal class XtreamLiveLoadingProgress(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val publish: (String) -> Unit
) {
    private val numberFormat = NumberFormat.getIntegerInstance()
    private var lastPublishedAt: Long? = null
    private var lastPublishedCount = 0

    fun startAttempt() {
        lastPublishedAt = null
        lastPublishedCount = 0
        publish(XtreamLiveProgressLabels.LOADING)
    }

    fun batchStaged(count: Int) = publishCount(count, force = false)

    fun readerDrained(count: Int) = publishCount(count, force = true)

    private fun publishCount(count: Int, force: Boolean) {
        if (count <= 0 || count == lastPublishedCount) return
        val now = clockMillis()
        val previous = lastPublishedAt
        if (!force && previous != null && now - previous < 250L) return
        lastPublishedAt = now
        lastPublishedCount = count
        val noun = if (count == 1) "channel" else "channels"
        publish("${XtreamLiveProgressLabels.LOADING}\n${numberFormat.format(count)} $noun processed")
    }
}
