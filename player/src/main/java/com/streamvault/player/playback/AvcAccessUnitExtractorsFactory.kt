package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor

/** Preserves TS access-unit detection while keeping AVC prefixes with their picture. */
@UnstableApi
internal class AvcAccessUnitExtractorsFactory(
    private val upstream: ExtractorsFactory
) : ForwardingExtractorsFactory(upstream) {
    override fun createExtractors(): Array<Extractor> = wrap(upstream.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(upstream.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = extractors.map {
        if (it is TsExtractor) AvcAccessUnitExtractor(it) else it
    }.toTypedArray()
}

@UnstableApi
private class AvcAccessUnitExtractor(delegate: Extractor) : ForwardingExtractor(delegate) {
    private val outputs = mutableMapOf<Int, AvcAccessUnitTrackOutput>()

    override fun init(output: ExtractorOutput) {
        outputs.clear()
        super.init(object : ForwardingExtractorOutput(output) {
            override fun track(id: Int, type: Int): TrackOutput {
                val delegate = super.track(id, type)
                return if (type == C.TRACK_TYPE_VIDEO) {
                    outputs.getOrPut(id) { AvcAccessUnitTrackOutput(delegate) }
                } else delegate
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        outputs.values.forEach { it.reset() }
        super.seek(position, timeUs)
    }

    override fun release() {
        outputs.clear()
        super.release()
    }
}
