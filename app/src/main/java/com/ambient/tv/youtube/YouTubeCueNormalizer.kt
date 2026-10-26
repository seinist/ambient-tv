@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.youtube

import android.text.Layout
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * YouTube's automatic captions (WebVTT) mark every cue `align:start position:0%`, which puts the
 * text at the left edge of the picture. Such cues are centered; every other position is kept.
 */
internal object YouTubeCueNormalizer {
    /** Largest start position (fraction of the width) still treated as "left edge". */
    const val LEFT_EDGE_POSITION = 0.05f

    fun isLeftEdge(cue: Cue): Boolean =
        cue.bitmap == null &&
            (cue.textAlignment == Layout.Alignment.ALIGN_NORMAL || cue.textAlignment == null) &&
            cue.position != Cue.DIMEN_UNSET && cue.position in 0f..LEFT_EDGE_POSITION &&
            (cue.positionAnchor == Cue.ANCHOR_TYPE_START || cue.positionAnchor == Cue.TYPE_UNSET)

    fun normalize(cue: Cue): Cue = if (!isLeftEdge(cue)) cue else cue.buildUpon()
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(Cue.DIMEN_UNSET)
        .build()

    fun normalize(cues: CuesWithTiming): CuesWithTiming =
        if (cues.cues.none(::isLeftEdge)) cues
        else CuesWithTiming(cues.cues.map(::normalize), cues.startTimeUs, cues.durationUs)
}

/** Media3 subtitle parsers for YouTube sessions only; other sources keep [DefaultSubtitleParserFactory]. */
internal class YouTubeSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)
    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)
    override fun create(format: Format): SubtitleParser = CenteringParser(delegate.create(format))

    private class CenteringParser(private val parser: SubtitleParser) : SubtitleParser {
        override fun parse(
            data: ByteArray, offset: Int, length: Int,
            outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>,
        ) = parser.parse(data, offset, length, outputOptions) { output.accept(YouTubeCueNormalizer.normalize(it)) }

        override fun reset() = parser.reset()
        override fun getCueReplacementBehavior(): Int = parser.cueReplacementBehavior
    }
}
