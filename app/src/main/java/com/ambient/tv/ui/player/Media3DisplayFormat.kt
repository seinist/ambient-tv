@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Source format, including a player resumed after its initial decoder events already fired. */
@Composable
internal fun ObserveMedia3DisplayFormat(
    player: ExoPlayer,
    onFormat: (Float, Int?, Int?) -> Unit,
    onSourceHdrChanged: (HdrStandard?) -> Unit = {},
) {
    val latestCallback by rememberUpdatedState(onFormat)
    val latestHdrCallback by rememberUpdatedState(onSourceHdrChanged)
    DisposableEffect(player) {
        fun publish(format: Format) {
            val standard = HdrSupport.sourceStandard(format)
            // Unspecified transfer/absent ColorInfo is unknown, not confirmed SDR.
            if (standard != null || format.colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_SDR)
                latestHdrCallback(standard)
            latestCallback(format.frameRate.takeIf { it.isFinite() && it > 0f } ?: 0f,
                format.width.takeIf { it > 0 }, format.height.takeIf { it > 0 })
        }
        val listener = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                inputFormat: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) = publish(inputFormat)
        }
        player.addAnalyticsListener(listener)
        player.videoFormat?.let(::publish)
        onDispose { player.removeAnalyticsListener(listener) }
    }
}
