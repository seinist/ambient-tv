package com.ambient.tv.ui.player.backend

private fun svpRate(value: Double): String =
    String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

/**
 * Live SVP state for Stats for Nerds and the SVP setting: "23.976 → 60 fps · balanced", or why it
 * is not interpolating. Null when SVP is switched off.
 */
internal fun formatSvpStatus(
    mode: String,
    allowed: Boolean,
    active: Boolean,
    overloaded: Boolean,
    sourceFps: Double?,
    targetFps: Double?,
    bypass: Int,
): String? {
    if (mode == "off") return null
    if (!allowed) return "unavailable"
    if (overloaded) return "off (load)"
    val source = sourceFps?.takeIf { it.isFinite() && it > 0.0 }
    val target = targetFps?.takeIf { it.isFinite() && it > 0.0 }
    if (!active) return when {
        source == null -> "waiting for source rate"
        target == null -> "display rate unknown"
        target <= source * 1.01 -> "not needed (source ≥ display)"
        else -> "starting"
    }
    val rates = if (source != null && target != null) "${svpRate(source)} → ${svpRate(target)} fps" else "on"
    return listOfNotNull(rates, mode.takeIf { it != "auto" }, "bypassed".takeIf { bypass != 0 }).joinToString(" · ")
}
