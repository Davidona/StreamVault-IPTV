package com.streamvault.feature.playback.player

import com.streamvault.domain.model.stremio.StremioMediaRequestHeaders
import com.streamvault.domain.model.stremio.stremioMediaRequestHeaders
import java.net.URI

internal sealed interface StremioMediaHeaderPolicy {
    data object NotApplicable : StremioMediaHeaderPolicy
    data object Invalid : StremioMediaHeaderPolicy
    data class Scoped(val value: StremioMediaRequestHeaders) : StremioMediaHeaderPolicy
}

/** Add-on request headers are scoped to the original direct HTTP media URL only. */
internal fun stremioMediaHeaderPolicy(
    mediaUrl: String,
    suppliedHeaders: Map<String, String>?
): StremioMediaHeaderPolicy {
    val scheme = runCatching { URI(mediaUrl).scheme?.lowercase() }.getOrNull()
    if (scheme != "http" && scheme != "https") return StremioMediaHeaderPolicy.NotApplicable

    val scoped = stremioMediaRequestHeaders(mediaUrl, suppliedHeaders)
        ?: return StremioMediaHeaderPolicy.Invalid
    return StremioMediaHeaderPolicy.Scoped(scoped)
}
