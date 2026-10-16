@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.display

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.player.HdrSupport
import com.ambient.tv.ui.player.PlaybackHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import timber.log.Timber

/** Requested UI mode only. This is not a measurement of the TV's HDMI output. */
internal class UiHdrState(private val mainWindow: Window) : UiHdrOutput {
    var headroom by mutableFloatStateOf(1f)
        private set
    var failed by mutableStateOf(false)
        private set
    var sdrToHdrStatus by mutableStateOf(SdrToHdrStatus.OFF)
        private set
    private var conversionHandle: PlaybackHandle? = null
    private val windows = linkedSetOf(mainWindow)
    private val controller = UiHdrController(this)
    private var popupCount = 0
    private val ambient = linkedSetOf<AmbientHdrLease>()
    private val ambientFailures = mutableSetOf<AmbientHdrLease>()
    private var ambientWindowSince = 0L
    internal val ambientOwner: AmbientHdrLease? get() {
        val priority = ambient.maxOfOrNull { it.priority } ?: return null
        return ambient.lastOrNull { it.priority == priority }
    }
    internal val suspended: Boolean get() = popupCount > 0

    internal fun attachAmbient(lease: AmbientHdrLease) {
        reset()
        ambient.add(lease)
    }

    internal fun detachAmbient(lease: AmbientHdrLease) {
        if (!ambient.remove(lease)) return
        ambientFailures.remove(lease)
        try { lease.update(null) } finally { reset() }
    }

    internal fun updateAmbient(facts: UiHdrEligibility, request: SdrToHdrRequest?, identity: Any?) {
        stopVideoConversion()
        val owner = ambientOwner ?: return
        ambient.filter { it !== owner }.forEach { it.update(null) }
        val safeRequest = request.takeIf { !suspended && !failed && owner.canRequest() && owner !in ambientFailures }
        if (owner.windowOutput) {
            // Configure the Canvas HDR target before publishing a shader request.
            val budget = ambientHdrHeadroom(facts, safeRequest)
            update(facts.copy(headroom = budget), identity)
            val status = owner.update(safeRequest.takeIf { headroom > 1f })
            if (headroom <= 1f) ambientWindowSince = 0L
            else if (ambientWindowSince == 0L) ambientWindowSince = SystemClock.elapsedRealtime()
            val unconfirmed = headroom > 1f && (facts.headroom == null || facts.headroom <= 1f) &&
                SystemClock.elapsedRealtime() - ambientWindowSince >= 2500L
            if (status == SdrToHdrStatus.FAILED || status == SdrToHdrStatus.UNAVAILABLE || unconfirmed) {
                ambientFailures.add(owner)
                owner.update(null)
                controller.reset()
                headroom = 1f
                ambientWindowSince = 0L
                Timber.w("Ambient HDR target not confirmed; retaining SDR for this presentation")
            }
        } else {
            val status = owner.update(safeRequest)
            // A confirmed PQ surface must request the HDR window even while the display
            // still reports SDR. Waiting for its ratio here makes the TV switch circular.
            update(ambientSurfaceHdrEligibility(facts, safeRequest, status), identity)
        }
    }

    internal fun updateVideoConversion(handle: PlaybackHandle?, request: SdrToHdrRequest?, enabled: Boolean) {
        if (conversionHandle !== handle) stopVideoConversion()
        conversionHandle = handle
        // Enforce the opt-in at the output boundary, not only in the polling caller.
        val safeRequest = request.takeIf { enabled && !suspended && !failed && ambientOwner == null }
        val result = handle?.updateSdrToHdr(safeRequest)
        sdrToHdrStatus = when {
            !enabled -> SdrToHdrStatus.OFF
            safeRequest == null || result == null -> SdrToHdrStatus.UNAVAILABLE
            else -> result
        }
    }

    private fun stopVideoConversion() {
        val handle = conversionHandle
        conversionHandle = null
        sdrToHdrStatus = SdrToHdrStatus.OFF
        try { handle?.updateSdrToHdr(null) } catch (e: Exception) {
            failed = true
            Timber.w(e, "SDR to HDR cleanup failed")
        }
    }

    override fun hdr(headroom: Float) {
        windows.forEach { request(it, headroom) }
        Timber.d("UI HDR requested; headroom=%s (HDMI output unverified)", headroom)
    }

    override fun sdr() {
        // Attempt every window even if one vendor window implementation throws.
        var failure: Exception? = null
        windows.forEach { window ->
            try { request(window, 1f) } catch (e: Exception) { failure = e }
        }
        failure?.let { throw it }
    }

