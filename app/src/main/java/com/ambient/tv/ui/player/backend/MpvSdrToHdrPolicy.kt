package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus

private val SDR_TRANSFERS = setOf(
    "bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8",
)
private val SDR_PRIMARIES = setOf("bt.709", "bt.601-525", "bt.601-625")

/** Every pipeline stage must positively identify SDR; configured decoder defaults are insufficient. */
internal fun supportsMpvSdrToHdrInput(
    vo: String?,
    gpuApi: String?,
    decodedTransfer: String?,
    sourceTransfer: String?,
    filteredTransfer: String?,
    primaries: String?,
): Boolean = vo?.trim()?.lowercase() == "gpu-next" && gpuApi?.trim()?.lowercase() == "vulkan" &&
    listOf(decodedTransfer, sourceTransfer, filteredTransfer).all { it?.trim()?.lowercase() in SDR_TRANSFERS } &&
    primaries?.trim()?.lowercase() in SDR_PRIMARIES

internal interface MpvHdrProperties {
    fun outputDataSpace(): Int = 0
    fun get(name: String): String?
    fun set(name: String, value: String): Boolean
}

/**
 * One transaction controller per MPV instance, called serially with a monotonic clock.
 * [update]'s supported flag must include a known SDR source and gpu-next/Vulkan output;
 * unknown sources, native HDR and other backends are ineligible. This never switches VO.
 * ACTIVE confirms MPV's target and Android's PQ producer surface, not physical panel output.
 */
internal class MpvSdrToHdrController(private val properties: MpvHdrProperties) {
    private var snapshot: MutableMap<String, String>? = null
    private var candidate: SdrToHdrRequest? = null
    private var eligibleSinceMs = 0L
    private var appliedAtMs = 0L
    private var failed = false
    private var status = SdrToHdrStatus.OFF

    val engaged: Boolean get() = snapshot != null
    val active: Boolean get() = status == SdrToHdrStatus.ACTIVE
    val conversionFailed: Boolean get() = failed
    var rollbackFailed: Boolean = false
        private set

    fun update(request: SdrToHdrRequest?, supported: Boolean, nowMs: Long): SdrToHdrStatus {
        if (request == null) {
            reset()
            return report(SdrToHdrStatus.OFF)
        }
        if (!supported) {
            reset()
            return report(SdrToHdrStatus.UNAVAILABLE)
        }
        if (failed) return report(SdrToHdrStatus.FAILED)
        // 204 is the technical floor, one nit above mpv's SDR reference white (203): a lower
        // target would make the converted picture darker than SDR. sdrToHdrRequest() itself
        // only produces 300..600, so this floor guards against bad input and is not a tuning value.
        if (request.peakNits !in 204..600) {
            fail()
            return status
        }
        // Same output, new SDR brightness: change hdr-reference-white live instead of
        // leaving HDR (a full reset would renegotiate the swapchain and the HDMI mode).
        if (engaged && candidate != request && request.sameOutput(candidate)) {
            if (!applyReferenceWhite(request)) return status
            candidate = request
        }
        if (candidate != request) {
            reset()
            if (failed) return status
            candidate = request
            eligibleSinceMs = nowMs
            return report(SdrToHdrStatus.WAITING)
        }
        if (!engaged) {
            if (nowMs < eligibleSinceMs) eligibleSinceMs = nowMs
            if (nowMs - eligibleSinceMs < SETTLE_MS) return report(SdrToHdrStatus.WAITING)
            val sdrTarget = try {
                sdrTargetConfirmed()
            } catch (_: Exception) {
                fail()
                return status
            }
            if (!sdrTarget) {
                reset()
                return report(SdrToHdrStatus.UNAVAILABLE)
            }
            if (!apply(request)) return status
            appliedAtMs = nowMs
        }

        // Do not accept a first confirmation arriving after the negotiation deadline.
        if (nowMs < appliedAtMs || (!active && nowMs - appliedAtMs > TARGET_TIMEOUT_MS)) {
            fail()
            return status
        }
        val confirmed = try {
            // PQ alone does not prove that inverse tone mapping remains enabled.
            if (!conversionOptionsConfirmed(request)) {
                fail()
                return status
            }
            // Target publication can lag option writes; never accept the applying poll.
            if (nowMs - appliedAtMs < TARGET_MIN_WAIT_MS) return report(SdrToHdrStatus.WAITING)
            targetConfirmed(request) && mpvHdrSurfaceConfirmed(properties.outputDataSpace())
        } catch (_: Exception) {
            fail()
            return status
        }
        if (confirmed) return report(SdrToHdrStatus.ACTIVE)
        if (active || nowMs - appliedAtMs >= TARGET_TIMEOUT_MS) {
            fail()
            return status
        }
        return report(SdrToHdrStatus.WAITING)
    }

