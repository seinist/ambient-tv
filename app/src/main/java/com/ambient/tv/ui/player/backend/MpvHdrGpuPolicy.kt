package com.ambient.tv.ui.player.backend

import androidx.compose.runtime.mutableStateMapOf
import com.ambient.tv.ui.player.HdrStandard

/**
 * Why HDR plays on the direct MediaCodec surface although "HDR über GPU verarbeiten" is on.
 * Null reason on a [MpvHdrGpuDecision] means the option does not concern this playback.
 */
enum class MpvHdrGpuReason {
    /** Output "Direkt" was chosen explicitly for this playback. */
    OUTPUT_DIRECT,
    /** Dolby Vision keeps its native path; MediaCodec copy-back has no RPU to reshape. */
    DOLBY_VISION,
    /** HLG (and HDR10+ on an HDR10+ display) keep their native path. */
    NOT_HDR10,
    /** Probe not finished or the device fails the MPV Vulkan gate (Vulkan 1.2, hostQueryReset). */
    VULKAN_UNAVAILABLE,
    /** The probe reported a Vulkan surface without the HDR10 (ST2084) colour space. */
    SWAPCHAIN_NO_HDR10,
    /** The display reports no HDR10; the regular tone-mapping path applies. */
    DISPLAY_NO_HDR10,
    /** Sparsam: no shader would run, so the GPU path brings nothing. */
    REDUCED_EFFECTS,
    /** mpv/Android did not confirm a PQ BT.2020 target in time, or Vulkan fell back. */
    NOT_CONFIRMED,
    /** Sustained dropped frames on the GPU path with nothing left to reduce. */
    OVERLOAD,
}

data class MpvHdrGpuDecision(val gpu: Boolean, val reason: MpvHdrGpuReason?) {
    companion object {
        val NOT_APPLICABLE = MpvHdrGpuDecision(false, null)
        val GPU = MpvHdrGpuDecision(true, null)
        fun direct(reason: MpvHdrGpuReason) = MpvHdrGpuDecision(false, reason)
    }
}

/**
 * HDR10 on an HDR10 display through vo=gpu-next + Vulkan with target-colorspace-hint
 * (passthrough), so HDR-safe shaders can run, instead of the forced mediacodec_embed.
 * Everything else keeps today's routing; [MpvHdrGpuDecision.reason] tells the UI why.
 * [failure] is a runtime verdict for this media (see [MpvHdrGpuSession]).
 */
internal fun mpvHdrGpuDecision(
    enabled: Boolean,
    output: String,
    source: HdrStandard?,
    display: Set<HdrStandard>,
    capabilities: VideoAccelerationCapabilities,
    reducedEffects: Boolean,
    failure: MpvHdrGpuReason? = null,
): MpvHdrGpuDecision {
    if (!enabled || source == null) return MpvHdrGpuDecision.NOT_APPLICABLE
    return when {
        source == HdrStandard.DOLBY_VISION -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.DOLBY_VISION)
        // HDR10+ on a display without HDR10+ reaches the panel as HDR10 anyway.
        source == HdrStandard.HLG || (source == HdrStandard.HDR10_PLUS && HdrStandard.HDR10_PLUS in display) ->
            MpvHdrGpuDecision.direct(MpvHdrGpuReason.NOT_HDR10)
        HdrStandard.HDR10 !in display -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.DISPLAY_NO_HDR10)
        !capabilities.mpvVulkanOutput -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.VULKAN_UNAVAILABLE)
        capabilities.device.hdr10SurfaceSupported == false -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10)
        // A runtime failure also switches this playback to "Direkt"; keep naming the cause.
        failure != null -> MpvHdrGpuDecision.direct(failure)
        output == "direct" -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.OUTPUT_DIRECT)
        reducedEffects -> MpvHdrGpuDecision.direct(MpvHdrGpuReason.REDUCED_EFFECTS)
        else -> MpvHdrGpuDecision.GPU
    }
}

/**
 * libplacebo passthrough: the swapchain is tagged from the source (PQ/BT.2020 + HDR10 metadata)
 * and strict mode renders into what was actually negotiated. target-trc/prim stay auto.
 */
internal val MPV_HDR_PASSTHROUGH_OPTIONS = linkedMapOf(
    "target-colorspace-hint" to "yes",
    "target-colorspace-hint-mode" to "source",
    "target-colorspace-hint-strict" to "yes",
)

