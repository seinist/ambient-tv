package com.ambient.tv.ui.player

enum class PlaybackLoadHint {
    NONE,
    VIDEO,
    AUDIO,
    DROPS,
}

data class PlaybackLoadSample(
    val sinceAnchorMs: Long,
    val badForMs: Long,
    val containerFps: Double?,
    val outputFps: Double?,
    val dropsPerSecond: Double?,
    val heavyVideo: Boolean,
    val heavyAudio: Boolean,
    val audioUnstable: Boolean,
)

/** Compares output with the file frame rate. A 24 fps film on a 60 Hz panel is not a failure. */
fun playbackLoadHint(sample: PlaybackLoadSample): PlaybackLoadHint {
    if (sample.sinceAnchorMs < 10_000L || sample.badForMs < 5_000L) return PlaybackLoadHint.NONE
    val container = sample.containerFps?.takeIf { it.isFinite() && it > 1.0 }
    val output = sample.outputFps?.takeIf { it.isFinite() && it > 0.0 }
    val short = container != null && output != null && output < container * 0.90
    val dropping = (sample.dropsPerSecond ?: 0.0) > 2.0
    if (!short && !dropping && !sample.audioUnstable) return PlaybackLoadHint.NONE
    if ((short || dropping) && sample.heavyVideo) return PlaybackLoadHint.VIDEO
    if (sample.audioUnstable && sample.heavyAudio) return PlaybackLoadHint.AUDIO
    if (short || dropping) return PlaybackLoadHint.DROPS
    return PlaybackLoadHint.NONE
}

fun heavyVideoLabel(label: String?): Boolean {
    val value = label?.lowercase().orEmpty()
    if (value.isBlank() || value == "off" || value == "none") return false
    return listOf("nnedi", "ravu", "artcnn", "anime4k", "fsrcnn", "nis", "fsr", "cunny", "deint").any { it in value }
}

/** Tracks grace and the sustained-bad window. A seek starts both clocks over. */
class PlaybackLoadClock(private val nowMs: () -> Long) {
    private var anchorMs = nowMs()
    private var badSinceMs: Long? = null
    private var lastPositionMs = Long.MIN_VALUE
    private var lastSampleMs = anchorMs

    fun reset() {
        anchorMs = nowMs()
        badSinceMs = null
        lastPositionMs = Long.MIN_VALUE
        lastSampleMs = anchorMs
    }

    fun mark(positionMs: Long, bad: Boolean): Pair<Long, Long> {
        val now = nowMs()
        val elapsed = (now - lastSampleMs).coerceAtLeast(0L)
        if (lastPositionMs != Long.MIN_VALUE && kotlin.math.abs(positionMs - lastPositionMs - elapsed) > 2_500L) {
            anchorMs = now
            badSinceMs = null
        }
        lastPositionMs = positionMs
        lastSampleMs = now
        if (!bad) badSinceMs = null else if (badSinceMs == null) badSinceMs = now
        val since = (now - anchorMs).coerceAtLeast(0L)
        val badFor = badSinceMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        return since to badFor
    }
}

class RateTracker {
    private var lastValue: Long? = null
    private var lastAtMs = 0L
    private var rate = 0.0

    fun reset() {
        lastValue = null
        lastAtMs = 0L
        rate = 0.0
    }

    fun observe(total: Long?, nowMs: Long): Double? {
        if (total == null) return null
        val previous = lastValue
        if (previous != null && nowMs > lastAtMs) {
            val seconds = (nowMs - lastAtMs) / 1000.0
            if (seconds >= 0.2) rate = (total - previous).coerceAtLeast(0L) / seconds
        }
        lastValue = total
        lastAtMs = nowMs
        return if (previous == null) 0.0 else rate
    }
}
