package com.ambient.tv.ui.display

/** ACTIVE confirms the renderer's target, never the physical TV/HDMI output. */
enum class SdrToHdrStatus { OFF, UNAVAILABLE, WAITING, ACTIVE, FAILED }

data class SdrToHdrRequest(
    val peakNits: Int,
    val displayId: Int,
    val displayModeId: Int,
    /** SDR white inside the PQ signal in nits; 0 = renderer default ([SDR_REFERENCE_WHITE_DEFAULT]). */
    val referenceWhiteNits: Int = 0,
) {
    /** Never above the output budget: a reference white over the peak would clip all highlights. */
    val effectiveReferenceWhiteNits: Int
        get() = effectiveSdrReferenceWhiteNits(referenceWhiteNits, peakNits)

    /** Same output negotiation; only the live brightness differs. */
    fun sameOutput(other: SdrToHdrRequest?): Boolean =
        other != null && other.copy(referenceWhiteNits = referenceWhiteNits) == this
}

fun normalizedSdrToHdrPeakNits(value: Int): Int = if (value == 0) 0 else value.coerceIn(300, 600)

/** BT.2408 / mpv MP_REF_WHITE: the brightness SDR white gets in an HDR container by default. */
const val SDR_REFERENCE_WHITE_DEFAULT = 203
const val SDR_REFERENCE_WHITE_MIN = 80
const val SDR_REFERENCE_WHITE_MAX = 600
/** Presets offered in Settings and the player; 0 = Auto (default 203). Any other value is custom. */
val SDR_REFERENCE_WHITE_PRESETS = listOf(0, 100, 203, 300, 400)

fun normalizedSdrReferenceWhiteNits(value: Int): Int =
    if (value <= 0) 0 else value.coerceIn(SDR_REFERENCE_WHITE_MIN, SDR_REFERENCE_WHITE_MAX)

fun effectiveSdrReferenceWhiteNits(requested: Int, peakNits: Int): Int {
    val white = normalizedSdrReferenceWhiteNits(requested).takeIf { it > 0 } ?: SDR_REFERENCE_WHITE_DEFAULT
    return if (peakNits > 0) minOf(white, peakNits) else white
}

/**
 * The current SDR/HDR ratio may still be 1 before the first HDR frame. A real, reported
 * ratio and peak are preferred. A verified video surface may instead probe a bounded target.
 * The caller supplies display eligibility for HDR10, not the SDR source's format.
 */
internal fun sdrToHdrRequest(
    display: UiHdrEligibility,
    enabled: Boolean,
    peakNits: Float?,
    displayId: Int,
    displayModeId: Int,
    allowSurfaceProbe: Boolean = false,
    requestedPeakNits: Int = 0,
    requestedReferenceWhiteNits: Int = 0,
): SdrToHdrRequest? {
    if (!enabled || display.sdk < 34 || !display.automatic || !display.windowReady ||
        !display.displayHdr || !display.wideColor || !display.sourceSupported) return null
    val measured = display.headroom != null && display.headroom.isFinite() && display.headroom >= 1f &&
        peakNits != null && peakNits.isFinite() && peakNits >= 300f
    // Some HDR TVs publish placeholder peak values and no mixed-SDR headroom.
    // Only a video path that verifies its negotiated PQ surface may probe those displays.
    // This is an output budget, never an estimate of the panel's measured brightness.
    if (!measured && !allowSurfaceProbe) return null
    val requested = normalizedSdrToHdrPeakNits(requestedPeakNits)
    val automatic = if (measured) peakNits.coerceAtMost(600f).toInt() else 300
    val target = if (requested == 0) automatic else if (measured) minOf(requested, automatic) else requested
    return SdrToHdrRequest(target,
        displayId, displayModeId, normalizedSdrReferenceWhiteNits(requestedReferenceWhiteNits))
}

enum class SdrToHdrPlayerKind { NONE, MPV_VULKAN, MPV_UNSUPPORTED, VLC_GL, VLC_VULKAN, MEDIA3 }

/** What the active engine can do right now. A null handle means no playback, not "unsupported". */
data class SdrToHdrPath(
    val kind: SdrToHdrPlayerKind,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val blocked: Boolean = false,
)

data class SdrToHdrSwitchFacts(
    val sdk: Int,
    val displayHdr: Boolean,
    val wideColor: Boolean,
    val mode: String,
    val reducedEffects: Boolean,
    val ratio: Float?,
    val peakNits: Float?,
    val player: SdrToHdrPlayerKind = SdrToHdrPlayerKind.NONE,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameRate: Float = 0f,
    val pathBlocked: Boolean = false,
    val hdr10Supported: Boolean = true,
)

/** The interface mode is only offered when the window can actually enter capped HDR. */
fun hdrInterfaceChoiceVisible(sdk: Int, displayHdr: Boolean, wideColor: Boolean): Boolean =
    sdk >= 35 && displayHdr && wideColor

/** Hide conversion unless both the display and the current playback path support it. */
fun sdrToHdrSwitchVisible(facts: SdrToHdrSwitchFacts): Boolean {
    if (facts.sdk < 34 || !facts.displayHdr || !facts.wideColor) return false
    if (facts.reducedEffects || facts.pathBlocked || !facts.hdr10Supported) return false
    val ratio = facts.ratio
    val peak = facts.peakNits
    val measured = ratio != null && ratio.isFinite() && ratio >= 1f &&
        peak != null && peak.isFinite() && peak >= 300f
    if (!measured && facts.player != SdrToHdrPlayerKind.MPV_VULKAN) return false
    return when (facts.player) {
        SdrToHdrPlayerKind.NONE, SdrToHdrPlayerKind.MPV_VULKAN -> true
        SdrToHdrPlayerKind.MPV_UNSUPPORTED, SdrToHdrPlayerKind.VLC_VULKAN -> false
        SdrToHdrPlayerKind.VLC_GL, SdrToHdrPlayerKind.MEDIA3 -> {
            val unknownSize = facts.frameWidth <= 0 || facts.frameHeight <= 0 ||
                !facts.frameRate.isFinite() || facts.frameRate <= 0f
            unknownSize || com.ambient.tv.ui.player.sdrHdrSizeAllowed(
                facts.frameWidth, facts.frameHeight, facts.frameRate,
            )
        }
    }
}

/** Playback controls require an actual, fully identified path, unlike pre-playback settings. */
fun sdrToHdrPlaybackControlVisible(facts: SdrToHdrSwitchFacts): Boolean =
    sdrToHdrSwitchEnabled(facts) && facts.player != SdrToHdrPlayerKind.NONE &&
        (facts.player == SdrToHdrPlayerKind.MPV_VULKAN ||
            (facts.frameWidth > 0 && facts.frameHeight > 0 && facts.frameRate.isFinite() && facts.frameRate > 0f))

/** The selected UI mode controls availability without changing hardware support. */
fun sdrToHdrSwitchEnabled(facts: SdrToHdrSwitchFacts): Boolean =
    sdrToHdrSwitchVisible(facts) && normalizedUiHdrMode(facts.mode) == "auto"
