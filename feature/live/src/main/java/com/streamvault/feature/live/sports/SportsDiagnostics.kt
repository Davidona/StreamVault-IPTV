package com.streamvault.feature.live.sports

import android.util.Log
import com.streamvault.domain.model.Program

internal object SportsDiagnostics {
    private const val TAG = "SportsHub"
    private const val PERFORMANCE_TAG = "SportsPerf"
    private const val MAX_LOG_VALUE_LENGTH = 160

    fun isEnabled(): Boolean = Log.isLoggable(TAG, Log.DEBUG)

    fun classificationCache(hits: Long, misses: Long, entries: Int, estimatedBytes: Long) {
        if (!Log.isLoggable(PERFORMANCE_TAG, Log.DEBUG)) return
        Log.d(PERFORMANCE_TAG, "stage=classification-cache hits=$hits misses=$misses entries=$entries estimatedBytes=$estimatedBytes")
    }

    fun performance(
        stage: String,
        elapsedMillis: Long,
        itemCount: Int? = null,
        groupCount: Int? = null,
    ) {
        if (!Log.isLoggable(PERFORMANCE_TAG, Log.DEBUG)) return
        val counts = buildList {
            itemCount?.let { add("items=$it") }
            groupCount?.let { add("groups=$it") }
        }.joinToString(" ")
        Log.d(
            PERFORMANCE_TAG,
            "stage=$stage elapsedMs=$elapsedMillis${if (counts.isEmpty()) "" else " $counts"}",
        )
    }

    fun classification(
        program: Program,
        classification: SportsClassification,
        eventFingerprint: String,
        channelId: Long,
        channelName: String,
        channelMatchConfidence: Float?,
    ) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            "classification programId=${program.id} " +
                "rawTitle=${quoted(program.title)} " +
                "sports=${classification.isSports} competitive=${classification.isCompetitiveEvent} " +
                "sport=${classification.sport} league=${quoted((classification.leagueInfo?.id ?: classification.league.orEmpty()))} " +
                "competitors=${classification.competitors.map(::quoted)} " +
                "confidence=${classification.confidence} reasons=${classification.reasons} " +
                "eventFingerprint=${quoted(eventFingerprint)} " +
                "channelId=$channelId channelName=${quoted(channelName)} " +
                "channelMatch=resolved channelMatchConfidence=${channelMatchConfidence ?: "unknown"}",
        )
    }

    fun dedupe(
        programId: Long,
        eventFingerprint: String,
        decision: String,
        groupSize: Int,
    ) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            "dedupe programId=$programId eventFingerprint=${quoted(eventFingerprint)} " +
                "decision=$decision groupSize=$groupSize",
        )
    }

    fun dedupeSummary(candidateCount: Int, eventCount: Int, mergedCandidateCount: Int) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            "dedupe-summary candidates=$candidateCount events=$eventCount " +
                "mergedCandidates=$mergedCandidateCount",
        )
    }

    fun fallbackMatch(eventId: String, match: SportsChannelMatch) {
        if (!isEnabled()) return
        Log.d(
            TAG,
            "fallback-match eventId=${quoted(eventId)} score=${match.score} " +
                "reasons=${match.reasons} providerId=${match.entry.channel.providerId} " +
                "channelId=${match.entry.channel.id} channelName=${quoted(match.entry.channel.name)}",
        )
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        append(
            value
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\"", "\\\"")
                .take(MAX_LOG_VALUE_LENGTH),
        )
        append('"')
    }
}
