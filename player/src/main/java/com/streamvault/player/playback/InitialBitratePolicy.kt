package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Chooses a conservative estimate for the first adaptive-track selection.
 *
 * Media3 normally has a network-type estimate, but a player recreation would otherwise lose the
 * estimate observed by this app's previous player. Reusing the latest provider estimate at 75%
 * gives the next selection a useful head start without immediately selecting the top rendition.
 */
@UnstableApi
class InitialBitratePolicy(
    private val recentEstimateProvider: () -> Long = { 0L }
) : DefaultBandwidthMeter.InitialBitrateSupplier {

    override fun getInitialBitrateEstimate(networkType: Int): Long = estimate(
        networkType = networkType,
        recentEstimateBps = recentEstimateProvider()
    )

    companion object {
        private const val RECENT_ESTIMATE_NUMERATOR = 3L
        private const val RECENT_ESTIMATE_DENOMINATOR = 4L
        private const val MIN_STARTUP_ESTIMATE_BPS = 250_000L
        private const val MAX_STARTUP_ESTIMATE_BPS = 8_000_000L

        fun estimate(networkType: Int, recentEstimateBps: Long): Long {
            val recentEstimate = recentEstimateBps.takeIf { it > 0L }
                ?: return defaultEstimateFor(networkType)

            return (recentEstimate * RECENT_ESTIMATE_NUMERATOR / RECENT_ESTIMATE_DENOMINATOR)
                .coerceIn(MIN_STARTUP_ESTIMATE_BPS, MAX_STARTUP_ESTIMATE_BPS)
        }

        private fun defaultEstimateFor(networkType: Int): Long = when (networkType) {
            C.NETWORK_TYPE_WIFI,
            C.NETWORK_TYPE_ETHERNET -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_WIFI.first()
            C.NETWORK_TYPE_2G -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_2G.first()
            C.NETWORK_TYPE_3G -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_3G.first()
            C.NETWORK_TYPE_4G -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_4G.first()
            C.NETWORK_TYPE_5G_NSA -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_NSA.first()
            C.NETWORK_TYPE_5G_SA -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATES_5G_SA.first()
            else -> DefaultBandwidthMeter.DEFAULT_INITIAL_BITRATE_ESTIMATE
        }
    }
}
