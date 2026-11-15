package com.ambient.tv.data.local

import kotlin.math.roundToInt

/** The UI buffer is independent of both display-mode selection and the video render target. */
object UiRenderResolutionPolicy {
    const val SYSTEM = "system"
    const val FULL_HD = "1080p"
    const val PANEL = "panel"
    val keys: Set<String> = setOf(SYSTEM, FULL_HD, PANEL)

    fun normalize(key: String?): String = key?.takeIf { it in keys } ?: SYSTEM

    /** Android 12 adds the embedded-window focus forwarding needed for TV remote input. */
    fun isSupported(isTelevision: Boolean, sdkInt: Int): Boolean = isTelevision && sdkInt >= 31

    fun effectiveKey(key: String?, isTelevision: Boolean, sdkInt: Int): String =
        if (isSupported(isTelevision, sdkInt)) normalize(key) else SYSTEM

    /**
     * Physical dimensions describe this window's visible area, not a guessed 4K panel size.
     * A resolution limit preserves its aspect ratio and never enlarges a smaller window.
     */
    fun targetSize(
        key: String?,
        logicalWidth: Int,
        logicalHeight: Int,
        physicalWidth: Int,
        physicalHeight: Int,
    ): Pair<Int, Int>? {
        val logical = validSize(logicalWidth, logicalHeight)
        val mode = normalize(key)
        if (mode == SYSTEM) return logical
        val physical = validSize(physicalWidth, physicalHeight) ?: logical ?: return null
        if (mode == PANEL) return physical

        val (width, height) = physical
        val scale = minOf(1.0, 1920.0 / maxOf(width, height), 1080.0 / minOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun validSize(width: Int, height: Int): Pair<Int, Int>? =
        (width to height).takeIf { width > 0 && height > 0 }
}
