package com.ambient.tv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.hardware.display.DisplayManager
import com.ambient.tv.data.local.AppPreferences
import timber.log.Timber
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Walks [ContextWrapper] chains; Compose's LocalContext is often not the Activity itself. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

data class SupportedDisplayMode(
    val key: String,
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
) {
    val label: String get() = String.format(Locale.ROOT, "%d × %d · %.3f Hz", width, height, refreshRate)
}

/** Android window-mode requests implementing Kodi-style resolution/rate matching. */
object DisplayRefreshRateMatcher {
    private const val MAX_CADENCE_DIFF_HZ = 0.6f

    fun supportedModes(context: Context): List<SupportedDisplayMode> = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        val display = context.findActivity()?.let(::getDisplayCompat)
            ?: manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        display.supportedModes.map(::choice).distinctBy { it.key }
            .sortedWith(compareBy<SupportedDisplayMode>({ it.width * it.height }, { it.refreshRate }))
    }.getOrDefault(emptyList())

    fun currentModeId(activity: Activity?): Int? = activity?.let(::getDisplayCompat)?.mode?.modeId

    fun currentModeKey(activity: Activity?): String? = activity?.let(::getDisplayCompat)?.mode?.let(::modeKey)

    fun modeKey(mode: Display.Mode): String = modeKey(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)

    fun modeKey(width: Int, height: Int, refreshRate: Float): String =
        String.format(Locale.ROOT, "%dx%d@%.3f", width, height, refreshRate)

    fun parseVideoDimensions(value: String?): Pair<Int, Int>? {
        val match = Regex("(\\d{2,5})\\s*[x×]\\s*(\\d{2,5})").find(value.orEmpty()) ?: return null
        val width = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val height = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return width to height
    }

    fun setPreferredMode(activity: Activity?, key: String, allowedModes: Set<String> = emptySet()) {
        val target = findMode(activity, key) ?: return
        if (allowedModes.isNotEmpty() && target.key !in allowedModes) return
        requestMode(activity, target)
    }

    /** Selects one of the two automatic policies, or preserves the legacy fixed/system policy. */
    fun matchContentFrameRate(
        activity: Activity?,
        contentFrameRate: Float,
        contentWidth: Int? = null,
        contentHeight: Int? = null,
        preferredModeKey: String = "system",
        fallbackModeKey: String? = null,
        allowedModes: Set<String> = emptySet(),
        allowDoubleRate: Boolean = true,
        allowThreeTwoPulldown: Boolean = true,
        preserveRefreshRate: Boolean = false,
        preserveOutputResolution: Boolean = false,
    ): SupportedDisplayMode? {
        if (activity == null) return null
        val display = getDisplayCompat(activity) ?: return null
        val all = display.supportedModes.map(::choice).distinctBy { it.key }
        val selected = selectContentMode(
            all, choice(display.mode), contentFrameRate, contentWidth, contentHeight,
            preferredModeKey, fallbackModeKey, allowedModes, allowDoubleRate, allowThreeTwoPulldown,
            preserveRefreshRate, preserveOutputResolution,
        )
        Timber.i("DisplayAuto policy=%s source=%sx%s@%s selected=%s allowed=%d interpolate=%s rendererScale=%s",
            preferredModeKey, contentWidth, contentHeight, contentFrameRate, selected?.label ?: "unavailable",
            allowedModes.size, preserveRefreshRate, preserveOutputResolution)
        Timber.d("DisplayAuto available=%s", all.joinToString { it.key })
        requestMode(activity, selected)
        return selected
    }

    /** Complete policy selection, independent of Android window calls and physical mode order. */
    internal fun selectContentMode(
        all: List<SupportedDisplayMode>, current: SupportedDisplayMode,
        contentFrameRate: Float, contentWidth: Int? = null, contentHeight: Int? = null,
        preferredModeKey: String = "system", fallbackModeKey: String? = null,
        allowedModes: Set<String> = emptySet(), allowDoubleRate: Boolean = true,
        allowThreeTwoPulldown: Boolean = true, preserveRefreshRate: Boolean = false,
        preserveOutputResolution: Boolean = false,
    ): SupportedDisplayMode? {
        val modes = all.filter { allowedModes.isEmpty() || it.key in allowedModes }
        if (modes.isEmpty()) return null
        val base = all.firstOrNull { it.key == preferredModeKey }
            ?: all.firstOrNull { it.key == fallbackModeKey }
            ?: all.firstOrNull { it.modeId == current.modeId }
            ?: all.firstOrNull { it.key == current.key } ?: modes.first()
        val cadenceTarget = contentFrameRate.takeIf { it > 0f && it.isFinite() }
        return when (preferredModeKey) {
            AppPreferences.DISPLAY_MODE_AUTO_CONTENT -> selectContentResolutionMode(
                modes, contentWidth, contentHeight, cadenceTarget, base,
                allowDoubleRate, allowThreeTwoPulldown, preserveRefreshRate,
            )
            AppPreferences.DISPLAY_MODE_AUTO_DISPLAY -> selectFullDisplayMode(
                modes, cadenceTarget, base, allowDoubleRate, allowThreeTwoPulldown, preserveRefreshRate,
            )
            else -> when {
                preserveOutputResolution -> selectRendererMode(modes, base, cadenceTarget, allowDoubleRate, allowThreeTwoPulldown, preserveRefreshRate)
                preserveRefreshRate -> selectInterpolationMode(modes, contentWidth, contentHeight, base, allowedModes.isNotEmpty())
                allowedModes.isNotEmpty() -> selectWhitelistedMode(modes, contentWidth, contentHeight, cadenceTarget, base, allowDoubleRate, allowThreeTwoPulldown)
                cadenceTarget == null -> base
                else -> selectCadenceMode(modes.filter { it.width == base.width && it.height == base.height }, cadenceTarget, allowDoubleRate, allowThreeTwoPulldown) ?: base
            }
        }
    }

    /** Match the source geometry where supported, including letterboxed sources such as 1920×800. */
    internal fun selectContentResolutionMode(
        modes: List<SupportedDisplayMode>, width: Int?, height: Int?, fps: Float?,
        base: SupportedDisplayMode?, double: Boolean, threeTwo: Boolean, preserveRefreshRate: Boolean,
    ): SupportedDisplayMode? {
        base ?: return null
        val contentWidth = width?.takeIf { it > 0 }
            ?: return selectRendererMode(modes, base, fps, double, threeTwo, preserveRefreshRate)
        val contentHeight = height?.takeIf { it > 0 }
            ?: return selectRendererMode(modes, base, fps, double, threeTwo, preserveRefreshRate)
        val exact = modes.filter { it.width == contentWidth && it.height == contentHeight }
        val fitting = modes.filter { it.width >= contentWidth && it.height >= contentHeight }
        val resolution = exact.firstOrNull()
            ?: fitting.minWithOrNull(compareBy<SupportedDisplayMode>({ it.width.toLong() * it.height }, { it.width }))
            ?: modes.maxByOrNull { it.width.toLong() * it.height }
            ?: return base
        val atResolution = modes.filter { it.width == resolution.width && it.height == resolution.height }
        if (preserveRefreshRate) {
            return atResolution.minByOrNull { abs(it.refreshRate - base.refreshRate) }
                ?.takeIf { abs(it.refreshRate - base.refreshRate) <= MAX_CADENCE_DIFF_HZ } ?: base
        }
        // A known source rate must honor the cadence switches. Falling back to the
        // nearest desktop rate could otherwise select disabled doubling or pulldown.
        if (fps != null) return selectCadenceMode(atResolution, fps, double, threeTwo)
        return atResolution.minByOrNull { abs(it.refreshRate - base.refreshRate) } ?: base
    }

    /** Keep the highest permitted physical output resolution so renderer upscalers can work. */
    internal fun selectFullDisplayMode(
        modes: List<SupportedDisplayMode>, fps: Float?, base: SupportedDisplayMode?,
        double: Boolean, threeTwo: Boolean, preserveRefreshRate: Boolean,
    ): SupportedDisplayMode? {
        base ?: return null
        val maximum = modes.maxByOrNull { it.width.toLong() * it.height } ?: return base
        val atResolution = modes.filter { it.width == maximum.width && it.height == maximum.height }
        if (preserveRefreshRate) return atResolution.minByOrNull { abs(it.refreshRate - base.refreshRate) } ?: base
        // A known source rate must honor the cadence switches. Falling back to the
        // nearest desktop rate could otherwise select disabled doubling or pulldown.
        if (fps != null) return selectCadenceMode(atResolution, fps, double, threeTwo)
        return atResolution.minByOrNull { abs(it.refreshRate - base.refreshRate) } ?: base
    }

    internal fun selectRendererMode(
        modes: List<SupportedDisplayMode>, base: SupportedDisplayMode?, fps: Float?,
        double: Boolean, threeTwo: Boolean, preserveRefreshRate: Boolean,
    ): SupportedDisplayMode? {
        base ?: return null
        if (preserveRefreshRate || fps == null) return base
        // Renderer scaling owns the final output: a content-resolution whitelist entry must
        // not move scaling back to the TV. Still honor the whitelist for cadence changes.
        return selectCadenceMode(modes.filter { it.width == base.width && it.height == base.height }, fps, double, threeTwo) ?: base
    }

    internal fun selectWhitelistedMode(
        allowedModes: List<SupportedDisplayMode>,
        contentWidth: Int?,
        contentHeight: Int?,
        contentFrameRate: Float?,
        fallbackMode: SupportedDisplayMode?,
        allowDoubleRate: Boolean,
        allowThreeTwoPulldown: Boolean,
    ): SupportedDisplayMode? {
        if ((contentWidth ?: 0) <= 0 || (contentHeight ?: 0) <= 0 || contentFrameRate == null) {
            return fallbackMode
        }
        val exactContentModes = allowedModes.filter { it.width == contentWidth && it.height == contentHeight }
        return selectCadenceMode(exactContentModes, contentFrameRate, allowDoubleRate, allowThreeTwoPulldown) ?: fallbackMode
    }

    internal fun selectCadenceMode(modes: List<SupportedDisplayMode>, fps: Float, double: Boolean, threeTwo: Boolean): SupportedDisplayMode? {
        if (!fps.isFinite() || fps <= 0f) return null
        // Prefer native cadence, then integer doubling, and only then 3:2 pulldown.
        val multiples = listOf(1f) + (if (double) listOf(2f) else emptyList()) +
            (if (threeTwo && fps in 23f..24.5f) listOf(2.5f) else emptyList())
        for (multiple in multiples) {
            modes.filter { abs(it.refreshRate - fps * multiple) <= MAX_CADENCE_DIFF_HZ }
                .minWithOrNull(compareBy<SupportedDisplayMode>({ abs(it.refreshRate - fps * multiple) }, { it.modeId }))
                ?.let { return it }
        }
        return null
    }

    internal fun selectInterpolationMode(modes: List<SupportedDisplayMode>, width: Int?, height: Int?, base: SupportedDisplayMode?, whitelisted: Boolean): SupportedDisplayMode? {
        base ?: return null
        if (!whitelisted) return base
        // SVP needs the display's target rate. Resolution matching must not collapse it to source FPS.
        return modes.filter { it.width == width && it.height == height && abs(it.refreshRate - base.refreshRate) <= MAX_CADENCE_DIFF_HZ }
            .minByOrNull { abs(it.refreshRate - base.refreshRate) } ?: base
    }

    fun restoreMode(activity: Activity?, modeId: Int?) {
        val window = activity?.window ?: return
        val params = window.attributes
        if (params.preferredDisplayModeId == (modeId ?: 0)) return
        PlaybackOutputTransition.begin(activity)
        params.preferredDisplayModeId = modeId ?: 0
        window.attributes = params
    }

    @Deprecated("Use restoreMode with the mode captured at playback start")
    fun clearPreference(activity: Activity?) = restoreMode(activity, null)

    private fun findMode(activity: Activity?, key: String): SupportedDisplayMode? {
        if (key == "system") return null
        val display = activity?.let(::getDisplayCompat) ?: return null
        return display.supportedModes.map(::choice).firstOrNull { it.key == key }
    }

    private fun requestMode(activity: Activity?, mode: SupportedDisplayMode?) {
        val window = activity?.window ?: return
        mode ?: return
        val params = window.attributes
        if (params.preferredDisplayModeId == mode.modeId && currentModeKey(activity) == mode.key) return
        PlaybackOutputTransition.begin(activity)
        params.preferredDisplayModeId = mode.modeId
        // Keep the renderer clock, audio and play intent running while HDMI renegotiates.
        // VideoSurfaceLayout updates the surface after DisplayManager reports the new mode.
        window.attributes = params
        Timber.i("Requested display mode ${mode.label} (id=${mode.modeId})")
    }

    private fun choice(mode: Display.Mode) = SupportedDisplayMode(
        key = modeKey(mode), modeId = mode.modeId,
        width = mode.physicalWidth, height = mode.physicalHeight, refreshRate = mode.refreshRate,
    )

    private fun getDisplayCompat(activity: Activity): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager?.defaultDisplay
    }
}

