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
 * the signal, a 1.0 reading counts as unknown.
 *
 * Proof needs [REQUIRED_HDR_READINGS] readings above the threshold, at least
 * [MIN_READING_GAP_MS] apart, taken while this app really outputs HDR (a confirmed PQ surface,
 * active SDR→HDR, HDR playback): one stray value (UI animation, HWC transition) is not enough.
 * The evidence belongs to the connected display ([displayEvidenceKey]: name and HDR
 * capabilities); another TV or AVR behind the same display id starts unproven again.
 */
internal object DisplayHdrRatioEvidence {
    const val REQUIRED_HDR_READINGS = 3
    const val MIN_READING_GAP_MS = 400L

    private class Entry(val key: String) {
        var readings = 0
        var lastMs = Long.MIN_VALUE
    }
    private val entries = java.util.concurrent.ConcurrentHashMap<Int, Entry>()

    fun note(displayId: Int, ratio: Float?, hdrOutput: Boolean, displayKey: String = "",
        nowMs: Long = System.nanoTime() / 1_000_000L) {
        val entry = entryFor(displayId, displayKey)
        if (!hdrOutput || displayHdrRatioMeansHdr(ratio) != true) return
        synchronized(entry) {
            if (entry.readings >= REQUIRED_HDR_READINGS) return
            if (entry.lastMs != Long.MIN_VALUE && nowMs - entry.lastMs < MIN_READING_GAP_MS) return
            entry.readings++
            entry.lastMs = nowMs
        }
    }

    fun note(display: android.view.Display?, ratio: Float?, hdrOutput: Boolean) {
        display ?: return
        note(display.displayId, ratio, hdrOutput, displayEvidenceKey(display))
    }

    fun reliable(displayId: Int, displayKey: String = ""): Boolean =
        entries[displayId]?.takeIf { it.key == displayKey }?.let { synchronized(it) { it.readings >= REQUIRED_HDR_READINGS } } == true

    fun reliable(display: android.view.Display?): Boolean =
        display != null && reliable(display.displayId, displayEvidenceKey(display))

    /** A changed display (other name/HDR capabilities) drops the evidence of the previous one. */
    private fun entryFor(displayId: Int, displayKey: String): Entry =
        entries.compute(displayId) { _, current -> current?.takeIf { it.key == displayKey } ?: Entry(displayKey) }!!

    internal fun clear() = entries.clear()
}

/** Identity of the display behind an id for [DisplayHdrRatioEvidence]: name plus HDR types. */
internal fun displayEvidenceKey(display: android.view.Display): String = runCatching {
    @Suppress("DEPRECATION")
    val types = display.hdrCapabilities?.supportedHdrTypes?.sorted().orEmpty()
    "${display.name}|${types.joinToString("+")}"
}.getOrDefault("")

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
