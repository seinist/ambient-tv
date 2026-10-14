package com.ambient.tv.ui.player.backend

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.ambient.tv.ui.player.performanceProfile
import com.ambient.tv.ui.player.videoImagePreferences

/** Global SVP switch, Maximum performance mode and the native filter must all agree. */
internal fun svpAllowed(context: Context): Boolean =
    videoImagePreferences(context).svpAvailable &&
        performanceProfile(context).allowSvpInterpolation && SvpNative.isEnabledAndAvailable()

internal fun svpDisplayFps(context: Context): Double? =
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.refreshRate?.toDouble()?.takeIf { it.isFinite() && it in 5.0..120.0 }

internal fun svpPreset(mode: String): Int = when (mode) {
    "light" -> 1
    "balanced" -> 2
    "quality" -> 3
    else -> 0
}

internal fun svpTargetChanged(mode: String, before: Double?, after: Double?): Boolean =
    mode != "off" && if (before == null || after == null) before != after else kotlin.math.abs(before - after) > 0.01

/** No guessed source rate: unknown / VFR / >= display rate remains unfiltered. */
internal fun mpvSvpFilter(mode: String, sourceFps: Double?, displayFps: Double?): String? {
    if (mode !in setOf("auto", "light", "balanced", "quality")) return null
    val source = sourceFps?.takeIf { it.isFinite() && it in 5.0..120.0 } ?: return null
    val target = displayFps?.takeIf { it.isFinite() && it in 5.0..120.0 } ?: return null
    if (target <= source * 1.01 || target / source > 5.0) return null
    return "ambientsvp=source=$source:fps=$target:preset=${svpPreset(mode)}:backend=0"
}