internal data class DisplayContentFormat(val fps: Float, val width: Int?, val height: Int?) {
    val hasRate get() = fps.isFinite() && fps > 0f
    val hasSize get() = (width ?: 0) > 0 && (height ?: 0) > 0
}

/** Decoder callbacks can report size and cadence separately; an unknown field is not a reset. */
internal fun mergeDisplayContentFormat(previous: DisplayContentFormat?, fps: Float, width: Int?, height: Int?): DisplayContentFormat {
    val newWidth = width?.takeIf { it > 0 }
    val newHeight = height?.takeIf { it > 0 }
    val hasNewSize = newWidth != null && newHeight != null
    return DisplayContentFormat(
        fps = fps.takeIf { it.isFinite() && it > 0f } ?: previous?.fps ?: 0f,
        width = if (hasNewSize) newWidth else previous?.width,
        height = if (hasNewSize) newHeight else previous?.height,
    )
}

internal fun displayFormatNeedsSize(preferred: String, whitelisted: Boolean): Boolean =
    preferred == AppPreferences.DISPLAY_MODE_AUTO_CONTENT || whitelisted && preferred != AppPreferences.DISPLAY_MODE_AUTO_DISPLAY

/** An adaptive stream must not remain at 30 Hz after upgrading from 30 to 60 fps. */
internal fun displayRateUpgradeNeeded(policy: String, preferred: String, displayHz: Float?, contentFps: Float?): Boolean =
    policy in setOf("start", "start_stop") && preferred in setOf("system", AppPreferences.DISPLAY_MODE_AUTO_CONTENT, AppPreferences.DISPLAY_MODE_AUTO_DISPLAY) &&
        displayHz != null && displayHz.isFinite() && displayHz > 0f &&
        contentFps != null && contentFps.isFinite() && contentFps > displayHz + 1f

