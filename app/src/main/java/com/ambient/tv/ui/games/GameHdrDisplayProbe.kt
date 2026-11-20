package com.ambient.tv.ui.games

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * Reads the HDR formats advertised by the physical Android display associated with
 * this UI context. This is not a Vulkan swapchain/surface query and it never enables HDR.
 * An unknown or detached display is treated as SDR/unknown.
 */
internal object GameHdrDisplayProbe {
    fun capture(context: Context): GameHdrDisplayCapabilities {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return GameHdrDisplayCapabilities()
        return runCatching {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display ?: windowManager?.defaultDisplay
            } else {
                windowManager?.defaultDisplay
            }
            val hdr = display?.hdrCapabilities ?: return GameHdrDisplayCapabilities()
            // Since Android 14, the *current display mode* can advertise a narrower
            // set than the panel as a whole (e.g. 4K/120 Hz with HDR disabled).
            @Suppress("DEPRECATION")
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.mode.supportedHdrTypes.toSet()
            } else {
                hdr.supportedHdrTypes.toSet()
            }

            GameHdrDisplayCapabilities(
                hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
                hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
                hdr10Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
                dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
                peakNits = hdr.desiredMaxLuminance.takeIf { it.isFinite() && it > 0f },
                averageNits = hdr.desiredMaxAverageLuminance.takeIf { it.isFinite() && it > 0f },
                minimumNits = hdr.desiredMinLuminance.takeIf { it.isFinite() && it >= 0f },
            )
        }.getOrDefault(GameHdrDisplayCapabilities())
    }
}
