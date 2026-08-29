package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelEpgMapping
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgSourceType
import org.junit.Test

class LiveGuideEpgOverrideTest {
    @Test
    fun `override summary reports no match when mapping is absent`() {
        assertThat(
            liveGuideOverrideSummary(
                mapping = null,
                currentCandidate = null,
                unknownValue = "Unknown",
                currentNone = "None",
                currentManualFormat = "Manual: %1\$s",
                currentProviderFormat = "Provider: %1\$s",
                currentExternalFormat = "External: %1\$s"
            )
        ).isEqualTo("None")
    }

    @Test
    fun `override summary reports manual mapping using the selected candidate`() {
        val mapping = ChannelEpgMapping(
            providerChannelId = 7L,
            providerId = 3L,
            sourceType = EpgSourceType.EXTERNAL,
            epgSourceId = 11L,
            xmltvChannelId = "news.xml",
            matchType = EpgMatchType.MANUAL,
            isManualOverride = true
        )
        val candidate = EpgOverrideCandidate(
            epgSourceId = 11L,
            epgSourceName = "External",
            xmltvChannelId = "news.xml",
            displayName = "News"
        )

        assertThat(
            liveGuideOverrideSummary(
                mapping = mapping,
                currentCandidate = candidate,
                unknownValue = "Unknown",
                currentNone = "None",
                currentManualFormat = "Manual: %1\$s",
                currentProviderFormat = "Provider: %1\$s",
                currentExternalFormat = "External: %1\$s"
            )
        ).startsWith("Manual: News")
    }
}