    private fun request(window: Window, ratio: Float) {
        if (ratio > 1f) {
            // setDesiredHdrHeadroom is API 35. COLOR_MODE_HDR without that cap washes SDR UI out.
            if (Build.VERSION.SDK_INT < 35) error("HDR UI headroom requires API 35")
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            window.setDesiredHdrHeadroom(minOf(ratio, UI_HDR_HEADROOM_CAP))
            // This getter confirms the request, not that the TV has changed its output mode.
            check(window.colorMode == ActivityInfo.COLOR_MODE_HDR)
        } else {
            window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
        }
    }

    internal fun update(eligibility: UiHdrEligibility, identity: Any?) {
        headroom = controller.update(eligibility.copy(windowReady = eligibility.windowReady && popupCount == 0),
            identity, SystemClock.elapsedRealtime())
        failed = failed || controller.failed
    }

    fun reset() {
        ambientWindowSince = 0L
        stopVideoConversion()
        ambient.forEach { lease ->
            try { lease.update(null) } catch (e: Exception) { failed = true; Timber.w(e, "Ambient HDR cleanup failed") }
        }
        controller.reset()
        headroom = 1f
        failed = failed || controller.failed
    }

    internal fun fail(error: Exception) {
        Timber.w(error, "UI HDR disabled; restoring default UI color mode")
        stopVideoConversion()
        ambient.forEach { runCatching { it.update(null) } }
        controller.fail()
        headroom = 1f
        failed = true
    }

    internal fun attach(window: Window) {
        if (!windows.add(window)) return
        if (headroom > 1f) try { request(window, headroom) } catch (e: Exception) { fail(e) }
    }

    internal fun detach(window: Window) {
        if (window === mainWindow) return
        if (headroom > 1f) try { request(window, 1f) } catch (e: Exception) { fail(e) }
        windows.remove(window)
    }

    // Compose Popup exposes no public Window API. Keep the entire UI in SDR while it is open.
    internal fun openPopup() { popupCount++; reset() }
    internal fun closePopup() { popupCount = (popupCount - 1).coerceAtLeast(0) }
}

internal val LocalUiHdrState = staticCompositionLocalOf<UiHdrState?> { null }

@Composable
internal fun rememberUiHdrState(activity: Activity, mode: String, sdrToHdrEnabled: Boolean = false, sdrToHdrPeakNits: Int = 0): UiHdrState {
    val state = remember(activity) { UiHdrState(activity.window) }
    val handheld = com.ambient.tv.ui.mobile.LocalHandheldUi.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, mode, sdrToHdrEnabled, sdrToHdrPeakNits, lifecycle) {
        // Mode changes, recreation and background transitions always start from the SDR baseline.
        state.reset()
        val modeKey = normalizedUiHdrMode(mode)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                // SDR never enters the request loop. Force does, even with no HDR video playing.
                if (Build.VERSION.SDK_INT < 34 || modeKey == "sdr" || state.failed) awaitCancellation()
                while (true) {
                    try {
                        val snapshot = BackgroundPlaybackSession.snapshot.value
                        val handle = BackgroundPlaybackSession.activeHandle()
                        val hasVideo = snapshot.metadata?.hasVideo == true && !snapshot.status.ended &&
                            snapshot.status.error == null
                        val display = activity.window.decorView.display
                        val ambientOwner = state.ambientOwner
                        // Ambient conversion stays an Automatic-only experiment. Force only
                        // requests the UI color mode and must not be rolled back by that path.
                        if (ambientOwner != null && modeKey == "auto") {
                            val facts = uiHdrEligibility(activity.window, display, mode, HdrStandard.HDR10)
                            val ambientRequest = if (display != null && !state.suspended) sdrToHdrRequest(
                                facts, true, display.hdrCapabilities?.desiredMaxLuminance,
                                display.displayId, display.mode.modeId,
                            ) else null
                            state.updateAmbient(facts, ambientRequest,
                                listOf(ambientOwner, display?.displayId, display?.mode?.modeId))
                            if (state.failed) { state.reset(); awaitCancellation() }
                            delay(500)
                            continue
                        }
                        val conversionEnabled = sdrToHdrEnabled && modeKey == "auto"
                        val conversionDisplay = if (conversionEnabled && hasVideo && !state.suspended)
                            uiHdrEligibility(activity.window, display, mode, HdrStandard.HDR10) else null
                        val request = if (conversionDisplay != null && display != null) sdrToHdrRequest(
                            conversionDisplay, true, display.hdrCapabilities?.desiredMaxLuminance,
                            display.displayId, display.mode.modeId,
                            allowSurfaceProbe = handle?.sdrToHdrPath()?.kind == SdrToHdrPlayerKind.MPV_VULKAN,
                            requestedPeakNits = sdrToHdrPeakNits,
                        ) else null
                        state.updateVideoConversion(handle, request, conversionEnabled)
                        val runtimeSource = if (hasVideo) handle?.uiHdrStandard() else null
                        val source = if (handheld && hasVideo) handheldHdrSource(
                            snapshot.backend, runtimeSource, snapshot.metadata.handheldHdrStandard,
                        ) else runtimeSource
                        val facts = uiHdrEligibility(activity.window, display, mode, source)
                        state.update(facts, listOf(snapshot.generation, display?.displayId, display?.mode?.modeId, source))
                    } catch (e: Exception) {
                        state.fail(e)
                    }
                    if (state.failed) {
                        state.reset()
                        awaitCancellation()
                    }
                    delay(500)
                }
            } finally { state.reset() }
        }
    }
    DisposableEffect(state) { onDispose { state.reset() } }
    return state
}

