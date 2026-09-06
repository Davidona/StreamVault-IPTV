@file:androidx.media3.common.util.UnstableApi

package com.streamvault.feature.playback.translation

import androidx.media3.common.C
import com.streamvault.player.LiveAudioPcmBuffer
import com.streamvault.player.PlayerEngine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TARGET_SAMPLE_RATE = 16_000
private const val TARGET_CHANNEL_COUNT = 1
private const val TARGET_BYTES_PER_SAMPLE = 2
// Upload small audio increments frequently; the service accumulates them into a
// phrase, emits in-progress partials, and finalises on a natural pause (VAD).
private const val UPLOAD_CHUNK_MS = 1_000L
private const val MAX_PENDING_AUDIO_BUFFERS = 120
// Minimum time a caption stays on screen before it can advance to newer text, so
// the subtitle doesn't change faster than a viewer can read. Trades a little
// latency for readability; intermediate updates during this window are coalesced.
private const val MIN_CAPTION_DISPLAY_MS = 2_200L
// Partials re-transcribe the whole phrase, so consecutive ones can rewrite the
// line wholesale; hold each at least this long or the text is unreadable churn.
private const val MIN_PARTIAL_DISPLAY_MS = 1_500L
// Clear the caption after this much idle time with no new text (TV-caption feel).
private const val SUBTITLE_LINGER_MS = 20_000L

internal data class CaptionTick(
    val text: String,
    val isFinal: Boolean,
    val chunkId: Long
)

