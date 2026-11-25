package com.ambient.tv.moonlight

import kotlinx.serialization.Serializable

/** A streaming profile; the native engine also validates device decoder and display capabilities. */
@Serializable
data class MoonlightStreamProfile(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    /** Network bitrate in kbit/s, as expected by Moonlight's StreamConfiguration. */
    val bitrateKbps: Int = 20000,
    val codec: String = "auto",
    /** Auto negotiates HDR10 only with a capable app, display and 10-bit hardware decoder. */
    val hdrMode: String = "auto",
    val audioChannels: Int = 2,
) {
    fun validated(): MoonlightStreamProfile {
        require(width in 640..7680 && height in 360..4320)
        require(width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= 33_177_600L)
        require(fps in 24..240)
        require(bitrateKbps in 1000..150_000)
        require(codec in CODECS)
        require(hdrMode in HDR_MODES)
        require(audioChannels in setOf(2, 6, 8))
        return this
    }
    companion object {
        val CODECS = setOf("auto", "h264", "hevc", "av1")
        val HDR_MODES = setOf("auto", "on", "off")
    }
}

enum class MoonlightStreamPhase { IDLE, CONNECTING, STREAMING, STOPPING, ENDED, FAILED }

/**
 * Explicit control-state transitions. A delayed callback from an old connection is ignored by
 * an owner-generation guard in the native implementation; STOPPING cannot be resurrected.
 */
fun moonlightNextPhase(current: MoonlightStreamPhase, event: String): MoonlightStreamPhase = when (event) {
    "connect" -> if (current in setOf(MoonlightStreamPhase.IDLE, MoonlightStreamPhase.ENDED, MoonlightStreamPhase.FAILED))
        MoonlightStreamPhase.CONNECTING else current
    "started" -> if (current == MoonlightStreamPhase.CONNECTING) MoonlightStreamPhase.STREAMING else current
    "stop" -> if (current in setOf(MoonlightStreamPhase.CONNECTING, MoonlightStreamPhase.STREAMING))
        MoonlightStreamPhase.STOPPING else current
    "ended" -> if (current in setOf(MoonlightStreamPhase.CONNECTING, MoonlightStreamPhase.STREAMING, MoonlightStreamPhase.STOPPING))
        MoonlightStreamPhase.ENDED else current
    "failed" -> if (current in setOf(MoonlightStreamPhase.CONNECTING, MoonlightStreamPhase.STREAMING, MoonlightStreamPhase.STOPPING))
        MoonlightStreamPhase.FAILED else current
    else -> current
}