/** Facts read from mpv and Android while the passthrough route plays. */
internal data class MpvHdrPassthroughFacts(
    val fileReady: Boolean,
    val playing: Boolean,
    val sourceGamma: String?,
    val targetGamma: String?,
    val targetPrimaries: String?,
    val targetPixelFormat: String?,
    val dataSpace: Int,
    val swapchainHdr10: Boolean?,
)

/**
 * Confirms the passthrough once per player: mpv must report a PQ/BT.2020 10-bit target and
 * Android a BT.2020/ST2084 producer surface within [timeoutMs] of steady playback. A failure is
 * final for this player; the UI then reopens the title on the direct surface.
 */
internal class MpvHdrPassthroughMonitor(private val timeoutMs: Long = 3_000L) {
    var confirmed = false
        private set
    var failure: MpvHdrGpuReason? = null
        private set
    private var since = -1L

    fun update(facts: MpvHdrPassthroughFacts, nowMs: Long): MpvHdrGpuReason? {
        if (failure != null) return failure
        if (facts.swapchainHdr10 == false) return fail(MpvHdrGpuReason.SWAPCHAIN_NO_HDR10)
        val pqSource = facts.sourceGamma?.trim()?.lowercase() in setOf("pq", "smpte2084")
        if (!facts.fileReady || !pqSource) {
            // A source that turns out not to be PQ simply renders SDR; nothing to confirm.
            since = -1L
            return null
        }
        val ok = facts.targetGamma?.trim()?.lowercase() in setOf("pq", "smpte2084") &&
            facts.targetPrimaries?.trim()?.lowercase() == "bt.2020" &&
            facts.targetPixelFormat?.trim()?.lowercase() in MPV_HDR_TARGET_PIXEL_FORMATS &&
            mpvHdrSurfaceConfirmed(facts.dataSpace)
        if (ok) {
            confirmed = true
            since = -1L
            return null
        }
        // Also after a confirmation: PQ lost for a whole window of steady playback means the
        // swapchain fell back to SDR. Short gaps (reload, renegotiation) are tolerated.
        if (!facts.playing) {
            since = -1L
            return null
        }
        if (since < 0L || nowMs < since) since = nowMs
        return if (nowMs - since >= timeoutMs) fail(MpvHdrGpuReason.NOT_CONFIRMED) else null
    }

    fun fail(reason: MpvHdrGpuReason): MpvHdrGpuReason {
        if (failure == null) failure = reason
        return failure!!
    }
}

/** Same libplacebo formats the SDR→HDR controller accepts as a 10-bit target. */
internal val MPV_HDR_TARGET_PIXEL_FORMATS = setOf("rgb10a2", "bgr10a2", "rgba16", "rgba16hf", "rgba16f")

/**
 * HDR media detected at runtime and passthrough failures, per URL for this app process.
 * A failure keeps the title on the direct surface until the app restarts.
 */
object MpvHdrGpuSession {
    private val detected = mutableStateMapOf<String, HdrStandard>()
    private val failures = mutableStateMapOf<String, MpvHdrGpuReason>()

    fun detectedHdr(url: String): HdrStandard? = detected[url]
    fun failure(url: String): MpvHdrGpuReason? = failures[url]
    fun recordDetected(url: String, standard: HdrStandard) { if (detected[url] != standard) detected[url] = standard }
    fun recordFailure(url: String, reason: MpvHdrGpuReason) { if (url !in failures) failures[url] = reason }
}

/** Settings: the device-level part of [mpvHdrGpuDecision] (null while probing or when nothing blocks). */
internal fun mpvHdrGpuDeviceReason(capabilities: VideoAccelerationCapabilities, display: Set<HdrStandard>): MpvHdrGpuReason? {
    if (!capabilities.checked) return null
    return mpvHdrGpuDecision(true, "auto", HdrStandard.HDR10, display, capabilities, reducedEffects = false).reason
        ?.takeIf { it in setOf(MpvHdrGpuReason.VULKAN_UNAVAILABLE, MpvHdrGpuReason.SWAPCHAIN_NO_HDR10, MpvHdrGpuReason.DISPLAY_NO_HDR10) }
}
