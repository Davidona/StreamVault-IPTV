package com.streamvault.player

enum class PlayerPcmEncoding {
    PCM_16_BIT,
    UNSUPPORTED
}

data class LiveAudioPcmBuffer(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: PlayerPcmEncoding
)

fun interface LiveAudioTap {
    fun onPcmAudio(buffer: LiveAudioPcmBuffer)
}