class LiveTranslationSession(
    private val scope: CoroutineScope,
    private val playerEngine: PlayerEngine,
    private val client: LiveTranslationClient,
    private val logicalUrl: String,
    private val providerId: Long,
    private val contentId: Long,
    private val onSourceLanguageDetected: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var sessionId: String? = null
    private var pollingJob: Job? = null
    private var audioBuffers = newAudioChannel()
    // Latest pending caption text waiting to be displayed. Conflated: if several
    // updates arrive during a dwell, only the most recent is kept.
    private var captionUpdates = newCaptionChannel()

    fun start() {
        stop()
        audioBuffers = newAudioChannel()
        captionUpdates = newCaptionChannel()
        playerEngine.setLiveAudioTap { buffer ->
            audioBuffers.trySend(buffer)
        }
        pollingJob = scope.launch {
            runCatching {
                sessionId = client.startSession(
                    logicalUrl = logicalUrl,
                    providerId = providerId,
                    contentId = contentId
                )
                coroutineScope {
                    launch { displayLoop() }
                    audioUploadLoop()
                }
            }.onFailure {
                if (it is CancellationException) {
                    clearCues()
                    return@onFailure
                }
                onError("Live translation unavailable: ${it.message.orEmpty().ifBlank { "service error" }}")
                clearCues()
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        playerEngine.clearLiveAudioTap()
        audioBuffers.close()
        captionUpdates.close()
        val currentSessionId = sessionId
        sessionId = null
        clearCues()
        if (currentSessionId != null) {
            scope.launch {
                client.stopSession(currentSessionId)
            }
        }
    }

    private suspend fun audioUploadLoop() {
        val chunk = ByteArrayOutputStream()
        var chunkStartMs: Long? = null
        var chunkEndMs = 0L
        var fallbackPositionMs = playerEngine.currentPosition.value
        for (buffer in audioBuffers) {
            if (!scope.isActive) return
            val activeSessionId = sessionId ?: return
            val converted = convertToPcm16Mono16k(buffer, fallbackPositionMs) ?: continue
            fallbackPositionMs = converted.endMs
            if (converted.data.isEmpty()) continue
            val activeChunkStartMs = chunkStartMs ?: converted.startMs
            chunkStartMs = activeChunkStartMs
            chunkEndMs = converted.endMs
            chunk.write(converted.data)
            if (chunkEndMs - activeChunkStartMs < UPLOAD_CHUNK_MS) {
                continue
            }
            uploadChunk(activeSessionId, chunk.toByteArray(), activeChunkStartMs, chunkEndMs)
            chunk.reset()
            chunkStartMs = null
            delay(50L)
        }
    }

    private suspend fun uploadChunk(sessionId: String, pcmChunk: ByteArray, startMs: Long, endMs: Long) {
        val update = client.uploadPcmChunk(
            sessionId = sessionId,
            pcm16Mono16k = pcmChunk,
            startMs = startMs,
            endMs = endMs
        )
        // Empty text means silence / no new transcription for this upload.
        if (update.text.isBlank()) {
            return
        }
        update.sourceLanguage?.let(onSourceLanguageDetected)
        // Hand the latest text to the display loop, which paces how fast captions
        // change. Conflated channel: if we're mid-dwell, this just replaces the
        // pending text so the viewer always advances to the most recent state.
        captionUpdates.trySend(
            CaptionTick(
                text = update.text,
                isFinal = update.isFinal,
                chunkId = update.chunkId
            )
        )
    }

    // Pacing/coalescing of caption changes lives in [runCaptionDisplayLoop] so it
    // can be unit-tested without the audio/network plumbing.
    private suspend fun displayLoop() {
        runCaptionDisplayLoop(
            captionUpdates = captionUpdates,
            lingerMs = SUBTITLE_LINGER_MS,
            finalDwellMs = MIN_CAPTION_DISPLAY_MS,
            partialDwellMs = MIN_PARTIAL_DISPLAY_MS,
            render = ::renderCaption,
            clear = ::clearCues
        )
    }

    private fun renderCaption(text: String) {
        playerEngine.setInjectedSubtitleText(text)
    }

    private fun clearCues() {
        playerEngine.setInjectedSubtitleText(null)
    }
}

/**
 * Paces caption changes for readability: each rendered line dwells on screen
 * ([finalDwellMs] for finals, [partialDwellMs] for partials) before the line may
 * change, and [clear] is invoked after [lingerMs] of no new text.
 *
 * [captionUpdates] is expected to be a *conflated* channel: while a line is
 * dwelling, newer updates collapse to the most recent one, so after the dwell we
 * jump straight to the latest text and pacing can never build up a backlog.
 *
 * Intentional tradeoff: because only the newest pending update survives the dwell
 * window, a finalized phrase can be skipped entirely if a newer (partial) update
 * arrives before its predecessor's dwell elapses. This is deliberate — we favour
 * showing the most current state over replaying superseded revisions, accepting
 * that an occasional finalized phrase is never rendered. See
 * LiveTranslationCaptionPacingTest for the behavior this guarantees.
 */
internal suspend fun runCaptionDisplayLoop(
    captionUpdates: ReceiveChannel<CaptionTick>,
    lingerMs: Long,
    finalDwellMs: Long,
    partialDwellMs: Long,
    render: (String) -> Unit,
    clear: () -> Unit
) {
    var hasVisibleCaption = false
    while (coroutineContext.isActive) {
        val result = withTimeoutOrNull(lingerMs) { captionUpdates.receiveCatching() }
        if (result == null) {
            if (hasVisibleCaption) {
                clear()
                hasVisibleCaption = false
            }
            continue
        }
        val tick = result.getOrNull() ?: break // channel closed -> session ended
        if (tick.text.isBlank()) continue
        render(tick.text)
        hasVisibleCaption = true
        delay(if (tick.isFinal) finalDwellMs else partialDwellMs)
    }
}

private data class ConvertedPcmChunk(
    val data: ByteArray,
    val startMs: Long,
    val endMs: Long
)

private fun newAudioChannel(): Channel<LiveAudioPcmBuffer> = Channel(
    capacity = MAX_PENDING_AUDIO_BUFFERS,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

// Conflated: only the newest pending caption is kept. A final superseded during a
// dwell is skipped in favour of the next phrase's text, which is the trade we want
// — show the most current state rather than replaying stale revisions.
private fun newCaptionChannel(): Channel<CaptionTick> = Channel(Channel.CONFLATED)

private fun convertToPcm16Mono16k(buffer: LiveAudioPcmBuffer, fallbackStartMs: Long): ConvertedPcmChunk? {
    if (buffer.encoding != C.ENCODING_PCM_16BIT || buffer.sampleRate <= 0 || buffer.channelCount <= 0) {
        return null
    }
    val inputFrameSize = buffer.channelCount * TARGET_BYTES_PER_SAMPLE
    val inputFrames = buffer.data.size / inputFrameSize
    if (inputFrames <= 0) return null

    val outputFrames = ((inputFrames.toLong() * TARGET_SAMPLE_RATE) / buffer.sampleRate)
        .coerceAtLeast(1L)
        .toInt()
    val output = ByteArray(outputFrames * TARGET_CHANNEL_COUNT * TARGET_BYTES_PER_SAMPLE)
    for (outputFrame in 0 until outputFrames) {
        val inputFrame = ((outputFrame.toLong() * buffer.sampleRate) / TARGET_SAMPLE_RATE)
            .coerceIn(0L, (inputFrames - 1).toLong())
            .toInt()
        var mixed = 0
        for (channel in 0 until buffer.channelCount) {
            val byteIndex = inputFrame * inputFrameSize + channel * TARGET_BYTES_PER_SAMPLE
            val low = buffer.data[byteIndex].toInt() and 0xFF
            val high = buffer.data[byteIndex + 1].toInt()
            mixed += (high shl 8) or low
        }
        val sample = (mixed / buffer.channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val outputIndex = outputFrame * TARGET_BYTES_PER_SAMPLE
        output[outputIndex] = (sample and 0xFF).toByte()
        output[outputIndex + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    val startMs = fallbackStartMs.coerceAtLeast(0L)
    val durationMs = (outputFrames * 1_000L) / TARGET_SAMPLE_RATE
    return ConvertedPcmChunk(
        data = output,
        startMs = startMs,
        endMs = startMs + durationMs
    )
}
