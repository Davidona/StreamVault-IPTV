package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.util.Locale

/** Pure recovery decisions shared by notices, retry handling, and preload protection. */
internal object PlayerRecoveryPolicy {
    fun buildActions(
        hasAlternateStream: Boolean,
        hasLastChannel: Boolean,
        shouldOfferGuide: Boolean
    ): List<PlayerNoticeAction> = buildList {
        add(PlayerNoticeAction.RETRY)
        if (hasAlternateStream) add(PlayerNoticeAction.ALTERNATE_STREAM)
        if (hasLastChannel) add(PlayerNoticeAction.LAST_CHANNEL)
        if (shouldOfferGuide) add(PlayerNoticeAction.OPEN_GUIDE)
    }

    fun shouldAttemptProviderAuthRetry(
        providerType: ProviderType,
        contentType: ContentType
    ): Boolean = contentType == ContentType.LIVE &&
        (providerType == ProviderType.XTREAM_CODES || providerType == ProviderType.STALKER_PORTAL)

    fun shouldCooldownLivePreloadAfterError(message: String?): Boolean {
        val normalized = message.orEmpty().lowercase(Locale.ROOT)
        return "401" in normalized ||
            "403" in normalized ||
            "429" in normalized ||
            "509" in normalized ||
            "forbidden" in normalized ||
            "unauthorized" in normalized ||
            "too many" in normalized ||
            "max connection" in normalized ||
            "provider limit" in normalized ||
            "connection limit" in normalized
    }
}
