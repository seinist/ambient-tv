@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.MpvPlaybackSnapshot
import com.ambient.tv.ui.player.backend.VlcPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackSnapshot
import kotlin.math.abs

@Composable
fun rememberPlaybackLoadHint(
    enabled: Boolean,
    positionMs: Long,
    containerFps: Double?,
    outputFps: Double?,
    droppedFrames: Long?,
    heavyVideo: Boolean,
    heavyAudio: Boolean,
    audioUnstable: Boolean = false,
    audioFaults: Long? = null,
    renderedFrames: Long? = null,
): PlaybackLoadHint {
    val clock = remember { PlaybackLoadClock { android.os.SystemClock.elapsedRealtime() } }
    val drops = remember { RateTracker() }
    val faults = remember { RateTracker() }
    val rendered = remember { RateTracker() }
    if (!enabled) {
        SideEffect {
            clock.reset()
            drops.reset()
            faults.reset()
            rendered.reset()
        }
        return PlaybackLoadHint.NONE
    }
    val now = android.os.SystemClock.elapsedRealtime()
    val dropRate = drops.observe(droppedFrames, now)
    val faultRate = faults.observe(audioFaults, now) ?: 0.0
    val renderedRate = rendered.observe(renderedFrames, now)
    val effectiveOutput = outputFps ?: renderedRate?.takeIf { it > 1.0 }
    val unstable = audioUnstable || faultRate > 0.0
    val behind = (containerFps != null && effectiveOutput != null && effectiveOutput < containerFps * 0.90) || (dropRate ?: 0.0) > 2.0
    val (since, badFor) = clock.mark(positionMs, behind || unstable)
    return playbackLoadHint(
        PlaybackLoadSample(
            sinceAnchorMs = since,
            badForMs = badFor,
            containerFps = containerFps,
            outputFps = effectiveOutput,
            dropsPerSecond = dropRate,
            heavyVideo = heavyVideo,
            heavyAudio = heavyAudio,
            audioUnstable = unstable,
        ),
    )
}

@Composable
fun BoxScope.PlaybackPerformanceNotice(hint: PlaybackLoadHint) {
    val text = when (hint) {
        PlaybackLoadHint.NONE -> return
        PlaybackLoadHint.VIDEO -> stringResource(R.string.audio_perf_video)
        PlaybackLoadHint.AUDIO -> stringResource(R.string.audio_perf_audio)
        PlaybackLoadHint.DROPS -> stringResource(R.string.audio_perf_drops)
    }
    Text(
        text,
        modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 28.dp, end = 140.dp),
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun mpvLoadHint(
    controller: MpvPlaybackController,
    snapshot: MpvPlaybackSnapshot,
    extraHeavyVideo: Boolean = false,
): PlaybackLoadHint {
    val session = controller.audioSession
    val heavyVideo = extraHeavyVideo ||
        heavyVideoLabel(snapshot.activeShaderPreset) ||
        heavyVideoLabel(snapshot.activeVideoFilters) ||
        heavyVideoLabel(snapshot.activeScaler) ||
        heavyVideoLabel(controller.imageOptions.scaler)
    return rememberPlaybackLoadHint(
        enabled = session.performanceHint,
        positionMs = snapshot.positionMs,
        containerFps = snapshot.containerFps,
        outputFps = snapshot.fps.takeIf { it > 0.0 },
        droppedFrames = snapshot.frameDropCount,
        heavyVideo = heavyVideo,
        heavyAudio = session.outputPlan().heavyAudio,
        audioUnstable = snapshot.avSyncSeconds?.let { abs(it) > 0.125 } == true,
    )
}

@Composable
fun vlcLoadHint(
    controller: VlcPlaybackController,
    snapshot: VlcPlaybackSnapshot,
    extraHeavyVideo: Boolean = false,
): PlaybackLoadHint {
    val stats = snapshot.runtimeStats
    val session = controller.audioSession
    return rememberPlaybackLoadHint(
        enabled = session.performanceHint,
        positionMs = snapshot.positionMs,
        containerFps = stats?.videoFps,
        outputFps = null,
        droppedFrames = stats?.lostPictures?.toLong(),
        heavyVideo = extraHeavyVideo,
        heavyAudio = session.outputPlan().heavyAudio,
        audioFaults = stats?.lostAudioBuffers?.toLong(),
    )
}

@Composable
fun media3LoadHint(player: ExoPlayer, stats: Media3NerdStats): PlaybackLoadHint {
    val session = Media3AudioProcessing.session(player)
    return rememberPlaybackLoadHint(
        enabled = session?.performanceHint == true,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        containerFps = player.videoFormat?.frameRate?.toDouble()?.takeIf { it > 1.0 },
        outputFps = null,
        droppedFrames = stats.droppedVideoFrames?.toLong(),
        heavyVideo = false,
        heavyAudio = session?.outputPlan()?.heavyAudio == true,
        audioFaults = stats.audioUnderruns.toLong(),
        renderedFrames = stats.renderedVideoFrames?.toLong(),
    )
}