/**
 * Media3 identifies the active transfer but cannot distinguish HDR10+ from HDR10 in Format.
 * VLC does not publish native HDR metadata. Handheld UI-HDR can safely refine those two cases
 * from the selected non-transcoded stream; television keeps renderer-only reporting unchanged.
 */
internal fun handheldHdrSource(
    backend: String,
    runtime: HdrStandard?,
    declared: HdrStandard?,
): HdrStandard? = when {
    runtime == HdrStandard.HDR10 && declared in setOf(HdrStandard.HDR10_PLUS, HdrStandard.DOLBY_VISION) -> declared
    runtime != null -> runtime
    backend.equals("vlc", ignoreCase = true) -> declared
    else -> null
}

internal fun uiHdrEligibility(window: Window, display: Display?, mode: String, source: HdrStandard?): UiHdrEligibility {
    val sdk = Build.VERSION.SDK_INT
    val normalized = normalizedUiHdrMode(mode)
    val automatic = normalized == "auto"
    val forced = normalized == "force"
    fun denied() = UiHdrEligibility(sdk, automatic, false, false, false, false, null, forced)
    if (Build.VERSION.SDK_INT < 34) return denied()
    val ready = (automatic || forced) && display?.isValid == true &&
        window.decorView.isAttachedToWindow && window.decorView.isHardwareAccelerated
    if (!ready) return denied()
    checkNotNull(display)
    val types = HdrSupport.enabledHdrTypes(display)
    val sourceType = when (source) {
        HdrStandard.HDR10 -> Display.HdrCapabilities.HDR_TYPE_HDR10
        HdrStandard.HLG -> Display.HdrCapabilities.HDR_TYPE_HLG
        HdrStandard.HDR10_PLUS -> Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        HdrStandard.DOLBY_VISION -> Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        null -> null
    }
    return UiHdrEligibility(sdk, automatic, true, display.isHdr, display.isWideColorGamut,
        sourceType != null && sourceType in types,
        if (display.isHdrSdrRatioAvailable) display.hdrSdrRatio else null,
        forced)
}

@Composable
fun MatchUiHdrDialogWindow() {
    val state = LocalUiHdrState.current
    val view = LocalView.current
    DisposableEffect(state, view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) state?.attach(window)
        else state?.openPopup()
        onDispose {
            if (window != null) state?.detach(window) else state?.closePopup()
        }
    }
}

@Composable
fun SuspendUiHdrForPopup() {
    val state = LocalUiHdrState.current
    DisposableEffect(state) {
        state?.openPopup()
        onDispose { state?.closePopup() }
    }
}

/**
 * Scale an sRGB design color into linear extended-sRGB. Near-white reaches the headroom cap.
 * Dark colors keep the same gain, so a dim focus frame does not flash up to the cap.
 * Channels are never left above the cap.
 */
fun uiHdrHighlight(color: Color, headroom: Float): Color {
    if (!headroom.isFinite() || headroom <= 1f) return color
    val linear = color.convert(ColorSpaces.LinearExtendedSrgb)
    val peak = maxOf(linear.red, linear.green, linear.blue)
    if (peak <= 0f) return color
    val cap = minOf(headroom, UI_HDR_HEADROOM_CAP)
    val scale = minOf(cap / peak, cap)
    return Color(
        (linear.red * scale).coerceAtMost(cap),
        (linear.green * scale).coerceAtMost(cap),
        (linear.blue * scale).coerceAtMost(cap),
        color.alpha,
        ColorSpaces.LinearExtendedSrgb,
    )
}

/** Same headroom gain for poster bitmaps. SDR (headroom ≤ 1) stays bit-exact, with no filter. */
fun uiHdrBitmapColorFilter(headroom: Float): androidx.compose.ui.graphics.ColorFilter? {
    if (!headroom.isFinite() || headroom <= 1f) return null
    val gain = minOf(headroom, UI_HDR_HEADROOM_CAP)
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(
        androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
            gain, 0f, 0f, 0f, 0f,
            0f, gain, 0f, 0f, 0f,
            0f, 0f, gain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )),
    )
}
