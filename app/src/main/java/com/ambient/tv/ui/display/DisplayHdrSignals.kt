package com.ambient.tv.ui.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * The only public signal of the display's *current* HDR state: `Display.getHdrSdrRatio()`
 * (API 34+). 1.0 while the display shows SDR; above 1 once it runs in an HDR mode. Null when
 * Android does not report it (`isHdrSdrRatioAvailable` false, older Android), which many TV
 * HWCs do: then the switch simply cannot be checked.
 */
internal fun displayHdrSdrRatio(display: Display?): Float? {
    if (display == null || Build.VERSION.SDK_INT < 34) return null
    return runCatching { if (display.isHdrSdrRatioAvailable) display.hdrSdrRatio else null }.getOrNull()
        ?.takeIf { it.isFinite() && it > 0f }
}

internal fun defaultDisplay(context: Context): Display? =
    runCatching { (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()

/**
 * A ratio of 1.0 only means "SDR" on a display that has shown a ratio above 1 before: some TV
 * HWCs report the ratio but keep it at 1.0 even in an HDMI HDR mode. Until a display proved
 * the signal (any HDR playback or HDR UI in this process), a 1.0 reading counts as unknown.
 */
internal object DisplayHdrRatioEvidence {
    private val proven = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun note(displayId: Int, ratio: Float?) {
        if (displayHdrRatioMeansHdr(ratio) == true) proven.add(displayId)
    }

    fun reliable(displayId: Int): Boolean = displayId in proven

    internal fun clear() = proven.clear()
}

/** Above this HDR/SDR ratio the display is taken to be in HDR mode (1.0 = SDR). */
internal const val DISPLAY_HDR_RATIO_THRESHOLD = 1.05f

/** true = display in HDR, false = display reports SDR, null = not reported by Android. */
internal fun displayHdrRatioMeansHdr(ratio: Float?): Boolean? =
    ratio?.takeIf { it.isFinite() && it > 0f }?.let { it > DISPLAY_HDR_RATIO_THRESHOLD }

/** The display state to act on: an SDR reading only counts once [reliable] (see [DisplayHdrRatioEvidence]). */
internal fun displayHdrModeFromRatio(ratio: Float?, reliable: Boolean): Boolean? =
    when (displayHdrRatioMeansHdr(ratio)) {
        true -> true
        false -> if (reliable) false else null
        null -> null
    }
