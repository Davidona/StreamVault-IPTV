package com.streamvault.feature.playback.player

import com.streamvault.feature.playback.api.PlaybackStreamPreparer
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result as DomainResult
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.player.PlayerEngine
import com.streamvault.player.playback.applyPlaybackTransportPolicy
import com.streamvault.player.playback.applyUnsafeTlsBypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject

/** Owns the provider/plugin preparation and player configuration boundary. */
class PlayerPreparationCoordinator @Inject constructor(
    private val streamPreparer: PlaybackStreamPreparer,
    private val playerPreferencesCoordinator: PlayerPreferencesCoordinator,
    private val providerRepository: com.streamvault.domain.repository.ProviderRepository,
    private val okHttpClient: OkHttpClient,
    private val playerEngineCoordinator: PlayerEngineCoordinator,
    private val playerContentResolver: PlayerContentResolver
) {
    internal data class Request(
        val streamInfo: StreamInfo,
        val contentType: ContentType,
        val providerId: Long,
        val currentStreamUrl: String?,
        val probePassedPlaybackKeys: Set<String>,
        val probeBeforePlayback: Boolean,
        val audioVideoOffsetMs: Int,
        val vodTrackPreferences: VodTrackPreferences? = null
    )

    internal sealed interface Result {
        data class Success(
            val streamInfo: StreamInfo,
            val probeCacheKey: String?
        ) : Result

        data class Failure(
            val message: String,
            val recoveryType: PlayerRecoveryType
        ) : Result
    }

    internal suspend fun prepare(
        request: Request,
        isCurrent: () -> Boolean
    ): Result? {
        if (!isCurrent()) return null

        val expiry = request.streamInfo.expirationTime
        if (expiry != null && expiry > 0L && expiry < System.currentTimeMillis()) {
            return Result.Failure(
                message = "This stream's subscription has expired. Please renew your subscription with the provider.",
                recoveryType = PlayerRecoveryType.SOURCE
            )
        }

        var preparedStreamInfo = request.streamInfo
        when (val pluginPrepareResult = streamPreparer.prepare(request.streamInfo)) {
            is DomainResult.Error -> {
                return Result.Failure(
                    message = pluginPrepareResult.message,
                    recoveryType = PlayerRecoveryType.NETWORK
                )
            }

            DomainResult.Loading -> Unit
            is DomainResult.Success -> preparedStreamInfo = pluginPrepareResult.data
        }

        var probeCacheKey: String? = null
        if (request.probeBeforePlayback && shouldProbePlaybackUrl(request, preparedStreamInfo.url)) {
            probePlaybackUrl(preparedStreamInfo)?.let { failure ->
                return Result.Failure(failure.message, failure.recoveryType)
            }
            probeCacheKey = resolvePlaybackProbeCacheKey(
                currentStreamUrl = request.currentStreamUrl,
                url = preparedStreamInfo.url
            )
        }

        if (!isCurrent()) return null
        applyPlaybackPreferences(
            engine = playerEngineCoordinator.currentEngine,
            contentType = request.contentType,
            audioVideoOffsetMs = request.audioVideoOffsetMs,
            vodTrackPreferences = request.vodTrackPreferences
        )
        if (!isCurrent()) return null

        val engine = playerEngineCoordinator.currentEngine
        engine.prepare(preparedStreamInfo)
        return Result.Success(
            streamInfo = preparedStreamInfo,
            probeCacheKey = probeCacheKey
        )
    }

    /**
     * Applies provider/plugin stream preparation without touching the foreground player.
     * Preload candidates use this path so request metadata is retained while probes,
     * preference changes, and foreground preparation remain side-effect free.
     */
    internal suspend fun prepareStreamForPreload(streamInfo: StreamInfo): StreamInfo? {
        val expiry = streamInfo.expirationTime
        if (expiry != null && expiry > 0L && expiry < System.currentTimeMillis()) {
            return null
        }
        val prepared = when (val result = streamPreparer.prepare(streamInfo)) {
            is DomainResult.Error -> return null
            DomainResult.Loading -> streamInfo
            is DomainResult.Success -> result.data
        }
        val preparedExpiry = prepared.expirationTime
        if (preparedExpiry != null && preparedExpiry > 0L && preparedExpiry < System.currentTimeMillis()) {
            return null
        }
        return prepared
    }

    internal suspend fun applyPlaybackPreferences(
        engine: PlayerEngine,
        contentType: ContentType,
        audioVideoOffsetMs: Int,
        vodTrackPreferences: VodTrackPreferences? = null
    ) {
        engine.setMuted(playerPreferencesCoordinator.playerMuted.first())
        engine.setPlaybackSpeed(
            if (contentType == ContentType.LIVE) {
                1f
            } else {
                playerPreferencesCoordinator.playerPlaybackSpeed.first()
            }
        )
        engine.setPreferredAudioLanguage(
            resolvePreferredAudioLanguage(
                preferredAudioLanguage = playerPreferencesCoordinator.preferredAudioLanguage.first(),
                appLanguage = playerPreferencesCoordinator.appLanguage.first()
            )
        )
        engine.setVodTrackPreferences(vodTrackPreferences)
        engine.setNetworkQualityPreferences(
            wifiMaxHeight = playerPreferencesCoordinator.playerWifiMaxVideoHeight.first(),
            ethernetMaxHeight = playerPreferencesCoordinator.playerEthernetMaxVideoHeight.first()
        )
        engine.setSurfaceMode(playerPreferencesCoordinator.playerSurfaceMode.first())
        engine.setPlaybackBufferMode(playerPreferencesCoordinator.playerPlaybackBufferMode.first())
        engine.setVodHttpProtocolMode(playerPreferencesCoordinator.playerVodHttpProtocolMode.first())
        engine.setFastRetryOnTransientFailures(playerPreferencesCoordinator.playerFastRetryOnTransientFailures.first())
        engine.setAudioVideoOffsetMs(audioVideoOffsetMs)
    }

    internal suspend fun resolveVodTrackPreferences(
        contentType: ContentType,
        providerId: Long,
        contentId: Long,
        seriesId: Long?
    ): VodTrackPreferences? {
        val scope = buildVodTrackPreferenceScope(
            contentType = contentType,
            providerId = providerId,
            contentId = contentId,
            seriesId = seriesId
        ) ?: return null
        return playerPreferencesCoordinator.getVodTrackPreferences(scope).first()
            ?: playerPreferencesCoordinator.globalVodTrackPreferences.first()
    }

    private suspend fun shouldProbePlaybackUrl(request: Request, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val providerId = request.providerId.takeIf { it > 0L } ?: return false
        val provider = providerRepository.getProvider(providerId) ?: return false
        val cacheKey = resolvePlaybackProbeCacheKey(
            currentStreamUrl = request.currentStreamUrl,
            url = url
        )
        if (provider.type != ProviderType.STALKER_PORTAL &&
            cacheKey in request.probePassedPlaybackKeys
        ) {
            return false
        }
        return (
            provider.type == ProviderType.XTREAM_CODES ||
                provider.type == ProviderType.STALKER_PORTAL
            ) && (
                playerContentResolver.isInternalStreamUrl(request.currentStreamUrl) ||
                    playerContentResolver.isInternalStreamUrl(url)
                )
    }

    private suspend fun probePlaybackUrl(streamInfo: StreamInfo): PlaybackProbeFailure? {
        return runCatching {
            withContext(Dispatchers.IO) {
                val request = HttpRequest.Builder()
                    .url(streamInfo.url)
                    .get()
                    .header("Range", "bytes=0-0")
                    .apply {
                        streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                        streamInfo.headers.forEach { (name, value) -> header(name, value) }
                    }
                    .build()
                val probeClient = if (
                    streamInfo.playbackTransportPolicy != null ||
                    streamInfo.allowInvalidSsl ||
                    streamInfo.proxyHost.isNotBlank()
                ) {
                    okHttpClient.newBuilder()
                        .apply {
                            val transportPolicy = streamInfo.playbackTransportPolicy
                            if (transportPolicy != null) {
                                applyPlaybackTransportPolicy(transportPolicy)
                            } else if (streamInfo.allowInvalidSsl) {
                                applyUnsafeTlsBypass()
                            }
                            streamInfo.httpProxy()?.let { proxy(it) }
                        }
                        .build()
                } else {
                    okHttpClient
                }
                probeClient.newCall(request).execute().use { response ->
                    resolvePlaybackProbeFailure(response.code)
                }
            }
        }.getOrNull()
    }

    private fun StreamInfo.httpProxy(): Proxy? {
        val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
        val port = proxyPort ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}
