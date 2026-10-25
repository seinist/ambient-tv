package com.ambient.tv.ui.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.display.LocalUiHdrState
import com.ambient.tv.ui.player.backend.VideoAccelerationRuntime
import kotlinx.coroutines.delay
import timber.log.Timber

/** Window-local experiment. Never changes the system setting; restores the captured attribute. */
internal class PlayerHdrWindowExperiment(private val window: Window) {
    var requested by mutableStateOf(false)
    var available by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var route: String? = null
    private var original: Boolean? = null

    fun update(playbackRoute: String?, supported: Boolean) {
        if (route != playbackRoute) {
            restore()
            route = playbackRoute
            requested = false
            failed = false
        }
        available = supported && playbackRoute != null && !failed
        if (Build.VERSION.SDK_INT < 34) return
        if (!available || !requested) {
            restore()
            return
        }
        if (original != null) return
        try {
            val params = window.attributes
            original = params.isHdrConversionEnabled
            params.setHdrConversionEnabled(false)
            window.attributes = params
            Timber.i("HDR-AUDIT window system conversion bypass requested; panel output unknown")
        } catch (error: Exception) {
            failed = true
            available = false
            requested = false
            Timber.w(error, "HDR-AUDIT window conversion override failed")
            restore()
        }
    }

    fun restore() {
        if (Build.VERSION.SDK_INT < 34) return
        val value = original ?: return
        try {
            val params = window.attributes
            params.setHdrConversionEnabled(value)
            window.attributes = params
            original = null
        } catch (error: Exception) {
            failed = true
            Timber.w(error, "HDR-AUDIT window conversion restore failed")
        }
    }
}

/** Shared lifecycle for Media3, MPV and VLC, including direct output and SDR-only UI mode. */
@Composable
internal fun ObservePlayerHdrEnvironment(activity: Activity, experiment: PlayerHdrWindowExperiment) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activity, experiment, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous: String? = null
            try {
                while (true) {
                    try {
                        val snapshot = BackgroundPlaybackSession.snapshot.value
                        val active = snapshot.active && snapshot.metadata?.hasVideo == true &&
                            !snapshot.status.ended && snapshot.status.error == null
                        val display = activity.window.decorView.display
                        val manager = activity.getSystemService(DisplayManager::class.java)
                        val conversion = if (Build.VERSION.SDK_INT >= 34)
                            runCatching { manager?.hdrConversionMode }.getOrNull() else null
                        val types = display?.let { runCatching { HdrSupport.enabledHdrTypes(it) }.getOrNull() }
                        experiment.update(snapshot.metadata?.route.takeIf { active },
                            Build.VERSION.SDK_INT >= 34 && conversion != null &&
                                conversion.conversionMode != android.hardware.display.HdrConversionMode.HDR_CONVERSION_UNSUPPORTED &&
                                !types.isNullOrEmpty())
                        if (active) {
                            val handle = BackgroundPlaybackSession.activeHandle()
                            val ratio = if (Build.VERSION.SDK_INT >= 34)
                                runCatching { display?.takeIf { it.isHdrSdrRatioAvailable }?.hdrSdrRatio }.getOrNull() else null
                            // HDR playback proves the ratio signal for the GPU route's display check;
                            // SDR playback readings never count ([DisplayHdrRatioEvidence]).
                            com.ambient.tv.ui.display.DisplayHdrRatioEvidence.note(display, ratio,
                                hdrOutput = handle?.uiHdrStandard() != null)
                            val caps = VideoAccelerationRuntime.capabilities.value
                            val facts = "sdk=${Build.VERSION.SDK_INT} backend=${snapshot.backend} " +
                                "display=${display?.displayId} mode=${display?.mode?.modeId} hdrTypes=$types " +
                                "wideColor=${display?.isWideColorGamut} hdrSdrRatio=$ratio " +
                                "ratioReliable=${display?.let { com.ambient.tv.ui.display.DisplayHdrRatioEvidence.reliable(it) }} " +
                                "systemConversion=$conversion bypassRequested=${experiment.requested} " +
                                "vulkanProbe=${caps.checked} vulkanColorSpaces=${caps.device.surfaceColorSpaces} " +
                                "vulkanFormats=${caps.device.surfaceFormats} hdr10Surface=${caps.device.hdr10SurfaceSupported} " +
                                "source=${handle?.uiHdrStandard()} sdrToHdrPath=${handle?.sdrToHdrPath()} panelMode=unknown"
                            if (facts != previous) {
                                Timber.i("HDR-AUDIT %s", facts)
                                previous = facts
                            }
                        } else previous = null
                    } catch (error: Exception) {
                        // A vendor diagnostic API must not interrupt playback or retain an override.
                        experiment.restore()
                        val message = "diagnosticError=${error.javaClass.simpleName}"
                        if (previous != message) Timber.w(error, "HDR-AUDIT display diagnostics unavailable")
                        previous = message
                    }
                    delay(1_000)
                }
            } finally {
                experiment.restore()
            }
        }
    }
    DisposableEffect(experiment) { onDispose { experiment.restore() } }
}

@Composable
internal fun PlayerHdrDiagnostics() {
    val state = LocalUiHdrState.current
    val preferences = videoImagePreferences(androidx.compose.ui.platform.LocalContext.current)
    val facts = com.ambient.tv.ui.settings.rememberHdrControlFacts(preferences.uiHdrModeKey)
    if (preferences.sdrToHdrEnabled) {
        com.ambient.tv.ui.display.sdrToHdrUnavailableReason(facts)?.let {
            ImageHint(stringResource(com.ambient.tv.ui.settings.sdrToHdrReasonTextRes(it)))
        }
    }
    val experiment = state?.conversionExperiment ?: return
    if (experiment.failed) ImageHint(stringResource(R.string.hdr_conversion_experiment_failed))
    if (!experiment.available) return
    val choices = listOf("system" to stringResource(R.string.player_automatic),
        "bypass" to stringResource(R.string.hdr_conversion_experiment_bypass))
    val selected = if (experiment.requested) "bypass" else "system"
    PlayerDropUpSelector(
        label = stringResource(R.string.hdr_conversion_experiment),
        value = choices.first { it.first == selected }.second,
        options = choices, selectedKey = selected,
        onSelected = { experiment.requested = it == "bypass" },
    )
    ImageHint(stringResource(R.string.hdr_conversion_experiment_hint))
}