    /** Restore once. Even a failed rollback permanently prevents another transaction. */
    fun reset() {
        candidate = null
        eligibleSinceMs = 0L
        appliedAtMs = 0L
        val previous = snapshot
        snapshot = null
        if (previous != null) {
            for (name in RESTORE_ORDER.filter(previous::containsKey)) {
                // Each option gets an attempt, including after a setter fails or throws.
                val restored = try {
                    properties.set(name, previous.getValue(name))
                } catch (_: Exception) {
                    false
                }
                if (!restored) {
                    failed = true
                    rollbackFailed = true
                }
            }
        }
        report(if (failed) SdrToHdrStatus.FAILED else SdrToHdrStatus.OFF)
    }

    fun fail() {
        failed = true
        reset()
    }

    /** Intercept ordinary option updates so leaving HDR restores the latest user setting. */
    fun updateBaseline(name: String, value: String): Boolean {
        val previous = snapshot ?: return false
        if (!previous.containsKey(name)) return false
        previous[name] = value
        return true
    }

    private fun apply(request: SdrToHdrRequest): Boolean {
        val values = linkedMapOf(
            "target-colorspace-hint-strict" to "yes",
            "target-colorspace-hint" to "yes",
            "target-colorspace-hint-mode" to "target",
            "target-prim" to "bt.2020",
            "target-trc" to "pq",
            "target-peak" to request.peakNits.toString(),
            "tone-mapping" to "spline",
            "inverse-tone-mapping" to "yes",
        )
        // Owned only when the user chose a brightness; Auto keeps mpv's own default (203 on Android).
        if (request.referenceWhiteNits > 0) values[REFERENCE_WHITE] = request.effectiveReferenceWhiteNits.toString()
        try {
            val previous = linkedMapOf<String, String>()
            for (name in values.keys) {
                val value = properties.get(name)
                if (value == null) {
                    fail()
                    return false
                }
                previous[name] = value
            }
            // Every original value must be available before the first native mutation.
            snapshot = previous
            for ((name, value) in values) {
                if (!properties.set(name, value)) {
                    fail()
                    return false
                }
            }
        } catch (_: Exception) {
            fail()
            return false
        }
        return true
    }

    /** Live SDR white change inside an engaged transaction. Restoration keeps the pre-conversion value. */
    private fun applyReferenceWhite(request: SdrToHdrRequest): Boolean {
        val previous = snapshot ?: return false
        try {
            if (request.referenceWhiteNits > 0) {
                if (!previous.containsKey(REFERENCE_WHITE)) {
                    val original = properties.get(REFERENCE_WHITE)
                    if (original == null) {
                        fail()
                        return false
                    }
                    previous[REFERENCE_WHITE] = original
                }
                if (!properties.set(REFERENCE_WHITE, request.effectiveReferenceWhiteNits.toString())) {
                    fail()
                    return false
                }
            } else {
                val original = previous.remove(REFERENCE_WHITE) ?: return true
                if (!properties.set(REFERENCE_WHITE, original)) {
                    failed = true
                    rollbackFailed = true
                    reset()
                    return false
                }
            }
        } catch (_: Exception) {
            fail()
            return false
        }
        return true
    }

    private fun target(name: String): String? =
        properties.get("video-target-params/$name")?.trim()?.lowercase()

