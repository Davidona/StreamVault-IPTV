package com.streamvault.player.tracks

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.player.DEVICE_SUBTITLE_TRACK_ID
import com.streamvault.player.ONLINE_SUBTITLE_TRACK_ID
import com.streamvault.player.SubtitleTrackSource
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerTrackControllerExternalSubtitleAutoSelectTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val controller = PlayerTrackController(context)

    private val deviceGroup = TrackGroup(
        "device-subtitle",
        Format.Builder()
            .setId(DEVICE_SUBTITLE_TRACK_ID)
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("und")
            .setLabel("movie.srt")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    )
    private val tracks = Tracks(
        listOf(
            Tracks.Group(
                deviceGroup,
                /* adaptiveSupported= */ false,
                /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
                /* trackSelected= */ booleanArrayOf(false)
            )
        )
    )

    private val onlineGroup = TrackGroup(
        "online-subtitle",
        Format.Builder()
            .setId(ONLINE_SUBTITLE_TRACK_ID)
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("en")
            .setLabel("downloaded.srt")
            .build()
    )
    private val onlineTracks = Tracks(
        listOf(
            Tracks.Group(
                onlineGroup,
                /* adaptiveSupported= */ false,
                /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
                /* trackSelected= */ booleanArrayOf(false)
            )
        )
    )

    private var currentTracks: Tracks = tracks
    private var parameters = TrackSelectionParameters.Builder(context).build()
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when (method.name) {
            "getCurrentTracks" -> currentTracks
            "getTrackSelectionParameters" -> parameters
            "setTrackSelectionParameters" -> {
                parameters = args!![0] as TrackSelectionParameters
                null
            }
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    @Test
    fun `freshly picked device subtitle wins over a stored disabled preference`() {
        controller.setVodTrackPreferences(null, VodTrackPreferences(subtitle = VodTrackPreference(disabled = true)))
        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.DEVICE)

        controller.onTracksChanged(tracks)
        controller.applyVodTrackPreferences(player)
        assertThat(parameters.disabledTrackTypes).contains(C.TRACK_TYPE_TEXT)

        controller.applyPendingExternalSubtitleAutoSelect(player)

        assertThat(parameters.overrides[deviceGroup]?.trackIndices).containsExactly(0)
        assertThat(parameters.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
        assertThat(controller.availableSubtitleTracks.value.single().isSelected).isTrue()
    }

    @Test
    fun `auto selected device subtitle survives later preference applications`() {
        controller.setVodTrackPreferences(null, VodTrackPreferences(subtitle = VodTrackPreference(disabled = true)))
        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.DEVICE)
        controller.onTracksChanged(tracks)
        controller.applyPendingExternalSubtitleAutoSelect(player)

        controller.applyVodTrackPreferences(player)

        assertThat(parameters.overrides[deviceGroup]?.trackIndices).containsExactly(0)
    }

    @Test
    fun `pending online request does not select a device subtitle`() {
        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.ONLINE)
        controller.onTracksChanged(tracks)

        controller.applyPendingExternalSubtitleAutoSelect(player)
        assertThat(parameters.overrides).isEmpty()

        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.DEVICE)
        controller.applyPendingExternalSubtitleAutoSelect(player)
        assertThat(parameters.overrides[deviceGroup]?.trackIndices).containsExactly(0)
    }

    @Test
    fun `reset selections clears a pending auto select`() {
        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.DEVICE)
        controller.resetSelections()
        controller.onTracksChanged(tracks)

        controller.applyPendingExternalSubtitleAutoSelect(player)

        assertThat(parameters.overrides).isEmpty()
    }

    @Test
    fun `remembered online subtitle survives a later tracks changed pass`() {
        currentTracks = onlineTracks
        controller.onTracksChanged(onlineTracks)
        val onlineTrackId = controller.availableSubtitleTracks.value.single().id
        controller.setVodTrackPreferences(
            player,
            VodTrackPreferences(
                subtitle = VodTrackPreference(
                    trackId = onlineTrackId,
                    language = "en",
                    label = "downloaded.srt",
                    externalSubtitleDownloadId = "12345"
                )
            )
        )
        controller.requestExternalSubtitleAutoSelect(SubtitleTrackSource.ONLINE)

        controller.applyVodTrackPreferences(player)
        controller.applyPendingExternalSubtitleAutoSelect(player)

        assertThat(parameters.overrides[onlineGroup]?.trackIndices).containsExactly(0)

        controller.onTracksChanged(onlineTracks)
        controller.applyVodTrackPreferences(player)
        controller.applyPendingExternalSubtitleAutoSelect(player)

        assertThat(parameters.overrides[onlineGroup]?.trackIndices).containsExactly(0)
        assertThat(parameters.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
    }
}
