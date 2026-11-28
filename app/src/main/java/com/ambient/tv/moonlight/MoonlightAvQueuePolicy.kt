package com.ambient.tv.moonlight

import kotlin.math.abs

/**
 * Project a fresh AudioTrack timestamp onto the monotonic clock used by the video renderer.
 * Android's unsigned 32-bit frame counter can wrap; align it to the current playback head.
 * Reject stale, future, implausible or pre-flush timestamps instead of making them sync anchors.
 */
internal fun moonlightProjectAudioHead(framePosition: Long, timestampNanos: Long, playbackHead: Long,
                                      written: Long, rate: Int, nowNanos: Long): Long? {
    if (rate !in 8000..192000 || framePosition < 0L || playbackHead < 0L || written < 0L ||
        timestampNanos <= 0L || nowNanos < timestampNanos) return null
    val age = nowNanos - timestampNanos
    if (age > 500_000_000L) return null
    val mask = 0xffffffffL
    val cycle = mask + 1L
    var aligned = (playbackHead and mask.inv()) or (framePosition and mask)
    if (aligned - playbackHead > cycle / 2L) aligned -= cycle
    else if (playbackHead - aligned > cycle / 2L) aligned += cycle
    if (aligned < 0L || aligned > written) return null
    val projected = (aligned + age * rate / 1_000_000_000L).coerceAtMost(written)
    if (abs(projected - playbackHead) > rate / 2L) return null
    return projected
}

/**
 * GameStream's PCM callback does not expose a shared host A/V PTS. This controller measures
 * only local queue imbalance. It never delays video to match audio, changes audio pitch,
 * or claims a content-level lip-sync measurement. Native/AudioTrack hardware floors remain
 * authoritative; reduce the audio queue target only after sustained excess with no underruns.
 */
internal class MoonlightAvQueuePolicy {
    data class Balance(val differenceMs: Double? = null, val reductionMs: Int = 0)
    private var excessSamples = 0
    private var stableSamples = 0
    private var lastUnderruns = -1
    private var cooldownUntil = 0L
    private var reduction = 0

    fun sample(now: Long, audio: MoonlightAudioMetrics, video: MoonlightVideoMetrics,
               pendingVideoFrames: Int): Balance {
        fun fresh(time: Long) = time > 0L && time <= now && now - time <= 1_000_000_000L
        val localAudio = audio.estimatedQueuedMs
        val localVideo = video.recentPresentationMs
        if (audio.recovering || !fresh(audio.sampledAtNanos) || !fresh(video.presentationSampleNanos) ||
            localAudio == null || localVideo == null || !localAudio.isFinite() || !localVideo.isFinite() ||
            localAudio < 0.0 || localVideo < 0.0 || video.fps <= 0 || pendingVideoFrames < 0) {
            excessSamples = 0; stableSamples = 0; reduction = 0
            lastUnderruns = audio.underruns
            return Balance()
        }
        val difference = localAudio + audio.nativeQueuedMs.coerceAtLeast(0) -
            (localVideo + pendingVideoFrames * 1000.0 / video.fps)
        if (lastUnderruns >= 0 && audio.underruns != lastUnderruns) {
            cooldownUntil = now + 5_000_000_000L
            excessSamples = 0; stableSamples = 0; reduction = 0
        }
        lastUnderruns = audio.underruns
        if (now < cooldownUntil || !audio.timestampBacked) {
            excessSamples = 0; stableSamples = 0; reduction = 0
        } else if (difference > 40.0 && pendingVideoFrames <= 2) {
            stableSamples = 0
            if (++excessSamples >= 3) {
                reduction = (reduction + 5).coerceAtMost(20)
                excessSamples = 0
            }
        } else if (difference < 15.0 || pendingVideoFrames > 2) {
            excessSamples = 0
            if (++stableSamples >= 4) {
                reduction = (reduction - 5).coerceAtLeast(0)
                stableSamples = 0
            }
        } else {
            excessSamples = 0; stableSamples = 0
        }
        return Balance(difference, reduction)
    }
}

/** Validate sample counts before advancing queue ownership or channel position. */
internal fun moonlightValidPcmWrite(written: Int, requested: Int, channels: Int): Boolean =
    channels > 0 && written in 0..requested && written % channels == 0

/** Do not extrapolate playback during startup, underruns or a paused sink. */
internal fun moonlightEstimateQueuedAudio(queuedMs: Double, elapsedNanos: Long, advancing: Boolean): Double =
    if (!advancing) queuedMs else (queuedMs - elapsedNanos.coerceIn(0L, 20_000_000L) / 1_000_000.0).coerceAtLeast(0.0)