/** Start-only matching is complete only when the physical display accepted a full format. */
internal fun displayStartRequestConfirmed(format: DisplayContentFormat, needsSize: Boolean,
    selected: SupportedDisplayMode?, actualKey: String?): Boolean =
    format.hasRate && (!needsSize || format.hasSize) && selected != null && actualKey == selected.key

/** Keep playback and settings in sync when HDMI publishes a different set of modes. */
@Composable
internal fun rememberAvailableDisplayModes(context: Context? = androidx.compose.ui.platform.LocalContext.current): List<SupportedDisplayMode> {
    var modes by remember(context) { mutableStateOf(context?.let(DisplayRefreshRateMatcher::supportedModes).orEmpty()) }
    DisposableEffect(context) {
        val manager = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        fun refresh() { modes = context?.let(DisplayRefreshRateMatcher::supportedModes).orEmpty() }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refresh()
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return modes
}

/** Captures the desktop mode once; fixed output selection is independent of cadence matching. */
@Composable
fun rememberDisplayModeRequest(
    activity: Activity?,
    playbackKey: String,
    adjustmentKey: String,
    preferredModeKey: String,
    whitelist: Set<String>,
    allowDoubleRate: Boolean = true,
    allowThreeTwoPulldown: Boolean = true,
    metadataRoute: String? = LocalPlaybackMetadata.current?.route,
): (Float, Int?, Int?) -> Unit {
    val interpolation = BackgroundPlaybackSession.activeHandle()?.usesFrameInterpolation == true
    val rendererScaling = BackgroundPlaybackSession.activeHandle()?.usesRendererScaling == true
    // Panel locks are keyed by PlaybackMetadata.route so they stay aligned with the OSD selector.
    val sessionFixedModeKey = metadataRoute?.let(PlaybackDisplayModeRevision::sessionFixedFor)
        .also { PlaybackDisplayModeRevision.tick }
    val availableModes = rememberAvailableDisplayModes(activity)
    var content by remember(activity, playbackKey) { mutableStateOf<DisplayContentFormat?>(null) }
    var switchedAtStart by remember(activity, playbackKey, adjustmentKey, preferredModeKey, whitelist, allowDoubleRate, allowThreeTwoPulldown, interpolation, rendererScaling, availableModes) { mutableStateOf(false) }
    val latestPolicy by rememberUpdatedState(adjustmentKey)
    val latestPreferred by rememberUpdatedState(preferredModeKey)
    val originalModeId = remember(activity) { DisplayRefreshRateMatcher.currentModeId(activity) }
    val originalModeKey = remember(activity) { DisplayRefreshRateMatcher.currentModeKey(activity) }
    DisposableEffect(activity, playbackKey, metadataRoute) {
        onDispose {
            // Drop the panel lock with this playback so the next title can cadence-match again.
            metadataRoute?.let(PlaybackDisplayModeRevision::clearSessionFixedFor)
        }
    }
    DisposableEffect(activity) {
        onDispose {
            if (latestPolicy == "start_stop" || latestPolicy == "always" || (latestPolicy == "off" && latestPreferred != "system")) {
                DisplayRefreshRateMatcher.restoreMode(activity, originalModeId)
            }
        }
    }
    LaunchedEffect(activity, playbackKey, adjustmentKey, preferredModeKey, whitelist, allowDoubleRate, allowThreeTwoPulldown, interpolation, rendererScaling, content, sessionFixedModeKey, metadataRoute, availableModes) {
        // Re-read by route so a key change cannot apply the previous title's panel lock.
        val fixed = metadataRoute?.let(PlaybackDisplayModeRevision::sessionFixedFor)
        if (fixed != null) {
            if (fixed == "system") DisplayRefreshRateMatcher.restoreMode(activity, originalModeId)
            else DisplayRefreshRateMatcher.setPreferredMode(activity, fixed)
            return@LaunchedEffect
        }
        if (adjustmentKey == "off") {
            if (preferredModeKey == "system") DisplayRefreshRateMatcher.restoreMode(activity, originalModeId)
            else DisplayRefreshRateMatcher.setPreferredMode(activity, preferredModeKey)
        } else if (adjustmentKey == "always" || !switchedAtStart ||
            displayRateUpgradeNeeded(adjustmentKey, preferredModeKey, activity?.let(androidx.core.content.ContextCompat::getDisplayOrDefault)?.refreshRate, content?.fps)) {
            val format = content ?: return@LaunchedEffect
            if (!format.hasRate && !format.hasSize) return@LaunchedEffect
            val needsSize = displayFormatNeedsSize(preferredModeKey, whitelist.isNotEmpty())
            if (needsSize && !format.hasSize) return@LaunchedEffect
            // Decoder size/FPS callbacks arrive separately; settle them before requesting HDMI.
            // A newer format cancels this effect and starts with the complete source format.
            delay(500)
            // One bounded retry covers a request issued while the window/HDMI output is settling.
            // Never clear the mode or pause/recreate the decoder to force a physical switch.
            repeat(2) { attempt ->
                val selected = DisplayRefreshRateMatcher.matchContentFrameRate(
                    activity, format.fps, format.width, format.height,
                    preferredModeKey, originalModeKey, whitelist, allowDoubleRate, allowThreeTwoPulldown,
                    preserveRefreshRate = interpolation,
                    preserveOutputResolution = rendererScaling,
                ) ?: return@LaunchedEffect
                delay(3_000)
                val actual = DisplayRefreshRateMatcher.currentModeKey(activity)
                // Do not latch an absent or ignored request. Later format/HDMI updates may retry.
                switchedAtStart = displayStartRequestConfirmed(format, needsSize, selected, actual)
                if (actual == selected.key) {
                    Timber.i("DisplayAuto confirmed physical mode=%s", actual)
                    return@LaunchedEffect
                }
                Timber.w("DisplayAuto attempt=%d requested=%s actual=%s; display/system did not apply the request",
                    attempt + 1, selected.key, actual)
            }
        }
    }
    return remember(activity, playbackKey) { { fps, width, height -> content = mergeDisplayContentFormat(content, fps, width, height) } }
}
