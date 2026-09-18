@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import com.streamvault.domain.model.StaticClearKeyLicense

internal fun staticClearKeyDrmSessionManager(license: StaticClearKeyLicense): DrmSessionManager {
    val response = buildString {
        append("{\"keys\":[")
        license.keys.forEachIndexed { index, key ->
            if (index > 0) append(',')
            append("{\"kty\":\"oct\",\"kid\":\"")
            append(key.keyIdBase64Url.jsonEscape())
            append("\",\"k\":\"")
            append(key.keyBase64Url.jsonEscape())
            append("\"}")
        }
        append("],\"type\":\"temporary\"}")
    }.toByteArray(Charsets.UTF_8)
    return DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
        .build(LocalMediaDrmCallback(response))
}

private fun String.jsonEscape(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
