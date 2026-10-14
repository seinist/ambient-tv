package com.ambient.tv.ui.display

/** Missing or unrecognised settings stay SDR. Only "auto" and "force" are HDR modes. */
fun normalizedUiHdrMode(value: String?): String = when (value) {
    "auto" -> "auto"
    "force" -> "force"
    else -> "sdr"
}

/**
 * Mixed-SDR headroom for the whole interface. Not a measured panel peak.
 * About 2× SDR white is enough for the UI to read as HDR without clipping design colors.
 */
const val UI_HDR_HEADROOM_CAP = 2.0f

data class UiHdrEligibility(
    val sdk: Int,
    val automatic: Boolean,
    val windowReady: Boolean,
    val displayHdr: Boolean,
    val wideColor: Boolean,
    val sourceSupported: Boolean,
    val headroom: Float?,
    val forced: Boolean = false,
) {
    val allowed: Boolean
        get() {
            // API 34 can set COLOR_MODE_HDR but cannot cap headroom. An uncapped window
            // washes SDR UI out, so the interface lift starts at API 35.
            val displayCapable = sdk >= 35 && windowReady && displayHdr && wideColor
            if (!displayCapable) return false
            // Force asks for UI HDR without a playing HDR source. Auto still needs that source
            // and a real ratio above SDR before COLOR_MODE_HDR.
            if (forced) return true
            return automatic && sourceSupported && headroom.isMeasuredAboveSdr()
        }

    /**
     * Ratio passed to [Window.setDesiredHdrHeadroom]. Always the fixed cap, never a
     * guessed panel peak and never the display's current ratio.
     */
    val requestedHeadroom: Float
        get() = UI_HDR_HEADROOM_CAP
}

private fun Float?.isMeasuredAboveSdr(): Boolean = this != null && isFinite() && this > 1f

/** Small interface lets failure/rollback be tested without pretending an emulator is an HDR TV. */
internal interface UiHdrOutput {
    fun hdr(headroom: Float)
    fun sdr()
}

/** One controller per Activity. All calls are on the main thread; a failure latches for its lifetime. */
internal class UiHdrController(private val output: UiHdrOutput) {
    var headroom: Float = 1f
        private set
    var failed: Boolean = false
        private set
    private var candidate: Any? = null
    private var since = 0L

    fun update(eligible: UiHdrEligibility, identity: Any?, nowMs: Long): Float {
        if (failed || !eligible.allowed || identity == null) {
            reset()
            return headroom
        }
        if (candidate != identity) {
            reset()
            candidate = identity
            since = nowMs
        }
        // HDMI/renderer handoffs must settle before requesting a different UI buffer format.
        if (!failed && nowMs - since >= 1_000) {
            val requested = eligible.requestedHeadroom
            if (headroom != requested) {
                try {
                    output.hdr(requested)
                    headroom = requested
                } catch (_: Exception) {
                    fail()
                }
            }
        }
        return headroom
    }

    fun reset() {
        candidate = null
        if (headroom > 1f) {
            headroom = 1f
            try { output.sdr() } catch (_: Exception) { failed = true }
        }
    }

    fun fail() {
        failed = true
        candidate = null
        headroom = 1f
        // Also roll back a partially applied HDR request whose setter threw.
        runCatching { output.sdr() }
    }
}
