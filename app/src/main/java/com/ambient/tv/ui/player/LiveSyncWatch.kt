package com.ambient.tv.ui.player

import kotlin.math.abs

/** Read on each renderer's owning thread. A null sample is lack of evidence, not a freeze. */
internal data class LiveSyncSample(
    val atMs: Long,
    val positionMs: Long,
    val generation: Int,
    val renderedFrames: Long? = null,
    val audioBuffers: Long? = null,
    val avSyncSeconds: Double? = null,
    /** MPV's video-related time-pos can stop while audio-pts continues. */
    val videoPositionMs: Long? = null,
)

internal enum class LiveSyncFault { VIDEO_STALLED, CLOCK_DRIFT }

/** Short RF errors and buffering are normal. Recover only persistent video/clock faults while
 * playback advances, with a per-channel budget that is rearmed only by sustained healthy play. */
internal class LiveSyncWatch {
    private var last: LiveSyncSample? = null
    private var frozenFrom: LiveSyncSample? = null
    private var driftFrom: Long? = null
    private var healthyFrom: Long? = null
    private var attempts = 0
    private var retryAt = 0L

    fun resetObservation() {
        last = null
        frozenFrom = null
        driftFrom = null
        healthyFrom = null
    }

    fun resetSession() {
        resetObservation()
        attempts = 0
        retryAt = 0
    }

    fun observe(sample: LiveSyncSample?, intentionalDelayMs: Int = 0): LiveSyncFault? {
        if (sample == null) { resetObservation(); return null }
        val before = last
        if (before != null && sample.atMs == before.atMs && sample.generation == before.generation) return null
        last = sample
        val elapsed = before?.let { sample.atMs - it.atMs }
        if (before == null || sample.generation != before.generation ||
            elapsed == null || elapsed !in 1L..3000L ||
            sample.positionMs < before.positionMs || sample.positionMs - before.positionMs > elapsed + 2500 ||
            (sample.renderedFrames != null && before.renderedFrames != null && sample.renderedFrames < before.renderedFrames) ||
            (sample.audioBuffers != null && before.audioBuffers != null && sample.audioBuffers < before.audioBuffers)) {
            frozenFrom = null; driftFrom = null; healthyFrom = null
            return null
        }
        val clockMoves = sample.positionMs - before.positionMs >= 150
        // Some backends do not expose presentation counters (permanent zero). Require evidence
        // that pictures were produced at least once before interpreting zero progress as a stall.
        val videoClockFrozen = sample.videoPositionMs != null && before.videoPositionMs != null &&
            kotlin.math.abs(sample.videoPositionMs - before.videoPositionMs) < 50L
        val videoFrozen = ((sample.renderedFrames ?: 0) > 0 && before.renderedFrames == sample.renderedFrames) ||
            videoClockFrozen
        if (videoFrozen && clockMoves) {
            if (frozenFrom == null) frozenFrom = before
        } else frozenFrom = null
        val drift = sample.avSyncSeconds?.let { it.isFinite() && abs(it) >= 0.65 + abs(intentionalDelayMs / 1000.0) } == true
        if (drift && clockMoves) {
            if (driftFrom == null) driftFrom = sample.atMs
        } else driftFrom = null
        val stall = frozenFrom?.let { start ->
            val audioAdvanced = sample.audioBuffers == null || start.audioBuffers == null || sample.audioBuffers > start.audioBuffers
            sample.atMs - start.atMs >= 4000 && sample.positionMs - start.positionMs >= 2500 && audioAdvanced
        } == true
        val drifted = driftFrom?.let { sample.atMs - it >= 4000 } == true
        val fault = when {
            stall -> LiveSyncFault.VIDEO_STALLED
            drifted -> LiveSyncFault.CLOCK_DRIFT
            else -> null
        }
        val healthy = clockMoves && !videoFrozen && !drift &&
            ((sample.renderedFrames ?: 0) > 0 || sample.avSyncSeconds != null || sample.videoPositionMs != null)
        if (healthy) {
            if (healthyFrom == null) healthyFrom = before.atMs
            if (sample.atMs - requireNotNull(healthyFrom) >= 30_000L) attempts = 0
        } else healthyFrom = null
        if (fault == null || attempts >= 2 || sample.atMs < retryAt) return null
        attempts++
        retryAt = sample.atMs + 15_000L
        resetObservation()
        return fault
    }
}
