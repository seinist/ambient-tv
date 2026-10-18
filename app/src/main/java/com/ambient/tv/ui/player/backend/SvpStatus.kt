package com.ambient.tv.ui.player.backend

private fun svpRate(value: Double): String =
    String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

/**
 * What SVP is doing right now, for the panel (localized) and Stats for Nerds (English).
 * [reason] is null while frames are interpolated.
 */
data class SvpState(
    val mode: String,
    val custom: Boolean = false,
    val reason: SvpInactiveReason? = null,
    val sourceFps: Double? = null,
    val targetFps: Double? = null,
    /** 1 CPU, 2 Vulkan, 0 unknown. */
    val backend: Int = 0,
) {
    val active: Boolean get() = reason == null
}

/** "23.976 → 60 fps" once both rates are known. */
internal fun svpRates(source: Double?, target: Double?): String? {
    val s = source?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val t = target?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return "${svpRate(s)} → ${svpRate(t)} fps"
}

/**
 * Builds the state from the engine's facts. [decision] is the static policy for the current
 * source; [runtimeReason] comes from the adapter (MPV metadata, VLC frame counter).
 */
internal fun svpState(
    mode: String,
    custom: Boolean,
    unavailable: SvpInactiveReason?,
    overloaded: Boolean,
    decision: SvpDecision,
    inChain: Boolean,
    runtimeReason: SvpInactiveReason?,
    backend: Int = 0,
): SvpState? {
    if (mode == "off") return null
    val run = decision as? SvpDecision.Run
    val reason = unavailable
        ?: SvpInactiveReason.OVERLOAD.takeIf { overloaded }
        ?: (decision as? SvpDecision.Skip)?.reason
        ?: runtimeReason
        // Planned but not attached yet (filter chain still being rebuilt).
        ?: SvpInactiveReason.STARTING.takeIf { !inChain }
    return SvpState(mode, custom, reason, run?.source, run?.target, backend)
}

/** English line for Stats for Nerds and the FPS overlay. Null when SVP is switched off. */
internal fun formatSvpStatus(state: SvpState?): String? {
    state ?: return null
    val reason = state.reason
    if (reason == null) {
        val mode = if (state.custom) "custom" else state.mode
        val backend = when (state.backend) { 1 -> "CPU"; 2 -> "Vulkan"; else -> null }
        return listOfNotNull(svpRates(state.sourceFps, state.targetFps) ?: "on", mode, backend).joinToString(" · ")
    }
    return when (reason) {
        SvpInactiveReason.ENGINE -> "unavailable (player)"
        SvpInactiveReason.PERFORMANCE_MODE -> "unavailable (performance mode)"
        SvpInactiveReason.LIBRARY -> "unavailable (library)"
        SvpInactiveReason.HDR -> "unavailable (HDR)"
        SvpInactiveReason.RESOLUTION -> "off (resolution limit)"
        SvpInactiveReason.SOURCE_RATE_LIMIT -> "off (source > 30 fps)"
        SvpInactiveReason.SOURCE_RATE_UNKNOWN -> "waiting for source rate"
        SvpInactiveReason.DISPLAY_RATE_UNKNOWN -> "display rate unknown"
        SvpInactiveReason.NOT_NEEDED -> "not needed (source ≥ target)"
        SvpInactiveReason.FORMAT -> "passthrough (format)"
        SvpInactiveReason.TIMING -> "passthrough (timing)"
        SvpInactiveReason.PASSTHROUGH -> "passthrough"
        SvpInactiveReason.STARTING -> "starting"
        SvpInactiveReason.OVERLOAD -> "off (load)"
    }
}