    private fun conversionOptionsConfirmed(request: SdrToHdrRequest): Boolean {
        val options = mapOf("target-colorspace-hint-strict" to "yes",
            "target-colorspace-hint" to "yes", "target-colorspace-hint-mode" to "target",
            "target-prim" to "bt.2020", "target-trc" to "pq",
            "tone-mapping" to "spline", "inverse-tone-mapping" to "yes")
        return options.all { (name, value) -> properties.get(name)?.trim()?.lowercase() == value } &&
            properties.get("target-peak")?.toDoubleOrNull() == request.peakNits.toDouble() &&
            (request.referenceWhiteNits <= 0 ||
                properties.get(REFERENCE_WHITE)?.toDoubleOrNull() == request.effectiveReferenceWhiteNits.toDouble())
    }

    private fun sdrTargetConfirmed(): Boolean {
        if (target("gamma") !in SDR_TRANSFERS || target("primaries") !in SDR_PRIMARIES) return false
        val peak = target("sig-peak")?.toDoubleOrNull() ?: return false
        return peak.isFinite() && peak > 0.0 && peak <= 1.0
    }

    private fun targetConfirmed(request: SdrToHdrRequest): Boolean {
        if (target("gamma") !in setOf("pq", "smpte2084")) return false
        if (target("primaries") != "bt.2020") return false
        if (target("pixelformat") !in HDR_PIXEL_FORMATS) return false
        val peak = target("sig-peak")?.toDoubleOrNull() ?: return false
        // command.c exposes hdr.max_luma / MP_REF_WHITE (203), not measured pixels.
        // Strict mode respects negotiated metadata: a swapchain may ignore target-peak.
        // Reject an unconfirmed cap rather than treating every PQ target as success.
        // hdr-reference-white only moves SDR white inside the container, not this cap.
        return peak.isFinite() && peak > 1.0 && peak * 203.0 <= request.peakNits * 1.05
    }

    private fun report(value: SdrToHdrStatus): SdrToHdrStatus {
        status = value
        return value
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val TARGET_MIN_WAIT_MS = 250L
        const val TARGET_TIMEOUT_MS = 2_000L

        // Keep strict negotiation enabled until all original target/hint options are back.
        /** mpv 6947452 `--hdr-reference-white=<auto|10-10000>`: SDR white in the HDR container (gpu-next). */
        const val REFERENCE_WHITE = "hdr-reference-white"

        val RESTORE_ORDER = listOf(
            REFERENCE_WHITE,
            "inverse-tone-mapping",
            "target-trc",
            "target-prim",
            "target-peak",
            "tone-mapping",
            "target-colorspace-hint-mode",
            "target-colorspace-hint",
            "target-colorspace-hint-strict",
        )

        // Actual libplacebo Vulkan formats; an incidental "10" or "16" is not proof.
        val HDR_PIXEL_FORMATS = setOf(
            "rgb10a2", "bgr10a2", "rgba16", "rgba16hf", "rgba16f",
        )
    }
}

/** Filters precede GPU rendering. A PQ target cannot substitute for these live input facts. */
internal fun mpvHdrOutputGamma(vo: String?, filteredGamma: String?, targetGamma: String?): String? =
    when (vo?.trim()?.lowercase()) {
        "mediacodec_embed" -> filteredGamma
        "gpu-next", "gpu" -> targetGamma
        else -> null
    }

/** Vulkan/GPU native HDR is the same kind of source as mediacodec_embed. Tone-mapped SDR stays null. */
internal fun mpvNativeHdrOutput(vo: String?, gamma: String?): String? {
    val output = vo?.trim()?.lowercase()
    if (output != "mediacodec_embed" && output != "gpu-next" && output != "gpu") return null
    return when (gamma?.trim()?.lowercase()) {
        "pq", "smpte2084" -> "hdr10"
        "hlg", "arib-std-b67" -> "hlg"
        else -> null
    }
}


/** Android producer metadata: require BT.2020 + ST2084 with an explicit range. */
internal fun mpvHdrSurfaceConfirmed(dataSpace: Int): Boolean =
    (dataSpace and (63 shl 16)) == (6 shl 16) &&
        (dataSpace and (31 shl 22)) == (7 shl 22) &&
        (dataSpace and (7 shl 27)) in setOf(1 shl 27, 2 shl 27)
