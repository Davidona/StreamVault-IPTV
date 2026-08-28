package com.streamvault.app.live

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.live.api.LivePreviewOrigin
import com.streamvault.feature.playback.preview.PreviewHandoffSource
import org.junit.Test

class AppLiveAdaptersTest {
    @Test
    fun previewOriginMapping_preservesBothHandoffDirections() {
        assertThat(LivePreviewOrigin.HOME.toPreviewHandoffSource())
            .isEqualTo(PreviewHandoffSource.HOME)
        assertThat(LivePreviewOrigin.GUIDE.toPreviewHandoffSource())
            .isEqualTo(PreviewHandoffSource.GUIDE)
        assertThat(PreviewHandoffSource.HOME.toLivePreviewOrigin())
            .isEqualTo(LivePreviewOrigin.HOME)
        assertThat(PreviewHandoffSource.GUIDE.toLivePreviewOrigin())
            .isEqualTo(LivePreviewOrigin.GUIDE)
    }
}
